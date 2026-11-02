package dev.dimmer.app

import android.content.Context
import android.provider.Settings

/**
 * 唯一直接触 Settings.Secure / Settings.Global 的类。
 * - 写入三元组（activated / level / persist），level 写入前钳制 [0,100]
 * - 尽力回读，失败返回 null
 * - SecurityException 统一映射为 Denied；其它异常映射为 Failed
 * - 成功写入后同步更新 Prefs 本地镜像
 * 绝不写 oplus_customize_reduce_white_point_leval（只读）。
 */
class RbcController(private val context: Context, private val prefs: Prefs) {

    sealed class Result {
        object Ok : Result()
        object Denied : Result()
        data class Failed(val reason: String) : Result()
    }

    private fun putSecureInt(name: String, value: Int): Result {
        if (!SecureSettingsGate.hasPermission(context)) return Result.Denied
        return try {
            val ok = Settings.Secure.putInt(context.contentResolver, name, value)
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
        val r = putSecureInt(KEY_LEVEL, v)
        if (r is Result.Ok) prefs.lastLevel = v
        return r
    }

    /**
     * 先写 level 再写 activated=1，使一次生效即为目标强度。
     * 写链结果记入 lastApplyOk；masterOn（用户意图）只在成功时同步，
     * 失败绝不动它——后台调用方失败由 lastApplyOk 承载。
     */
    fun turnOn(level: Int): Result {
        val r1 = setLevel(level)
        if (r1 !is Result.Ok) {
            prefs.lastApplyOk = false
            return r1
        }
        val r2 = putSecureInt(KEY_ACTIVATED, 1)
        if (r2 is Result.Ok) {
            prefs.masterOn = true
            prefs.lastApplyOk = true
        } else {
            prefs.lastApplyOk = false
        }
        return r2
    }

    /** 只写 activated=0，level 保留以便下次恢复；写链结果同样记入 lastApplyOk。 */
    fun turnOff(): Result {
        val r = putSecureInt(KEY_ACTIVATED, 0)
        if (r is Result.Ok) {
            prefs.masterOn = false
            prefs.lastApplyOk = true
        } else {
            prefs.lastApplyOk = false
        }
        return r
    }

    fun setPersist(on: Boolean): Result {
        val r = putSecureInt(KEY_PERSIST, if (on) 1 else 0)
        if (r is Result.Ok) prefs.persistOn = on
        return r
    }

    /** 尽力回读三元组；读不到对应位置为 null。 */
    fun readBack(): Triple<Int?, Int?, Int?> =
        Triple(readSecureInt(KEY_ACTIVATED), readSecureInt(KEY_LEVEL), readSecureInt(KEY_PERSIST))

    fun readActivated(): Int? = readSecureInt(KEY_ACTIVATED)

    private fun readSecureInt(name: String): Int? = try {
        Settings.Secure.getInt(context.contentResolver, name)
    } catch (t: Throwable) {
        null
    }

    /** 尽力读 ColorOS 白点值键（只读，永不写）。解析失败返回 null。 */
    fun readOplusLeval(): Float? = try {
        Settings.Global.getString(context.contentResolver, OPLUS_KEY)?.toFloatOrNull()
    } catch (t: Throwable) {
        null
    }

    /** C-2：能解析为 float 且 >0 即判定系统滑块已介入；读不到视为未介入。 */
    fun isOplusIntervening(): Boolean = (readOplusLeval() ?: 0f) > 0f

    companion object {
        const val KEY_ACTIVATED = "reduce_bright_colors_activated"
        const val KEY_LEVEL = "reduce_bright_colors_level"
        const val KEY_PERSIST = "reduce_bright_colors_persist_across_reboots"
        const val OPLUS_KEY = "oplus_customize_reduce_white_point_leval"

        fun clampLevel(v: Int): Int = v.coerceIn(0, 100)
    }
}
