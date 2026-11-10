package dev.dimmer.app

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 降饱和通道（分支 B）：经 Shizuku 以 shell 身份调用 color_display 的
 * IColorDisplayManager.setSaturationLevel（AOSP 标准面，DTM slot 150，
 * 与 RBC slot 250 互不影响）。
 * - UI 语义 v=0..100（大=强）；API 语义相反（100=原色、0=灰阶）→ 实参 100−v
 * - 状态仅驻内存：重启或 Shizuku 重启后丢失 → binder 复活 / 开机路径按
 *   Prefs.satLevel 静默重放（replayIfNeeded）
 * - 全部调用做异常防御；Shizuku 未存活/未授权时一律不发起 binder 调用
 *
 * 防混淆（评审 F-6）：Android 有三条互不相干的 "saturation" 路径——
 * ① color_display.setSaturationLevel(int 0..100)：本实现，全局去饱和、仅内存；
 * ② display/IDisplayManager.setSaturationLevel(float 0..1)：另一条 SystemApi；
 * ③ persist.sys.sf.color_saturation：Settings 色彩模式（持久化）。
 * 勿改为②/③——语义与持久性均不同。
 */
class DesatController(private val prefs: Prefs) {

    sealed class Result {
        object Ok : Result()
        object Unavailable : Result()
        data class Failed(val reason: String) : Result()
    }

    enum class State { UNAVAILABLE, NEED_PERMISSION, READY }

    fun shizukuAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun shizukuGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** pre-v11 服务端无授权机制（checkSelfPermission 恒 -1）：按不可用兜底，防行停在"点按授权"假态。 */
    private fun shizukuPreV11(): Boolean = try {
        Shizuku.isPreV11()
    } catch (t: Throwable) {
        true
    }

    /** 行可用性：存活 ∧ 已授权 → READY；存活未授权 → NEED_PERMISSION；否则不可用。 */
    fun state(): State = when {
        !shizukuAlive() -> State.UNAVAILABLE
        shizukuPreV11() -> State.UNAVAILABLE
        !shizukuGranted() -> State.NEED_PERMISSION
        else -> State.READY
    }

    /** 用户是否仍可被弹窗询问授权；拒绝且"不再询问"返回 false（异常按 true 兜底尝试授权）。 */
    fun shouldShowRationale(): Boolean = try {
        Shizuku.shouldShowRequestPermissionRationale()
    } catch (t: Throwable) {
        true
    }

    /** 写入档位（钳制 [0,100]）；成功才更新 satLevel 镜像。 */
    fun applyLevel(v: Int): Result {
        if (state() != State.READY) return Result.Unavailable
        val value = v.coerceIn(0, 100)
        return try {
            writeSaturation(100 - value)
            prefs.satLevel = value
            Result.Ok
        } catch (t: Throwable) {
            Result.Failed(t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    /** binder 复活/开机路径的静默重放：仅当档位>0 且通道就绪；异常静默。 */
    fun replayIfNeeded() {
        val v = prefs.satLevel
        if (v <= 0 || state() != State.READY) return
        try {
            writeSaturation(100 - v)
        } catch (t: Throwable) {
            // 静默失败：重放属尽力而为，下一次复活/打开再试
        }
    }

    /** 拉起 Shizuku 授权弹窗（NEED_PERMISSION 态由行点击触发）。 */
    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            // 旧版本 Shizuku 无授权流程，静默
        }
    }

    /**
     * 以 `service call color_display 2 i32 v` 同款裸 transact 调 setSaturationLevel：
     * Parcel = writeInterfaceToken(DESCRIPTOR) + writeInt(v)，code=2（AIDL 序号
     * 与实机 service call 一致）。
     * **同步调用（非 ONEWAY）**：读 reply 回执——readException() 把服务端
     * 异常/code 失配抛回、readInt() 取 setSaturationLevel 的 boolean 返回，
     * 服务端拒绝（false）抛 IllegalStateException，失败经调用方 try-catch
     * 进入 Result.Failed；原 ONEWAY + wrapper 恒 true 会双重吞失败。
     * 不走 Stub.asInterface 反射——ZUI/Android16 上该方法签名不可用（F-10
     * 实机 NoSuchMethodException 实锤）；裸 transact 不依赖隐藏 API 类结构。
     */
    private fun writeSaturation(apiLevel: Int) {
        val raw = SystemServiceHelper.getSystemService(SERVICE_COLOR_DISPLAY)
            ?: throw IllegalStateException("color_display binder 为空")
        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(apiLevel)
            if (!ShizukuBinderWrapper(raw).transact(TRANSACTION_SET_SATURATION, data, reply, 0)) {
                throw IllegalStateException("transact 本地返回 false")
            }
            reply.readException()
            if (reply.readInt() == 0) {
                throw IllegalStateException("setSaturationLevel 返回 false")
            }
            // 金丝雀回读（评审 F-1）：AIDL 方法为隐式顺序编号，ROM 重排可令
            // code2 命中 isDeviceColorManaged（色域设备恒返 1）→ 静默假成功。
            // 追加 isSaturationActivated(code4) 读激活态对拍：写 level<100 应
            // 激活、写 100 应关闭；不符即报失败（错报好过假成功）。激活标志
            // 在服务端 setMatrix 内同步置位，读回值即时可信。
            if (readSaturationActivated(raw) != (apiLevel < 100)) {
                throw IllegalStateException("激活态回执不符，方法表可能被重排")
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** 回读全局去饱和激活态（AIDL code 4，boolean 返回）；供金丝雀校验。 */
    private fun readSaturationActivated(raw: android.os.IBinder): Boolean {
        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!ShizukuBinderWrapper(raw).transact(TRANSACTION_IS_SATURATION_ACTIVATED, data, reply, 0)) {
                throw IllegalStateException("isSaturationActivated transact 返回 false")
            }
            reply.readException()
            reply.readInt() != 0
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        const val REQUEST_CODE = 7001
        private const val SERVICE_COLOR_DISPLAY = "color_display"
        private const val DESCRIPTOR = "android.hardware.display.IColorDisplayManager"
        private const val TRANSACTION_SET_SATURATION = 2
        private const val TRANSACTION_IS_SATURATION_ACTIVATED = 4
    }
}
