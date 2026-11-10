package dev.dimmer.app

import android.app.Application
import android.provider.Settings
import com.google.android.material.color.DynamicColors
import rikka.shizuku.Shizuku

class DimmerApp : Application() {

    /** 覆盖监听器：唯一对外共享组件（MainActivity / DimTileService / DimPresetTileService 取用）。 */
    lateinit var guard: OplusSliderGuard
        private set

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        val prefs = Prefs(this)
        val controller = RbcController(this, prefs)
        val desat = DesatController(prefs)
        val warm = NightWarmController(this, prefs, desat)
        guard = OplusSliderGuard(this, controller, prefs, warm)
        guard.register()
        // 旧版暖色残留一次性迁移：general_color_temperature 非恒等残留会与
        // 夜灯通道双暖叠加——warmLevel>0（旧机制接管中）时写回恒等。
        // K0 不可读属已记档限制（出厂色调丢失与旧版 release 语义相同）
        if (!prefs.warmGeneralMigrated) {
            prefs.warmGeneralMigrated = true
            if (prefs.warmLevel > 0 && SecureSettingsGate.hasPermission(this)) {
                try {
                    Settings.Secure.putString(
                        contentResolver, KEY_GENERAL_TEMP, IDENTITY_TEMP
                    )
                } catch (t: Throwable) {
                    // best-effort：失败下次升级/清数据自愈
                }
            }
        }
        // 降饱和（slot 150，仅存内存）：Shizuku binder 到达（复活/进程重启时
        // 已活）且 satLevel>0 时静默重放。暖色（夜灯通道）由系统持久化，
        // binder 复活不丢，只做 reconcile 对账。sticky 变体覆盖"进程重启时
        // binder 已活"（非 sticky 只在未来到达时触发）
        try {
            Shizuku.addBinderReceivedListenerSticky {
                desat.replayIfNeeded()
                warm.reconcile()
            }
        } catch (t: Throwable) {
            // Shizuku 未安装/未启动属常态，静默
        }
        // 全部依赖就绪后再发布 instance，避免外部拿到半初始化的 guard
        instance = this
    }

    companion object {
        private const val KEY_GENERAL_TEMP = "general_color_temperature"
        private const val IDENTITY_TEMP = "1.0,1.0,1.0"
        var instance: DimmerApp? = null
            private set
    }
}
