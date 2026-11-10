package dev.dimmer.app

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 唯一直接触 Settings.Secure / Settings.Global 的类。
 * - 写入：RBC secure 三元组（activated / level / persist）+ 面板层 global 开关键
 * - level 写入前钳制 [0,100]
 * - 尽力回读，失败返回 null
 * - SecurityException 统一映射为 Denied；其它异常映射为 Failed
 * - 成功写入后同步更新 Prefs 本地镜像
 * 绝不写 oplus_customize_reduce_white_point_leval 与 screen_brightness。
 */
class RbcController(private val context: Context, private val prefs: Prefs) {

    sealed class Result {
        object Ok : Result()
        object Denied : Result()
        data class Failed(val reason: String) : Result()
    }

    private fun put(global: Boolean, name: String, value: Int): Result {
        if (!SecureSettingsGate.hasPermission(context)) return Result.Denied
        return try {
            val ok = if (global) {
                Settings.Global.putInt(context.contentResolver, name, value)
            } else {
                Settings.Secure.putInt(context.contentResolver, name, value)
            }
            if (ok) Result.Ok else Result.Failed("putInt 返回 false")
        } catch (se: SecurityException) {
            Result.Denied
        } catch (t: Throwable) {
            Result.Failed(t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    /** 写 level（钳制）；成功后更新 lastLevel 镜像。 */
    fun setLevel(level: Int): Result {
        val v = clampLevel(level)
        val r = put(false, KEY_LEVEL, v)
        if (r is Result.Ok) prefs.lastLevel = v
        return r
    }

    /**
     * 先写 level 再写 activated=1，使一次生效即为目标强度。
     * 写链失败（Failed）记入 lastApplyOk；masterOn（用户意图）只在成功时同步，
     * 失败绝不动它——后台调用方失败由 lastApplyOk 承载。
     * Denied 不记 lastApplyOk；链内 Denied 可能发生在 level 已写之后，键可能已
     * 部分写入（含 lastLevel 镜像同步），由回读/镜像兜底。
     */
    fun turnOn(level: Int): Result {
        val r1 = setLevel(level)
        if (r1 !is Result.Ok) {
            // 链首 Denied 即 level 未写入，不记失败；仅真实写入失败才污染镜像
            if (r1 is Result.Failed) prefs.lastApplyOk = false
            return r1
        }
        val r2 = put(false, KEY_ACTIVATED, 1)
        if (r2 is Result.Ok) {
            prefs.masterOn = true
            prefs.lastApplyOk = true
        } else if (r2 is Result.Failed) {
            // 此处 Denied 时 level/lastLevel 已写入：不记 lastApplyOk，由回读/镜像兜底
            prefs.lastApplyOk = false
        }
        return r2
    }

    /** 只写 activated=0，level 保留以便下次恢复；写链失败（Failed）同样记入 lastApplyOk，Denied 不动镜像。 */
    fun turnOff(): Result {
        val r = put(false, KEY_ACTIVATED, 0)
        if (r is Result.Ok) {
            prefs.masterOn = false
            prefs.lastApplyOk = true
        } else if (r is Result.Failed) {
            // 单键写入：Denied 即 activated 未写，不记失败
            prefs.lastApplyOk = false
        }
        return r
    }

    fun setPersist(on: Boolean): Result {
        val r = put(false, KEY_PERSIST, if (on) 1 else 0)
        if (r is Result.Ok) prefs.persistOn = on
        return r
    }

    /** 面板层开关（ColorOS 硬件降白点）：只写 on/off 键，与 RBC 叠加。 */
    fun setPanelDim(on: Boolean): Result =
        put(true, OPLUS_PANEL_KEY, if (on) 1 else 0)

    /** 尽力回读三元组；读不到对应位置为 null。 */
    fun readBack(): Triple<Int?, Int?, Int?> =
        Triple(readSecureInt(KEY_ACTIVATED), readSecureInt(KEY_LEVEL), readSecureInt(KEY_PERSIST))

    fun readActivated(): Int? = readSecureInt(KEY_ACTIVATED)

    fun readLevel(): Int? = readSecureInt(KEY_LEVEL)

    fun readPanelDim(): Int? = try {
        Settings.Global.getInt(context.contentResolver, OPLUS_PANEL_KEY)
    } catch (t: Throwable) {
        null
    }

    private fun readSecureInt(name: String): Int? = try {
        Settings.Secure.getInt(context.contentResolver, name)
    } catch (t: Throwable) {
        null
    }

    companion object {
        const val KEY_ACTIVATED = "reduce_bright_colors_activated"
        const val KEY_LEVEL = "reduce_bright_colors_level"
        const val KEY_PERSIST = "reduce_bright_colors_persist_across_reboots"
        const val OPLUS_LEVAL_KEY = "oplus_customize_reduce_white_point_leval"
        const val OPLUS_PANEL_KEY = "oplus_customize_reduce_white_point_key"

        fun clampLevel(v: Int): Int = v.coerceIn(0, 100)

        /** 面板层通道仅存在于 OPlus 系（ColorOS/OxygenOS）硬件；其它 ROM 无消费者，写入为空转。 */
        fun hasPanelLayer(): Boolean {
            val hay = listOf(Build.BRAND, Build.MANUFACTURER, Build.PRODUCT)
            return hay.any { h -> h != null && OPLUS_BRANDS.any { h.lowercase().contains(it) } }
        }

        private val OPLUS_BRANDS = listOf("oppo", "oneplus", "realme", "oplus")
    }
}
