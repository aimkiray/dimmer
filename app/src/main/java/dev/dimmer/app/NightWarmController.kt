package dev.dimmer.app

import android.content.Context
import android.provider.Settings
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 暖色通道（A1 终版·夜灯）：经 Shizuku 以 shell 身份调 color_display 的
 * IColorDisplayManager 夜灯面（code 6-9，AOSP 16 隐式序号，Y700/ZUI 实测对齐）。
 * 夜灯占独立 DTM 槽位，与 RBC slot250 零交集——ZUI 对开/关/调温全程自带
 * ~1.5s 渐变动画，无 general_color_temperature 的 M⊗M 竞态，无闪。
 * ZUI 的 settings 键单向不对称：服务写会回写键、键写温度被忽略——故温度
 * 只走 binder；activated 键写有效，作无 Shizuku 时的降级释放路径。
 *
 * 接管/释放模型：
 * - v=1..100 → K 线性映射 [MAX_K..MIN_K]（ZUI 实测钳位 [4000,6480]）；
 *   v≤1 折叠为释放（6480K 与关亚感知，不白占系统夜灯态）
 * - 接管首写快照系统夜灯态（wasOn+temp0）；释放时还回：wasOn→还 temp0
 *   保活，!wasOn→关激活后回写 temp0（关态写温度仅回写键、不拉起）
 * - 外部改动尊重：释放前比对系统温度与我方最后写入值，不符（用户在
 *   takeover 期间动过）→ 不写设备、只清镜像
 * - 金丝雀对拍（与 DesatController 同约定）：写温度/激活后回读校验，
 *   防 AIDL 隐式序号被 ROM 重排后静默假成功
 * - 夜灯态由系统持久化（非纯内存）：binder 复活不重断言，只 reconcile
 *   对账——镜像与系统态不符即外部操作，放弃接管清镜像
 * - 主线程约定（与 DesatController 同型）：全部方法假定主线程调用
 */
