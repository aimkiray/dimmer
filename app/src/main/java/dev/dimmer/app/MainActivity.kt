package dev.dimmer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.R as MR

/** 唯一 Activity：权限门（态 A）+ 主控界面（态 B）。onResume 重新检测权限并回读状态。 */
class MainActivity : AppCompatActivity(), OplusSliderGuard.Listener {

    private lateinit var prefs: Prefs
    private lateinit var controller: RbcController

    private var suppress = false

    private lateinit var gateScroll: NestedScrollView
    private lateinit var mainGroup: NestedScrollView
    private lateinit var tvGrantCmd: TextView
    private lateinit var tvGrantCmdAlt: TextView
    private lateinit var btnCopyGrant: MaterialButton
    private lateinit var btnCopyGrantAlt: MaterialButton
    private lateinit var btnRecheck: MaterialButton
    private lateinit var heroCard: MaterialCardView
    private lateinit var masterSwitch: MaterialSwitch
    private lateinit var tvMasterState: TextView
    private lateinit var levelSlider: Slider
    private lateinit var tvLevelValue: TextView
    private lateinit var switchPersist: MaterialSwitch
    private lateinit var switchBoot: MaterialSwitch
    private lateinit var switchReapply: MaterialSwitch
    private lateinit var panelSwitch: MaterialSwitch
    private lateinit var btnReapply: MaterialButton
    private lateinit var rootView: View

