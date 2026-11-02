package dev.dimmer.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** WRITE_SECURE_SETTINGS 权限判定与授权命令文案。 */
object SecureSettingsGate {

    const val PERMISSION: String = Manifest.permission.WRITE_SECURE_SETTINGS

    const val GRANT_COMMAND =
        "adb shell pm grant dev.dimmer.app android.permission.WRITE_SECURE_SETTINGS"

    /**
     * 备选授权路径：ColorOS 等拒绝 pm grant（SecurityException，uid2000 无
     * GRANT_RUNTIME_PERMISSIONS）的 ROM 上，重装时 -g 在安装期直接授予清单内
     * 权限（含 WRITE_SECURE_SETTINGS），-r 保留应用数据。
     * <apk路径> 为占位符：使用时替换为本机实际构建出的 app-debug.apk 绝对路径。
     */
    const val GRANT_COMMAND_ALT = "adb install -r -g <apk路径>"

    const val VERIFY_COMMAND = "adb shell dumpsys display | findstr rbc"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * 探针写（可选双重确认）：原设计为"把 activated 当前值原样写回"。
     * 现按同值短路规则不再实际写——欲写值恒等于当前值，写回无实际变更，
     * 且会触发本 App 的 ContentObserver 造成伪覆盖事件。
     * 权限判定采信 checkSelfPermission（读不到键值时同样采信，与旧逻辑一致）。
     */
    fun probeWrite(context: Context): Boolean {
        if (!hasPermission(context)) return false
        return try {
            // 可读性兜底校验：键不可达时仍采信 checkSelfPermission（见上注释）
            Settings.Secure.getInt(context.contentResolver, RbcController.KEY_ACTIVATED)
            true
        } catch (t: Throwable) {
            true
        }
    }
}