class NightWarmController(
    context: Context,
    private val prefs: Prefs,
    private val shizuku: DesatController,
) {

    private val appContext = context.applicationContext

    /**
     * v≥2 接管：无活动接管时先快照系统夜灯态 → 写温度 → 未激活则激活。
     * 先写温度再激活：off→on 直接淡入目标暖度，避免"淡入旧温再跳"双动画；
     * 已激活（含用户自开夜灯被接管）仅温度渐变。v≤1 折叠 release()。
     * 快照条件 `!captured || warmLevel==0`：warmLevel==0 即无活动接管，
     * 旧快照必然陈旧（快照后写温度失败的 captured=true+warmLevel=0 残留），
     * 重拍无害且幂等。
     * 成功才更新镜像。
     */
    fun applyLevel(v: Int): DesatController.Result {
        val value = v.coerceIn(0, 100)
        if (value <= 1) return release()
        if (shizuku.state() != DesatController.State.READY) return DesatController.Result.Unavailable
        return try {
            val raw = service()
            if (!prefs.warmNightCaptured || prefs.warmLevel == 0) {
                prefs.captureNight(readActivated(raw), readTemp(raw))
            }
            val k = kelvinFor(value)
            writeTemp(raw, k)
            if (readTemp(raw) != k) {
                throw IllegalStateException("温度回执不符，方法表可能被重排")
            }
            if (!readActivated(raw)) {
                writeActivated(raw, true)
                if (!readActivated(raw)) {
                    throw IllegalStateException("激活回执不符，方法表可能被重排")
                }
            }
            prefs.warmLevel = value
            DesatController.Result.Ok
        } catch (t: Throwable) {
            DesatController.Result.Failed(t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    /**
     * 释放：binder 路径优先——先核对系统温度仍是我方最后写入值（用户在
     * takeover 期间动过 → 尊重、只清镜像不写设备）；未动过则按快照还回：
     * wasOn→还 temp0 保活；!wasOn→关激活后回写 temp0（温度键已被我们覆写，
     * 不还则用户下次开夜灯得到我方残值）。
     * binder 失败降级 Secure 键写 activated=0——仅 !wasOn 执行：wasOn 时
     * 降级无法还温度还会误杀用户原生夜灯，宁留残值由用户自行处理。
     * 成功才清 warmLevel 与捕获标记。
     */
    fun release(): DesatController.Result {
        if (prefs.warmLevel == 0 && !prefs.warmNightCaptured) return DesatController.Result.Ok
        return try {
            releaseViaBinder()
            finishRelease()
            DesatController.Result.Ok
        } catch (t: Throwable) {
            if (releaseViaSettingsKey()) {
                finishRelease()
                DesatController.Result.Ok
            } else {
                DesatController.Result.Failed(t.javaClass.simpleName + ": " + (t.message ?: ""))
            }
        }
    }

    /**
     * RBC 关闭联动释放（主开关/磁贴 turnOff 成功、guard 检出外部置 0 后调用）：
     * 返回 Result 供调用方按需提示；失败时 warmLevel 镜像保留真实状态，
     * 下次 populateMain/reconcile 再收口。
     */
    fun releaseAfterDimOff(): DesatController.Result {
        if (prefs.warmLevel == 0 && !prefs.warmNightCaptured) return DesatController.Result.Ok
        val r = release()
        if (r is DesatController.Result.Failed) {
            Log.w(TAG, "releaseAfterDimOff 失败: ${r.reason}")
        }
        return r
    }

    /**
     * 系统态对账（binder 复活 / READY 首见 / 授权回执后调用）：
     * warmLevel>0 时核对系统夜灯态——夜灯态由系统持久化，binder 复活不丢，
     * 故不做 sat 式重断言；未激活或温度不符即外部操作（手动关/改温/计划
     * 触发），放弃接管只清镜像，尊重用户/系统操作。
     */
    fun reconcile() {
        if (shizuku.state() != DesatController.State.READY) return
        val v = prefs.warmLevel
        if (v <= 0) return
        try {
            val raw = service()
            if (!readActivated(raw) || readTemp(raw) != kelvinFor(v)) {
                Log.w(TAG, "夜灯态与镜像不符（外部改动），放弃接管 v=$v")
                prefs.warmLevel = 0
                prefs.warmNightCaptured = false
            }
        } catch (t: Throwable) {
            // best-effort：读失败保留镜像，下次再对账
        }
    }

    private fun releaseViaBinder() {
        if (shizuku.state() != DesatController.State.READY) {
            throw IllegalStateException("Shizuku 未就绪")
        }
        val raw = service()
        // 外部改动检测：有活动接管（warmLevel>0）时，系统温度与我方最后
        // 写入值不符 → 用户在 takeover 期间动过温度 → 尊重不写设备
        if (prefs.warmLevel > 0 && readTemp(raw) != kelvinFor(prefs.warmLevel)) {
            Log.w(TAG, "释放前夜灯温度被外部改动，放弃设备写")
            return
        }
        if (prefs.warmNightWasOn) {
            // 用户本开着夜灯：还其温度（关态写仅回写键不拉起，覆盖外部关闭象限）
            writeTemp(raw, prefs.warmNightTemp0)
        } else {
            // 前置读避免 no-op 写依赖服务端返回语义；先关再静默回写温度键
            if (readActivated(raw)) {
                writeActivated(raw, false)
            }
            writeTemp(raw, prefs.warmNightTemp0)
        }
    }

    private fun finishRelease() {
        prefs.releaseNight()
    }

    /**
     * 降级释放：Secure 键写 activated=0（ZUI 实测可通知服务；温度键写无效）。
     * wasOn=true 时不执行——降级还不了温度，写 0 只会误杀用户原生夜灯；
     * 无 WSS 权限时显式短路（比靠 SecurityException 异常流更可读）。
     */
    private fun releaseViaSettingsKey(): Boolean {
        if (prefs.warmNightWasOn) return false
        if (!SecureSettingsGate.hasPermission(appContext)) return false
        return try {
            Settings.Secure.putInt(appContext.contentResolver, KEY_NIGHT_ACTIVATED, 0)
        } catch (t: Throwable) {
            false
        }
    }

    private fun service(): android.os.IBinder =
        SystemServiceHelper.getSystemService(SERVICE_COLOR_DISPLAY)
            ?: throw IllegalStateException("color_display binder 为空")

    private fun writeTemp(raw: android.os.IBinder, kelvin: Int) {
        transactWrite(raw, TRANSACTION_SET_NIGHT_TEMP) { it.writeInt(kelvin) }
    }

    private fun writeActivated(raw: android.os.IBinder, on: Boolean) {
        transactWrite(raw, TRANSACTION_SET_NIGHT_ACTIVATED) { it.writeInt(if (on) 1 else 0) }
    }

    private fun readActivated(raw: android.os.IBinder): Boolean =
        transactReadInt(raw, TRANSACTION_IS_NIGHT_ACTIVATED) != 0

    private fun readTemp(raw: android.os.IBinder): Int =
        transactReadInt(raw, TRANSACTION_GET_NIGHT_TEMP)

    /** 同步 setter transact（与 DesatController.writeSaturation 同约定）：服务端 boolean 返回 0 视为拒绝。 */
    private fun transactWrite(raw: android.os.IBinder, code: Int, arg: (android.os.Parcel) -> Unit) {
        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            arg(data)
            if (!ShizukuBinderWrapper(raw).transact(code, data, reply, 0)) {
                throw IllegalStateException("transact 本地返回 false")
            }
            reply.readException()
            if (reply.readInt() == 0) {
                throw IllegalStateException("code=$code 返回 false")
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun transactReadInt(raw: android.os.IBinder, code: Int): Int {
        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!ShizukuBinderWrapper(raw).transact(code, data, reply, 0)) {
                throw IllegalStateException("transact 本地返回 false")
            }
            reply.readException()
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        private const val TAG = "NightWarm"
        private const val SERVICE_COLOR_DISPLAY = "color_display"
        private const val DESCRIPTOR = "android.hardware.display.IColorDisplayManager"

        // AOSP 16 IColorDisplayManager.aidl 隐式顺序号（Y700/ZUI 实机对齐验证）
        private const val TRANSACTION_IS_NIGHT_ACTIVATED = 6
        private const val TRANSACTION_SET_NIGHT_ACTIVATED = 7
        private const val TRANSACTION_GET_NIGHT_TEMP = 8
        private const val TRANSACTION_SET_NIGHT_TEMP = 9

        private const val KEY_NIGHT_ACTIVATED = "night_display_activated"

        /** ZUI 实测钳位区间；AOSP 范围更宽，本区间跨 ROM 合法。 */
        private const val MIN_K = 4000
        private const val MAX_K = 6480

        /** v∈[2,100] → K 线性映射：2→MAX_K（最弱），100→MIN_K（最深）；v≤1 由调用方折叠为释放。 */
        fun kelvinFor(v: Int): Int =
            (MAX_K - (v.coerceIn(1, 100) - 1) / 99.0 * (MAX_K - MIN_K)).toInt()
    }
}
