package dev.dimmer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import rikka.shizuku.Shizuku
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** 唯一 Activity：权限门（态 A）+ 主控界面（态 B）。onResume 重新检测权限并回读状态。 */
class MainActivity : AppCompatActivity(), OplusSliderGuard.Listener {

    private lateinit var prefs: Prefs
    private lateinit var controller: RbcController
    private lateinit var desat: DesatController
    private lateinit var warm: NightWarmController

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
    private lateinit var rowWarm: View
    private lateinit var warmSlider: Slider
    private lateinit var tvWarmValue: TextView
    private lateinit var tvWarmSub: TextView
    private lateinit var rowSat: View
    private lateinit var satSlider: Slider
    private lateinit var tvSatValue: TextView
    private lateinit var tvSatSub: TextView
    private lateinit var switchPersist: MaterialSwitch
    private lateinit var switchBoot: MaterialSwitch
    private lateinit var switchReapply: MaterialSwitch
    private lateinit var panelSwitch: MaterialSwitch
    private lateinit var rowPanel: View
    private lateinit var tvPanelSub: TextView
    private lateinit var rootView: View

    // 诊断弹层引用：仅弹层展示期间非空，populateMain 顺带刷新
    private var sheetDialog: BottomSheetDialog? = null
    private var sheetView: View? = null
    private var sheetActivated: TextView? = null
    private var sheetLevel: TextView? = null
    private var sheetPersist: TextView? = null
    private var sheetPanel: TextView? = null
    private var lastRead: Triple<Int?, Int?, Int?> = Triple(null, null, null)
    /** 进程内是否已在 READY 态重放过降饱和（授权弹窗期进程死亡→结果丢失的残端兜底）。 */
    private var satReadySeen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // applicationContext：controller 不持 Activity，防生命周期外引用泄漏
        controller = RbcController(applicationContext, prefs)
        desat = DesatController(prefs)
        warm = NightWarmController(applicationContext, prefs, desat)
        setContentView(R.layout.activity_main)
        bindViews()
        wireGate()
        wireMain()
        // Shizuku 授权结果监听挂 Activity 全生命周期（官方 demo 同法）：
        // 授权页是外部 Activity，拉起它必触发本页 onPause——若按
        // onResume/onPause 挂摘，结果回传时监听已被摘除，replayIfNeeded
        // 成为死代码（评审 D:S-1）
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        } catch (t: Throwable) {
            // Shizuku 不可用时静默
        }
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

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        } catch (t: Throwable) {
            // Shizuku 不可用时静默
        }
        sheetDialog?.dismiss()
        super.onDestroy()
    }

    /** Shizuku 授权回调：授权成功则重放本地降饱和档位并刷新行状态。 */
    private val shizukuPermListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != DesatController.REQUEST_CODE) return@OnRequestPermissionResultListener
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                snack(getString(R.string.msg_applied))
                desat.replayIfNeeded()
                warm.reconcile()
            } else {
                snack(getString(R.string.sat_permission_denied))
            }
            populateMain()
        }

    private fun bindViews() {
        rootView = findViewById(R.id.root)
        gateScroll = findViewById(R.id.gateScroll)
        mainGroup = findViewById(R.id.mainGroup)
        // 手势导航栏兜底：底部 inset 转为滚动内容内边距（ScrollView 自身 padding
        // 对未填满视口的内容不生效），保证末项与手势区之间留出间隙
        val minGap = (40 * resources.displayMetrics.density).toInt()
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
        rowWarm = findViewById(R.id.rowWarm)
        warmSlider = findViewById(R.id.warmSlider)
        tvWarmValue = findViewById(R.id.tvWarmValue)
        tvWarmSub = findViewById(R.id.tvWarmSub)
        rowSat = findViewById(R.id.rowSat)
        satSlider = findViewById(R.id.satSlider)
        tvSatValue = findViewById(R.id.tvSatValue)
        tvSatSub = findViewById(R.id.tvSatSub)
        findViewById<View>(R.id.heroHeader).setOnClickListener { masterSwitch.toggle() }
        switchPersist = findViewById(R.id.switchPersist)
        switchBoot = findViewById(R.id.switchBoot)
        switchReapply = findViewById(R.id.switchReapply)
        findViewById<View>(R.id.rowPersist).setOnClickListener { switchPersist.toggle() }
        findViewById<View>(R.id.rowBoot).setOnClickListener { switchBoot.toggle() }
        findViewById<View>(R.id.rowReapply).setOnClickListener { switchReapply.toggle() }
        panelSwitch = findViewById(R.id.panelSwitch)
        rowPanel = findViewById(R.id.rowPanel)
        rowPanel.setOnClickListener { panelSwitch.toggle() }
        tvPanelSub = findViewById(R.id.tvPanelSub)
        findViewById<View>(R.id.rowAdvanced).setOnClickListener { openAdvancedSheet() }
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
        // FR-13 能力门控：非 OPlus 设备无面板层通道，行禁用并标注
        if (!RbcController.hasPanelLayer()) {
            panelSwitch.isEnabled = false
            rowPanel.isEnabled = false
            tvPanelSub.setText(R.string.panel_sub_unsupported)
        }
        masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppress) return@setOnCheckedChangeListener
            if (!requirePermissionOrGate()) {
                setSwitchChecked(masterSwitch, !isChecked)
                return@setOnCheckedChangeListener
            }
            val r = if (isChecked) {
                controller.turnOn(levelSlider.value.toInt())
            } else {
                controller.turnOff().also {
                    // 调暗关闭联动释放暖色（夜灯态独立于 RBC 存活，不放会成僵尸暖色）
                    if (it is RbcController.Result.Ok &&
                        warm.releaseAfterDimOff() is DesatController.Result.Failed
                    ) {
                        snack(getString(R.string.warm_release_failed))
                    }
                }
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

        // 暖色：Shizuku 夜灯通道，与降饱和同域授权；行点击承载"未授权→拉起授权"
        rowWarm.setOnClickListener { onShizukuRowClick() }
        warmSlider.addOnChangeListener { _, value, _ ->
            tvWarmValue.text = value.toInt().toString()
        }
        warmSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                when (val r = warm.applyLevel(slider.value.toInt())) {
                    is DesatController.Result.Ok -> populateMain()
                    is DesatController.Result.Unavailable -> {
                        snack(getString(R.string.sat_sub_unavailable))
                        populateMain()
                    }
                    is DesatController.Result.Failed -> {
                        snack(getString(R.string.msg_write_failed, r.reason))
                        populateMain()
                    }
                }
            }
        })

        // 降饱和：Shizuku 通道，与 RBC 无关；行点击仅承载"未授权→拉起授权"动作
        rowSat.setOnClickListener { onShizukuRowClick() }
        satSlider.addOnChangeListener { _, value, _ ->
            tvSatValue.text = value.toInt().toString()
        }
        satSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                when (val r = desat.applyLevel(slider.value.toInt())) {
                    is DesatController.Result.Ok -> populateMain()
                    is DesatController.Result.Unavailable -> {
                        snack(getString(R.string.sat_sub_unavailable))
                        populateMain()
                    }
                    is DesatController.Result.Failed -> {
                        snack(getString(R.string.msg_write_failed, r.reason))
                        populateMain()
                    }
                }
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

    /** 暖色/降饱和行共用：NEED_PERMISSION 拉起授权（永久拒绝引导设置），UNAVAILABLE 提示激活。 */
    private fun onShizukuRowClick() {
        when (desat.state()) {
            DesatController.State.NEED_PERMISSION ->
                // 拒绝且不再询问时 requestPermission 静默不弹窗，引导到
                // Shizuku 设置内手动开启（评审 D:m 项）
                if (desat.shouldShowRationale()) {
                    desat.requestPermission()
                } else {
                    snack(getString(R.string.sat_permission_permanent))
                }
            DesatController.State.UNAVAILABLE ->
                snack(getString(R.string.sat_sub_unavailable))
            else -> {}
        }
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
            // Shizuku 通道共用就绪态：暖色（夜灯）与降饱和同域
            val satState = desat.state()
            // 进程内首见 READY 幂等重放一次：覆盖"授权弹窗期间进程死亡→结果
            // 丢失"与开机漏放的残端（写同值无害，best-effort）
            if (satState == DesatController.State.READY && !satReadySeen) {
                satReadySeen = true
                desat.replayIfNeeded()
            }
            // 每次 READY 都对账暖色（2 次 binder 读）：后台期间系统夜灯被
            // 外部动过时，镜像在滑块读值前收口，UI 不撒谎
            if (satState == DesatController.State.READY) warm.reconcile()
            // 暖色：RBC 激活才可调；READY 才可升档，非 READY 仅放行降档释放
            // （release 有 Secure 键降级不依赖 Shizuku）——僵尸态留释放路径
            warmSlider.value = prefs.warmLevel.toFloat()
            tvWarmValue.text = prefs.warmLevel.toString()
            warmSlider.isEnabled =
                on && (satState == DesatController.State.READY || prefs.warmLevel > 0)
            rowWarm.isClickable = on && satState != DesatController.State.READY
            rowWarm.isFocusable = rowWarm.isClickable
            tvWarmSub.text = getString(
                when {
                    !on -> R.string.warm_need_rbc
                    satState == DesatController.State.READY -> R.string.warm_sub
                    satState == DesatController.State.NEED_PERMISSION -> R.string.sat_sub_permission
                    else -> R.string.sat_sub_unavailable
                }
            )
            // 降饱和：Shizuku 存活∧已授权才可调；未授权行可点击拉起授权
            satSlider.value = prefs.satLevel.toFloat()
            tvSatValue.text = prefs.satLevel.toString()
            satSlider.isEnabled = satState == DesatController.State.READY
            rowSat.isClickable = satState != DesatController.State.READY
            rowSat.isFocusable = rowSat.isClickable
            tvSatSub.text = getString(
                when (satState) {
                    DesatController.State.READY -> R.string.sat_sub
                    DesatController.State.NEED_PERMISSION -> R.string.sat_sub_permission
                    DesatController.State.UNAVAILABLE -> R.string.sat_sub_unavailable
                }
            )
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
            else -> getString(
                if (RbcController.hasPanelLayer()) R.string.value_unknown
                else R.string.value_unsupported
            )
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
            // 与主界面同一组 inset 源（MDC 自带的 paddingBottomSystemWindowInsets
            // 已在主题中关闭，此处为唯一 inset 写入点）
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            )
            v.updatePadding(bottom = base + bottom)
            insets
        }
        content.findViewById<TextView>(R.id.tvVersion).text = "v" + currentVersion()
        sheetActivated = content.findViewById(R.id.tvReadActivated)
        sheetLevel = content.findViewById(R.id.tvReadLevel)
        sheetPersist = content.findViewById(R.id.tvReadPersist)
        sheetPanel = content.findViewById(R.id.tvReadPanel)
        content.findViewById<TextView>(R.id.tvVerifyCmd).text = SecureSettingsGate.VERIFY_COMMAND
        content.findViewById<View>(R.id.btnCopyVerify)
            .setOnClickListener { copyToClipboard(SecureSettingsGate.VERIFY_COMMAND) }
        // 重新写入降级至弹层：与「当前状态」回读同节，低频兜底操作收进低频入口
        content.findViewById<View>(R.id.btnSheetReapply).setOnClickListener {
            reapply()
            dialog.dismiss()
        }
        content.findViewById<View>(R.id.btnGithub).setOnClickListener { openUrl(GITHUB_URL) }
        // updateUrl 为闭包局部变量：弹层每次打开独立持有，旧弹层的异步回调
        // 不会污染新弹层状态（评审 M-1）
        var updateUrl: String? = null
        val btnUpdate = content.findViewById<TextView>(R.id.btnUpdate)
        btnUpdate.setOnClickListener {
            val url = updateUrl
            if (url != null) {
                openUrl(url)
                return@setOnClickListener
            }
            btnUpdate.text = getString(R.string.update_checking)
            btnUpdate.isEnabled = false
            Thread {
                val result = checkUpdate()
                runOnUiThread {
                    btnUpdate.isEnabled = true
                    when (result) {
                        is UpdateCheck.Failed ->
                            btnUpdate.text = getString(R.string.update_failed)
                        is UpdateCheck.UpToDate ->
                            btnUpdate.text = getString(R.string.update_latest)
                        is UpdateCheck.Available -> {
                            updateUrl = result.url
                            btnUpdate.text = getString(R.string.update_available, result.tag)
                        }
                    }
                }
            }.start()
        }
        sheetView = content
        dialog.setOnDismissListener {
            sheetView = null
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
        // 弹层存活时锚到弹层视图：Snackbar 锚 Activity rootView 会被 bottom sheet 遮挡
        Snackbar.make(sheetView ?: rootView, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            snack(getString(R.string.msg_open_link_failed))
        }
    }

    private fun currentVersion(): String =
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            .versionName ?: "0"

    private sealed interface UpdateCheck {
        data object Failed : UpdateCheck
        data object UpToDate : UpdateCheck
        data class Available(val tag: String, val url: String) : UpdateCheck
    }

    private fun checkUpdate(): UpdateCheck {
        val conn = try {
            URL(RELEASE_API).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            return UpdateCheck.Failed
        }
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "dimmer/" + currentVersion())
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val json =
                        JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.optString("tag_name")
                    if (tag.isEmpty()) UpdateCheck.Failed
                    else if (!isNewer(tag, currentVersion())) UpdateCheck.UpToDate
                    else UpdateCheck.Available(
                        tag,
                        sanitizeReleaseUrl(json.optString("html_url"))
                    )
                }
                // 仓库尚无 release 时该端点返回 404——视为"已是最新"而非网络失败
                HttpURLConnection.HTTP_NOT_FOUND -> UpdateCheck.UpToDate
                else -> UpdateCheck.Failed
            }
        } catch (t: Throwable) {
            UpdateCheck.Failed
        } finally {
            conn.disconnect()
        }
    }

    // 纵深防御：只放行 github.com 的 https 链接，其余回落到仓库主页
    private fun sanitizeReleaseUrl(url: String): String {
        val u = Uri.parse(url)
        return if (u.scheme == "https" && u.host?.endsWith("github.com") == true) {
            url
        } else {
            GITHUB_URL
        }
    }

    // 纯数字点分版本比较：剥 v 前缀与 -pre/+build 后缀后按段逐个比（v0.2.0 > 0.1.9）
    private fun isNewer(tag: String, current: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V')
            .substringBefore('-').substringBefore('+')
            .split('.')
            .map { it.filter { c -> c in '0'..'9' }.toLongOrNull() ?: 0L }
        val a = parts(tag); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }; val y = b.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
    }

    companion object {
        private const val GITHUB_URL = "https://github.com/aimkiray/dimmer"
        private const val RELEASE_API =
            "https://api.github.com/repos/aimkiray/dimmer/releases/latest"
    }
}
