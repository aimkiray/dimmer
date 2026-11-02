package dev.dimmer.app

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** FR-8 快捷设置磁贴：显示状态、点按切换/重应用；未授权拉起门界面。 */
class DimTileService : TileService() {

    private val prefs: Prefs by lazy { Prefs(this) }
    private val controller: RbcController by lazy { RbcController(this, prefs) }

    override fun onStartListening() {
        super.onStartListening()
        // 补偿性复查（observer 仅进程存活期有效）
        DimmerApp.instance?.guard?.checkNow()
        refreshTile()
    }

    private fun currentActivated(): Int =
        controller.readActivated() ?: if (prefs.masterOn && prefs.lastApplyOk) 1 else 0

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
            val on = currentActivated() == 1
            t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                t.label = getString(R.string.app_name)
                t.subtitle = getString(
                    R.string.tile_subtitle_level,
                    controller.readBack().second ?: prefs.lastLevel
                )
            } else {
                // API 26–28 无 subtitle：等级并入 label 兜底显示
                t.label = getString(
                    R.string.tile_label_level,
                    getString(R.string.app_name),
                    controller.readBack().second ?: prefs.lastLevel
                )
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
        val on = currentActivated() == 1
        val r = if (on) controller.turnOff() else controller.turnOn(prefs.lastLevel)
        when (r) {
            // 镜像不在此改：controller 写成功才同步 masterOn，失败由 lastApplyOk 承载；
            // Denied/Failed 路径对称——都只刷新磁贴（Denied 另拉门界面）
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
            // startActivityAndCollapse 自 API 34 起弃用（官方建议改 startActivity）；
            // minSdk 26 需兼容保留该调用以维持"拉起即折叠面板"语义
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        } catch (t: Throwable) {
            // 静默失败，不崩溃
        }
    }
}
