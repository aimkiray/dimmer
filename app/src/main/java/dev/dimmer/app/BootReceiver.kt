package dev.dimmer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FR-9 开机自恢复：BOOT_COMPLETED 后，
 * 若开关 ON、权限仍在、本地 masterOn=true，则延迟约 5 秒重写
 * level=lastLevel + activated=1；写入幂等，失败重试一次。
 * 降饱和（satLevel>0）同路径尽力重放一次——不受开机恢复开关约束
 * （该开关只管 RBC 恢复）；Shizuku 未活/未授权时内部跳过，
 * 由进程内 binder received listener 兜底。
 * 权限失效时静默跳过（EC-6）。
 * 延时/重试窗口内用户显式 OFF（或关掉自恢复选项）不得被复活：
 * 每次写前重读 prefs.masterOn && autoRestoreOnBoot。
 * 同进程内重复投递（BOOT_COMPLETED 多发）经 inFlight 单飞防重。
 * 写链失败只记 lastApplyOk，绝不覆盖 masterOn（用户意图）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = Prefs(context)
        // 降饱和重放独立于 RBC：satLevel>0 即用户意图，且不受 autoRestoreOnBoot
        // 门控（该开关只管 RBC 恢复），故先于其早退取值；Shizuku 未活时
        // replayIfNeeded 内部跳过（binder 复活由进程内 alive listener 兜底）
        val needSat = prefs.satLevel > 0
        if (!prefs.autoRestoreOnBoot && !needSat) return

        val needRbc = prefs.masterOn && SecureSettingsGate.hasPermission(context)
        if (!needRbc && !needSat) return
        // 单飞：重复投递直接丢弃（process 级原子标记）
        if (!inFlight.compareAndSet(false, true)) return

        val pending = goAsync()
        val controller = RbcController(context.applicationContext, prefs)
        val desat = DesatController(prefs)
        Thread {
            try {
                Thread.sleep(DELAY_MS)
                if (needSat) {
                    try {
                        desat.replayIfNeeded()
                    } catch (t: Throwable) {
                        // 静默失败
                    }
                }
                if (needRbc) {
                    for (attempt in 1..2) {
                        // 每次写前重查：延时窗口内的显式 OFF / 选项变更不被复活
                        if (!prefs.masterOn || !prefs.autoRestoreOnBoot) break
                        val r = controller.turnOn(prefs.lastLevel)
                        if (r is RbcController.Result.Ok) break
                        if (attempt < 2) Thread.sleep(RETRY_MS)
                    }
                }
                // 失败不处理：lastApplyOk 已由 turnOn 置 false，
                // masterOn 保留用户开意图（开机自恢复不被杀死）
            } catch (t: Throwable) {
                // 静默失败，不弹通知不崩溃
            } finally {
                inFlight.set(false)
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val DELAY_MS = 5000L
        private const val RETRY_MS = 2000L
        private val inFlight = AtomicBoolean(false)
    }
}
