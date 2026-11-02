package dev.dimmer.app

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences 封装；读写做异常与类型防御。 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("dimmer_prefs", Context.MODE_PRIVATE)

    var lastLevel: Int
        get() = try {
            sp.getInt(KEY_LAST_LEVEL, DEFAULT_LEVEL).coerceIn(0, 100)
        } catch (t: Throwable) {
            DEFAULT_LEVEL
        }
        set(v) = sp.edit().putInt(KEY_LAST_LEVEL, v.coerceIn(0, 100)).apply()

    /**
     * 用户意图镜像：仅由用户显式操作（总开关 / 磁贴切换成功）修改。
     * 后台应用失败不得改它（吃意图、杀开机自恢复），失败由 lastApplyOk 承载。
     */
    var masterOn: Boolean
        get() = try {
            sp.getBoolean(KEY_MASTER_ON, false)
        } catch (t: Throwable) {
            false
        }
        set(v) = sp.edit().putBoolean(KEY_MASTER_ON, v).apply()

    /** 应用结果镜像：上次 turnOn/turnOff 写链是否成功（失败=false）。与 masterOn（意图）分离。 */
    var lastApplyOk: Boolean
        get() = try {
            sp.getBoolean(KEY_LAST_APPLY_OK, true)
        } catch (t: Throwable) {
            true
        }
        set(v) = sp.edit().putBoolean(KEY_LAST_APPLY_OK, v).apply()

    var autoRestoreOnBoot: Boolean
        get() = try {
            sp.getBoolean(KEY_AUTO_RESTORE, true)
        } catch (t: Throwable) {
            true
        }
        set(v) = sp.edit().putBoolean(KEY_AUTO_RESTORE, v).apply()

    /** persist 键本地镜像：Secure 读被拒 ROM 上供开关回显（写成功后同步）。 */
    var persistOn: Boolean
        get() = try {
            sp.getBoolean(KEY_PERSIST_ON, false)
        } catch (t: Throwable) {
            false
        }
        set(v) = sp.edit().putBoolean(KEY_PERSIST_ON, v).apply()

    var autoReapply: Boolean
        get() = try {
            sp.getBoolean(KEY_AUTO_REAPPLY, false)
        } catch (t: Throwable) {
            false
        }
        set(v) = sp.edit().putBoolean(KEY_AUTO_REAPPLY, v).apply()

    /** 一次性提示标记：本次介入/外部关闭事件是否已提示过（介入解除时复位）。 */
    var oplusWarned: Boolean
        get() = try {
            sp.getBoolean(KEY_OPLUS_WARNED, false)
        } catch (t: Throwable) {
            false
        }
        set(v) = sp.edit().putBoolean(KEY_OPLUS_WARNED, v).apply()

    companion object {
        const val DEFAULT_LEVEL = 100
        private const val KEY_LAST_LEVEL = "last_level"
        private const val KEY_MASTER_ON = "master_on"
        private const val KEY_LAST_APPLY_OK = "last_apply_ok"
        private const val KEY_PERSIST_ON = "persist_on"
        private const val KEY_AUTO_RESTORE = "auto_restore_on_boot"
        private const val KEY_AUTO_REAPPLY = "auto_reapply"
        private const val KEY_OPLUS_WARNED = "oplus_warned"
    }
}