    // 诊断弹层引用：仅弹层展示期间非空，populateMain 顺带刷新
    private var sheetDialog: BottomSheetDialog? = null
    private var sheetActivated: TextView? = null
    private var sheetLevel: TextView? = null
    private var sheetPersist: TextView? = null
    private var sheetPanel: TextView? = null
    private var lastRead: Triple<Int?, Int?, Int?> = Triple(null, null, null)

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
        gateScroll = findViewById(R.id.gateScroll)
        mainGroup = findViewById(R.id.mainGroup)
        // 手势导航栏兜底：底部 inset 转为滚动内容内边距（ScrollView 自身 padding
        // 对未填满视口的内容不生效），保证末项与手势区之间留出间隙
        val minGap = (16 * resources.displayMetrics.density).toInt()
        listOf(gateScroll, mainGroup).forEach { sv ->
            val content = sv.getChildAt(0)
            val base = content.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(sv) { v, insets ->
                val bottom = maxOf(
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                    insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom,
                    minGap
                )
                content.updatePadding(bottom = base + bottom)
                insets
            }
        }
        tvGrantCmd = findViewById(R.id.tvGrantCmd)
        tvGrantCmdAlt = findViewById(R.id.tvGrantCmdAlt)
        btnCopyGrant = findViewById(R.id.btnCopyGrant)
        btnCopyGrantAlt = findViewById(R.id.btnCopyGrantAlt)
        btnRecheck = findViewById(R.id.btnRecheck)
        heroCard = findViewById(R.id.heroCard)
        masterSwitch = findViewById(R.id.masterSwitch)
        tvMasterState = findViewById(R.id.tvMasterState)
        levelSlider = findViewById(R.id.levelSlider)
        tvLevelValue = findViewById(R.id.tvLevelValue)
        findViewById<View>(R.id.heroHeader).setOnClickListener { masterSwitch.toggle() }
        switchPersist = findViewById(R.id.switchPersist)
        switchBoot = findViewById(R.id.switchBoot)
        switchReapply = findViewById(R.id.switchReapply)
        findViewById<View>(R.id.rowPersist).setOnClickListener { switchPersist.toggle() }
        findViewById<View>(R.id.rowBoot).setOnClickListener { switchBoot.toggle() }
        findViewById<View>(R.id.rowReapply).setOnClickListener { switchReapply.toggle() }
        panelSwitch = findViewById(R.id.panelSwitch)
        findViewById<View>(R.id.rowPanel).setOnClickListener { panelSwitch.toggle() }
        findViewById<View>(R.id.rowAdvanced).setOnClickListener { openAdvancedSheet() }
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
        gateScroll.visibility = View.VISIBLE
        mainGroup.visibility = View.GONE
    }

    // ---------- 态 B：主界面 ----------

    private fun wireMain() {
        btnReapply.setOnClickListener { reapply() }

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
                // 记录用户设置基准（供强度磁贴切换回读）
                prefs.preferredLevel = v
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
        }

        panelSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppress) return@setOnCheckedChangeListener
            if (!requirePermissionOrGate()) {
                setSwitchChecked(panelSwitch, !isChecked)
                return@setOnCheckedChangeListener
            }
            handleResult(controller.setPanelDim(isChecked), okFeedback = true) { populateMain() }
        }
    }

    private fun showMain() {
        gateScroll.visibility = View.GONE
        mainGroup.visibility = View.VISIBLE
        populateMain()
    }

    private fun populateMain() {
        val (act, lvl, per) = controller.readBack()
        lastRead = Triple(act, lvl, per)
        suppress = true
        try {
            // 有效镜像 = 用户意图 ∧ 上次应用结果：应用失败时镜像不再伪报"开"
            val mirrorOn = prefs.masterOn && prefs.lastApplyOk
            val on = (act ?: if (mirrorOn) 1 else 0) == 1
            masterSwitch.isChecked = on
            // 四态：开 / 关 / 未生效(act=0) / 读不到(act=null)——读不到不再与真关混淆
            tvMasterState.text = when {
                act == 1 -> getString(R.string.state_on)
                act == null && prefs.masterOn -> getString(R.string.state_unavailable)
                prefs.masterOn -> getString(R.string.state_inactive)
                else -> getString(R.string.state_off)
            }
            updateHeroColors(on)
            val level = RbcController.clampLevel(lvl ?: prefs.lastLevel)
            levelSlider.value = level.toFloat()
            tvLevelValue.text = level.toString()
            switchPersist.isChecked = (per ?: if (prefs.persistOn) 1 else 0) == 1
            switchBoot.isChecked = prefs.autoRestoreOnBoot
            switchReapply.isChecked = prefs.autoReapply
            panelSwitch.isChecked = controller.readPanelDim() == 1
        } finally {
            suppress = false
        }
        refreshReadback(act, lvl, per)
    }

    /** 主控卡配色：容器恒定 surfaceContainerHigh，开/关由大数字与状态行着色区分。 */
    private fun updateHeroColors(on: Boolean) {
        heroCard.setCardBackgroundColor(
            MaterialColors.getColor(heroCard, MR.attr.colorSurfaceContainerHigh)
        )
        val accent = MaterialColors.getColor(
            heroCard,
            if (on) androidx.appcompat.R.attr.colorPrimary
            else MR.attr.colorOnSurfaceVariant
        )
        tvLevelValue.setTextColor(accent)
        tvMasterState.setTextColor(accent)
    }

    /** FR-5 状态回读：读不到时回退本地镜像并标注；仅弹层展示期间刷新。 */
    private fun refreshReadback(act: Int?, lvl: Int?, per: Int?) {
        val tvAct = sheetActivated ?: return
        val tvLvl = sheetLevel ?: return
        val tvPer = sheetPersist ?: return
        val tvPan = sheetPanel ?: return
        tvAct.text = when (act) {
            1 -> getString(R.string.value_on)
            0 -> getString(R.string.value_off)
            else -> getString(
                R.string.denied_local_str,
                getString(
                    if (prefs.masterOn && prefs.lastApplyOk) R.string.value_on
                    else R.string.value_off
                )
            )
        }
        tvLvl.text = lvl?.toString()
            ?: getString(R.string.denied_local_int, prefs.lastLevel)
        tvPer.text = when (per) {
            1 -> getString(R.string.value_on)
            0 -> getString(R.string.value_off)
            else -> getString(
                R.string.denied_local_str,
                getString(if (prefs.persistOn) R.string.value_on else R.string.value_off)
            )
        }
        tvPan.text = when (controller.readPanelDim()) {
            1 -> getString(R.string.value_on)
            0 -> getString(R.string.value_off)
            else -> getString(R.string.value_unknown)
        }
    }

    /** 诊断底部弹层：视图引用挂到可空字段，dismiss 时置空。 */
    private fun openAdvancedSheet() {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_advanced, null)
        dialog.setContentView(content)
        val scroll = content.findViewById<NestedScrollView>(R.id.sheetScroll)
        val base = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = base + bars.bottom)
            insets
        }
        sheetActivated = content.findViewById(R.id.tvReadActivated)
        sheetLevel = content.findViewById(R.id.tvReadLevel)
        sheetPersist = content.findViewById(R.id.tvReadPersist)
        sheetPanel = content.findViewById(R.id.tvReadPanel)
        content.findViewById<TextView>(R.id.tvVerifyCmd).text = SecureSettingsGate.VERIFY_COMMAND
        content.findViewById<View>(R.id.btnCopyVerify)
            .setOnClickListener { copyToClipboard(SecureSettingsGate.VERIFY_COMMAND) }
        dialog.setOnDismissListener {
            sheetActivated = null
            sheetLevel = null
            sheetPersist = null
            sheetPanel = null
            sheetDialog = null
        }
        sheetDialog = dialog
        refreshReadback(lastRead.first, lastRead.second, lastRead.third)
        dialog.show()
    }

    override fun onExternalChange() {
        // 回调已由 guard 投递到主线程：整体重填充即可
        populateMain()
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
        Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT)
            .setAnchorView(if (mainGroup.visibility == View.VISIBLE) btnReapply else null)
            .show()
    }
}
