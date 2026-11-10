package dev.dimmer.app

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * C-3 变更监听：观察 ColorOS 白点滑块键（global，拖动该滑块即改写 RBC level）、
 * 本 App activated 键（secure）与面板层开关键（global）。
 * 非 OPlus 设备仅观察 activated 键。
 * 进程存活期内有效；onResume / Tile.onStartListening 做补偿性复查（checkNow）。
 * FR-10：autoReapply ON → 静默重写 level+activated；OFF → 仅通知前台刷新。
 * 连续 onChange 在短时窗内去抖合并为一次处理；自动重写前做同值短路。
 */
class OplusSliderGuard(
    private val app: Application,
    private val controller: RbcController,
    private val prefs: Prefs,
    private val warm: NightWarmController? = null
) {

    interface Listener {
        /** 发生外部变更（滑块改强度 / activated 置 0 / 面板层拨动）：仅刷新 UI。 */
        fun onExternalChange()
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
        // FR-13：非 OPlus 设备无面板层通道，两个 oplus global 键无需观察
        if (RbcController.hasPanelLayer()) {
            cr.registerContentObserver(
                Settings.Global.getUriFor(RbcController.OPLUS_LEVAL_KEY), false, observer
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor(RbcController.OPLUS_PANEL_KEY), false, observer
            )
        }
        cr.registerContentObserver(
            Settings.Secure.getUriFor(RbcController.KEY_ACTIVATED), false, observer
        )
        registered = true
    }

    /** observer 回调：区分触发来源，避免自身写入造成环路。 */
    private fun handleChange(uri: Uri?) {
        when {
            // 面板层键被外部拨动（本 App 也写它，事件只当 UI 刷新信号）
            uri == Settings.Global.getUriFor(RbcController.OPLUS_PANEL_KEY) ->
                listener?.onExternalChange()
            // 系统滑块键只可能被系统设置写（本 App 永不写），变化即真实覆盖事件
            uri == Settings.Global.getUriFor(RbcController.OPLUS_LEVAL_KEY) ->
                if (prefs.masterOn) dispatchEvent()
            // activated 被外部置 0（本 App 关闭时先置 masterOn=false，不会走到这里）
            else -> if (prefs.masterOn && controller.readActivated() == 0) dispatchEvent()
        }
    }

    /** 补偿性复查（onResume / Tile.onStartListening 调用）：只查外部置 0。 */
    fun checkNow() {
        if (prefs.masterOn && controller.readActivated() == 0) dispatchEvent()
    }

    private fun dispatchEvent() {
        handler.post {
            // 执行前重查：投递窗口内用户显式 OFF 不重写
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
                // 外部置 0 且不回写：联动释放暖色，防僵尸暖色残留
                // （释放有 Secure 键降级，不依赖 Shizuku；ZUI 读拒设备
                //   readActivated 恒 null、dispatchEvent 根本不触发，
                //   外部关在此类设备上属不可检测的既有平台限制）
                warm?.releaseAfterDimOff()
            }
            listener?.onExternalChange()
        }
    }

    companion object {
        // 300ms：ColorOS 滑块拖动会连发多次 onChange，短窗合并为一次事件即可
        // 感知"拖动已停"；过长会让外部置 0 的响应明显迟钝
        private const val DEBOUNCE_MS = 300L
    }
}
