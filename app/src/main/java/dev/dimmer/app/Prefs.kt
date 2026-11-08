package dev.dimmer.app

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences 封装；读写做异常与类型防御。 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("dimmer_prefs", Context.MODE_PRIVATE)

    init {
        // preferredLevel 播种冻结：首次初始化（含升级用户）无键时以 lastLevel 播种一次，
        // 此后 getter 恒命中已存键，不再随 lastLevel 动态回退
        try {
            if (!sp.contains(KEY_PREFERRED_LEVEL)) {
                sp.edit().putInt(KEY_PREFERRED_LEVEL, lastLevel).apply()
            }
        } catch (t: Throwable) {
            // 播种失败仅停留在无键态，由 getter 兜底 DEFAULT_LEVEL
        }
    }

    var lastLevel: Int
        get() = try {
            sp.getInt(KEY_LAST_LEVEL, DEFAULT_LEVEL).coerceIn(0, 100)
        } catch (t: Throwable) {
            DEFAULT_LEVEL
        }
        set(v) = sp.edit().putInt(KEY_LAST_LEVEL, v.coerceIn(0, 100)).apply()

    /**
     * 用户上次显式设置的强度（偏好基准）：首次初始化时以 lastLevel 播种冻结，
     * 此后仅由主界面拖动强度滑块显式设置更新；预设磁贴切换不改写它。
     */
    var preferredLevel: Int
        get() = try {
            sp.getInt(KEY_PREFERRED_LEVEL, DEFAULT_LEVEL).coerceIn(0, 100)
        } catch (t: Throwable) {
            DEFAULT_LEVEL
        }
        set(v) = sp.edit().putInt(KEY_PREFERRED_LEVEL, v.coerceIn(0, 100)).apply()

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

    companion object {
        const val DEFAULT_LEVEL = 100
        private const val KEY_LAST_LEVEL = "last_level"
        private const val KEY_PREFERRED_LEVEL = "preferred_level"
        private const val KEY_MASTER_ON = "master_on"
        private const val KEY_LAST_APPLY_OK = "last_apply_ok"
        private const val KEY_PERSIST_ON = "persist_on"
        private const val KEY_AUTO_RESTORE = "auto_restore_on_boot"
        private const val KEY_AUTO_REAPPLY = "auto_reapply"
    }
}
