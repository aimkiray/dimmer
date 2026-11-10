package dev.dimmer.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** FR-12 快捷设置磁贴：显示状态与当前强度，点按在 100 与上次设置强度（preferredLevel，仅主界面更新）之间切换；未授权拉起门界面。 */
class DimPresetTileService : TileService() {

    private val prefs: Prefs by lazy { Prefs(this) }
    // applicationContext：controller 不持 Service，防生命周期外引用泄漏（与 MainActivity 同约定）
    private val controller: RbcController by lazy { RbcController(applicationContext, prefs) }

    override fun onStartListening() {
        super.onStartListening()
        // 补偿性复查（observer 仅进程存活期有效）
        DimmerApp.instance?.guard?.checkNow()
        refreshTile()
    }

    private fun currentLevel(): Int =
        RbcController.clampLevel(controller.readLevel() ?: prefs.lastLevel)

    private fun refreshTile() {
        val t = qsTile ?: return
        if (!SecureSettingsGate.hasPermission(this)) {
            t.state = Tile.STATE_INACTIVE
            t.label = getString(R.string.tile_label_unauthorized)
            // Tile.setSubtitle 自 API 29 引入；低版本走 label 兜底
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                t.subtitle = null
            }
        } else {
            // 单次回读减少 IPC；activated 读不到回退本地镜像，level 读不到回退 lastLevel
            val (rawActivated, rawLevel) = controller.readBack()
            val activated = rawActivated ?: if (prefs.masterOn && prefs.lastApplyOk) 1 else 0
            val level = RbcController.clampLevel(rawLevel ?: prefs.lastLevel)
            t.state = if (activated == 1) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                t.label = getString(R.string.tile_preset_label)
                t.subtitle = getString(R.string.tile_subtitle_level, level)
            } else {
                // API 26–28 无 subtitle：等级并入 label 兜底显示
                t.label = getString(R.string.tile_preset_label_level, level)
            }
        }
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!SecureSettingsGate.hasPermission(this)) {
            openGate()
            return
        }
        // 满档↔偏好档切换：当前为 100 时回上次设置强度，其余一律跳满档；
        // 关闭态点按等价于以目标档直接开启（turnOn 内部先写 level 再写 activated）
        val target = if (currentLevel() == FULL_LEVEL) prefs.preferredLevel else FULL_LEVEL
        when (controller.turnOn(target)) {
            // 镜像不在此改：controller 写成功才同步 masterOn，写失败由 lastApplyOk 承载
            // （Denied 不记失败，键可能已部分写入）；Denied/Failed 路径对称——都只刷新磁贴（Denied 另拉门界面）
            is RbcController.Result.Denied -> {
                openGate()
                refreshTile()
            }
            else -> refreshTile()
        }
    }

    private fun openGate() {
        try {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // targetSdk≥34 时 Intent 版 startActivityAndCollapse 被系统变更门控，
            // 必抛 UnsupportedOperationException（会被下方 catch 吞掉、门界面拉不起来），
            // 须改走 PendingIntent 重载；API<34 无此门控，保留 Intent 版
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        } catch (t: Throwable) {
            // 静默失败，不崩溃
        }
    }

    companion object {
        const val FULL_LEVEL = 100
    }
}
