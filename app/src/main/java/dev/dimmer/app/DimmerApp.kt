package dev.dimmer.app

import android.app.Application
import com.google.android.material.color.DynamicColors

class DimmerApp : Application() {

    /** 覆盖监听器：唯一对外共享组件（MainActivity / DimTileService 取用）。 */
    lateinit var guard: OplusSliderGuard
        private set

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        val prefs = Prefs(this)
        val controller = RbcController(this, prefs)
        guard = OplusSliderGuard(this, controller, prefs)
        guard.register()
        // 全部依赖就绪后再发布 instance，避免外部拿到半初始化的 guard
        instance = this
    }

    companion object {
        var instance: DimmerApp? = null
            private set
    }
}
