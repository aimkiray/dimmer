package dev.dimmer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.materialswitch.MaterialSwitch

/** 唯一 Activity：权限门（态 A）+ 主控界面（态 B）。onResume 重新检测权限并回读状态。 */
class MainActivity : AppCompatActivity(), OplusSliderGuard.Listener {

    private lateinit var prefs: Prefs
    private lateinit var controller: RbcController

    private var suppress = false

    private lateinit var gateGroup: android.widget.ScrollView
    private lateinit var mainGroup: LinearLayout
    private lateinit var tvGrantCmd: TextView
    private lateinit var tvGrantCmdAlt: TextView
    private lateinit var btnCopyGrant: Button
    private lateinit var btnCopyGrantAlt: Button
    private lateinit var btnRecheck: Button
    private lateinit var banner: com.google.android.material.card.MaterialCardView
    private lateinit var tvBannerText: TextView
    private lateinit var btnGoSettings: Button
    private lateinit var btnApplyAnyway: Button
    private lateinit var btnCloseBanner: TextView
    private var bannerDismissed = false
    private lateinit var masterSwitch: MaterialSwitch
    private lateinit var tvMasterState: TextView
    private lateinit var levelSlider: Slider
    private lateinit var tvLevelValue: TextView
    private lateinit var switchPersist: MaterialSwitch
    private lateinit var switchBoot: MaterialSwitch
    private lateinit var switchReapply: MaterialSwitch
    private lateinit var tvReadActivated: TextView
    private lateinit var tvReadLevel: TextView
    private lateinit var tvReadPersist: TextView
    private lateinit var tvReadOplus: TextView
    private lateinit var btnCopyVerify: Button
    private lateinit var btnReapply: Button
    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        controller = RbcController(this, prefs)
        setContentView(R.layout.activity_main)
        bindViews()
        wireGate()
        wireMain()
    }

    override fun onResume() {
        super.onResume()
        DimmerApp.instance?.guard?.listener = this
        // F-04 补偿性复查：observer 仅进程存活期有效，resume 时复核外部覆盖/置 0
        DimmerApp.instance?.guard?.checkNow()
        refreshGate()
    }

    override fun onPause() {
        DimmerApp.instance?.guard?.let { if (it.listener === this) it.listener = null }
        super.onPause()
    }

    private fun bindViews() {
        rootView = findViewById(R.id.root)
        gateGroup = findViewById(R.id.gateScroll)
        mainGroup = findViewById(R.id.mainGroup)
        tvGrantCmd = findViewById(R.id.tvGrantCmd)
        tvGrantCmdAlt = findViewById(R.id.tvGrantCmdAlt)
        btnCopyGrant = findViewById(R.id.btnCopyGrant)
        btnCopyGrantAlt = findViewById(R.id.btnCopyGrantAlt)
        btnRecheck = findViewById(R.id.btnRecheck)
        banner = findViewById(R.id.coexistBanner)
        tvBannerText = findViewById(R.id.tvBannerText)
        btnGoSettings = findViewById(R.id.btnGoSettings)
        btnApplyAnyway = findViewById(R.id.btnApplyAnyway)
        btnCloseBanner = findViewById(R.id.btnCloseBanner)
        btnCloseBanner.setOnClickListener {
            bannerDismissed = true
            banner.visibility = View.GONE
        }
        masterSwitch = findViewById(R.id.masterSwitch)
        tvMasterState = findViewById(R.id.tvMasterState)
        levelSlider = findViewById(R.id.levelSlider)
        tvLevelValue = findViewById(R.id.tvLevelValue)
        switchPersist = findViewById(R.id.switchPersist)
        switchBoot = findViewById(R.id.switchBoot)
        switchReapply = findViewById(R.id.switchReapply)
        tvReadActivated = findViewById(R.id.tvReadActivated)
        tvReadLevel = findViewById(R.id.tvReadLevel)
        tvReadPersist = findViewById(R.id.tvReadPersist)
        tvReadOplus = findViewById(R.id.tvReadOplus)
        btnCopyVerify = findViewById(R.id.btnCopyVerify)
        btnReapply = findViewById(R.id.btnReapply)
    }

    // ---------- 态 A：门界面 ----------

    private fun wireGate() {
        tvGrantCmd.text = SecureSettingsGate.GRANT_COMMAND
        tvGrantCmdAlt.text = SecureSettingsGate.GRANT_COMMAND_ALT
        btnCopyGrant.setOnClickListener { copyToClipboard(SecureSettingsGate.GRANT_COMMAND) }
        btnCopyGrantAlt.setOnClickListener { copyToClipboard(SecureSettingsGate.GRANT_COMMAND_ALT) }
        btnRecheck.setOnClickListener { refreshGate() }
    }

    /** P-2/P-4：检测权限，决定显示门界面或主界面。 */
    private fun refreshGate() {
        if (SecureSettingsGate.hasPermission(this) && SecureSettingsGate.probeWrite(this)) {
            showMain()
        } else {
            showGate()
        }
    }

    private fun showGate() {
        gateGroup.visibility = View.VISIBLE
        mainGroup.visibility = View.GONE
    }

    // ---------- 态 B：主界面 ----------

    private fun wireMain() {
        btnGoSettings.setOnClickListener {
            try {
                startActivity(Intent("android.settings.ACCESSIBILITY_SETTINGS"))
            } catch (t: Throwable) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (t2: Throwable) {
                    snack(getString(R.string.msg_no_settings))
                }
            }
        }
        btnApplyAnyway.setOnClickListener { reapply() }
        btnReapply.setOnClickListener { reapply() }
        btnCopyVerify.setOnClickListener { copyToClipboard(SecureSettingsGate.VERIFY_COMMAND) }

        masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppress) return@setOnCheckedChangeListener
            if (!requirePermissionOrGate()) {
                setSwitchChecked(masterSwitch, !isChecked)
                return@setOnCheckedChangeListener
            }
            val r = if (isChecked) {
                controller.turnOn(levelSlider.value.toInt())
            } else {
                controller.turnOff()
            }
            handleResult(r, okFeedback = true) { populateMain() }
        }

        // EC-12：仅 onStopTrackingTouch 写一次，拖动中只更新数值文本
        levelSlider.addOnChangeListener { _, value, _ ->
            tvLevelValue.text = value.toInt().toString()
        }
        levelSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                if (!requirePermissionOrGate()) return
                val v = slider.value.toInt()
                val r = if (masterSwitch.isChecked) {
                    // 开关 ON 时保持/重写 activated=1
                    controller.turnOn(v)
                } else {
                    controller.setLevel(v)
                }
                handleResult(r, okFeedback = false) { populateMain() }
            }
        })

        switchPersist.setOnCheckedChangeListener { _, isChecked ->
            if (suppress) return@setOnCheckedChangeListener
            if (!requirePermissionOrGate()) {
                setSwitchChecked(switchPersist, !isChecked)
                return@setOnCheckedChangeListener
            }
            handleResult(controller.setPersist(isChecked), okFeedback = true) { populateMain() }
        }

        switchBoot.setOnCheckedChangeListener { _, isChecked ->
            if (suppress) return@setOnCheckedChangeListener
            prefs.autoRestoreOnBoot = isChecked
            snack(getString(R.string.msg_applied))
        }

        switchReapply.setOnCheckedChangeListener { _, isChecked ->
            if (suppress) return@setOnCheckedChangeListener
            prefs.autoReapply = isChecked
            snack(getString(R.string.msg_applied))
            if (isChecked) updateBanner()
        }
    }

    private fun showMain() {
        gateGroup.visibility = View.GONE
        mainGroup.visibility = View.VISIBLE
        populateMain()
    }

    private fun populateMain() {
        val (act, lvl, per) = controller.readBack()
        suppress = true
        try {
            // 有效镜像 = 用户意图 ∧ 上次应用结果：应用失败时镜像不再伪报"开"
            val mirrorOn = prefs.masterOn && prefs.lastApplyOk
            val on = (act ?: if (mirrorOn) 1 else 0) == 1
            masterSwitch.isChecked = on
            // §6-B2 三态：开 / 关 / 未生效（镜像为开但设备读为 0 或读不到）
            tvMasterState.text = when {
                act == 1 -> getString(R.string.state_on)
                prefs.masterOn -> getString(R.string.state_inactive)
                else -> getString(R.string.state_off)
            }
            val level = RbcController.clampLevel(lvl ?: prefs.lastLevel)
            levelSlider.value = level.toFloat()
            tvLevelValue.text = level.toString()
            switchPersist.isChecked = (per ?: if (prefs.persistOn) 1 else 0) == 1
            switchBoot.isChecked = prefs.autoRestoreOnBoot
            switchReapply.isChecked = prefs.autoReapply
        } finally {
            suppress = false
        }
        refreshReadback(act, lvl, per)
        updateBanner()
    }

    /** FR-5 状态回读区：读不到显示"未知"并回退本地镜像（标注"本地"）。 */
    private fun refreshReadback(act: Int?, lvl: Int?, per: Int?) {
        tvReadActivated.text = act?.toString()
            ?: getString(R.string.readback_local, if (prefs.masterOn && prefs.lastApplyOk) 1 else 0)
        tvReadLevel.text = lvl?.toString()
            ?: getString(R.string.readback_local, prefs.lastLevel)
        tvReadPersist.text = per?.toString()
            ?: getString(R.string.readback_local, if (prefs.persistOn) 1 else 0)
        tvReadOplus.text = controller.readOplusLeval()?.toString()
            ?: getString(R.string.readback_unknown)
    }

    /**
     * C-2：警示条条件显示。两种触发源文案/按钮各自自洽——
     * 滑块介入（leval>0）：原文案 + 「去系统设置」「仍然应用」；
     * 仅外部关闭（activated=0 且无滑块介入）：不误归因滑块、隐藏设置入口。
     */
    private fun updateBanner() {
        val intervening = controller.isOplusIntervening()
        val extOff = !intervening && prefs.masterOn && controller.readActivated() == 0
        when {
            intervening && !bannerDismissed -> {
                tvBannerText.text = getString(R.string.banner_text)
                btnGoSettings.visibility = View.VISIBLE
                banner.visibility = View.VISIBLE
            }
            extOff && !bannerDismissed -> {
                tvBannerText.text = getString(R.string.banner_text_extoff)
                btnGoSettings.visibility = View.GONE
                banner.visibility = View.VISIBLE
            }
            else -> {
                banner.visibility = View.GONE
                // 条件消失即解除本次关闭，下一次介入/外部关闭重新弹出
                if (!intervening && !extOff) bannerDismissed = false
            }
        }
    }

    override fun onExternalOverrideEvent() {
        // 回调已由 guard 投递到主线程：整体重填充，同步开关/三态/回读行/警示条
        populateMain()
        if (!prefs.oplusWarned) {
            snack(
                getString(
                    if (controller.isOplusIntervening()) R.string.msg_overridden
                    else R.string.msg_ext_off
                )
            )
            prefs.oplusWarned = true
        }
    }

    /** FR-11 一键重新应用：无视当前状态重写 level+activated。 */
    private fun reapply() {
        if (!requirePermissionOrGate()) return
        handleResult(controller.turnOn(levelSlider.value.toInt()), okFeedback = true) {
            populateMain()
        }
    }

    /** P-5：写路径前的权限判定；失败回门界面。返回 true 表示可继续写。 */
    private fun requirePermissionOrGate(): Boolean {
        if (SecureSettingsGate.hasPermission(this)) return true
        snack(getString(R.string.msg_perm_lost))
        showGate()
        return false
    }

    private fun handleResult(
        r: RbcController.Result,
        okFeedback: Boolean,
        onOk: () -> Unit
    ) {
        when (r) {
            is RbcController.Result.Ok -> {
                if (okFeedback) snack(getString(R.string.msg_applied))
                onOk()
            }
            is RbcController.Result.Denied -> {
                snack(getString(R.string.msg_perm_lost))
                showGate()
            }
            is RbcController.Result.Failed -> {
                snack(getString(R.string.msg_write_failed, r.reason))
                // 视觉态回滚：按设备真值/有效镜像重填充，避免控件停留在未生效的目标态
                populateMain()
            }
        }
    }

    private fun setSwitchChecked(s: MaterialSwitch, checked: Boolean) {
        suppress = true
        try {
            s.isChecked = checked
        } finally {
            suppress = false
        }
    }

    private fun copyToClipboard(text: String) {
        val ok = try {
            val cm = getSystemService(ClipboardManager::class.java)
            if (cm == null) {
                false
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("adb", text))
                true
            }
        } catch (t: Throwable) {
            false
        }
        snack(getString(if (ok) R.string.msg_copied else R.string.msg_copy_failed))
    }

    private fun snack(msg: String) {
        Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT).show()
    }
}
