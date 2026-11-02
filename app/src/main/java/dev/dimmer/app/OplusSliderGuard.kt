package dev.dimmer.app

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * C-3 变更监听：观察 ColorOS 白点值键（global）与本 App activated 键（secure）。
 * 进程存活期内有效；onResume / Tile.onStartListening 做补偿性复查（checkNow）。
 * FR-10：autoReapply ON → 静默重写 level+activated；OFF → 通知前台显示提示。
 * 连续 onChange 在短时窗内去抖合并为一次处理；自动重写前做同值短路。
 */
class OplusSliderGuard(
    private val app: Application,
    private val controller: RbcController,
    private val prefs: Prefs
) {

    interface Listener {
        /** 检测到外部覆盖（ColorOS 滑块介入或 activated 被外部置 0）且未自动重应用。 */
        fun onExternalOverrideEvent()
    }

    @Volatile
    var listener: Listener? = null

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    // 去抖状态：observer 回调已在 handler（主线程）上分发，单线程访问即可
    private var debouncePosted = false
    private var pendingUri: Uri? = null

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // 短时窗合并：连续 onChange 只保留最后一次 uri，去抖为一次处理
            if (uri != null) pendingUri = uri
            if (debouncePosted) return
            debouncePosted = true
            handler.postDelayed({
                debouncePosted = false
                val u = pendingUri
                pendingUri = null
                handleChange(u)
            }, DEBOUNCE_MS)
        }
    }

    fun register() {
        if (registered) return
        val cr = app.contentResolver
        cr.registerContentObserver(
            Settings.Global.getUriFor(RbcController.OPLUS_KEY), false, observer
        )
        cr.registerContentObserver(
            Settings.Secure.getUriFor(RbcController.KEY_ACTIVATED), false, observer
        )
        registered = true
    }

    /** observer 回调：区分触发来源，避免自身写入造成环路。 */
    private fun handleChange(uri: Uri?) {
        val intervention = controller.isOplusIntervening()
        val extOff = prefs.masterOn && controller.readActivated() == 0
        if (!intervention && !extOff) {
            // 介入解除 / 恢复一致：重置一次性提示标记
            prefs.oplusWarned = false
            return
        }
        if (!prefs.masterOn) return
        when {
            // oplus 键只可能被系统写（本 App 永不写），其变化即真实外部事件
            uri == Settings.Global.getUriFor(RbcController.OPLUS_KEY) && intervention ->
                dispatchEvent()
            // activated 被外部置 0（本 App 关闭时会先把 masterOn 置 false，不会走到这里）
            extOff -> dispatchEvent()
        }
    }

    /**
     * 补偿性复查（onResume / Tile.onStartListening 调用）。
     * 返回当前是否处于"系统滑块介入"状态（供 UI 决定警示条显示）。
     */
    fun checkNow(): Boolean {
        val intervention = controller.isOplusIntervening()
        val extOff = prefs.masterOn && controller.readActivated() == 0
        if (intervention || extOff) {
            if (prefs.masterOn) dispatchEvent()
        } else {
            prefs.oplusWarned = false
        }
        return intervention
    }

    private fun dispatchEvent() {
        handler.post {
            // 执行前重查：投递窗口内用户显式 OFF 不重写、不提示
            if (!prefs.masterOn) return@post
            if (prefs.autoReapply && SecureSettingsGate.hasPermission(app)) {
                // FR-10 ON：静默重写 level+activated（幂等）；
                // 同值短路——设备已是目标态则跳过写入（失败由 turnOn 记 lastApplyOk）
                val target = prefs.lastLevel
                val (act, lvl, _) = controller.readBack()
                if (act != 1 || lvl != target) {
                    controller.turnOn(target)
                }
            } else {
                listener?.onExternalOverrideEvent()
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
