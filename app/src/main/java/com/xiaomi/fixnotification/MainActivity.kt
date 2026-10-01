package com.xiaomi.fixnotification

import com.xiaomi.fixnotification.ai.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.Gravity
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xiaomi.fixnotification.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appListAdapter: AppListAdapter? = null
    private var debloatAdapter: DebloatListAdapter? = null

    // Quản lý tab đang chọn để đồng bộ tuyệt đối giữa giao diện và Bottom Navigation
    private var currentTabId = R.id.nav_system

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                importCustomVideo(uri)
            }
        }

    // Quản lý trạng thái cuộn ẩn/hiện thanh menu giống YouTube
    private var isBottomNavVisible = true
    private var accumulatedScroll = 0
    private val scrollThreshold = 30 // pixels

    // Quản lý trạng thái đóng mở popup có hiệu ứng nảy đàn hồi thu nhỏ về nút
    private var activeThemePopupWindow: PopupWindow? = null
    private var activeThemePopupView: View? = null
    private var activeFilterPopupWindow: PopupWindow? = null
    private var activeFilterPopupView: View? = null
    private var activeActionPopupWindow: PopupWindow? = null
    private var activeActionPopupView: View? = null

    private val requestPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuUtils.SHIZUKU_REQ_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    appendLog("[Permission] Đã cấp quyền Shizuku thành công!")
                    loadDebloatApps()
                } else {
                    appendLog("[Permission] Quyền Shizuku bị từ chối.")
                }
                updateShizukuStatus()
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateShizukuStatus()
        appendLog("[Shizuku] Dịch vụ Shizuku đã sẵn sàng.")
        if (ShizukuUtils.hasShizukuPermission()) {
            loadDebloatApps()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        updateShizukuStatus()
        appendLog("[Shizuku] Dịch vụ Shizuku đã ngắt kết nối.")
    }

    private var auroraBgAnimator: android.animation.ValueAnimator? = null
    private var pendingImageCallback: ((Uri) -> Unit)? = null
    private val pickImageLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pendingImageCallback?.invoke(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applySavedTheme(this)
        super.onCreate(savedInstanceState)

        currentTabId = intent?.getIntExtra("KEY_ACTIVE_TAB_ID", currentTabId) ?: currentTabId
        if (savedInstanceState != null) {
            currentTabId = savedInstanceState.getInt("KEY_ACTIVE_TAB_ID", currentTabId)
        }

        // Bật Edge-to-Edge tràn viền toàn màn hình & trong suốt thanh trạng thái / điều hướng
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hiệu ứng chuyển động dải màu Aurora sống động đồng bộ cho toàn bộ giao diện app
        auroraBgAnimator = ThemeUtils.attachAuroraBackground(binding.rootView)

        setupWindowInsets()
        initViews()
        initScrollBehavior()
        initRecyclerView()
        initDebloatListeners()
        initSettings()
        initBottomNav()
        registerShizukuListeners()
        updateShizukuStatus()
        loadInstalledApps()
        loadDebloatApps()

        // Tự động khởi tạo và đồng bộ kho tri thức Drive trong nền ngay khi mở App
        try {
            LimiKnowledgeBase.initIfNeeded(this)
            LimiKnowledgeBase.syncRemoteKnowledge(this)
        } catch (_: Throwable) {}

        setupDoubleBackToExit()

        // Tự động mở bảng thông báo hướng dẫn sử dụng & lưu ý pin quan trọng khi vào app
        binding.root.postDelayed({
            try {
                showGuideWarningDialog(forceShow = false)
            } catch (_: Throwable) {}
        }, 350)
    }

    private var backPressedTime: Long = 0
    private var backToast: Toast? = null

    private fun setupDoubleBackToExit() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentTime = System.currentTimeMillis()
                if (currentTime - backPressedTime < 2000) {
                    backToast?.cancel()
                    finishAffinity()
                } else {
                    backPressedTime = currentTime
                    backToast?.cancel()
                    backToast = Toast.makeText(this@MainActivity, "Vuốt hoặc nhấn quay lại một lần nữa để thoát ứng dụng", Toast.LENGTH_SHORT)
                    backToast?.show()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tabId = intent?.getIntExtra("KEY_ACTIVE_TAB_ID", -1) ?: -1
        if (tabId != -1) {
            selectTab(tabId, animated = true)
        }
    }

    override fun onResume() {
        super.onResume()
        auroraBgAnimator = ThemeUtils.applyBackground(binding.rootView, existingAnimator = auroraBgAnimator)
        try {
            AppUpdateManager.onResumeCheckPendingInstall(this)
        } catch (_: Throwable) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("KEY_ACTIVE_TAB_ID", currentTabId)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            auroraBgAnimator?.cancel()
            auroraBgAnimator = null
        } catch (_: Throwable) {}
        unregisterShizukuListeners()
        executor.shutdown()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navHeight = (64 * resources.displayMetrics.density).toInt()
            val navBottomMargin = (12 * resources.displayMetrics.density).toInt() + systemBars.bottom
            val totalNavHeight = navHeight + navBottomMargin

            // Cho nội dung cuộn thoải mái không bị che bởi thanh dock nổi
            binding.nestedScrollView.updatePadding(
                top = systemBars.top,
                left = systemBars.left,
                right = systemBars.right,
                bottom = totalNavHeight + (32 * resources.displayMetrics.density).toInt()
            )

            // Cập nhật vị trí thanh dock nổi
            (binding.bottomNav.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val baseSideMargin = (16 * resources.displayMetrics.density).toInt()
                lp.leftMargin = baseSideMargin + systemBars.left
                lp.rightMargin = baseSideMargin + systemBars.right
                lp.bottomMargin = navBottomMargin
                binding.bottomNav.layoutParams = lp
            }

            insets
        }
    }

    private fun initScrollBehavior() {
        binding.scrollTerminal.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        binding.nestedScrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY

            if (dy > 0) { // Đang cuộn xuống -> Ẩn thanh menu
                if (accumulatedScroll < 0) accumulatedScroll = 0
                accumulatedScroll += dy
                if (accumulatedScroll > scrollThreshold && isBottomNavVisible) {
                    isBottomNavVisible = false
                    hideBottomNav()
                }
            } else if (dy < 0) { // Đang cuộn lên -> Hiện thanh menu
                if (accumulatedScroll > 0) accumulatedScroll = 0
                accumulatedScroll += dy
                if (accumulatedScroll < -scrollThreshold && !isBottomNavVisible) {
                    isBottomNavVisible = true
                    showBottomNav()
                }
            }
        }
    }

    private fun hideBottomNav() {
        val bottomMargin = (binding.bottomNav.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        val hideDistance = (binding.bottomNav.height + bottomMargin + (40 * resources.displayMetrics.density).toInt()).toFloat()
        binding.bottomNav.animate()
            .translationY(if (hideDistance > 0f) hideDistance else 400f)
            .setInterpolator(FastOutSlowInInterpolator())
            .setDuration(220)
            .start()
    }

    private fun showBottomNav() {
        binding.bottomNav.animate()
            .translationY(0f)
            .setInterpolator(FastOutSlowInInterpolator())
            .setDuration(220)
            .start()
    }

    private fun initViews() {
        // Tag HyperOS: "Hyper" đổi theo theme (đen ở giao diện trắng, trắng ở giao diện tối), "OS" xanh #2F6BFF
        val primaryHex = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(this, R.color.text_primary))
        binding.tvDeviceBadge.text = androidx.core.text.HtmlCompat.fromHtml(
            "<font color='$primaryHex'>Hyper</font><font color='#2F6BFF'>OS</font>",
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        binding.tvDeviceBadge.isClickable = true
        binding.tvDeviceBadge.isFocusable = true
        ViewAnimationExtensions.applySpringTouch(binding.tvDeviceBadge)
        binding.tvDeviceBadge.setOnClickListener {
            openDeveloperOptionsOrDeviceInfo()
        }

        binding.etMilletPackages.setText(FixCommands.DEFAULT_MILLET_PACKAGES)

        binding.btnRequestShizuku.setOnClickListener {
            ShizukuUtils.requestShizukuPermission()
        }

        // Tab 1: System Commands
        binding.btnRunCmd1.setOnClickListener {
            runSingleCommand(1, "Fix FCM & Heartbeat", FixCommands.CMD_1)
        }
        binding.btnCopyCmd1.setOnClickListener {
            copyToClipboard("Lệnh 1: Fix FCM & Heartbeat", FixCommands.CMD_1)
        }

        binding.btnRunCmd2.setOnClickListener {
            runSingleCommand(2, "Tắt Doze & Standby", FixCommands.CMD_2)
        }
        binding.btnCopyCmd2.setOnClickListener {
            copyToClipboard("Lệnh 2: Tắt Doze & Standby", FixCommands.CMD_2)
        }

        binding.btnRunCmd3.setOnClickListener {
            runSingleCommand(3, "Tắt Wi-Fi Power Save & Greezer", FixCommands.CMD_3)
        }
        binding.btnCopyCmd3.setOnClickListener {
            copyToClipboard("Lệnh 3: Tắt Wi-Fi Power Save & Greezer", FixCommands.CMD_3)
        }

        binding.btnRunCmd4.setOnClickListener {
            val packages = binding.etMilletPackages.text.toString()
            val cmd4 = FixCommands.getCmd4(packages)
            runSingleCommand(4, "Miễn trừ MILLET & Sync", cmd4)
        }
        binding.btnCopyCmd4.setOnClickListener {
            val packages = binding.etMilletPackages.text.toString()
            val cmd4 = FixCommands.getCmd4(packages)
            copyToClipboard("Lệnh 4: Miễn trừ MILLET & Sync", cmd4)
        }

        binding.btnRunCustomCommand.setOnClickListener {
            executeCustomCommand()
        }

        val quickCommandChips = listOf(
            Pair(binding.chipCmdWhitelist, "dumpsys deviceidle whitelist"),
            Pair(binding.chipCmdBattery, "dumpsys battery"),
            Pair(binding.chipCmdPackagesE, "pm list packages -e"),
            Pair(binding.chipCmdPackages3, "pm list packages -3"),
            Pair(binding.chipCmdGetprop, "getprop ro.build.version.incremental"),
            Pair(binding.chipCmdPower, "dumpsys power | grep mWakefulness"),
            Pair(binding.chipCmdHideGestureLine, "settings put global hide_gesture_line 1"),
            Pair(binding.chipCmdShowGestureLine, "settings put global hide_gesture_line 0")
        )

        fun updateQuickCmdChipStyles(activeBtn: com.google.android.material.button.MaterialButton?, animated: Boolean = true) {
            val activeBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.nav_active_indicator_pill))
            val inactiveBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.button_capsule_bg))
            val activeStroke = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.nav_active_indicator_stroke))
            val inactiveStroke = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.card_stroke))
            val activeTextColor = ContextCompat.getColor(this, R.color.primary)
            val inactiveTextColor = ContextCompat.getColor(this, R.color.text_secondary)

            for ((chip, _) in quickCommandChips) {
                val isSelected = chip == activeBtn
                if (isSelected) {
                    chip.backgroundTintList = activeBg
                    chip.strokeColor = activeStroke
                    chip.setTextColor(activeTextColor)
                    chip.typeface = Typeface.DEFAULT_BOLD
                    if (animated) {
                        ViewAnimationExtensions.animateBounce(chip)
                    }
                } else {
                    chip.backgroundTintList = inactiveBg
                    chip.strokeColor = inactiveStroke
                    chip.setTextColor(inactiveTextColor)
                    chip.typeface = Typeface.DEFAULT
                }
            }

            activeBtn?.let { btn ->
                binding.scrollChipCommands.post {
                    val scrollX = (btn.left - (binding.scrollChipCommands.width - btn.width) / 2).coerceAtLeast(0)
                    if (animated) {
                        binding.scrollChipCommands.smoothScrollTo(scrollX, 0)
                    } else {
                        binding.scrollChipCommands.scrollTo(scrollX, 0)
                    }
                }
            }
        }

        binding.btnClearCustomCommand.setOnClickListener {
            binding.etCustomCommand.text?.clear()
            updateQuickCmdChipStyles(null, animated = false)
        }

        ViewAnimationExtensions.applySpringTouch(binding.btnOpenGpsFixDialog)
        binding.btnOpenGpsFixDialog.setOnClickListener {
            showGpsFixDialog(it)
        }

        quickCommandChips.forEach { (chip, cmd) ->
            ViewAnimationExtensions.applySpringTouch(chip)
            chip.setOnClickListener {
                updateQuickCmdChipStyles(chip, animated = true)
                binding.etCustomCommand.setText(cmd)
                binding.etCustomCommand.setSelection(cmd.length)
                binding.etCustomCommand.requestFocus()
                Toast.makeText(this@MainActivity, "Đã chọn lệnh: $cmd", Toast.LENGTH_SHORT).show()
            }
        }

        binding.etCustomCommand.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                executeCustomCommand()
                true
            } else {
                false
            }
        }

        binding.btnAddBankPackages.setOnClickListener {
            executor.execute {
                val installedBanks = AppScanner.scanInstalledBankApps(this@MainActivity)
                mainHandler.post {
                    if (installedBanks.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Không tìm thấy app ngân hàng nào đang cài trên máy", Toast.LENGTH_SHORT).show()
                        appendLog("[Bank Scanner] Không phát hiện app ngân hàng nào đang cài đặt trên máy.", LogTarget.FIX)
                        return@post
                    }

                    val currentText = binding.etMilletPackages.text.toString()
                    val currentList = FixCommands.parsePackages(currentText).toMutableList()
                    val addedBankNames = mutableListOf<String>()

                    for ((pkg, name) in installedBanks) {
                        if (!currentList.contains(pkg)) {
                            currentList.add(pkg)
                            addedBankNames.add(name)
                        }
                    }

                    if (addedBankNames.isNotEmpty()) {
                        binding.etMilletPackages.setText(currentList.joinToString(", "))
                        Toast.makeText(this@MainActivity, "Đã thêm ${addedBankNames.size} app ngân hàng có trên máy", Toast.LENGTH_SHORT).show()
                        appendLog("[Bank Scanner] Đã quét và bổ sung ${addedBankNames.size} app ngân hàng có trên máy: ${addedBankNames.joinToString(", ")}", LogTarget.FIX)
                    } else {
                        Toast.makeText(this@MainActivity, "Tất cả các app ngân hàng trên máy (${installedBanks.size} app) đã có trong Lệnh 4", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnResetMilletPackages.setOnClickListener {
            executor.execute {
                val installedBanks = AppScanner.scanInstalledBankApps(this@MainActivity)
                mainHandler.post {
                    val resetList = FixCommands.BASE_MILLET_PACKAGES.toMutableList()
                    val addedNames = mutableListOf<String>()
                    for ((pkg, name) in installedBanks) {
                        if (!resetList.contains(pkg)) {
                            resetList.add(pkg)
                            addedNames.add(name)
                        }
                    }
                    binding.etMilletPackages.setText(resetList.joinToString(", "))
                    if (addedNames.isNotEmpty()) {
                        Toast.makeText(this@MainActivity, "Đã đặt lại app ban đầu + ${addedNames.size} app ngân hàng có trên máy", Toast.LENGTH_SHORT).show()
                        appendLog("[Bank Scanner] Đặt lại danh sách mặc định + ${addedNames.size} app ngân hàng có trên máy (${addedNames.joinToString(", ")})", LogTarget.FIX)
                    } else {
                        Toast.makeText(this@MainActivity, "Đã đặt lại danh sách app ban đầu", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnRunAll.setOnClickListener {
            runAllCommands()
        }

        // Tab 2: Refresh, Search, Select All & Nút Thu Gọn (5 app) / Mở Rộng
        binding.btnRefreshApps.setOnClickListener {
            ViewAnimationExtensions.animateMorphSpin(binding.btnRefreshApps)
            loadInstalledApps()
        }

        binding.btnToggleExpandApps.setOnClickListener {
            val currentCollapsed = appListAdapter?.isCollapsed ?: true
            val newCollapsed = !currentCollapsed
            appListAdapter?.setCollapsed(newCollapsed)
            updateToggleExpandButtonText(newCollapsed)
        }

        binding.cbSelectAllApps.setOnCheckedChangeListener { _, isChecked ->
            appListAdapter?.selectAll(isChecked)
        }

        binding.etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appListAdapter?.filter(s?.toString() ?: "")
                updateToggleExpandButtonText(appListAdapter?.isCollapsed ?: false)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Phím tắt mở cài đặt Xiaomi trong Tab 2
        binding.btnOpenXiaomiAutostart.setOnClickListener {
            appendLog("[Xiaomi] Đang mở Quản lý Tự khởi chạy...", LogTarget.PERMS)
            val autostartIntents = listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartActivity")),
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                Intent().setComponent(ComponentName("com.miui.securityadd", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent().setComponent(ComponentName("com.miui.cleanmaster", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainActivity")),
                Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            )

            var opened = false
            for (intent in autostartIntents) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    opened = true
                    break
                } catch (_: Throwable) {}
            }

            if (!opened) {
                executor.execute {
                    ShizukuUtils.execShizukuCommand(FixCommands.CMD_OPEN_XIAOMI_AUTOSTART)
                }
            }
        }

        binding.btnOpenXiaomiBattery.setOnClickListener {
            appendLog("[Xiaomi] Đang mở Cài đặt tối ưu hóa pin ứng dụng...", LogTarget.PERMS)
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Throwable) {
                executor.execute {
                    ShizukuUtils.execShizukuCommand("am start -a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
                }
            }
        }

        binding.btnGrantAllApps.setOnClickListener {
            runGrantAllApps()
        }


        fun copyTerminalLog(tag: String, text: CharSequence?) {
            var rawContent = text?.toString()?.trim() ?: ""
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            val defaultPlaceholders = listOf(
                "[Debloat] Chờ lệnh gỡ/tắt ứng dụng...",
                "[System] Chờ lệnh...",
                "[System] Đã xóa log.",
                "[App-Perms] Chờ lệnh...",
                "[App-Perms] Đã xóa log.",
                "[Debloat] Đã xóa log."
            )
            val isOnlyPlaceholder = defaultPlaceholders.any { rawContent.equals(it, ignoreCase = true) }

            // Nếu terminal hiện tại trống hoặc chỉ có câu chào mặc định, kiểm tra xem có terminal nào khác có log thực tế không
            if (rawContent.isBlank() || isOnlyPlaceholder) {
                val candidateOutputs = listOf(
                    "Debloat" to binding.tvTerminalOutputDebloat.text?.toString()?.trim(),
                    "Fix Hệ Thống" to binding.tvTerminalOutputFix.text?.toString()?.trim(),
                    "Cấp Quyền" to binding.tvTerminalOutputPerms.text?.toString()?.trim(),
                    "Custom" to binding.tvTerminalOutput.text?.toString()?.trim()
                )
                val activeNonEmpty = candidateOutputs.firstOrNull { (_, txt) ->
                    !txt.isNullOrBlank() && defaultPlaceholders.none { placeholder -> txt.equals(placeholder, ignoreCase = true) }
                }

                if (activeNonEmpty != null && activeNonEmpty.second != null) {
                    rawContent = activeNonEmpty.second!!
                }
            }

            if (rawContent.isBlank() || defaultPlaceholders.any { rawContent.equals(it, ignoreCase = true) }) {
                val lastErr = LimiAiService.lastRecordedError
                if (lastErr != null) {
                    val dateFormatted = try {
                        android.text.format.DateFormat.format("HH:mm:ss dd/MM/yyyy", lastErr.timestamp)
                    } catch (_: Throwable) {
                        "${lastErr.timestamp}"
                    }
                    val lastErrText = """
🚨 [LỖI THỰC THI GẦN NHẤT]:
• Lệnh/Tác vụ: ${lastErr.commandName}
• Câu lệnh: ${lastErr.commandText}
• Mã thoát (Exit Code): ${lastErr.exitCode}
• Lỗi chi tiết (Stderr): ${lastErr.stderr.ifEmpty { "(Trống)" }}
• Đầu ra (Stdout): ${lastErr.stdout.ifEmpty { "(Trống)" }}
• Thời gian: $dateFormatted

💡 Mẹo: Nếu gặp lỗi [-1000] hoặc Permission Denial trên Xiaomi/HyperOS, hãy bật 'Cài đặt qua USB' và 'Gỡ lỗi USB (Cài đặt bảo mật)' trong Tùy chọn nhà phát triển.
                    """.trimIndent()
                    val clip = ClipData.newPlainText("Limi Error Log", lastErrText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "📋 Đã sao chép thông tin lỗi thực thi gần nhất!", Toast.LENGTH_LONG).show()
                    return
                }
                Toast.makeText(this, "Chưa có nhật ký nào để sao chép", Toast.LENGTH_SHORT).show()
                return
            }

            val lines = rawContent.lines().filter { it.isNotBlank() }
            val fullMeaningfulText = lines.joinToString("\n")
            val errorKeywords = listOf(
                "[lỗi]", "[error]", "[fail]", "[fatal]", "exception", "error:", "denied",
                "permission denied", "securityexception", "failure", "not found",
                "crash", "timed out", "mã lỗi", "thất bại", "không thể", "lỗi:",
                "[-1000]", "binder", "ontransact", "deadobject", "status_error", "failure ["
            )

            val errorLineIndices = mutableSetOf<Int>()
            for ((idx, line) in lines.withIndex()) {
                val lower = line.lowercase()
                if (errorKeywords.any { lower.contains(it) }) {
                    val start = (idx - 1).coerceAtLeast(0)
                    val end = (idx + 2).coerceAtMost(lines.size - 1)
                    for (i in start..end) {
                        errorLineIndices.add(i)
                    }
                }
            }

            if (errorLineIndices.isNotEmpty()) {
                val errorExtract = errorLineIndices.sorted().joinToString("\n") { lines[it] }
                val clipText = """
🚨 [ĐOẠN LỖI THỰC THI TERMINAL - $tag]:
$errorExtract

📋 [TOÀN BỘ NHẬT KÝ]:
$fullMeaningfulText
""".trimIndent()
                val clip = ClipData.newPlainText("Terminal Error ($tag)", clipText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 Đã sao chép đoạn lỗi & nhật ký Terminal vào bộ nhớ tạm!", Toast.LENGTH_LONG).show()
            } else {
                val clip = ClipData.newPlainText("Terminal Log ($tag)", fullMeaningfulText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 Đã sao chép nhật ký Terminal vào bộ nhớ tạm!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCopyLog.setOnClickListener { copyTerminalLog("Custom", binding.tvTerminalOutput.text) }
        binding.btnCopyLogFix.setOnClickListener { copyTerminalLog("Fix Hệ Thống", binding.tvTerminalOutputFix.text) }
        binding.btnCopyLogPerms.setOnClickListener { copyTerminalLog("Cấp Quyền", binding.tvTerminalOutputPerms.text) }
        binding.btnCopyLogDebloat.setOnClickListener { copyTerminalLog("Debloat", binding.tvTerminalOutputDebloat.text) }

        listOf(
            binding.btnCopyLog, binding.btnCopyLogFix, binding.btnCopyLogPerms, binding.btnCopyLogDebloat,
            binding.btnClearLog, binding.btnClearLogFix, binding.btnClearLogPerms, binding.btnClearLogDebloat
        ).forEach { ViewAnimationExtensions.applySpringTouch(it) }

        binding.btnClearLog.setOnClickListener {
            binding.tvTerminalOutput.text = "[System] Đã xóa log.\n"
        }

        binding.btnClearLogFix.setOnClickListener {
            binding.tvTerminalOutputFix.text = "[System] Đã xóa log.\n"
        }

        binding.btnClearLogPerms.setOnClickListener {
            binding.tvTerminalOutputPerms.text = "[App-Perms] Đã xóa log.\n"
        }

        binding.btnClearLogDebloat.setOnClickListener {
            binding.tvTerminalOutputDebloat.text = "[Debloat] Đã xóa log.\n"
        }

        // Cho phép người dùng kéo lên / kéo xuống xem lại lịch sử trong bảng Terminal mà không bị màn hình ngoài chặn
        val terminalTouchListener = View.OnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        binding.scrollTerminal.setOnTouchListener(terminalTouchListener)
        binding.tvTerminalOutput.setOnTouchListener(terminalTouchListener)
        binding.scrollTerminalFix.setOnTouchListener(terminalTouchListener)
        binding.tvTerminalOutputFix.setOnTouchListener(terminalTouchListener)
        binding.scrollTerminalPerms.setOnTouchListener(terminalTouchListener)
        binding.tvTerminalOutputPerms.setOnTouchListener(terminalTouchListener)
        binding.scrollTerminalDebloat.setOnTouchListener(terminalTouchListener)
        binding.tvTerminalOutputDebloat.setOnTouchListener(terminalTouchListener)

        binding.btnOpenGuide.setOnClickListener {
            showGuideWarningDialog(forceShow = true, anchorView = it)
        }

        binding.cardOpenAdvanced.setOnClickListener {
            showAdvancedFlagshipDialog(it)
        }

        binding.btnBannerOpenAdvanced.setOnClickListener {
            showAdvancedFlagshipDialog(it)
        }

        val interactiveViews = listOf(
            binding.btnOpenGuide,
            binding.btnRequestShizuku,
            binding.btnRunAll,
            binding.btnRunCmd1,
            binding.btnCopyCmd1,
            binding.btnRunCmd2,
            binding.btnCopyCmd2,
            binding.btnRunCmd3,
            binding.btnCopyCmd3,
            binding.btnRunCmd4,
            binding.btnCopyCmd4,
            binding.btnAddBankPackages,
            binding.btnResetMilletPackages,
            binding.btnRunCustomCommand,
            binding.btnClearCustomCommand,
            binding.btnRefreshApps,
            binding.btnToggleExpandApps,
            binding.btnOpenXiaomiAutostart,
            binding.btnOpenXiaomiBattery,
            binding.btnGrantAllApps,
            binding.cardOpenAdvanced,
            binding.btnBannerOpenAdvanced
        )
        interactiveViews.forEach { view ->
            ViewAnimationExtensions.applySpringTouch(view)
        }

        // ================= KÉO THẢ DI CHUYỂN & CHẠM MỞ AI CHATBOT =================
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        var startRawX = 0f
        var startRawY = 0f
        var initialViewX = 0f
        var initialViewY = 0f
        var isDraggingFab = false

        binding.fabLimiAiChat.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    initialViewX = view.x
                    initialViewY = view.y
                    isDraggingFab = false
                    view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                    if (dist > touchSlop) {
                        isDraggingFab = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    if (isDraggingFab) {
                        val parent = view.parent as? View ?: binding.rootView
                        val parentWidth = parent.width
                        val parentHeight = parent.height

                        val minX = 12f * resources.displayMetrics.density
                        val maxX = (parentWidth - view.width - 12 * resources.displayMetrics.density)
                        val minY = 50f * resources.displayMetrics.density
                        val maxY = (parentHeight - view.height - 76 * resources.displayMetrics.density)

                        val newX = (initialViewX + dx).coerceIn(minX, maxX)
                        val newY = (initialViewY + dy).coerceIn(minY, maxY)

                        view.x = newX
                        view.y = newY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    if (!isDraggingFab) {
                        // Người dùng chạm bấm -> Mở popup trò chuyện Limi AI
                        ViewAnimationExtensions.animateBounce(view)
                        showLimiAiChatDialog()
                    } else {
                        // Tự động hít nhẹ về phía mép trái hoặc phải gần nhất để không cản trở màn hình
                        val parent = view.parent as? View ?: binding.rootView
                        val parentWidth = parent.width
                        val margin = 14f * resources.displayMetrics.density
                        val targetX = if (view.x + view.width / 2f < parentWidth / 2f) {
                            margin
                        } else {
                            parentWidth - view.width - margin
                        }
                        view.animate()
                            .x(targetX)
                            .setDuration(220)
                            .setInterpolator(FastOutSlowInInterpolator())
                            .start()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    false
                }
                else -> false
            }
        }
    }

    private fun copyToClipboard(title: String, cmdText: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(title, cmdText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Đã sao chép: $title", Toast.LENGTH_SHORT).show()
    }

    private fun showGuideWarningDialog(forceShow: Boolean = false, initialTabId: Int = currentTabId, anchorView: View? = null) {
        if (isFinishing || isDestroyed) return

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val shouldShow = prefs.getBoolean("show_guide_on_launch", true)
        if (!forceShow && !shouldShow) {
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_guide_warning, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvGuideDialogSubtitle)
        val scrollGuideTabs = dialogView.findViewById<android.widget.HorizontalScrollView>(R.id.scrollGuideTabs)
        val scrollGuideContent = dialogView.findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollGuideContent)

        val btnTabSystem = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGuideTabSystem)
        val btnTabPerms = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGuideTabPerms)
        val btnTabDebloat = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGuideTabDebloat)
        val btnTabCustom = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGuideTabCustom)

        val layoutSystem = dialogView.findViewById<View>(R.id.layoutGuideSystem)
        val layoutPerms = dialogView.findViewById<View>(R.id.layoutGuidePerms)
        val layoutDebloat = dialogView.findViewById<View>(R.id.layoutGuideDebloat)
        val layoutCustom = dialogView.findViewById<View>(R.id.layoutGuideCustom)

        val guideTabsList = listOf(
            Triple(btnTabSystem, layoutSystem, 1),
            Triple(btnTabPerms, layoutPerms, 2),
            Triple(btnTabCustom, layoutCustom, 3),
            Triple(btnTabDebloat, layoutDebloat, 4)
        )

        guideTabsList.forEach { (btn, _, _) ->
            btn?.let { ViewAnimationExtensions.applySpringTouch(it) }
        }

        var currentSelectedGuideTab = -1

        fun selectGuideTab(tabIndex: Int, animated: Boolean = true) {
            if (currentSelectedGuideTab == tabIndex) return
            currentSelectedGuideTab = tabIndex

            val activeBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.nav_active_indicator_pill))
            val inactiveBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.button_capsule_bg))
            val activeStroke = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.nav_active_indicator_stroke))
            val inactiveStroke = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.card_stroke))
            val activeTextColor = ContextCompat.getColor(this, R.color.text_nav_active)
            val inactiveTextColor = ContextCompat.getColor(this, R.color.text_nav_inactive)

            var selectedButton: com.google.android.material.button.MaterialButton? = null
            var selectedLayout: View? = null

            for ((btn, layout, index) in guideTabsList) {
                val isSelected = index == tabIndex
                if (btn != null) {
                    btn.backgroundTintList = if (isSelected) activeBg else inactiveBg
                    btn.strokeColor = if (isSelected) activeStroke else inactiveStroke
                    btn.setTextColor(if (isSelected) activeTextColor else inactiveTextColor)
                    btn.typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }

                if (isSelected) {
                    selectedButton = btn
                    selectedLayout = layout
                } else {
                    layout?.visibility = View.GONE
                }
            }

            // Animate tab content transition
            selectedLayout?.let { targetLayout ->
                targetLayout.visibility = View.VISIBLE
                if (animated) {
                    targetLayout.alpha = 0f
                    targetLayout.translationY = 20f
                    targetLayout.animate()
                        .alpha(1.0f)
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                        .start()
                    scrollGuideContent?.scrollTo(0, 0)
                } else {
                    targetLayout.alpha = 1.0f
                    targetLayout.translationY = 0f
                }
            }

            // Animate button bounce and smooth scroll
            selectedButton?.let { targetBtn ->
                if (animated) {
                    ViewAnimationExtensions.animateBounce(targetBtn)
                }
                scrollGuideTabs?.post {
                    val scrollX = (targetBtn.left - (scrollGuideTabs.width - targetBtn.width) / 2).coerceAtLeast(0)
                    if (animated) {
                        scrollGuideTabs.smoothScrollTo(scrollX, 0)
                    } else {
                        scrollGuideTabs.scrollTo(scrollX, 0)
                    }
                }
            }

            tvSubtitle?.text = when (tabIndex) {
                1 -> "Tối ưu thông báo, pin & kết nối HyperOS/MIUI"
                2 -> "Cấp quyền màn hình khóa, pop-up và chạy nền"
                3 -> "Chạy lệnh ADB Shell & theo dõi nhật ký Terminal"
                4 -> "Dọn rác & gỡ ứng dụng hệ thống an toàn"
                else -> "Cẩm nang hướng dẫn sử dụng toàn diện"
            }
        }

        btnTabSystem?.setOnClickListener { selectGuideTab(1, animated = true) }
        btnTabPerms?.setOnClickListener { selectGuideTab(2, animated = true) }
        btnTabCustom?.setOnClickListener { selectGuideTab(3, animated = true) }
        btnTabDebloat?.setOnClickListener { selectGuideTab(4, animated = true) }

        // Chọn tab mở đầu theo đúng trang hiện tại
        val defaultTabIndex = when (initialTabId) {
            R.id.nav_permissions -> 2
            R.id.nav_custom -> 3
            R.id.nav_debloat -> 4
            else -> 1
        }
        selectGuideTab(defaultTabIndex, animated = false)

        val cbDoNotShowAgain = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cbDoNotShowAgain)
        val btnDismiss = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDismissGuide)
        val btnCopy = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyFcmCode)
        val btnDial = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialFcmCode)
        val btnCloseHeader = dialogView.findViewById<View>(R.id.btnCloseGuideDialog)

        btnCloseHeader?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnDismiss?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnCopy?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnDial?.let { ViewAnimationExtensions.applySpringTouch(it) }

        cbDoNotShowAgain?.isChecked = !prefs.getBoolean("show_guide_on_launch", true)

        btnCopy?.setOnClickListener { anchor ->
            ViewAnimationExtensions.animateBounce(anchor)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("FCM Code", "*#*#426#*#*")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép: *#*#426#*#*", Toast.LENGTH_SHORT).show()
        }

        btnDial?.setOnClickListener { anchor ->
            ViewAnimationExtensions.animateBounce(anchor)
            try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode("*#*#426#*#*")))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Mở trình gọi điện thoại và bấm: *#*#426#*#*", Toast.LENGTH_LONG).show()
            }
        }

        fun bindGuideCommandText(tvCmdId: Int, cmdText: String) {
            val tvCmd = dialogView.findViewById<TextView>(tvCmdId)
            tvCmd?.let { ViewAnimationExtensions.applySpringTouch(it) }
            tvCmd?.setOnClickListener { v ->
                ViewAnimationExtensions.animateBounce(v)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", cmdText))
                Toast.makeText(this, "Đã sao chép: $cmdText", Toast.LENGTH_SHORT).show()
            }
        }

        bindGuideCommandText(R.id.tvCmdWhitelist, "dumpsys deviceidle whitelist")
        bindGuideCommandText(R.id.tvCmdBattery, "dumpsys battery")
        bindGuideCommandText(R.id.tvCmdPackagesE, "pm list packages -e")
        bindGuideCommandText(R.id.tvCmdPackages3, "pm list packages -3")
        bindGuideCommandText(R.id.tvCmdGetprop, "getprop ro.build.version.incremental")
        bindGuideCommandText(R.id.tvCmdPower, "dumpsys power | grep mWakefulness")
        bindGuideCommandText(R.id.tvCmdHideGesture, "settings put global hide_gesture_line 1")
        bindGuideCommandText(R.id.tvCmdShowGesture, "settings put global hide_gesture_line 0")

        btnCloseHeader?.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }

        btnDismiss?.setOnClickListener {
            val doNotShow = cbDoNotShowAgain?.isChecked ?: false
            prefs.edit().putBoolean("show_guide_on_launch", !doNotShow).apply()
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }

        dialog.show()
        ViewAnimationExtensions.revealDialog(dialogView, anchorView)
    }

    private fun showAdvancedFlagshipDialog(anchorView: View? = null) {
        if (isFinishing || isDestroyed) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_advanced_flagship, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnClose = dialogView.findViewById<ImageView>(R.id.btnCloseAdvancedDialog)
        val btnDismiss = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDismissAdvanced)
        val btnRunAllAdv = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunAllAdvanced)

        // Command 1
        val btnRunAdv1 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunAdv1)
        val btnCopyAdv1 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyAdv1)

        // Command 2
        val btnRunAdv2 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunAdv2)
        val btnCopyAdv2 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyAdv2)

        // Command 3
        val btnRunAdv3 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunAdv3)
        val btnCopyAdv3 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyAdv3)

        // Command 4
        val btnRunAdv4 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunAdv4)
        val btnCopyAdv4 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyAdv4)

        // Command 5
        val btnRunAdv5 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunAdv5)
        val btnCopyAdv5 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyAdv5)

        // Command 6
        val btnRunAdv6 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunAdv6)
        val btnCopyAdv6 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyAdv6)

        // Command 7
        val btnRunAdv7 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunAdv7)
        val btnCopyAdv7 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyAdv7)

        fun copyToClipboard(title: String, cmdText: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(title, cmdText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép: $title", Toast.LENGTH_SHORT).show()
        }

        btnCopyAdv1.setOnClickListener { copyToClipboard("Lệnh NC 1", FixCommands.CMD_ADV_1_MILLET_TRAFFIC) }
        btnCopyAdv2.setOnClickListener { copyToClipboard("Lệnh NC 2", FixCommands.CMD_ADV_2_PHANTOM_KILLER) }
        btnCopyAdv3.setOnClickListener { copyToClipboard("Lệnh NC 3", FixCommands.CMD_ADV_3_GMS_WHITELIST) }
        btnCopyAdv4.setOnClickListener { copyToClipboard("Lệnh NC 4", FixCommands.CMD_ADV_4_FORCE_SYNC) }
        btnCopyAdv5.setOnClickListener { copyToClipboard("Lệnh NC 5", FixCommands.CMD_ADV_5_REFRESH_FCM) }
        btnCopyAdv6.setOnClickListener { copyToClipboard("Lệnh NC 6", FixCommands.CMD_ADV_6_ADAPTIVE_BATTERY) }
        btnCopyAdv7.setOnClickListener { copyToClipboard("Lệnh NC 7", FixCommands.CMD_ADV_7_POWERKEEPER) }

        btnRunAdv1.setOnClickListener {
            runSingleCommand(1, "NC 1: Tắt Millet Traffic", FixCommands.CMD_ADV_1_MILLET_TRAFFIC)
        }
        btnRunAdv2.setOnClickListener {
            runSingleCommand(2, "NC 2: Tắt Phantom Process Killer", FixCommands.CMD_ADV_2_PHANTOM_KILLER)
        }
        btnRunAdv3.setOnClickListener {
            runSingleCommand(3, "NC 3: Bảo Vệ GMS & GSF", FixCommands.CMD_ADV_3_GMS_WHITELIST)
        }
        btnRunAdv4.setOnClickListener {
            runSingleCommand(4, "NC 4: Cưỡng Bức Đồng Bộ Google Push", FixCommands.CMD_ADV_4_FORCE_SYNC)
        }
        btnRunAdv5.setOnClickListener {
            runSingleCommand(5, "NC 5: Làm mới lại FCM Server", FixCommands.CMD_ADV_5_REFRESH_FCM)
        }
        btnRunAdv6.setOnClickListener {
            runSingleCommand(6, "NC 6: Tắt AI Pin Thích Ứng", FixCommands.CMD_ADV_6_ADAPTIVE_BATTERY)
        }
        btnRunAdv7.setOnClickListener {
            runSingleCommand(7, "NC 7: Vô Hiệu Hóa PowerKeeper", FixCommands.CMD_ADV_7_POWERKEEPER)
        }

        btnRunAllAdv.setOnClickListener {
            runAllAdvancedCommands()
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }

        btnClose.setOnClickListener { ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView) }
        btnDismiss.setOnClickListener { ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView) }

        dialog.show()
        ViewAnimationExtensions.revealDialog(dialogView, anchorView)
    }

    private fun runAllAdvancedCommands() {
        if (!ShizukuUtils.hasShizukuPermission()) {
            appendLog("[Lỗi] Chưa được cấp quyền Shizuku.", LogTarget.FIX)
            Toast.makeText(this, "⚠️ Bạn chưa cấp quyền Shizuku để chạy lệnh Flagship!", Toast.LENGTH_LONG).show()
            showShizukuRequiredDialog("Chạy 7 Lệnh Nâng Cao Flagship")
            return
        }

        val advCommands = FixCommands.ADVANCED_COMMANDS

        setButtonsEnabled(false)
        binding.tvTerminalOutputFix.text = ""
        appendLog("[Bắt đầu] Chạy Quy Trình NÂNG CAO Cho Flagship (Lệnh NC 1 -> 7)", LogTarget.FIX)
        appendLog("Áp dụng: Toàn bộ dòng máy Flagship & HyperOS 3 / 2", LogTarget.FIX)
        appendLog("========================================", LogTarget.FIX)

        Toast.makeText(this, "Đang chạy 7 lệnh Nâng Cao Flagship...", Toast.LENGTH_SHORT).show()

        executor.execute {
            try {
                var successCount = 0
                var failCount = 0

                for (item in advCommands) {
                    mainHandler.post {
                        appendLog("\n-> Đang chạy Lệnh NC ${item.index}: ${item.title}", LogTarget.FIX)
                        val displayCmd = if (item.command.length > 120) item.command.take(117) + "..." else item.command
                        appendLog("$ $displayCmd", LogTarget.FIX)
                    }

                    val result = ShizukuUtils.execShizukuCommand(item.command)
                    if (result.exitCode == 0) {
                        if (item.command.contains("max_phantom_processes")) {
                            getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                .edit().putBoolean("phantom_killer_fixed", true).apply()
                        }
                        successCount++
                    } else {
                        failCount++
                    }

                    mainHandler.post {
                        if (result.exitCode == 0) {
                            appendLog("[Lệnh NC ${item.index} OK] Exit Code: 0", LogTarget.FIX)
                            if (result.stdout.isNotEmpty()) {
                                appendLog("Out:\n${result.stdout}", LogTarget.FIX)
                            }
                        } else {
                            LimiAiService.recordLastError("Lệnh NC ${item.index}: ${item.title}", item.command, result.exitCode, result.stderr, result.stdout)
                            appendLog("[Lệnh NC ${item.index} INFO] Exit Code: ${result.exitCode}", LogTarget.FIX)
                            if (result.stderr.isNotEmpty()) {
                                appendLog("Err:\n${result.stderr}", LogTarget.FIX)
                            }
                        }
                    }

                    Thread.sleep(250)
                }

                mainHandler.post {
                    appendLog("\n========================================", LogTarget.FIX)
                    if (failCount == 0) {
                        appendLog("[Hoàn tất] Đã thực hiện thành công toàn bộ ${advCommands.size}/${advCommands.size} Lệnh Nâng Cao Flagship!", LogTarget.FIX)
                        appendLog("[Lưu ý] Khóa đa nhiệm (Lock app) trong RAM và khởi động lại máy để nạp cấu hình.", LogTarget.FIX)
                        Toast.makeText(this@MainActivity, "✅ Đã hoàn tất thành công ${advCommands.size} lệnh Nâng Cao!", Toast.LENGTH_LONG).show()
                    } else {
                        appendLog("[Hoàn tất] Kết quả: $successCount lệnh thành công, $failCount lệnh chưa xong.", LogTarget.FIX)
                        appendLog("[Lưu ý] Vui lòng kiểm tra lại log các lệnh trên.", LogTarget.FIX)
                        Toast.makeText(this@MainActivity, "⚠️ Hoàn tất: $successCount thành công, $failCount thất bại!", Toast.LENGTH_LONG).show()
                    }
                    appendLog("========================================", LogTarget.FIX)
                    setButtonsEnabled(true)
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    appendLog("[Lỗi ngoại lệ Flagship] ${e.localizedMessage ?: "Lỗi không xác định"}", LogTarget.FIX)
                    setButtonsEnabled(true)
                    Toast.makeText(this@MainActivity, "Lỗi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showGpsFixDialog(anchorView: View? = null) {
        if (isFinishing || isDestroyed) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_gps_fix_commands, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnClose = dialogView.findViewById<ImageView>(R.id.btnCloseGpsDialog)
        val btnRunAllGps = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunAllGpsCommands)
        val btnCopyAllGps = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyAllGpsCommands)

        val btnRunGps1 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunGpsCmd1)
        val btnCopyGps1 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyGpsCmd1)

        val btnRunGps2 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunGpsCmd2)
        val btnCopyGps2 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyGpsCmd2)

        val btnRunGps3 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunGpsCmd3)
        val btnCopyGps3 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyGpsCmd3)

        val btnRunGps4 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunGpsCmd4)
        val btnCopyGps4 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyGpsCmd4)

        val btnRunGps5 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunGpsCmd5)
        val btnCopyGps5 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyGpsCmd5)

        val btnRunGps6 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunGpsCmd6)
        val btnCopyGps6 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyGpsCmd6)

        val allGpsActionButtons = listOf(
            btnClose, btnRunAllGps, btnCopyAllGps,
            btnRunGps1, btnCopyGps1,
            btnRunGps2, btnCopyGps2,
            btnRunGps3, btnCopyGps3,
            btnRunGps4, btnCopyGps4,
            btnRunGps5, btnCopyGps5,
            btnRunGps6, btnCopyGps6
        )

        allGpsActionButtons.forEach { btn ->
            btn?.let { ViewAnimationExtensions.applySpringTouch(it) }
        }

        fun copyGpsCmd(title: String, cmdText: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(title, cmdText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép: $title", Toast.LENGTH_SHORT).show()
        }

        val gpsList = FixCommands.GPS_COMMANDS

        btnCopyGps1?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            copyGpsCmd(gpsList[0].title, gpsList[0].command)
        }
        btnCopyGps2?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            copyGpsCmd(gpsList[1].title, gpsList[1].command)
        }
        btnCopyGps3?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            copyGpsCmd(gpsList[2].title, gpsList[2].command)
        }
        btnCopyGps4?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            copyGpsCmd(gpsList[3].title, gpsList[3].command)
        }
        btnCopyGps5?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            copyGpsCmd(gpsList[4].title, gpsList[4].command)
        }
        btnCopyGps6?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            copyGpsCmd(gpsList[5].title, gpsList[5].command)
        }

        btnRunGps1?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            runSingleCommand(1, "GPS 1: ${gpsList[0].title}", gpsList[0].command, target = LogTarget.CUSTOM)
        }
        btnRunGps2?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            runSingleCommand(2, "GPS 2: ${gpsList[1].title}", gpsList[1].command, target = LogTarget.CUSTOM)
        }
        btnRunGps3?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            runSingleCommand(3, "GPS 3: ${gpsList[2].title}", gpsList[2].command, target = LogTarget.CUSTOM)
        }
        btnRunGps4?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            runSingleCommand(4, "GPS 4: ${gpsList[3].title}", gpsList[3].command, target = LogTarget.CUSTOM)
        }
        btnRunGps5?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            runSingleCommand(5, "GPS 5: ${gpsList[4].title}", gpsList[4].command, target = LogTarget.CUSTOM)
        }
        btnRunGps6?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            runSingleCommand(6, "GPS 6: ${gpsList[5].title}", gpsList[5].command, target = LogTarget.CUSTOM)
        }

        btnCopyAllGps?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            copyGpsCmd("Toàn bộ lệnh Fix GPS Việt Nam", FixCommands.ALL_GPS_COMMANDS_TEXT)
        }

        btnRunAllGps?.setOnClickListener { v ->
            ViewAnimationExtensions.animateBounce(v)
            runAllGpsCommands()
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }

        btnClose?.setOnClickListener { ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView) }

        dialog.show()
        dialog.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val dialogWidth = (displayMetrics.widthPixels * 0.94).toInt().coerceAtMost((500 * displayMetrics.density).toInt())
            window.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        ViewAnimationExtensions.revealDialog(dialogView, anchorView)
    }

    private fun runAllGpsCommands() {
        if (!ShizukuUtils.hasShizukuPermission()) {
            appendLog("[Lỗi] Chưa được cấp quyền Shizuku.", LogTarget.CUSTOM)
            Toast.makeText(this, "⚠️ Bạn chưa cấp quyền Shizuku để chạy lệnh GPS!", Toast.LENGTH_LONG).show()
            showShizukuRequiredDialog("Chạy 6 Lệnh GPS Việt Nam")
            return
        }

        val gpsCommands = FixCommands.GPS_COMMANDS

        setButtonsEnabled(false)
        binding.tvTerminalOutput.text = ""
        appendLog("[Bắt đầu] Chạy Quy Trình TỐI ƯU HÓA ĐỊNH VỊ GPS VIỆT NAM (Lệnh 1 -> 6)", LogTarget.CUSTOM)
        appendLog("Cấu hình: NTP Server vn.pool.ntp.org + A-GPS + Fused Location + Quét trong nhà", LogTarget.CUSTOM)
        appendLog("========================================", LogTarget.CUSTOM)

        Toast.makeText(this, "Đang chạy 6 lệnh Fix GPS Việt Nam...", Toast.LENGTH_SHORT).show()

        executor.execute {
            try {
                var successCount = 0
                var failCount = 0

                for (item in gpsCommands) {
                    mainHandler.post {
                        appendLog("\n-> Đang chạy Lệnh GPS ${item.index}: ${item.title}", LogTarget.CUSTOM)
                        val displayCmd = if (item.command.length > 120) item.command.take(117) + "..." else item.command
                        appendLog("$ $displayCmd", LogTarget.CUSTOM)
                    }

                    val result = ShizukuUtils.execShizukuCommand(item.command)
                    if (result.exitCode == 0) {
                        successCount++
                    } else {
                        failCount++
                    }

                    mainHandler.post {
                        if (result.exitCode == 0) {
                            appendLog("[Lệnh GPS ${item.index} OK] Exit Code: 0", LogTarget.CUSTOM)
                            if (result.stdout.isNotEmpty()) {
                                appendLog("Out:\n${result.stdout}", LogTarget.CUSTOM)
                            }
                        } else {
                            LimiAiService.recordLastError("Lệnh GPS ${item.index}: ${item.title}", item.command, result.exitCode, result.stderr, result.stdout)
                            appendLog("[Lệnh GPS ${item.index} INFO] Exit Code: ${result.exitCode}", LogTarget.CUSTOM)
                            if (result.stderr.isNotEmpty()) {
                                appendLog("Err:\n${result.stderr}", LogTarget.CUSTOM)
                            }
                        }
                    }

                    Thread.sleep(250)
                }

                mainHandler.post {
                    appendLog("\n========================================", LogTarget.CUSTOM)
                    if (failCount == 0) {
                        appendLog("[Hoàn tất] Đã thực hiện thành công toàn bộ ${gpsCommands.size}/${gpsCommands.size} Lệnh Fix GPS Việt Nam!", LogTarget.CUSTOM)
                        appendLog("[Kết quả] Đã chuyển máy chủ NTP về Việt Nam & nạp lại quỹ đạo vệ tinh. GPS sẽ bắt sóng tức thì và chuẩn xác từng mét!", LogTarget.CUSTOM)
                        Toast.makeText(this@MainActivity, "✅ Đã hoàn tất thành công ${gpsCommands.size} lệnh Fix GPS!", Toast.LENGTH_LONG).show()
                    } else {
                        appendLog("[Hoàn tất] Kết quả: $successCount lệnh thành công, $failCount lệnh chưa xong.", LogTarget.CUSTOM)
                        appendLog("[Lưu ý] Vui lòng kiểm tra lại log các lệnh trên.", LogTarget.CUSTOM)
                        Toast.makeText(this@MainActivity, "⚠️ Hoàn tất: $successCount thành công, $failCount thất bại!", Toast.LENGTH_LONG).show()
                    }
                    appendLog("========================================", LogTarget.CUSTOM)
                    setButtonsEnabled(true)
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    appendLog("[Lỗi ngoại lệ Fix GPS] ${e.localizedMessage ?: "Lỗi không xác định"}", LogTarget.CUSTOM)
                    setButtonsEnabled(true)
                    Toast.makeText(this@MainActivity, "Lỗi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateToggleExpandButtonText(isCollapsed: Boolean) {
        val total = appListAdapter?.getTotalCount() ?: 0
        if (isCollapsed && total > 5) {
            binding.btnToggleExpandApps.text = "Mở rộng +${total - 5}"
            binding.btnToggleExpandApps.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.button_capsule_bg))
            binding.btnToggleExpandApps.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.glass_card_stroke))
            binding.btnToggleExpandApps.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.rvInstalledApps.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.rvInstalledApps.isNestedScrollingEnabled = false
        } else {
            binding.btnToggleExpandApps.text = "Thu gọn (5 app)"
            binding.btnToggleExpandApps.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.nav_active_indicator_pill))
            binding.btnToggleExpandApps.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.nav_active_indicator_stroke))
            binding.btnToggleExpandApps.setTextColor(ContextCompat.getColor(this, R.color.text_nav_active))
            if (total > 5) {
                // Khung cuộn độc lập cao 360dp cho danh sách app, không làm tràn kéo dài cả trang
                binding.rvInstalledApps.layoutParams.height = (360 * resources.displayMetrics.density).toInt()
                binding.rvInstalledApps.isNestedScrollingEnabled = true
            } else {
                binding.rvInstalledApps.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                binding.rvInstalledApps.isNestedScrollingEnabled = false
            }
        }
        binding.rvInstalledApps.requestLayout()
    }

    private fun initRecyclerView() {
        binding.rvInstalledApps.layoutManager = LinearLayoutManager(this)
        appListAdapter = AppListAdapter(
            allApps = emptyList(),
            onSelectionChanged = { selectedList ->
                val pkgString = selectedList.joinToString(", ")
                binding.etMilletPackages.setText(pkgString)
                binding.cbSelectAllApps.text = "Chọn tất cả (${selectedList.size} apps)"
            },
            onAppClick = { item, anchor ->
                val (bgColorRes, textColorRes) = when (item.category) {
                    "Chat" -> Pair(R.color.badge_chat_bg, R.color.badge_chat_text)
                    "MXH" -> Pair(R.color.badge_mxh_bg, R.color.badge_mxh_text)
                    "Ngân hàng", "Ví điện tử" -> Pair(R.color.badge_bank_bg, R.color.badge_bank_text)
                    "GMS" -> Pair(R.color.badge_gms_bg, R.color.badge_gms_text)
                    "Email" -> Pair(R.color.badge_email_bg, R.color.badge_email_text)
                    else -> Pair(R.color.badge_default_bg, R.color.badge_default_text)
                }
                val autoDesc = when (item.category) {
                    "Chat" -> "Ứng dụng nhắn tin / gọi điện trực tuyến. Cần cấp đầy đủ quyền Thông báo, Màn hình khóa, Cửa sổ Pop-up và Tắt tối ưu hóa pin để không bị trễ tin nhắn khi tắt màn hình."
                    "MXH" -> "Ứng dụng mạng xã hội. Cần quyền tự khởi chạy và chạy nền để nhận thông báo tương tác, bài viết và tin nhắn mới ngay lập tức."
                    "Ngân hàng", "Ví điện tử" -> "Ứng dụng tài chính / ngân hàng. Cần cấp quyền chạy nền và thêm vào Lệnh 4 (Millet Whitelist) để nhận mã OTP và biến động số dư theo thời gian thực."
                    "GMS" -> "Dịch vụ thuộc Google / Google Play Services. Đảm bảo chạy nền liên tục để duy trì kênh kết nối Firebase Cloud Messaging (FCM Push)."
                    "Email" -> "Ứng dụng thư điện tử. Cần quyền tự khởi chạy và chạy nền để đồng bộ email công việc liên tục."
                    else -> "Ứng dụng đang được cài đặt trên máy. Cấp quyền chạy nền sẽ ngăn Xiaomi tự động đóng băng ngầm khi bạn không mở app."
                }
                showAppDetailsDialog(
                    appName = item.name,
                    packageName = item.packageName,
                    badgeText = item.category,
                    badgeBgColor = ContextCompat.getColor(this, bgColorRes),
                    badgeTextColor = ContextCompat.getColor(this, textColorRes),
                    icon = item.icon,
                    description = autoDesc,
                    anchorView = anchor
                )
            }
        )
        binding.rvInstalledApps.adapter = appListAdapter

        // Cho phép cuộn riêng mượt mà bên trong danh sách app khi đã mở rộng
        binding.rvInstalledApps.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                val isExpanded = !(appListAdapter?.isCollapsed ?: true)
                if (isExpanded) {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            rv.parent.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            rv.parent.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                return false
            }
        })

        // Khởi tạo Debloat RecyclerView (Giống hệt trang Cấp Quyền)
        binding.rvDebloatApps.layoutManager = LinearLayoutManager(this)
        binding.rvDebloatApps.setHasFixedSize(true)
        binding.rvDebloatApps.setItemViewCacheSize(25)
        debloatAdapter = DebloatListAdapter(
            allApps = emptyList(),
            onSelectionChanged = { selectedList ->
                binding.tvDebloatSelectedCount.text = "Đã chọn: ${selectedList.size}"
                binding.cbSelectAllDebloat.text = "Chọn tất cả (${selectedList.size} apps)"
            },
            onQuickUninstallClick = { app ->
                debloatSingleApp(app, isUninstall = true)
            },
            onAppClick = { item, anchor ->
                val desc = if (item.description.isNotEmpty()) {
                    "${item.description}\n\n• Loại: ${item.appType.displayName}\n• Khuyến nghị: Có thể gỡ bỏ an toàn hoặc tắt app để giải phóng RAM & tài nguyên ngầm."
                } else {
                    when (item.appType) {
                        AppType.BLOATWARE -> "Ứng dụng bloatware rác không cần thiết. Có thể gỡ cài đặt hoàn toàn để máy nhẹ và mượt hơn."
                        AppType.SYSTEM -> "Dịch vụ / Ứng dụng hệ thống Xiaomi (System Package). Cân nhắc kỹ trước khi gỡ. Nếu nghi ngờ, bạn nên chọn 'Vô hiệu hóa (Tắt app)' thay vì gỡ hẳn."
                        AppType.GOOGLE -> "Ứng dụng / Dịch vụ thuộc hệ sinh thái Google. Chỉ nên gỡ bỏ nếu bạn không sử dụng các tính năng liên quan của Google."
                        AppType.USER -> "Ứng dụng do người dùng cài đặt. Có thể gỡ bỏ hoặc vô hiệu hóa bất kỳ lúc nào mà không ảnh hưởng hệ thống."
                    }
                }
                showAppDetailsDialog(
                    appName = item.name,
                    packageName = item.packageName,
                    badgeText = item.appType.displayName,
                    badgeBgColor = Color.parseColor(item.appType.badgeBgColor),
                    badgeTextColor = Color.parseColor(item.appType.badgeTextColor),
                    icon = item.icon,
                    description = desc,
                    anchorView = anchor
                )
            }
        )
        binding.rvDebloatApps.adapter = debloatAdapter

        // Cho phép cuộn riêng mượt mà bên trong danh sách Debloat khi mở rộng
        binding.rvDebloatApps.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                val isExpanded = !(debloatAdapter?.isCollapsed ?: true)
                if (isExpanded) {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            rv.parent.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            rv.parent.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                return false
            }
        })
    }

    private fun showAppDetailsDialog(
        appName: String,
        packageName: String,
        badgeText: String,
        badgeBgColor: Int,
        badgeTextColor: Int,
        icon: Drawable?,
        description: String = "",
        anchorView: View? = null
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_details, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val ivIcon = dialogView.findViewById<ImageView>(R.id.ivDetailAppIcon)
        val tvName = dialogView.findViewById<TextView>(R.id.tvDetailAppName)
        val tvBadge = dialogView.findViewById<TextView>(R.id.tvDetailAppBadge)
        val tvPkg = dialogView.findViewById<TextView>(R.id.tvDetailPackageName)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tvDetailAppDesc)
        val btnCopy = dialogView.findViewById<View>(R.id.btnCopyDetailPackage)
        val btnOpenSettings = dialogView.findViewById<View>(R.id.btnOpenAppInfoSettings)
        val btnClose = dialogView.findViewById<View>(R.id.btnDetailClose)

        tvName.text = appName
        tvPkg.text = packageName
        tvBadge.text = badgeText

        val badgeDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6 * resources.displayMetrics.density
            setColor(badgeBgColor)
        }
        tvBadge.background = badgeDrawable
        tvBadge.setTextColor(badgeTextColor)

        if (icon != null) {
            ivIcon.setImageDrawable(icon)
        } else {
            ivIcon.setImageResource(R.drawable.ic_default_app_icon)
        }

        if (description.isNotEmpty()) {
            tvDesc.visibility = View.VISIBLE
            tvDesc.text = description
        } else {
            tvDesc.visibility = View.GONE
        }

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Package Name", packageName))
            Toast.makeText(this, "Đã sao chép: $packageName", Toast.LENGTH_SHORT).show()
        }

        btnOpenSettings.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Throwable) {
                Toast.makeText(this, "Không thể mở trang thông tin ứng dụng", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }

        dialog.show()
        ViewAnimationExtensions.revealDialog(dialogView, anchorView)
    }

    private fun updateToggleExpandDebloatButtonText(isCollapsed: Boolean) {
        val total = debloatAdapter?.getTotalCount() ?: 0
        if (isCollapsed && total > 5) {
            binding.btnToggleExpandDebloat.text = "Mở rộng +${total - 5}"
        } else {
            binding.btnToggleExpandDebloat.text = "Thu gọn (5 app)"
            binding.btnToggleExpandDebloat.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.nav_active_indicator_pill))
            binding.btnToggleExpandDebloat.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.nav_active_indicator_stroke))
            binding.btnToggleExpandDebloat.setTextColor(ContextCompat.getColor(this, R.color.text_nav_active))
            if (total > 5) {
                // Khung cuộn độc lập cao 360dp cho danh sách app, cuộn mượt bên trong không làm tràn dài cả trang
                binding.rvDebloatApps.layoutParams.height = (360 * resources.displayMetrics.density).toInt()
                binding.rvDebloatApps.isNestedScrollingEnabled = true
            } else {
                binding.rvDebloatApps.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                binding.rvDebloatApps.isNestedScrollingEnabled = false
            }
        }
        binding.rvDebloatApps.requestLayout()
    }

    private fun initDebloatListeners() {
        ViewAnimationExtensions.applySpringTouch(binding.btnToggleExpandDebloat)
        ViewAnimationExtensions.applySpringTouch(binding.btnRefreshDebloat)
        ViewAnimationExtensions.applySpringTouch(binding.btnDebloatFilterPopup)
        ViewAnimationExtensions.applySpringTouch(binding.btnDebloatActionPopup)

        binding.btnToggleExpandDebloat.setOnClickListener {
            val currentCollapsed = debloatAdapter?.isCollapsed ?: false
            val newCollapsed = !currentCollapsed
            debloatAdapter?.setCollapsed(newCollapsed)
            updateToggleExpandDebloatButtonText(newCollapsed)
        }

        binding.cbSelectAllDebloat.setOnCheckedChangeListener { _, isChecked ->
            debloatAdapter?.selectAll(isChecked)
        }

        binding.etSearchDebloat.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                debloatAdapter?.filterByQuery(s?.toString() ?: "")
                updateToggleExpandDebloatButtonText(debloatAdapter?.isCollapsed ?: false)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnRefreshDebloat.setOnClickListener {
            ViewAnimationExtensions.animateMorphSpin(binding.btnRefreshDebloat)
            loadDebloatApps()
        }

        // 1. Popup Bộ lọc ứng dụng
        binding.btnDebloatFilterPopup.setOnClickListener { view ->
            showDebloatFilterPopup(view)
        }

        // 2. Popup Thao tác gỡ/tắt/khôi phục
        binding.btnDebloatActionPopup.setOnClickListener { view ->
            showDebloatActionPopup(view)
        }
    }

    private fun showDebloatFilterPopup(anchorView: View) {
        if (activeFilterPopupWindow?.isShowing == true) {
            val pView = activeFilterPopupView
            val pWindow = activeFilterPopupWindow
            if (pView != null && pWindow != null) {
                ViewAnimationExtensions.dismissPopup(pView, pWindow, anchorView) {
                    activeFilterPopupWindow = null
                    activeFilterPopupView = null
                }
            }
            return
        }

        val popupView = layoutInflater.inflate(R.layout.popup_debloat_filter, null)
        val targetWidth = if (anchorView.width > 0) anchorView.width else ViewGroup.LayoutParams.WRAP_CONTENT
        val popupWindow = android.widget.PopupWindow(
            popupView,
            targetWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 20f
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
        }

        activeFilterPopupWindow = popupWindow
        activeFilterPopupView = popupView

        popupWindow.setOnDismissListener {
            activeFilterPopupWindow = null
            activeFilterPopupView = null
        }

        popupWindow.setTouchInterceptor { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchorView) {
                    activeFilterPopupWindow = null
                    activeFilterPopupView = null
                }
                true
            } else {
                false
            }
        }

        val currentType = debloatAdapter?.currentFilterType
        val activeBg = R.drawable.bg_theme_item_active
        val inactiveBg = R.drawable.bg_theme_item_inactive

        popupView.findViewById<View>(R.id.itemFilterAll)?.setBackgroundResource(if (currentType == null) activeBg else inactiveBg)
        popupView.findViewById<View>(R.id.itemFilterBloatware)?.setBackgroundResource(if (currentType == AppType.BLOATWARE) activeBg else inactiveBg)
        popupView.findViewById<View>(R.id.itemFilterSystem)?.setBackgroundResource(if (currentType == AppType.SYSTEM) activeBg else inactiveBg)
        popupView.findViewById<View>(R.id.itemFilterUser)?.setBackgroundResource(if (currentType == AppType.USER) activeBg else inactiveBg)

        popupView.findViewById<View>(R.id.ivCheckFilterAll)?.visibility = if (currentType == null) View.VISIBLE else View.INVISIBLE
        popupView.findViewById<View>(R.id.ivCheckFilterBloatware)?.visibility = if (currentType == AppType.BLOATWARE) View.VISIBLE else View.INVISIBLE
        popupView.findViewById<View>(R.id.ivCheckFilterSystem)?.visibility = if (currentType == AppType.SYSTEM) View.VISIBLE else View.INVISIBLE
        popupView.findViewById<View>(R.id.ivCheckFilterUser)?.visibility = if (currentType == AppType.USER) View.VISIBLE else View.INVISIBLE

        popupView.findViewById<View>(R.id.itemFilterAll).setOnClickListener {
            debloatAdapter?.filterByType(null)
            binding.btnDebloatFilterPopup.text = "Bộ lọc: Tất cả ▾"
            updateToggleExpandDebloatButtonText(debloatAdapter?.isCollapsed ?: false)
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchorView)
        }

        popupView.findViewById<View>(R.id.itemFilterBloatware).setOnClickListener {
            debloatAdapter?.filterByType(AppType.BLOATWARE)
            binding.btnDebloatFilterPopup.text = "Bộ lọc: Bloatware ▾"
            updateToggleExpandDebloatButtonText(debloatAdapter?.isCollapsed ?: false)
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchorView)
        }

        popupView.findViewById<View>(R.id.itemFilterSystem).setOnClickListener {
            debloatAdapter?.filterByType(AppType.SYSTEM)
            binding.btnDebloatFilterPopup.text = "Bộ lọc: Hệ Thống ▾"
            updateToggleExpandDebloatButtonText(debloatAdapter?.isCollapsed ?: false)
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchorView)
        }

        popupView.findViewById<View>(R.id.itemFilterUser).setOnClickListener {
            debloatAdapter?.filterByType(AppType.USER)
            binding.btnDebloatFilterPopup.text = "Bộ lọc: Người Dùng ▾"
            updateToggleExpandDebloatButtonText(debloatAdapter?.isCollapsed ?: false)
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchorView)
        }

        popupWindow.showAsDropDown(anchorView, 0, (4 * resources.displayMetrics.density).toInt())
        ViewAnimationExtensions.revealPopupFromAnchor(popupView, anchorView)
    }

    private fun showDebloatActionPopup(anchorView: View) {
        if (activeActionPopupWindow?.isShowing == true) {
            val pView = activeActionPopupView
            val pWindow = activeActionPopupWindow
            if (pView != null && pWindow != null) {
                ViewAnimationExtensions.dismissPopup(pView, pWindow, anchorView) {
                    activeActionPopupWindow = null
                    activeActionPopupView = null
                }
            }
            return
        }

        val popupView = layoutInflater.inflate(R.layout.popup_debloat_action, null)
        val targetWidth = if (anchorView.width > 0) anchorView.width else ViewGroup.LayoutParams.WRAP_CONTENT
        val popupWindow = android.widget.PopupWindow(
            popupView,
            targetWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 20f
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
        }

        activeActionPopupWindow = popupWindow
        activeActionPopupView = popupView

        popupWindow.setOnDismissListener {
            activeActionPopupWindow = null
            activeActionPopupView = null
        }

        popupWindow.setTouchInterceptor { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchorView) {
                    activeActionPopupWindow = null
                    activeActionPopupView = null
                }
                true
            } else {
                false
            }
        }

        popupView.findViewById<View>(R.id.itemActionUninstall).setOnClickListener {
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchorView)
            debloatSelectedApps(isUninstall = true)
        }

        popupView.findViewById<View>(R.id.itemActionDisable).setOnClickListener {
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchorView)
            debloatSelectedApps(isUninstall = false)
        }

        popupView.findViewById<View>(R.id.itemActionRestore).setOnClickListener {
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchorView)
            showRestoreUninstalledAppsDialog(anchorView)
        }

        popupView.findViewById<View>(R.id.itemActionSelectBloatware).setOnClickListener {
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchorView)
            debloatAdapter?.selectOnlyBloatware()
            val selectedCount = debloatAdapter?.getSelectedApps()?.size ?: 0
            binding.tvDebloatSelectedCount.text = "Đã chọn: $selectedCount"
            Toast.makeText(this, "Đã chọn $selectedCount bloatware rác gợi ý", Toast.LENGTH_SHORT).show()
        }

        popupView.findViewById<View>(R.id.itemActionUnselectAll).setOnClickListener {
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchorView)
            debloatAdapter?.selectAll(false)
            binding.cbSelectAllDebloat.isChecked = false
            binding.tvDebloatSelectedCount.text = "Đã chọn: 0"
        }

        popupWindow.showAsDropDown(anchorView, 0, (4 * resources.displayMetrics.density).toInt())
        ViewAnimationExtensions.revealPopupFromAnchor(popupView, anchorView)
    }

    private fun showRestoreUninstalledAppsDialog(anchorView: View? = null) {
        if (!ShizukuUtils.hasShizukuPermission()) {
            appendLog("[Lỗi] Chưa được cấp quyền Shizuku.", LogTarget.DEBLOAT)
            Toast.makeText(this, "Cần cấp quyền Shizuku trước!", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_restore_apps, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvRestoreSubtitle)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseRestoreDialog)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchRestoreApp)
        val cbSelectAll = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cbSelectAllRestore)
        val tvCountStatus = dialogView.findViewById<TextView>(R.id.tvRestoreCountStatus)
        val layoutLoading = dialogView.findViewById<View>(R.id.layoutRestoreLoading)
        val layoutEmpty = dialogView.findViewById<View>(R.id.layoutRestoreEmpty)
        val rvApps = dialogView.findViewById<RecyclerView>(R.id.rvRestoreApps)
        val btnCustom = dialogView.findViewById<View>(R.id.btnCustomPackageRestore)
        val btnPerformRestore = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPerformBatchRestore)

        var restoreAdapter: RestoreAppsAdapter? = null

        fun updateSelectionUI(selectedApps: List<DebloatAppItem>) {
            val count = selectedApps.size
            btnPerformRestore.text = "Khôi phục ($count)"
            btnPerformRestore.isEnabled = count > 0
            tvCountStatus.text = "$count đã chọn"
            val total = restoreAdapter?.getTotalCount() ?: 0
            cbSelectAll.isChecked = (count == total && total > 0)
        }

        restoreAdapter = RestoreAppsAdapter(
            allApps = emptyList(),
            onSelectionChanged = { selectedApps ->
                updateSelectionUI(selectedApps)
            },
            onQuickRestore = { item ->
                ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
                restoreSingleApp(item.packageName, item.name)
            }
        )

        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = restoreAdapter

        layoutLoading.visibility = View.VISIBLE
        rvApps.visibility = View.GONE
        layoutEmpty.visibility = View.GONE

        // Quét tìm các app đã bị gỡ bỏ hoặc bị disable trong background
        executor.execute {
            val uninstalledApps = AppScanner.loadUninstalledAppsForRestore(this@MainActivity)
            mainHandler.post {
                layoutLoading.visibility = View.GONE
                if (uninstalledApps.isEmpty()) {
                    layoutEmpty.visibility = View.VISIBLE
                    rvApps.visibility = View.GONE
                    tvSubtitle.text = "Không có app hệ thống nào bị gỡ bỏ"
                } else {
                    layoutEmpty.visibility = View.GONE
                    rvApps.visibility = View.VISIBLE
                    tvSubtitle.text = "Tìm thấy ${uninstalledApps.size} ứng dụng đã gỡ bỏ / bị tắt"
                    restoreAdapter?.updateData(uninstalledApps)
                }
            }
        }

        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            restoreAdapter?.selectAll(isChecked)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                restoreAdapter?.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnPerformRestore.setOnClickListener {
            val selected = restoreAdapter?.getSelectedApps() ?: emptyList()
            if (selected.isNotEmpty()) {
                ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
                restoreBatchApps(selected)
            }
        }

        btnCustom.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
            showCustomPackageRestoreDialog()
        }

        btnClose.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }

        dialog.show()
        ViewAnimationExtensions.revealDialog(dialogView, anchorView)
    }

    private fun restoreBatchApps(selectedApps: List<DebloatAppItem>) {
        val listPreview = selectedApps.take(6).joinToString("\n") { "• ${it.name} (${it.packageName})" } +
                if (selectedApps.size > 6) "\n... và ${selectedApps.size - 6} ứng dụng khác" else ""

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🔄 Xác nhận khôi phục ${selectedApps.size} ứng dụng")
            .setMessage("Bạn có muốn khôi phục (Cài lại & Bật lại) các ứng dụng đã chọn?\n\n$listPreview")
            .setPositiveButton("Khôi phục tất cả") { _, _ ->
                appendLog("\n========================================", LogTarget.DEBLOAT)
                appendLog("[Khôi phục] Đang khôi phục ${selectedApps.size} ứng dụng...", LogTarget.DEBLOAT)
                appendLog("========================================", LogTarget.DEBLOAT)

                Toast.makeText(this, "Đang khôi phục ${selectedApps.size} ứng dụng...", Toast.LENGTH_SHORT).show()

                executor.execute {
                    var successCount = 0
                    for ((idx, app) in selectedApps.withIndex()) {
                        mainHandler.post {
                            appendLog("[${idx + 1}/${selectedApps.size}] Khôi phục: ${app.name} (${app.packageName})", LogTarget.DEBLOAT)
                        }
                        val r1 = ShizukuUtils.execShizukuCommand("cmd package install-existing ${app.packageName}")
                        val r2 = ShizukuUtils.execShizukuCommand("pm enable ${app.packageName}")

                        if (r1.exitCode == 0 || r2.exitCode == 0 || r1.stdout.contains("installed", ignoreCase = true)) {
                            successCount++
                            mainHandler.post {
                                appendLog("  -> Khôi phục thành công: ${app.packageName}", LogTarget.DEBLOAT)
                            }
                        } else {
                            mainHandler.post {
                                appendLog("  -> Thất bại: ${r1.stderr.ifEmpty { r2.stderr }}", LogTarget.DEBLOAT)
                            }
                        }
                        Thread.sleep(60)
                    }

                    mainHandler.post {
                        appendLog("\n[Khôi phục Hoàn tất] Thành công: $successCount/${selectedApps.size}", LogTarget.DEBLOAT)
                        Toast.makeText(this@MainActivity, "✅ Đã khôi phục xong $successCount ứng dụng!", Toast.LENGTH_LONG).show()
                        loadDebloatApps()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showCustomPackageRestoreDialog() {
        val inputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = "Nhập Package Name (VD: com.miui.player)"
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt(),
                0
            )
        }
        val editText = com.google.android.material.textfield.TextInputEditText(inputLayout.context).apply {
            textSize = 14f
        }
        inputLayout.addView(editText)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🔄 Khôi phục ứng dụng đã xóa")
            .setMessage("Nhập Package Name của ứng dụng hệ thống đã gỡ bỏ để cài đặt lại (install-existing) và bật lại:")
            .setView(inputLayout)
            .setPositiveButton("Khôi phục ngay") { _, _ ->
                val pkg = editText.text?.toString()?.trim() ?: ""
                if (FixCommands.isValidPackageName(pkg)) {
                    restoreSingleApp(pkg, pkg)
                } else {
                    Toast.makeText(this, "Package Name không hợp lệ!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun restoreSingleApp(packageName: String, appName: String) {
        val cleanPkg = packageName.trim()
        if (!FixCommands.isValidPackageName(cleanPkg)) {
            appendLog("[Lỗi Bảo mật] Tên gói $packageName không hợp lệ.", LogTarget.DEBLOAT)
            Toast.makeText(this, "Tên gói không hợp lệ!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!ShizukuUtils.hasShizukuPermission()) {
            appendLog("[Lỗi] Chưa được cấp quyền Shizuku.", LogTarget.DEBLOAT)
            Toast.makeText(this, "Cần cấp quyền Shizuku trước!", Toast.LENGTH_SHORT).show()
            return
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🔄 Xác nhận khôi phục")
            .setMessage("Khôi phục (Cài đặt lại & Bật lại) ứng dụng:\n\n• $appName\n• Package: $cleanPkg")
            .setPositiveButton("Khôi phục ngay") { _, _ ->
                appendLog("\n========================================", LogTarget.DEBLOAT)
                appendLog("[Khôi phục] Bắt đầu khôi phục $appName ($cleanPkg)...", LogTarget.DEBLOAT)
                val cmdInstall = "cmd package install-existing $cleanPkg"
                val cmdEnable = "pm enable $cleanPkg"
                appendLog("$ $cmdInstall", LogTarget.DEBLOAT)
                appendLog("$ $cmdEnable", LogTarget.DEBLOAT)

                executor.execute {
                    val res1 = ShizukuUtils.execShizukuCommand(cmdInstall)
                    val res2 = ShizukuUtils.execShizukuCommand(cmdEnable)

                    mainHandler.post {
                        val isSuccess = res1.exitCode == 0 || res1.stdout.contains("installed", ignoreCase = true) ||
                                res2.exitCode == 0 || res2.stdout.contains("new state: enabled", ignoreCase = true)

                        if (isSuccess) {
                            appendLog("  -> Khôi phục thành công: $cleanPkg", LogTarget.DEBLOAT)
                            Toast.makeText(this@MainActivity, "✅ Đã khôi phục $appName thành công!", Toast.LENGTH_SHORT).show()
                            loadDebloatApps()
                        } else {
                            val err = (res1.stderr + " " + res2.stderr).trim().ifEmpty { res1.stdout + " " + res2.stdout }
                            appendLog("  -> Kết quả: $err", LogTarget.DEBLOAT)
                            Toast.makeText(this@MainActivity, "Thông báo: $err", Toast.LENGTH_SHORT).show()
                            LimiAiService.recordLastError(
                                commandName = "[Khôi phục] $appName ($cleanPkg)",
                                commandText = "$cmdInstall && $cmdEnable",
                                exitCode = if (res1.exitCode != 0) res1.exitCode else res2.exitCode,
                                stderr = (res1.stderr + " " + res2.stderr).trim(),
                                stdout = (res1.stdout + " " + res2.stdout).trim()
                            )
                            loadDebloatApps()
                        }
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun loadDebloatApps() {
        executor.execute {
            val apps = AppScanner.loadAllAppsForDebloat(this@MainActivity)
            mainHandler.post {
                debloatAdapter?.updateData(apps)
                updateToggleExpandDebloatButtonText(debloatAdapter?.isCollapsed ?: false)
                binding.cbSelectAllDebloat.text = "Chọn tất cả (${apps.size} apps)"
                binding.cbSelectAllDebloat.isChecked = false
                binding.tvDebloatSelectedCount.text = "Đã chọn: 0"
                appendLog("[Debloat] Đã nạp danh sách ${apps.size} ứng dụng (kèm phân loại hệ thống/bloatware).", LogTarget.DEBLOAT)
            }
        }
    }

    private fun debloatSingleApp(item: DebloatAppItem, isUninstall: Boolean) {
        val cleanPkg = item.packageName.trim()
        if (!FixCommands.isValidPackageName(cleanPkg)) {
            appendLog("[Lỗi Bảo mật] Tên gói $cleanPkg không hợp lệ.", LogTarget.DEBLOAT)
            Toast.makeText(this, "Tên gói không hợp lệ!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!ShizukuUtils.hasShizukuPermission()) {
            appendLog("[Lỗi] Chưa được cấp quyền Shizuku.", LogTarget.DEBLOAT)
            Toast.makeText(this, "Cần cấp quyền Shizuku trước!", Toast.LENGTH_SHORT).show()
            return
        }

        val actionName = if (isUninstall) "Gỡ bỏ hoàn toàn" else "Vô hiệu hóa"
        val dialogTitle = if (isUninstall) "🗑️ Xác nhận gỡ app" else "⏸️ Xác nhận tắt app"
        val dialogMsg = if (isUninstall) {
            "Bạn có chắc chắn muốn gỡ cài đặt hoàn toàn ứng dụng:\n\n• ${item.name}\n• Package: $cleanPkg"
        } else {
            "Bạn có chắc chắn muốn tắt ứng dụng:\n\n• ${item.name}\n• Package: $cleanPkg"
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(dialogTitle)
            .setMessage(dialogMsg)
            .setPositiveButton(if (isUninstall) "🗑️ Gỡ ngay" else "Tắt app") { _, _ ->
                val cmd = if (isUninstall) {
                    "pm uninstall --user 0 $cleanPkg"
                } else {
                    "pm disable-user --user 0 $cleanPkg"
                }

                appendLog("\n[Debloat] $actionName: ${item.name} ($cleanPkg)", LogTarget.DEBLOAT)
                appendLog("$ $cmd", LogTarget.DEBLOAT)

                executor.execute {
                    val result = executeSmartDebloatCommand(cleanPkg, isUninstall)
                    mainHandler.post {
                        if (result.exitCode == 0 || result.stdout.contains("Success", ignoreCase = true) || result.stdout.contains("disabled", ignoreCase = true) || result.stdout.contains("suspended", ignoreCase = true)) {
                            appendLog("  -> $actionName thành công: $cleanPkg", LogTarget.DEBLOAT)
                            Toast.makeText(this@MainActivity, "✅ Đã $actionName ${item.name} thành công!", Toast.LENGTH_SHORT).show()
                            loadDebloatApps()
                        } else {
                            val errOutput = result.stderr.ifEmpty { result.stdout }.ifEmpty { "Exit code ${result.exitCode}" }
                            appendLog("  -> Thất bại (${result.exitCode}): $errOutput", LogTarget.DEBLOAT)
                            if (errOutput.contains("-1000") || errOutput.contains("SecurityException", ignoreCase = true) || errOutput.contains("Permission Denial", ignoreCase = true)) {
                                appendLog("  💡 [Mẹo Xiaomi/HyperOS]: Lỗi [-1000] do cơ chế bảo mật của máy. Hãy vào Cài đặt -> Tùy chọn nhà phát triển -> Bật 'Cài đặt qua USB' và 'Gỡ lỗi USB (Cài đặt bảo mật)' để cấp phép đầy đủ cho Shizuku.", LogTarget.DEBLOAT)
                            }
                            Toast.makeText(this@MainActivity, "❌ $actionName thất bại: $errOutput", Toast.LENGTH_SHORT).show()
                            LimiAiService.recordLastError(
                                commandName = "[Debloat] $actionName: ${item.name} ($cleanPkg)",
                                commandText = cmd,
                                exitCode = result.exitCode,
                                stderr = result.stderr,
                                stdout = result.stdout
                            )
                        }
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun executeSmartDebloatCommand(cleanPkg: String, isUninstall: Boolean): ShizukuUtils.CommandResult {
        try {
            val primaryCmd = if (isUninstall) "pm uninstall --user 0 $cleanPkg" else "pm disable-user --user 0 $cleanPkg"
            val result = ShizukuUtils.execShizukuCommand(primaryCmd)
            if (result.exitCode == 0 || result.stdout.contains("Success", ignoreCase = true)) {
                return result
            }

            // Fallback 1: cmd package
            val fallbackCmd1 = if (isUninstall) "cmd package uninstall --user 0 $cleanPkg" else "cmd package disable-user --user 0 $cleanPkg"
            val res1 = ShizukuUtils.execShizukuCommand(fallbackCmd1)
            if (res1.exitCode == 0 || res1.stdout.contains("Success", ignoreCase = true)) {
                return res1
            }

            // Fallback 2: nếu gỡ bị chặn -1000, tự chuyển sang tắt disable-user
            if (isUninstall) {
                val fallbackCmd2 = "pm disable-user --user 0 $cleanPkg"
                val res2 = ShizukuUtils.execShizukuCommand(fallbackCmd2)
                if (res2.exitCode == 0 || res2.stdout.contains("Success", ignoreCase = true) || res2.stdout.contains("disabled", ignoreCase = true)) {
                    return res2
                }
            }

            // Fallback 3: pm suspend
            val fallbackCmd3 = "pm suspend --user 0 $cleanPkg"
            val res3 = ShizukuUtils.execShizukuCommand(fallbackCmd3)
            if (res3.exitCode == 0 || res3.stdout.contains("Success", ignoreCase = true) || res3.stdout.contains("suspended", ignoreCase = true)) {
                return res3
            }

            return result
        } catch (e: Throwable) {
            return ShizukuUtils.CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Lỗi Shizuku Binder/IPC: ${e.javaClass.simpleName} - ${e.message ?: "Mất kết nối Shizuku"}"
            )
        }
    }

    private fun debloatSelectedApps(isUninstall: Boolean) {
        if (!ShizukuUtils.hasShizukuPermission()) {
            appendLog("[Lỗi] Chưa được cấp quyền Shizuku.", LogTarget.DEBLOAT)
            Toast.makeText(this, "⚠️ Bạn chưa cấp quyền Shizuku để thực hiện Debloat!", Toast.LENGTH_LONG).show()
            showShizukuRequiredDialog("Gỡ bỏ / Vô hiệu hóa ứng dụng hệ thống")
            return
        }

        val rawSelectedApps = debloatAdapter?.getSelectedApps() ?: emptyList()
        val selectedApps = rawSelectedApps.filter { FixCommands.isValidPackageName(it.packageName) }
        if (selectedApps.isEmpty()) {
            Toast.makeText(this, "Bạn chưa chọn ứng dụng nào để xử lý!", Toast.LENGTH_SHORT).show()
            return
        }

        val actionName = if (isUninstall) "Gỡ bỏ hoàn toàn" else "Vô hiệu hóa"
        val dialogTitle = if (isUninstall) "⚠️ Xác nhận gỡ ${selectedApps.size} ứng dụng" else "⏸️ Xác nhận tắt ${selectedApps.size} ứng dụng"
        val listPreview = selectedApps.take(6).joinToString("\n") { "• ${it.name} (${it.packageName})" } +
                if (selectedApps.size > 6) "\n... và ${selectedApps.size - 6} ứng dụng khác" else ""
        val dialogMsg = "Bạn có chắc chắn muốn $actionName ${selectedApps.size} ứng dụng đã chọn?\n\n$listPreview"

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(dialogTitle)
            .setMessage(dialogMsg)
            .setPositiveButton(if (isUninstall) "🗑️ Gỡ tất cả" else "Tắt tất cả") { _, _ ->
                appendLog("\n========================================", LogTarget.DEBLOAT)
                appendLog("[Debloat] Bắt đầu $actionName cho ${selectedApps.size} ứng dụng...", LogTarget.DEBLOAT)
                appendLog("========================================", LogTarget.DEBLOAT)

                Toast.makeText(this, "Đang thực hiện $actionName cho ${selectedApps.size} app...", Toast.LENGTH_SHORT).show()

                executor.execute {
                    var successCount = 0
                    var failCount = 0

                    for ((idx, app) in selectedApps.withIndex()) {
                        val cleanPkg = app.packageName.trim()
                        val cmd = if (isUninstall) {
                            "pm uninstall --user 0 $cleanPkg"
                        } else {
                            "pm disable-user --user 0 $cleanPkg"
                        }

                        mainHandler.post {
                            appendLog("[${idx + 1}/${selectedApps.size}] $actionName: ${app.name} ($cleanPkg)", LogTarget.DEBLOAT)
                        }

                        val result = executeSmartDebloatCommand(cleanPkg, isUninstall)
                        if (result.exitCode == 0 || result.stdout.contains("Success", ignoreCase = true) || result.stdout.contains("disabled", ignoreCase = true) || result.stdout.contains("suspended", ignoreCase = true)) {
                            successCount++
                            mainHandler.post {
                                appendLog("  -> Thành công: $cleanPkg", LogTarget.DEBLOAT)
                            }
                        } else {
                            failCount++
                            val errOutput = result.stderr.ifEmpty { result.stdout }.ifEmpty { "Exit code ${result.exitCode}" }
                            mainHandler.post {
                                appendLog("  -> Thất bại (${result.exitCode}): $errOutput", LogTarget.DEBLOAT)
                                if (errOutput.contains("-1000") || errOutput.contains("SecurityException", ignoreCase = true)) {
                                    appendLog("  💡 [Mẹo Xiaomi/HyperOS]: Lỗi [-1000]. Hãy bật 'Cài đặt qua USB' và 'Gỡ lỗi USB (Cài đặt bảo mật)' trong Tùy chọn nhà phát triển.", LogTarget.DEBLOAT)
                                }
                            }
                            LimiAiService.recordLastError(
                                commandName = "[Debloat] $actionName: ${app.name} ($cleanPkg)",
                                commandText = cmd,
                                exitCode = result.exitCode,
                                stderr = result.stderr,
                                stdout = result.stdout
                            )
                        }
                        Thread.sleep(60)
                    }

                    mainHandler.post {
                        appendLog("\n[Debloat Hoàn tất] Thành công: $successCount, Thất bại: $failCount", LogTarget.DEBLOAT)
                        Toast.makeText(this@MainActivity, "✅ Đã $actionName xong: $successCount thành công!", Toast.LENGTH_LONG).show()
                        loadDebloatApps()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun loadInstalledApps() {
        appendLog("[Scanner] Đang quét ứng dụng và nạp biểu tượng icon...", LogTarget.PERMS)
        executor.execute {
            val apps = AppScanner.loadInstalledAppsWithIcons(this@MainActivity)
            val installedBanks = AppScanner.scanInstalledBankApps(this@MainActivity)

            mainHandler.post {
                appListAdapter?.updateData(apps, triggerSelectionCallback = false)
                val selectedCount = apps.count { it.isSelected }
                val selectedPkgs = apps.filter { it.isSelected }.map { it.packageName }
                if (selectedPkgs.isNotEmpty()) {
                    binding.etMilletPackages.setText(selectedPkgs.joinToString(", "))
                }
                binding.cbSelectAllApps.text = "Chọn tất cả (${apps.size} apps)"
                binding.cbSelectAllApps.isChecked = (selectedCount == apps.size && apps.isNotEmpty())
                updateToggleExpandButtonText(appListAdapter?.isCollapsed ?: true)
                appendLog("[Scanner] Hoàn thành nạp ${apps.size} ứng dụng.", LogTarget.PERMS)

                // Tự động quét và bổ sung các app ngân hàng thực tế có sẵn trên máy vào Lệnh 4
                if (installedBanks.isNotEmpty()) {
                    val currentText = binding.etMilletPackages.text.toString()
                    val currentList = FixCommands.parsePackages(currentText).toMutableList()
                    val addedBankNames = mutableListOf<String>()

                    for ((pkg, name) in installedBanks) {
                        if (!currentList.contains(pkg)) {
                            currentList.add(pkg)
                            addedBankNames.add(name)
                        }
                    }

                    if (addedBankNames.isNotEmpty()) {
                        binding.etMilletPackages.setText(currentList.joinToString(", "))
                        appendLog("[Bank Scanner] Đã tự động thêm ${addedBankNames.size} app ngân hàng có sẵn trên máy vào Lệnh 4: ${addedBankNames.joinToString(", ")}", LogTarget.FIX)
                    }
                }
            }
        }
    }

    private fun selectTab(tabId: Int, animated: Boolean = true) {
        currentTabId = tabId
        val targetView = when (tabId) {
            R.id.nav_permissions -> binding.navTabPermissions
            R.id.nav_debloat -> binding.navTabDebloat
            R.id.nav_custom -> binding.navTabCustom
            R.id.nav_settings -> binding.navTabSettings
            else -> binding.navTabSystem
        }

        binding.containerSystemFix.visibility = if (tabId == R.id.nav_system) View.VISIBLE else View.GONE
        binding.containerAppPerms.visibility = if (tabId == R.id.nav_permissions) View.VISIBLE else View.GONE
        binding.containerDebloat.visibility = if (tabId == R.id.nav_debloat) View.VISIBLE else View.GONE
        binding.containerCustom.visibility = if (tabId == R.id.nav_custom) View.VISIBLE else View.GONE
        binding.containerSettings.visibility = if (tabId == R.id.nav_settings) View.VISIBLE else View.GONE

        // Nút AI Chatbot Limi Floating: hiển thị tại 4 tab chính, ẩn khi vào tab Cài đặt
        binding.fabLimiAiChat.visibility = if (tabId == R.id.nav_settings) View.GONE else View.VISIBLE

        // Cập nhật Header: Khi ở Tab Cài đặt, hiển thị Tiêu đề "Cài đặt" + Lá cờ + Themes ▾ + Badge phiên bản y hệt trang Cài đặt gốc
        if (tabId == R.id.nav_settings) {
            binding.tvAppTitle.text = "Cài đặt"
            binding.headerSettingsActions.visibility = View.VISIBLE
            binding.headerMainActions.visibility = View.GONE
            binding.cardShizukuStatus.visibility = View.GONE
            binding.btnOpenGpsFixDialog.visibility = View.GONE
            binding.tvTabSubtitle.text = "Cấu hình & Tùy biến · HyperOS"
        } else {
            binding.tvAppTitle.text = getString(R.string.app_name)
            binding.headerSettingsActions.visibility = View.GONE
            binding.headerMainActions.visibility = View.VISIBLE
            binding.cardShizukuStatus.visibility = View.VISIBLE
            binding.btnOpenGpsFixDialog.visibility = if (tabId == R.id.nav_custom) View.VISIBLE else View.GONE
            binding.tvTabSubtitle.text = when (tabId) {
                R.id.nav_permissions -> "Cấp quyền màn hình khóa, pop-up và nền"
                R.id.nav_debloat -> "Dọn rác & gỡ ứng dụng hệ thống an toàn"
                R.id.nav_custom -> "Bộ công cụ chạy lệnh Shell/ADB"
                else -> "Fix Noti · HyperOS / MIUI"
            }
        }

        val activeColor = androidx.core.content.ContextCompat.getColor(this, R.color.text_nav_active)
        val inactiveColor = androidx.core.content.ContextCompat.getColor(this, R.color.text_nav_inactive)

        val tabs = listOf(
            Triple(binding.navTabSystem, binding.ivNavSystem, binding.tvNavSystem),
            Triple(binding.navTabPermissions, binding.ivNavPermissions, binding.tvNavPermissions),
            Triple(binding.navTabCustom, binding.ivNavCustom, binding.tvNavCustom),
            Triple(binding.navTabDebloat, binding.ivNavDebloat, binding.tvNavDebloat),
            Triple(binding.navTabSettings, binding.ivNavSettings, binding.tvNavSettings)
        )

        // Chỉ hiển thị tên nút khi ở tab được chọn (active), ẩn tên nút khi ở tab khác
        tabs.forEach { (tabView, iv, tv) ->
            val isActive = (tabView == targetView)
            val color = if (isActive) activeColor else inactiveColor
            iv.setColorFilter(color)
            tv.setTextColor(color)
            tv.visibility = if (isActive) View.VISIBLE else View.GONE
            tv.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (isActive && animated) {
                if (tabView == binding.navTabSettings) {
                    // Giữ nguyên icon và hiệu ứng bánh răng xoay của tab Cài Đặt theo đúng yêu cầu
                    iv.animate().scaleX(1.15f).scaleY(1.15f).setDuration(160).withEndAction {
                        iv.animate().scaleX(1.0f).scaleY(1.0f).setDuration(160).start()
                    }.start()
                    iv.animate().rotationBy(60f).setDuration(280).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                } else {
                    // Áp dụng Morphicons Spring Physics Animation cho tất cả các icon menu còn lại
                    val kickAngle = when (tabView) {
                        binding.navTabSystem -> 12f
                        binding.navTabPermissions -> -10f
                        binding.navTabCustom -> 14f
                        binding.navTabDebloat -> -14f
                        else -> 10f
                    }
                    ViewAnimationExtensions.animateMorphIcon(iv, rotationDegrees = kickAngle)
                }
            }
        }

        binding.bottomNav.post {
            val pillBgColor = androidx.core.content.ContextCompat.getColor(this, R.color.nav_active_indicator_pill)
            val pillStrokeColor = androidx.core.content.ContextCompat.getColor(this, R.color.nav_active_indicator_stroke)
            val pillDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 26f * resources.displayMetrics.density
                setColor(pillBgColor)
                setStroke((1.2f * resources.displayMetrics.density).toInt(), pillStrokeColor)
            }
            binding.navIndicatorPill.background = pillDrawable

            val targetX = targetView.left.toFloat()
            val targetWidth = targetView.width
            val pill = binding.navIndicatorPill
            val lp = pill.layoutParams
            if (targetWidth > 0 && lp.width != targetWidth) {
                lp.width = targetWidth
                pill.layoutParams = lp
            }

            val maxBoundary = maxOf(0f, (binding.navTabsWrapper.width - targetWidth).toFloat())
            if (!animated || pill.visibility != View.VISIBLE) {
                pill.visibility = View.VISIBLE
                pill.x = targetX.coerceIn(0f, maxBoundary)
                pill.scaleX = 1.0f
                pill.scaleY = 1.0f
            } else {
                pill.visibility = View.VISIBLE
                ViewAnimationExtensions.animateFluidJellyPill(pill, targetX, targetWidth, duration = 240L, maxBoundaryX = maxBoundary)
            }
        }
        binding.nestedScrollView.smoothScrollTo(0, 0)
        showBottomNav()
    }

    private fun initBottomNav() {
        val activeColor = androidx.core.content.ContextCompat.getColor(this, R.color.text_nav_active)
        val inactiveColor = androidx.core.content.ContextCompat.getColor(this, R.color.text_nav_inactive)

        val tabs = listOf(
            Triple(binding.navTabSystem, binding.ivNavSystem, binding.tvNavSystem),
            Triple(binding.navTabPermissions, binding.ivNavPermissions, binding.tvNavPermissions),
            Triple(binding.navTabCustom, binding.ivNavCustom, binding.tvNavCustom),
            Triple(binding.navTabDebloat, binding.ivNavDebloat, binding.tvNavDebloat),
            Triple(binding.navTabSettings, binding.ivNavSettings, binding.tvNavSettings)
        )

        val tabViewsWithId = listOf(
            Pair(binding.navTabSystem, R.id.nav_system),
            Pair(binding.navTabPermissions, R.id.nav_permissions),
            Pair(binding.navTabCustom, R.id.nav_custom),
            Pair(binding.navTabDebloat, R.id.nav_debloat),
            Pair(binding.navTabSettings, R.id.nav_settings)
        )

        var startRawX = 0f
        var isDraggingPill = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        val tabTouchListener = View.OnTouchListener { v, event ->
            val pill = binding.navIndicatorPill
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    isDraggingPill = false
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.rawX - startRawX)
                    if (dx > touchSlop) {
                        isDraggingPill = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isDraggingPill) {
                        val location = IntArray(2)
                        binding.navTabsWrapper.getLocationOnScreen(location)
                        val relativeX = event.rawX - location[0]
                        val pillWidth = if (pill.width > 0) pill.width else v.width
                        val minX = 0f
                        val maxX = maxOf(0f, (binding.navTabsWrapper.width - pillWidth).toFloat())
                        val desiredX = (relativeX - pillWidth / 2f).coerceIn(minX, maxX)

                        // Nền viên thuốc bám sát di chuyển theo vị trí ngón tay của người dùng
                        pill.x = desiredX

                        // Đổi màu preview các tab theo vị trí tay lướt qua lại
                        val closestTab = tabViewsWithId.minByOrNull { (tabView, _) ->
                            val tabCenter = tabView.left + tabView.width / 2f
                            kotlin.math.abs(relativeX - tabCenter)
                        }

                        tabs.forEach { (tabView, iv, tv) ->
                            val isHovered = (tabView == closestTab?.first)
                            val color = if (isHovered) activeColor else inactiveColor
                            iv.setColorFilter(color)
                            tv.setTextColor(color)
                            tv.visibility = if (isHovered) View.VISIBLE else View.GONE
                        }
                    }
                    isDraggingPill
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    if (isDraggingPill) {
                        isDraggingPill = false
                        val location = IntArray(2)
                        binding.navTabsWrapper.getLocationOnScreen(location)
                        val relativeX = event.rawX - location[0]
                        val closestTab = tabViewsWithId.minByOrNull { (tabView, _) ->
                            val tabCenter = tabView.left + tabView.width / 2f
                            kotlin.math.abs(relativeX - tabCenter)
                        }
                        if (closestTab != null) {
                            selectTab(closestTab.second, animated = true)
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    if (isDraggingPill) {
                        isDraggingPill = false
                        selectTab(currentTabId, animated = true)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        binding.navTabSystem.setOnTouchListener(tabTouchListener)
        binding.navTabPermissions.setOnTouchListener(tabTouchListener)
        binding.navTabDebloat.setOnTouchListener(tabTouchListener)
        binding.navTabCustom.setOnTouchListener(tabTouchListener)
        binding.navTabSettings.setOnTouchListener(tabTouchListener)

        binding.navTabSystem.setOnClickListener {
            selectTab(R.id.nav_system, animated = true)
        }
        binding.navTabPermissions.setOnClickListener {
            selectTab(R.id.nav_permissions, animated = true)
        }
        binding.navTabDebloat.setOnClickListener {
            selectTab(R.id.nav_debloat, animated = true)
        }
        binding.navTabCustom.setOnClickListener {
            selectTab(R.id.nav_custom, animated = true)
        }
        binding.navTabSettings.setOnClickListener {
            selectTab(R.id.nav_settings, animated = true)
        }

        binding.bottomNav.post {
            selectTab(currentTabId, animated = false)
        }
    }

    private fun initSettings() {
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            binding.tvMainVersion.text = "Phiên bản $versionName · Bộ công cụ LIMI"
        } catch (_: Throwable) {}

        val settingsInteractiveButtons = listOf(
            binding.ivFlagVietnam,
            binding.btnThemes,
            binding.cardMyDevice,
            binding.cardCheckFixProgress,
            binding.cardSettingSplash,
            binding.cardSettingShortcuts,
            binding.cardSettingReset,
            binding.cardSettingDeveloper,
            binding.btnOpenGuideInSettings,
            binding.btnOpenVideoGuide,
            binding.btnCheckAppUpdate
        )
        settingsInteractiveButtons.forEach { btn ->
            btn?.let { ViewAnimationExtensions.applySpringTouch(it) }
        }

        binding.ivFlagVietnam.setOnClickListener { anchor ->
            ViewAnimationExtensions.animateBounce(anchor)
            val intent = Intent(this, VietnamVideoActivity::class.java)
            startActivity(intent)
        }

        updateThemesButtonIcon()

        binding.btnThemes.setOnClickListener { anchor ->
            showThemeSelectorPopup(anchor)
        }

        // ================= CÀI ĐẶT 4 Ô VUÔNG POPUP =================
        binding.cardSettingSplash.setOnClickListener {
            showSettingSplashDialog()
        }

        binding.cardSettingShortcuts.setOnClickListener {
            showSettingShortcutsDialog()
        }

        binding.cardSettingReset.setOnClickListener {
            showSettingResetDialog()
        }

        binding.cardSettingDeveloper.setOnClickListener {
            showSettingDeveloperDialog()
        }

        // ================= MỤC 3: MY DEVICE & TIẾN ĐỘ FIX =================
        binding.cardMyDevice.setOnClickListener {
            val intent = Intent(this, MyDeviceActivity::class.java)
            startActivity(intent)
        }

        binding.cardCheckFixProgress.setOnClickListener {
            showFixProgressCheckDialog()
        }

        binding.btnOpenGuideInSettings.setOnClickListener {
            showGuideWarningDialog(forceShow = true, anchorView = it)
        }

        binding.btnOpenVideoGuide.setOnClickListener {
            val videoUrl = "https://drive.google.com/drive/folders/1rylkubajy0gEqUxchwlvLsyNLI32zOON?usp=sharing"
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl))
                startActivity(intent)
            } catch (e: Throwable) {
                Toast.makeText(this, "Không thể mở liên kết video", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCheckAppUpdate.setOnClickListener {
            AppUpdateManager.checkUpdate(this, manualTrigger = true)
        }
    }

    private fun scrollToAndHighlightView(targetView: View, toastMsg: String? = null) {
        selectTab(R.id.nav_system, animated = true)
        toastMsg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        binding.nestedScrollView.postDelayed({
            val rect = Rect()
            targetView.getDrawingRect(rect)
            binding.nestedScrollView.offsetDescendantRectToMyCoords(targetView, rect)
            binding.nestedScrollView.smoothScrollTo(0, Math.max(0, rect.top - (60 * resources.displayMetrics.density).toInt()))

            targetView.animate()
                .scaleX(1.03f)
                .scaleY(1.03f)
                .setDuration(250)
                .withEndAction {
                    targetView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(250)
                        .start()
                }
                .start()
        }, 220)
    }

    private fun showFixProgressCheckDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_fix_progress_check, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.windowAnimations = R.style.DialogPopAnimation

        val btnClose = dialogView.findViewById<View>(R.id.btnDialogFixCheckClose)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tvFixPercentNumber)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvFixStatusTitle)
        val tvDetail = dialogView.findViewById<TextView>(R.id.tvFixCountDetail)
        val progress = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressFixPercent)
        val container = dialogView.findViewById<LinearLayout>(R.id.layoutFixItemsContainer)
        val btnGoToFix = dialogView.findViewById<View>(R.id.btnGoToFixTab)
        val cardPermNotice = dialogView.findViewById<View>(R.id.cardFixProgressPermNotice)
        val btnGoToPerm = dialogView.findViewById<View>(R.id.btnFixProgressGoToPerm)

        var unfixedAction: (() -> Unit)? = null

        btnClose?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnGoToFix?.let { ViewAnimationExtensions.applySpringTouch(it) }
        cardPermNotice?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnGoToPerm?.let { ViewAnimationExtensions.applySpringTouch(it) }

        btnClose?.setOnClickListener { dialog.dismiss() }
        btnGoToFix?.setOnClickListener {
            dialog.dismiss()
            unfixedAction?.invoke() ?: run {
                selectTab(R.id.nav_system, animated = true)
            }
        }

        val openPermAction: (View) -> Unit = {
            dialog.dismiss()
            selectTab(R.id.nav_permissions, animated = true)
        }
        cardPermNotice?.setOnClickListener(openPermAction)
        btnGoToPerm?.setOnClickListener(openPermAction)

        // Quét các hạng mục trong thread ngầm
        executor.execute {
            data class FixCheckItem(val title: String, val detail: String, val isFixed: Boolean)
            val items = mutableListOf<FixCheckItem>()

            // 1. FCM Reschedule (Lệnh 1)
            val fcmResched = try { Settings.Global.getInt(contentResolver, "fcm_reschedule_needed") == 1 } catch (_: Throwable) { false }
            items.add(FixCheckItem("FCM Reschedule & Push Signal", if (fcmResched) "Đã cấu hình giá trị 1" else "Chưa kích hoạt (giá trị 0)", fcmResched))

            // 2. GTalk Heartbeat 120s (Lệnh 1)
            val heartbeat = try { Settings.Global.getInt(contentResolver, "gtalk_heartbeat_interval_ms") == 120000 } catch (_: Throwable) { false }
            items.add(FixCheckItem("FCM Heartbeat Interval (120s)", if (heartbeat) "Đã duy trì kết nối 120.000ms" else "Chưa tối ưu thời gian nhịp tim", heartbeat))

            // 3. Doze Always On tắt (Lệnh 2)
            val dozeOff = try { Settings.Global.getInt(contentResolver, "doze_always_on") == 0 } catch (_: Throwable) { false }
            items.add(FixCheckItem("Vô hiệu hóa Doze Sleep", if (dozeOff) "Đã tắt chế độ ngủ sâu (Doze 0)" else "Đang bật chế độ ngủ Doze gốc", dozeOff))

            // 4. App Standby tắt (Lệnh 2)
            val standbyOff = try { Settings.Global.getInt(contentResolver, "app_standby_enabled") == 0 } catch (_: Throwable) { false }
            items.add(FixCheckItem("Vô hiệu hóa App Standby", if (standbyOff) "Đã tắt hạn chế app chờ ngầm" else "Chưa tắt App Standby", standbyOff))

            // 5. Wi-Fi Power Save tắt (Lệnh 3)
            val wifiPsOff = try { Settings.Global.getInt(contentResolver, "wifi_power_save") == 0 } catch (_: Throwable) { false }
            items.add(FixCheckItem("Chống ngắt Wi-Fi khi tắt màn", if (wifiPsOff) "Đã tắt tiết kiệm pin Wi-Fi" else "Chưa tắt Wi-Fi Power Save", wifiPsOff))

            // 6. Greezer tắt (Lệnh 3)
            val greezerOff = try { Settings.Global.getInt(contentResolver, "greezer_enable") == 0 } catch (_: Throwable) { false }
            items.add(FixCheckItem("Tắt bộ đóng băng Greezer", if (greezerOff) "Đã vô hiệu hóa Greezer Freezer" else "Greezer đang bật", greezerOff))

            // 7. Millet Whitelist App (Lệnh 4)
            val milletVal = try { Settings.System.getString(contentResolver, "MILLET_NO_RESTRICT_APP") ?: "" } catch (_: Throwable) { "" }
            val milletOk = milletVal.isNotEmpty()
            items.add(FixCheckItem("Danh sách trắng Millet Xiaomi", if (milletOk) "Đã nạp danh sách app không giới hạn" else "Chưa cấu hình Millet Whitelist", milletOk))

            // 8. Millet Traffic Freeze tắt (Flagship 1)
            val milletTrafficOff = try { Settings.System.getInt(contentResolver, "network_traffic_millet_enable") == 0 } catch (_: Throwable) { false }
            items.add(FixCheckItem("Tắt đóng băng mạng Millet Traffic", if (milletTrafficOff) "Đã mở cổng socket lưu lượng mạng" else "Millet Traffic đang bật", milletTrafficOff))

            // 9. Phantom Process Killer (Flagship 2)
            var phantomOk = false
            try {
                val clazz = Class.forName("android.provider.DeviceConfig")
                val getPropertyMethod = clazz.getMethod("getProperty", String::class.java, String::class.java)
                val prop = getPropertyMethod.invoke(null, "activity_manager", "max_phantom_processes") as? String
                if (prop != null && (prop.contains("2147483647") || (prop.toIntOrNull() ?: 0) > 32)) {
                    phantomOk = true
                }
            } catch (_: Throwable) {}

            val appPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

            if (!phantomOk && ShizukuUtils.hasShizukuPermission()) {
                val phantomCheck = ShizukuUtils.execShizukuCommand("device_config get activity_manager max_phantom_processes")
                if (phantomCheck.stdout.contains("2147483647") || (phantomCheck.stdout.trim().toIntOrNull() ?: 0) > 32) {
                    phantomOk = true
                    appPrefs.edit().putBoolean("phantom_killer_fixed", true).apply()
                }
            } else if (!phantomOk) {
                phantomOk = appPrefs.getBoolean("phantom_killer_fixed", false)
            }
            items.add(FixCheckItem("Tắt Phantom Process Killer", if (phantomOk) "Đã nâng giới hạn max tiến trình con" else "Đang giới hạn 32 tiến trình con", phantomOk))

            // 10. AI Pin Thích Ứng tắt (Flagship 6)
            val adaptiveOff = try { Settings.Global.getInt(contentResolver, "adaptive_battery_management_enabled") == 0 } catch (_: Throwable) { false }
            items.add(FixCheckItem("Tắt AI Quản lý pin thích ứng", if (adaptiveOff) "Đã tắt AI tự động bóp app" else "AI Pin thích ứng đang bật", adaptiveOff))

            // 11. PowerKeeper Smart Power tắt (Flagship 7)
            val pkOff = try { Settings.Global.getInt(contentResolver, "miui_powerkeeper_smart_power_enabled") == 0 } catch (_: Throwable) { false }
            items.add(FixCheckItem("Vô hiệu hóa PowerKeeper bóp ngầm", if (pkOff) "Đã tắt cơ chế tự bóp của PowerKeeper" else "PowerKeeper đang bật mặc định", pkOff))

            // 12. MIUI Freezer tắt (Flagship 7)
            val freezerOff = try { Settings.Global.getInt(contentResolver, "miui_freezer_enable") == 0 } catch (_: Throwable) { false }
            items.add(FixCheckItem("Khóa HyperCore & MIUI Freezer", if (freezerOff) "Đã tắt bộ đóng băng HyperCore" else "MIUI Freezer đang bật", freezerOff))

            // 13. NTP Server Việt Nam (GPS 1)
            val ntpVal = try { Settings.Global.getString(contentResolver, "ntp_server") ?: "" } catch (_: Throwable) { "" }
            val ntpOk = ntpVal.contains("vn.pool.ntp.org")
            items.add(FixCheckItem("Đồng bộ NTP Server Việt Nam", if (ntpOk) "Đã kết nối vn.pool.ntp.org" else "Đang dùng NTP mặc định", ntpOk))

            // 14. A-GPS Kích hoạt (GPS 2)
            val agpsOk = try { Settings.Global.getInt(contentResolver, "assisted_gps_enabled") == 1 } catch (_: Throwable) { false }
            items.add(FixCheckItem("Hỗ trợ định vị A-GPS", if (agpsOk) "Đã kích hoạt Assisted GPS" else "Chưa kích hoạt A-GPS", agpsOk))

            // 15. Quét Wi-Fi/BLE định vị ngầm (GPS 5)
            val scanOk = try { Settings.Global.getInt(contentResolver, "wifi_scan_always_enabled") == 1 } catch (_: Throwable) { false }
            items.add(FixCheckItem("Quét Wi-Fi/BLE vị trí trong nhà", if (scanOk) "Đã bật quét định vị độ chính xác cao" else "Chưa bật quét Wi-Fi liên tục", scanOk))

            val fixedCount = items.count { it.isFixed }
            val totalCount = items.size
            val percent = (fixedCount * 100) / totalCount

            // Tìm mục chưa fix đầu tiên theo thứ tự danh sách để điều hướng
            val firstUnfixedIndex = items.indexOfFirst { !it.isFixed }
            unfixedAction = when (firstUnfixedIndex) {
                0, 1 -> { { scrollToAndHighlightView(binding.cardCmd1, "🎯 Chuyển đến Lệnh 1: Fix FCM & Heartbeat") } }
                2, 3 -> { { scrollToAndHighlightView(binding.cardCmd2, "🎯 Chuyển đến Lệnh 2: Tắt Doze & Standby") } }
                4, 5 -> { { scrollToAndHighlightView(binding.cardCmd3, "🎯 Chuyển đến Lệnh 3: Tắt Wi-Fi Power Save & Greezer") } }
                6 -> { { scrollToAndHighlightView(binding.cardCmd4, "🎯 Chuyển đến Lệnh 4: Miễn trừ MILLET") } }
                in 7..11 -> {
                    {
                        selectTab(R.id.nav_system, animated = true)
                        showAdvancedFlagshipDialog()
                        Toast.makeText(this@MainActivity, "👑 Chuyển đến 7 Lệnh Flagship Nâng Cao", Toast.LENGTH_SHORT).show()
                    }
                }
                in 12..14 -> {
                    {
                        showGpsFixDialog()
                        Toast.makeText(this@MainActivity, "📍 Chuyển đến 6 Lệnh GPS Việt Nam", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {
                    {
                        selectTab(R.id.nav_system, animated = true)
                        Toast.makeText(this@MainActivity, "✅ Toàn bộ 15/15 mục hệ thống đã được tối ưu hoàn hảo!", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            mainHandler.post {
                tvPercent?.text = "$percent%"
                progress?.progress = percent
                tvDetail?.text = "$fixedCount / $totalCount mục đã được tối ưu"

                when {
                    percent == 100 -> {
                        tvTitle?.text = "✅ Hệ thống tối ưu hoàn hảo"
                        tvPercent?.setTextColor(Color.parseColor("#34D399"))
                        btnGoToFix?.visibility = View.GONE
                    }
                    percent >= 60 -> {
                        tvTitle?.text = "⚡ Đã tối ưu phần lớn các mục"
                        tvPercent?.setTextColor(Color.parseColor("#FFA040"))
                        btnGoToFix?.visibility = View.VISIBLE
                    }
                    else -> {
                        tvTitle?.text = "⚠️ Hệ thống chưa được tối ưu"
                        tvPercent?.setTextColor(Color.parseColor("#FF5252"))
                        btnGoToFix?.visibility = View.VISIBLE
                    }
                }

                container?.removeAllViews()
                for ((idx, item) in items.withIndex()) {
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding((8 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt())
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        isClickable = true
                        isFocusable = true
                    }
                    ViewAnimationExtensions.applySpringTouch(row)

                    val rowClickAction: () -> Unit = when (idx) {
                        0, 1 -> { { dialog.dismiss(); scrollToAndHighlightView(binding.cardCmd1, "🎯 Chuyển đến Lệnh 1: Fix FCM & Heartbeat") } }
                        2, 3 -> { { dialog.dismiss(); scrollToAndHighlightView(binding.cardCmd2, "🎯 Chuyển đến Lệnh 2: Tắt Doze & Standby") } }
                        4, 5 -> { { dialog.dismiss(); scrollToAndHighlightView(binding.cardCmd3, "🎯 Chuyển đến Lệnh 3: Tắt Wi-Fi Power Save & Greezer") } }
                        6 -> { { dialog.dismiss(); scrollToAndHighlightView(binding.cardCmd4, "🎯 Chuyển đến Lệnh 4: Miễn trừ MILLET") } }
                        in 7..11 -> { { dialog.dismiss(); selectTab(R.id.nav_system, animated = true); showAdvancedFlagshipDialog(); Toast.makeText(this@MainActivity, "👑 Chuyển đến 7 Lệnh Flagship Nâng Cao", Toast.LENGTH_SHORT).show() } }
                        in 12..14 -> { { dialog.dismiss(); showGpsFixDialog(); Toast.makeText(this@MainActivity, "📍 Chuyển đến 6 Lệnh GPS Việt Nam", Toast.LENGTH_SHORT).show() } }
                        else -> { { dialog.dismiss(); selectTab(R.id.nav_system, animated = true) } }
                    }
                    row.setOnClickListener { rowClickAction() }

                    val icon = ImageView(this@MainActivity).apply {
                        val size = (18 * resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            rightMargin = (10 * resources.displayMetrics.density).toInt()
                        }
                        if (item.isFixed) {
                            setImageResource(R.drawable.ic_check_circle_small)
                            setColorFilter(Color.parseColor("#34D399"))
                        } else {
                            setImageResource(R.drawable.ic_close_white)
                            setColorFilter(Color.parseColor("#FF5252"))
                        }
                    }

                    val textCol = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val titleView = TextView(this@MainActivity).apply {
                        text = item.title
                        setTextColor(if (item.isFixed) Color.parseColor("#FFFFFF") else Color.parseColor("#E0E0E0"))
                        textSize = 12.5f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }

                    val detailView = TextView(this@MainActivity).apply {
                        text = item.detail
                        setTextColor(if (item.isFixed) Color.parseColor("#9E9E9E") else Color.parseColor("#FF8A80"))
                        textSize = 10.5f
                    }

                    textCol.addView(titleView)
                    textCol.addView(detailView)

                    row.addView(icon)
                    row.addView(textCol)
                    container?.addView(row)
                }
            }
        }

        dialog.show()
    }

    private fun showLimiAiChatDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_limi_ai_chat, null)
        val dialog = android.app.Dialog(this, R.style.FullScreenChatDialogTheme).apply {
            setContentView(dialogView)
            setCancelable(true)
        }

        val rvMessages = dialogView.findViewById<RecyclerView>(R.id.rvLimiChatMessages)
        val layoutHeader = dialogView.findViewById<LinearLayout>(R.id.layoutLimiChatHeader)
        val layoutInputContainer = dialogView.findViewById<LinearLayout>(R.id.layoutLimiChatInputContainer)
        val layoutTyping = dialogView.findViewById<View>(R.id.layoutLimiTyping)
        val etInput = dialogView.findViewById<EditText>(R.id.etLimiChatInput)
        val btnSend = dialogView.findViewById<View>(R.id.btnLimiChatSend)
        val btnClose = dialogView.findViewById<View>(R.id.btnAiChatClose)
        val btnClear = dialogView.findViewById<View>(R.id.btnAiClearChat)
        val btnKnowledge = dialogView.findViewById<View>(R.id.btnAiKnowledge)
        val viewChatBlur = dialogView.findViewById<View>(R.id.viewChatBlurBackground)

        // HIỆU ỨNG ĐỔI MÀU NỀN ĐỘNG (Dynamic Aurora Mesh / Fluid Gradient Color Shifting)
        val bgAnimator = viewChatBlur?.let { ThemeUtils.attachAuroraBackground(it, 0f) }
        val btnAttachImage = dialogView.findViewById<View>(R.id.btnLimiChatAttachImage)
        val layoutSelectedImagePreview = dialogView.findViewById<View>(R.id.layoutSelectedImagePreview)
        val tvChatImagePreviewTitle = dialogView.findViewById<TextView>(R.id.tvChatImagePreviewTitle)
        val scrollChatSelectedImages = dialogView.findViewById<android.widget.HorizontalScrollView>(R.id.scrollChatSelectedImages)
        val containerChatSelectedImages = dialogView.findViewById<LinearLayout>(R.id.layoutChatSelectedImagesContainer)
        val btnRemoveSelectedImage = dialogView.findViewById<View>(R.id.btnRemoveSelectedImage)

        val selectedChatBitmaps = mutableListOf<android.graphics.Bitmap>()
        val selectedChatBase64List = mutableListOf<String>()

        fun refreshChatImageThumbnails() {
            containerChatSelectedImages?.removeAllViews()
            tvChatImagePreviewTitle?.text = "📷 Đã đính kèm ảnh phân tích (${selectedChatBitmaps.size}/5):"
            if (selectedChatBitmaps.isEmpty()) {
                layoutSelectedImagePreview?.visibility = View.GONE
                return
            }
            layoutSelectedImagePreview?.visibility = View.VISIBLE
            val density = resources.displayMetrics.density
            val thumbSize = (56 * density).toInt()
            val marginEnd = (8 * density).toInt()
            val btnSize = (20 * density).toInt()

            for ((index, bmp) in selectedChatBitmaps.withIndex()) {
                val itemFrame = android.widget.FrameLayout(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                        this.marginEnd = marginEnd
                    }
                }

                val card = com.google.android.material.card.MaterialCardView(this@MainActivity).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    radius = 8 * density
                    strokeWidth = (1 * density).toInt()
                    strokeColor = Color.parseColor("#5038BDF8")
                    cardElevation = 0f
                    setCardBackgroundColor(Color.parseColor("#0F172A"))
                }

                val ivThumb = ImageView(this@MainActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                }
                card.addView(ivThumb)
                itemFrame.addView(card)

                // Nút xóa từng ảnh đính kèm
                val btnDel = ImageView(this@MainActivity).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(btnSize, btnSize).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.END
                        topMargin = (2 * density).toInt()
                        rightMargin = (2 * density).toInt()
                    }
                    setImageResource(R.drawable.ic_close_white)
                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#E0EF4444"))
                    }
                    background = bg
                    setPadding((3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt())
                    setColorFilter(Color.WHITE)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (index in 0 until selectedChatBitmaps.size) {
                            selectedChatBitmaps.removeAt(index)
                            if (index < selectedChatBase64List.size) {
                                selectedChatBase64List.removeAt(index)
                            }
                            refreshChatImageThumbnails()
                        }
                    }
                }
                itemFrame.addView(btnDel)
                containerChatSelectedImages?.addView(itemFrame)
            }
        }

        btnAttachImage?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnAttachImage?.setOnClickListener {
            if (selectedChatBitmaps.size >= 5) {
                Toast.makeText(this@MainActivity, "Đã chọn tối đa 5 ảnh đính kèm!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingImageCallback = { uri ->
                try {
                    val bmp = LimiVisionEngine.decodeSampledBitmapFromUri(this@MainActivity, uri)
                    if (bmp != null) {
                        if (selectedChatBitmaps.size < 5) {
                            selectedChatBitmaps.add(bmp)
                            refreshChatImageThumbnails()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Không thể đọc ảnh này", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    Toast.makeText(this@MainActivity, "Lỗi khi xử lý ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            pickImageLauncher.launch("image/*")
        }

        fun showFullscreenImageViewer(bmp: Bitmap) {
            if (isFinishing || isDestroyed) return
            try {
                val viewerView = layoutInflater.inflate(R.layout.dialog_image_viewer, null)
                val ivBlurBackground = viewerView.findViewById<ImageView>(R.id.ivBlurBackground)
                val ivFullPhoto = viewerView.findViewById<com.xiaomi.fixnotification.ai.ZoomableImageView>(R.id.ivFullPhoto)
                val btnCloseViewer = viewerView.findViewById<View>(R.id.btnCloseImageViewer)
                val layoutRoot = viewerView.findViewById<View>(R.id.layoutImageViewerRoot)
                val tvImageSource = viewerView.findViewById<TextView>(R.id.tvImageSource)

                // 1. Tạo hiệu ứng hình nền mờ điện ảnh siêu mượt
                val blurred = FastBlurHelper.blur(bmp, radius = 14, scale = 0.12f)
                if (blurred != null) {
                    ivBlurBackground?.setImageBitmap(blurred)
                } else {
                    ivBlurBackground?.setImageBitmap(bmp)
                }

                val viewDarkOverlay = viewerView.findViewById<View>(R.id.viewDarkOverlay)

                // 2. Nạp ảnh chính nét căng ở trung tâm hỗ trợ zoom đa điểm
                ivFullPhoto?.setImageBitmap(bmp)
                tvImageSource?.visibility = View.GONE

                val viewerDialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setView(viewerView)
                    .setCancelable(true)
                    .create()

                ivFullPhoto?.onSingleTap = {
                    viewerDialog.dismiss()
                }
                ivFullPhoto?.onDismissRequest = {
                    viewerDialog.dismiss()
                }
                ivFullPhoto?.onSwipeProgress = { alpha, _ ->
                    ivBlurBackground?.alpha = alpha * 0.85f
                    viewDarkOverlay?.alpha = alpha
                    btnCloseViewer?.alpha = alpha
                }

                viewerDialog.show()
                viewerDialog.window?.let { window ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        window.attributes = window.attributes.apply {
                            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                    window.statusBarColor = Color.TRANSPARENT
                    window.navigationBarColor = Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                        window.isStatusBarContrastEnforced = false
                    }
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    window.decorView.fitsSystemWindows = false
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.decorView.setPadding(0, 0, 0, 0)
                    window.attributes?.windowAnimations = R.style.DialogPopAnimation
                }

                val density = resources.displayMetrics.density
                ViewCompat.setOnApplyWindowInsetsListener(viewerView) { _, insets ->
                    val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                    val topSafe = statusBars.top.coerceAtLeast((24 * density).toInt())
                    (btnCloseViewer?.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                        lp.topMargin = topSafe + (8 * density).toInt()
                        btnCloseViewer.layoutParams = lp
                    }
                    insets
                }

                btnCloseViewer?.let { ViewAnimationExtensions.applySpringTouch(it) }
                btnCloseViewer?.setOnClickListener {
                    viewerDialog.dismiss()
                }

                layoutRoot?.setOnClickListener {
                    viewerDialog.dismiss()
                }
            } catch (e: Throwable) {
                Toast.makeText(this@MainActivity, "Không thể mở ảnh phóng to", Toast.LENGTH_SHORT).show()
            }
        }

        val chatAdapter = LimiChatAdapter(
            messages = mutableListOf(),
            onActionClick = null,
            onFeedbackThumbUp = { msg, pos ->
                Toast.makeText(this@MainActivity, "Cảm ơn bạn đã thích phản hồi này! ❤️", Toast.LENGTH_SHORT).show()
            },
            onFeedbackThumbDown = { msg, pos ->
                Toast.makeText(this@MainActivity, "Cảm ơn góp ý của bạn! Limi sẽ cải thiện tốt hơn.", Toast.LENGTH_SHORT).show()
            },
            onFeedbackContribute = { msg, pos ->
                btnKnowledge?.performClick()
            },
            onImageClick = { bmp ->
                showFullscreenImageViewer(bmp)
            }
        )

        var isUserScrolledUp = false
        val layoutMgr = LinearLayoutManager(this).apply {
            stackFromEnd = false
        }
        rvMessages?.layoutManager = layoutMgr
        rvMessages?.itemAnimator = null
        rvMessages?.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        rvMessages?.adapter = chatAdapter

        rvMessages?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (recyclerView.canScrollVertically(1)) {
                        isUserScrolledUp = true
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    isUserScrolledUp = false
                }
            }
        })

        // Khởi tạo và tự động đồng bộ kho tri thức Drive trong nền nếu có cấu hình
        LimiKnowledgeBase.initIfNeeded(this@MainActivity)
        if (LimiKnowledgeBase.hasCustomUrl(this@MainActivity)) {
            LimiKnowledgeBase.syncRemoteKnowledge(this@MainActivity)
        }

        // Nếu chưa có tin nhắn nào trong lịch sử, nạp lời chào khởi đầu của Limi (Tự động phát hiện nếu vừa có lỗi)
        if (LimiAiService.getHistory().isEmpty()) {
            val welcomeMsg = if (LimiAiService.hasRecentError()) {
                val err = LimiAiService.getLastError()!!
                LimiChatMessage(
                    sender = MessageSender.LIMI,
                    text = "Xin chào bạn! Limi nhận thấy bạn vừa gặp lỗi khi thực thi **${err.commandName}** (Mã lỗi: ${err.exitCode}) trong ứng dụng.\n\nBạn có muốn Limi giải thích nguyên nhân và hướng dẫn cách khắc phục ngay bây giờ không?\n\n⚠️ *Lưu ý: Trợ lý AI Limi có thể đưa ra thông tin chưa chính xác, vui lòng kiểm tra lại các thông tin quan trọng.*",
                    actionButtons = listOf(
                        ChatActionButton("🔍 Phân Tích & Sửa Lỗi Vừa Xảy Ra", R.drawable.ic_shizuku_warning, ChatActionType.EXPLAIN_LAST_ERROR),
                        ChatActionButton("🎬 Xem Video Hướng Dẫn", R.drawable.ic_play_arrow, ChatActionType.OPEN_VIDEO_GUIDE)
                    )
                )
            } else {
                LimiChatMessage(
                    sender = MessageSender.LIMI,
                    text = "Xin chào! Mình là **Limi** – Trợ lý AI chuyên trách của Bộ công cụ LIMI.\n\nMình có thể hỗ trợ bạn:\n• Hướng dẫn chi tiết 15 lệnh fix thông báo & 7 lệnh Flagship.\n• Cấp quyền Shizuku, tự khởi chạy ứng dụng và tối ưu hóa pin.\n• Tra cứu thông số thiết bị Xiaomi, ô tô Xiaomi Auto và thiết bị thông minh.\n\nBạn cần hỗ trợ về vấn đề gì hôm nay?\n\n*Lưu ý: Trợ lý AI có thể đưa ra thông tin tham khảo, vui lòng đối chiếu với thực tế thiết bị.*",
                    actionButtons = listOf(
                        ChatActionButton("Xem Video Hướng Dẫn", R.drawable.ic_play_arrow, ChatActionType.OPEN_VIDEO_GUIDE),
                        ChatActionButton("Thông Tin Thiết Bị", R.drawable.ic_device_info, ChatActionType.OPEN_MY_DEVICE)
                    )
                )
            }
            LimiAiService.addMessage(welcomeMsg)
        }
        chatAdapter.setMessages(LimiAiService.getHistory())
        if (chatAdapter.itemCount > 0) {
            rvMessages?.scrollToPosition(chatAdapter.itemCount - 1)
        }

        var currentTypingRunnable: Runnable? = null
        var currentTypingMessage: LimiChatMessage? = null
        var currentTypingFullText: String = ""
        var currentTypingPendingButtons: List<ChatActionButton> = emptyList()
        var currentTypingPendingAppPreview: AppPreviewInfo? = null
        var currentTypingPendingBloatwareList: List<BloatwareSelectionItem> = emptyList()
        var currentTypingPendingImageBitmap: Bitmap? = null
        var currentTypingPendingRichSections: List<LimiChatSection> = emptyList()

        var isGenerating = false
        var pendingOfflineRunnable: Runnable? = null

        fun setSendButtonState(isBusy: Boolean) {
            isGenerating = isBusy
            val btn = btnSend as? com.google.android.material.button.MaterialButton
            if (isBusy) {
                btn?.icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_stop_white)
                btn?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                btn?.isEnabled = true
            } else {
                btn?.icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_send_white)
                btn?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.primary))
                btn?.isEnabled = true
            }
        }

        // Tự động khôi phục trạng thái nếu AI đang tạo câu trả lời trong nền
        if (LimiAiService.isGeneratingActive()) {
            layoutTyping?.visibility = View.VISIBLE
            setSendButtonState(true)
        }

        // Đăng ký nhận kết quả thời gian thực từ luồng nền AI
        LimiAiService.registerUiListeners(
            onSuccess = { reply, returnedBmp, richSections ->
                mainHandler.post {
                    if (dialog.isShowing && !isFinishing && !isDestroyed) {
                        if (currentTypingRunnable == null) {
                            layoutTyping?.visibility = View.GONE
                            setSendButtonState(false)
                            chatAdapter.setMessages(LimiAiService.getHistory())
                            if (chatAdapter.itemCount > 0) {
                                rvMessages?.scrollToPosition(chatAdapter.itemCount - 1)
                            }
                        }
                    }
                }
            },
            onError = { _ ->
                mainHandler.post {
                    if (dialog.isShowing && !isFinishing && !isDestroyed) {
                        layoutTyping?.visibility = View.GONE
                        setSendButtonState(false)
                        chatAdapter.setMessages(LimiAiService.getHistory())
                        if (chatAdapter.itemCount > 0) {
                            rvMessages?.scrollToPosition(chatAdapter.itemCount - 1)
                        }
                    }
                }
            },
            onStateChange = { isBusy ->
                mainHandler.post {
                    if (dialog.isShowing && !isFinishing && !isDestroyed) {
                        setSendButtonState(isBusy)
                        layoutTyping?.visibility = if (isBusy) View.VISIBLE else View.GONE
                    }
                }
            }
        )

        fun finalizeCurrentTyping() {
            if (currentTypingRunnable != null) {
                mainHandler.removeCallbacks(currentTypingRunnable!!)
                currentTypingRunnable = null
                currentTypingMessage?.text = currentTypingFullText
                currentTypingMessage?.actionButtons = currentTypingPendingButtons
                currentTypingMessage?.appPreview = currentTypingPendingAppPreview
                currentTypingMessage?.bloatwareList = currentTypingPendingBloatwareList
                currentTypingMessage?.imageBitmap = currentTypingPendingImageBitmap
                currentTypingMessage?.richSections = currentTypingPendingRichSections
                chatAdapter.completeLastMessage(
                    currentTypingFullText,
                    currentTypingPendingButtons,
                    currentTypingPendingAppPreview,
                    currentTypingPendingBloatwareList,
                    currentTypingPendingImageBitmap,
                    currentTypingPendingRichSections
                )
                currentTypingMessage = null
                currentTypingFullText = ""
                currentTypingPendingButtons = emptyList()
                currentTypingPendingAppPreview = null
                currentTypingPendingBloatwareList = emptyList()
                currentTypingPendingImageBitmap = null
                currentTypingPendingRichSections = emptyList()
            }
            setSendButtonState(false)
        }

        fun streamTypewriterMessage(
            fullText: String,
            buttons: List<ChatActionButton> = emptyList(),
            appPreview: AppPreviewInfo? = null,
            bloatwareList: List<BloatwareSelectionItem> = emptyList(),
            imageBitmap: Bitmap? = null,
            richSections: List<LimiChatSection> = emptyList()
        ) {
            finalizeCurrentTyping()
            layoutTyping?.visibility = View.GONE
            setSendButtonState(true)

            // Khởi tạo tin nhắn hiển thị; nếu LimiAiService đã lưu phản hồi trong lịch sử thì tái sử dụng thay vì thêm mới
            val history = LimiAiService.getHistory()
            val existingLimiMsg = history.lastOrNull()?.takeIf {
                it.sender == MessageSender.LIMI && (it.text == fullText || it.text.isEmpty())
            }

            val aiMsg = existingLimiMsg ?: LimiChatMessage(
                sender = MessageSender.LIMI,
                text = "",
                actionButtons = emptyList(),
                appPreview = null,
                bloatwareList = emptyList(),
                imageBitmap = null,
                richSections = emptyList()
            ).also { LimiAiService.addMessage(it) }

            aiMsg.text = ""
            currentTypingMessage = aiMsg
            currentTypingFullText = fullText
            currentTypingPendingButtons = buttons
            currentTypingPendingAppPreview = appPreview
            currentTypingPendingBloatwareList = bloatwareList
            currentTypingPendingImageBitmap = imageBitmap
            currentTypingPendingRichSections = richSections

            if (chatAdapter.itemCount == 0 || chatAdapter.getMessages().lastOrNull()?.id != aiMsg.id) {
                chatAdapter.addMessage(aiMsg)
            }
            rvMessages?.scrollToPosition(chatAdapter.itemCount - 1)

            var currentIndex = 0
            val totalLength = fullText.length
            val chunkSize = when {
                totalLength > 800 -> 8
                totalLength > 400 -> 5
                totalLength > 200 -> 3
                totalLength > 80 -> 2
                else -> 1
            }
            val delayMs = 12L
            isUserScrolledUp = false

            val runnable = object : Runnable {
                override fun run() {
                    if (isFinishing || isDestroyed || !dialog.isShowing) {
                        aiMsg.text = fullText
                        aiMsg.actionButtons = buttons
                        aiMsg.appPreview = appPreview
                        aiMsg.bloatwareList = bloatwareList
                        aiMsg.imageBitmap = imageBitmap
                        aiMsg.richSections = richSections
                        chatAdapter.completeLastMessage(fullText, buttons, appPreview, bloatwareList, imageBitmap, richSections)
                        currentTypingRunnable = null
                        currentTypingMessage = null
                        currentTypingFullText = ""
                        currentTypingPendingButtons = emptyList()
                        currentTypingPendingAppPreview = null
                        currentTypingPendingBloatwareList = emptyList()
                        currentTypingPendingImageBitmap = null
                        currentTypingPendingRichSections = emptyList()
                        setSendButtonState(false)
                        return
                    }

                    currentIndex = (currentIndex + chunkSize).coerceAtMost(totalLength)
                    val currentText = fullText.substring(0, currentIndex)
                    aiMsg.text = currentText
                    chatAdapter.updateLastMessageText(currentText)

                    if (currentIndex < totalLength) {
                        // Tự động bám theo đáy văn bản mượt mà, không giật nếu người dùng không chủ động cuộn lên
                        if (!isUserScrolledUp) {
                            val targetPos = chatAdapter.itemCount - 1
                            if (targetPos >= 0) {
                                val lm = rvMessages?.layoutManager as? LinearLayoutManager
                                val child = lm?.findViewByPosition(targetPos)
                                if (child != null && rvMessages != null) {
                                    val bottomDiff = child.bottom - (rvMessages.height - rvMessages.paddingBottom)
                                    if (bottomDiff > 0) {
                                        rvMessages.scrollBy(0, bottomDiff)
                                    }
                                } else {
                                    lm?.scrollToPositionWithOffset(targetPos, 0)
                                }
                            }
                        }
                        mainHandler.postDelayed(this, delayMs)
                    } else {
                        aiMsg.text = fullText
                        aiMsg.actionButtons = buttons
                        aiMsg.appPreview = appPreview
                        aiMsg.bloatwareList = bloatwareList
                        aiMsg.imageBitmap = imageBitmap
                        aiMsg.richSections = richSections
                        // Kích hoạt Full Bind để hiện ngay lập tức thanh Like / Unlike & Góp ý & Hình ảnh
                        chatAdapter.completeLastMessage(fullText, buttons, appPreview, bloatwareList, imageBitmap, richSections)
                        val targetPos = chatAdapter.itemCount - 1
                        if (targetPos >= 0 && !isUserScrolledUp) {
                            rvMessages?.post {
                                val lm = rvMessages.layoutManager as? LinearLayoutManager
                                val child = lm?.findViewByPosition(targetPos)
                                if (child != null && rvMessages != null) {
                                    val bottomDiff = child.bottom - (rvMessages.height - rvMessages.paddingBottom)
                                    if (bottomDiff > 0) {
                                        rvMessages.scrollBy(0, bottomDiff)
                                    }
                                } else {
                                    lm?.scrollToPositionWithOffset(targetPos, 0)
                                }
                            }
                        }
                        currentTypingRunnable = null
                        currentTypingMessage = null
                        currentTypingFullText = ""
                        currentTypingPendingButtons = emptyList()
                        currentTypingPendingAppPreview = null
                        currentTypingPendingBloatwareList = emptyList()
                        currentTypingPendingImageBitmap = null
                        currentTypingPendingRichSections = emptyList()
                        setSendButtonState(false)
                    }
                }
            }

            currentTypingRunnable = runnable
            mainHandler.post(runnable)
        }

        fun sendMessageText(text: String, isInstant: Boolean = false) {
            isUserScrolledUp = false
            val trimmed = text.trim()
            val attachedBitmaps = ArrayList(selectedChatBitmaps)
            val attachedB64List = ArrayList(selectedChatBase64List)
            val firstBmp = attachedBitmaps.firstOrNull()
            val firstB64 = attachedB64List.firstOrNull()

            if (trimmed.isEmpty() && attachedBitmaps.isEmpty()) return
            finalizeCurrentTyping()
            etInput?.setText("")

            // Reset trạng thái xem trước ảnh sau khi gửi
            selectedChatBitmaps.clear()
            selectedChatBase64List.clear()
            refreshChatImageThumbnails()

            val displayText = if (trimmed.isEmpty() && attachedBitmaps.isNotEmpty()) {
                if (attachedBitmaps.size == 1) "📷 [Gửi 1 ảnh chụp màn hình/minh họa]" else "📷 [Gửi ${attachedBitmaps.size} ảnh chụp màn hình/minh họa]"
            } else trimmed

            val q = LimiAiService.normalizeVietnameseQuery(displayText)
            val isConfirmOrCancel = LimiAiService.getPendingAction() != PendingAiAction.NONE ||
                    q in listOf("ok", "oke", "được", "duoc", "chạy đi", "chay di", "đồng ý", "dong y", "tiến hành", "chạy luôn", "yes", "yep", "chạy ngay", "làm đi", "triển đi", "chạy", "thực hiện", "thực hiện đi", "uh", "ừ", "ok nhé", "oke nhé", "xóa đi", "xoa di", "gỡ đi", "go di", "xóa giúp", "gỡ giúp", "cấp đi", "cap di", "cấp quyền đi", "mở đi", "mo di", "mở tab", "chắc chắn", "xóa luôn", "xoa luon", "gỡ luôn", "chuẩn", "duyệt", "đổi đi", "doi di", "đổi luôn", "doi luon", "đổi tiếng", "đổi tiếng việt", "chuyển đi", "chuyen di", "bật tiếng việt", "hủy", "huy", "thôi", "thoi", "không", "khong", "cancel", "đừng", "dung", "dừng", "dung lai", "bỏ qua", "không cần", "khong can", "ko", "k", "no", "không xóa", "khong xoa", "thôi đừng xóa")

            if (isConfirmOrCancel) {
                chatAdapter.clearConfirmationButtons()
            }

            val userMsg = LimiChatMessage(
                sender = MessageSender.USER,
                text = displayText,
                imageBitmap = firstBmp,
                imageBitmaps = attachedBitmaps,
                imageBase64 = firstB64,
                imageBase64List = attachedB64List
            )
            LimiAiService.addMessage(userMsg)
            chatAdapter.addMessage(userMsg)
            if (chatAdapter.itemCount > 0) {
                rvMessages?.scrollToPosition(chatAdapter.itemCount - 1)
            }

            layoutTyping?.visibility = View.VISIBLE
            setSendButtonState(true)

            // BƯỚC 1 (Mặc định): Nếu không đính kèm ảnh, kiểm tra nhanh bộ máy Offline / Rule Engine cục bộ
            if (attachedB64List.isEmpty()) {
                val offlineResult = LimiAiService.getInstantOfflineResult(displayText, this@MainActivity)
                if (offlineResult != null) {
                    val res = offlineResult
                    val offlineRunnable = Runnable {
                        pendingOfflineRunnable = null
                        streamTypewriterMessage(res.text, res.actionButtons, res.appPreview, res.bloatwareList, res.imageBitmap)

                        // Nếu người dùng trả lời đồng ý bằng lời nói ("ok", "được", "chạy đi", "xóa đi", "đổi đi"...), tự động thực thi tác vụ sau khi tin nhắn gõ xong
                        val execBtn = res.actionButtons.firstOrNull {
                            it.actionType == ChatActionType.EXECUTE_15_FIX_COMMANDS ||
                            it.actionType == ChatActionType.EXECUTE_7_FLAGSHIP_COMMANDS ||
                            it.actionType == ChatActionType.EXECUTE_6_GPS_COMMANDS ||
                            it.actionType == ChatActionType.EXECUTE_RESET_ALL ||
                            it.actionType == ChatActionType.EXECUTE_UNINSTALL_APP ||
                            it.actionType == ChatActionType.EXECUTE_UNINSTALL_BLOATWARE ||
                            it.actionType == ChatActionType.EXECUTE_CHANGE_VIETNAMESE_LOCALE ||
                            it.actionType == ChatActionType.OPEN_DEBLOAT_TAB ||
                            it.actionType == ChatActionType.OPEN_PERMISSIONS_TAB
                        }
                        val affirmativeWords = listOf("ok", "oke", "được", "duoc", "chạy đi", "chay di", "đồng ý", "dong y", "tiến hành", "chạy luôn", "yes", "chạy ngay", "làm đi", "triển đi", "chạy", "thực hiện", "thực hiện đi", "uh", "ừ", "ok nhé", "oke nhé", "xóa đi", "xoa di", "gỡ đi", "go di", "xóa giúp", "gỡ giúp", "cấp đi", "cap di", "cấp quyền đi", "mở đi", "mo di", "mở tab", "chắc chắn", "xóa luôn", "xoa luon", "gỡ luôn", "chuẩn", "duyệt", "xóa rác", "dọn rác", "xóa bloatware", "xoa bloatware", "đổi đi", "doi di", "đổi luôn", "doi luon", "đổi tiếng", "đổi tiếng việt", "chuyển đi", "chuyen di", "bật tiếng việt")
                        if (execBtn != null && displayText.lowercase() in affirmativeWords) {
                            mainHandler.postDelayed({
                                if (dialog.isShowing && !isFinishing && !isDestroyed) {
                                    chatAdapter.onActionClick?.invoke(execBtn)
                                }
                            }, 1300)
                        }
                    }
                    pendingOfflineRunnable = offlineRunnable
                    mainHandler.postDelayed(offlineRunnable, 200)
                    return
                }
            }

            // BƯỚC 2 (Tự động chuyển qua API Key): Kích hoạt gọi Google Gemini Multimodal API Key khi:
            // - Người dùng đính kèm ảnh chụp màn hình / hình ảnh lỗi (hỗ trợ tối đa 5 ảnh phân tích cùng lúc)
            // - Người dùng sau khi đọc bước 1 mà vẫn bảo lỗi / chưa được / không hiểu
            // - Hoặc câu hỏi phức tạp khó hiểu chưa có trong rule offline
            LimiAiService.sendMessage(
                userText = displayText,
                imageBitmap = firstBmp,
                imageBase64 = firstB64,
                imageBitmaps = attachedBitmaps,
                imageBase64List = attachedB64List,
                context = this@MainActivity,
                onSuccess = { reply, returnedBmp, richSections ->
                    mainHandler.post {
                        if (dialog.isShowing && !isFinishing && !isDestroyed) {
                            streamTypewriterMessage(reply, imageBitmap = returnedBmp, richSections = richSections)
                        } else {
                            layoutTyping?.visibility = View.GONE
                            setSendButtonState(false)
                        }
                    }
                },
                onError = { _ ->
                    mainHandler.post {
                        layoutTyping?.visibility = View.GONE
                        setSendButtonState(false)
                        if (dialog.isShowing && !isFinishing && !isDestroyed) {
                            val errorText = "⚠️ [Limi AI] Không thể kết nối đến máy chủ hoặc máy chủ đang quá tải. Bạn hãy thử lại sau vài giây nhé!"
                            streamTypewriterMessage(errorText)
                        }
                    }
                }
            )
        }

        chatAdapter.onActionClick = onAction@ { actionBtn ->
            chatAdapter.clearConfirmationButtons()
            when (actionBtn.actionType) {
                ChatActionType.OPEN_VIDEO_GUIDE -> {
                    val videoUrl = "https://drive.google.com/drive/folders/1rylkubajy0gEqUxchwlvLsyNLI32zOON?usp=sharing"
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl))
                        startActivity(intent)
                    } catch (e: Throwable) {
                        Toast.makeText(this, "Không thể mở liên kết video hướng dẫn", Toast.LENGTH_SHORT).show()
                    }
                }
                ChatActionType.OPEN_DONATE -> {
                    dialog.dismiss()
                    showDonateDeveloperDialog()
                }
                ChatActionType.OPEN_COMMUNITY -> {
                    try {
                        val zaloIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://zalo.me/g/kgjjkz596"))
                        startActivity(zaloIntent)
                    } catch (e: Throwable) {
                        Toast.makeText(this, "Không thể mở liên kết Zalo", Toast.LENGTH_SHORT).show()
                    }
                }
                ChatActionType.OPEN_MY_DEVICE -> {
                    dialog.dismiss()
                    val intent = Intent(this, MyDeviceActivity::class.java)
                    startActivity(intent)
                }
                ChatActionType.OPEN_PROGRESS -> {
                    dialog.dismiss()
                    showFixProgressCheckDialog()
                }
                ChatActionType.OPEN_BATTERY_HEALTH -> {
                    dialog.dismiss()
                    val intent = Intent(this, BatteryHealthActivity::class.java)
                    startActivity(intent)
                }
                ChatActionType.OPEN_SHIZUKU_GUIDE -> {
                    sendMessageText("Hướng dẫn các bước kích hoạt Shizuku qua Ghép nối Wi-Fi không dây từng bước một", isInstant = true)
                }
                ChatActionType.OPEN_DEVELOPER_OPTIONS -> {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        Toast.makeText(this, "⚙️ Đang mở Tùy chọn nhà phát triển...", Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                        Toast.makeText(this, "Không thể mở Tùy chọn nhà phát triển: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
                ChatActionType.OPEN_DEVICE_INFO_SETTINGS -> {
                    try {
                        startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                        Toast.makeText(this, "📱 Mở Thông tin thiết bị: Nhấn 7 lần vào Phiên bản OS / HyperOS để bật Dev!", Toast.LENGTH_LONG).show()
                    } catch (e: Throwable) {
                        Toast.makeText(this, "Không thể mở Thông tin thiết bị: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
                ChatActionType.OPEN_SHIZUKU_APP -> {
                    val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                        Toast.makeText(this, "🚀 Đang mở ứng dụng Shizuku...", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Chưa cài đặt app Shizuku trên máy. Hãy cài Shizuku từ Play Store / GitHub nhé!", Toast.LENGTH_LONG).show()
                    }
                }
                ChatActionType.OPEN_RESET_DIALOG -> {
                    dialog.dismiss()
                    showSettingResetDialog()
                }
                ChatActionType.EXPLAIN_LAST_ERROR -> {
                    sendMessageText("Phân tích chi tiết lỗi vừa xảy ra trong app và cách khắc phục", isInstant = true)
                }
                ChatActionType.EXECUTE_CHANGE_VIETNAMESE_LOCALE -> {
                    dialog.dismiss()
                    if (!ShizukuUtils.hasShizukuPermission()) {
                        Toast.makeText(this, "Cần cấp quyền Shizuku trước!", Toast.LENGTH_SHORT).show()
                        return@onAction
                    }
                    Toast.makeText(this, "🇻🇳 Limi đang chuyển đổi ngôn ngữ sang Tiếng Việt...", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, VietnamVideoActivity::class.java)
                    startActivity(intent)
                }
                ChatActionType.COPY_TEXT -> {
                    val textToCopy = actionBtn.payload ?: ""
                    if (textToCopy.isNotBlank()) {
                        try {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Limi Translation", textToCopy)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this, "📋 Đã sao chép bản dịch vào bộ nhớ tạm", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Không thể sao chép: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                ChatActionType.RETRANSLATE_WITH_AI -> {
                    val payload = actionBtn.payload ?: ""
                    val parts = payload.split("::", limit = 2)
                    val lang = if (parts.isNotEmpty()) parts[0] else "vi"
                    val content = if (parts.size > 1) parts[1] else payload
                    val retranslatePrompt = "Hãy phân tích ngữ cảnh và dịch lại thật tự nhiên, chính xác đoạn văn bản sau sang ngôn ngữ '$lang':\n\n$content"
                    etInput?.setText(retranslatePrompt)
                    btnSend?.performClick()
                }
                ChatActionType.SUMMARIZE_WITH_AI -> {
                    val content = actionBtn.payload ?: ""
                    val prompt = if (content.length < 80) {
                        "Hãy tra cứu thông tin chi tiết, phân tích chuyên sâu và cập nhật mới nhất về:\n\n$content"
                    } else {
                        "Hãy tóm tắt chuyên sâu đoạn văn bản sau, chắt lọc 3-5 ý cốt lõi và số liệu quan trọng nhất:\n\n$content"
                    }
                    etInput?.setText(prompt)
                    btnSend?.performClick()
                }
                ChatActionType.OPEN_YOUTUBE_SEARCH -> {
                    val query = actionBtn.payload ?: "nhạc remix"
                    Toast.makeText(this, "🎵 Đang mở YouTube: $query", Toast.LENGTH_SHORT).show()
                    try {
                        val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                        ytIntent.setPackage("com.google.android.youtube")
                        startActivity(ytIntent)
                    } catch (_: Exception) {
                        try {
                            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                            startActivity(webIntent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Không thể mở trình duyệt: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                ChatActionType.OPEN_GOOGLE_LENS -> {
                    val success = LimiVisionEngine.openGoogleLens(this@MainActivity)
                    if (!success) {
                        Toast.makeText(this@MainActivity, "Không tìm thấy Google Lens trên thiết bị", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "🔍 Đang mở Google Lens để quét vật thể...", Toast.LENGTH_SHORT).show()
                    }
                }
                ChatActionType.IDENTIFY_WITH_AI -> {
                    Toast.makeText(this@MainActivity, "📷 Chọn ảnh để Gemini AI phân tích chuyên sâu...", Toast.LENGTH_SHORT).show()
                    btnAttachImage?.performClick()
                }
                ChatActionType.EXECUTE_15_FIX_COMMANDS -> {
                    dialog.dismiss()
                    selectTab(R.id.nav_system, animated = true)
                    Toast.makeText(this, "⚡ Limi đang thực thi 15 Lệnh Fix Thông Báo Hệ Thống...", Toast.LENGTH_SHORT).show()
                    mainHandler.postDelayed({
                        runAllCommands()
                    }, 350)
                }
                ChatActionType.EXECUTE_7_FLAGSHIP_COMMANDS -> {
                    dialog.dismiss()
                    selectTab(R.id.nav_system, animated = true)
                    Toast.makeText(this, "💎 Limi đang thực thi 7 Lệnh Flagship Nâng Cao...", Toast.LENGTH_SHORT).show()
                    mainHandler.postDelayed({
                        runAllAdvancedCommands()
                    }, 350)
                }
                ChatActionType.EXECUTE_6_GPS_COMMANDS -> {
                    dialog.dismiss()
                    selectTab(R.id.nav_system, animated = true)
                    Toast.makeText(this, "📍 Limi đang thực thi 6 Lệnh Tối Ưu Định Vị GPS Việt Nam...", Toast.LENGTH_SHORT).show()
                    mainHandler.postDelayed({
                        runAllGpsCommands()
                    }, 350)
                }
                ChatActionType.EXECUTE_RESET_ALL -> {
                    dialog.dismiss()
                    Toast.makeText(this, "🔄 Limi đang khôi phục cài đặt gốc toàn bộ lệnh...", Toast.LENGTH_SHORT).show()
                    mainHandler.postDelayed({
                        runResetAllCommands()
                    }, 350)
                }
                ChatActionType.EXECUTE_UNINSTALL_APP -> {
                    val targetPkg = actionBtn.payload ?: LimiAiService.getPendingTargetPackage()
                    if (targetPkg.isNullOrBlank()) {
                        Toast.makeText(this, "Không tìm thấy thông tin gói ứng dụng", Toast.LENGTH_SHORT).show()
                        return@onAction
                    }
                    if (!ShizukuUtils.hasShizukuPermission()) {
                        Toast.makeText(this, "Cần cấp quyền Shizuku trước!", Toast.LENGTH_SHORT).show()
                        streamTypewriterMessage("⚠️ Không thể thực hiện vì chưa được cấp quyền Shizuku. Bạn hãy kích hoạt Shizuku trước nhé!", listOf(
                            ChatActionButton("⚡ Hướng Dẫn Shizuku", R.drawable.ic_shizuku_warning, ChatActionType.OPEN_SHIZUKU_GUIDE)
                        ))
                        return@onAction
                    }

                    val safety = LimiAiService.checkPackageSafetyAndImpact(this, targetPkg)
                    if (safety.level == PackageRiskLevel.CRITICAL_SYSTEM) {
                        Toast.makeText(this, "🛑 Đã ngăn chặn: Ứng dụng hệ thống cốt lõi không được gỡ bỏ!", Toast.LENGTH_LONG).show()
                        streamTypewriterMessage("🛑 **CẢNH BÁO TỐI KHẨN CẤP - ĐÃ TỰ ĐỘNG NGĂN CHẶN!**\n\nỨng dụng `$targetPkg` (${safety.appName}) thuộc nhóm **${safety.categoryName}**.\n\n⚠️ **HẬU QUẢ NẾU XÓA**: Gây treo logo Bootloop hoặc mất hoàn toàn giao diện hệ thống!\n\nLimi AI đã **từ chối thực thi lệnh này** để bảo vệ an toàn cho thiết bị của bạn.")
                        return@onAction
                    }

                    val cmd = "pm uninstall --user 0 $targetPkg"
                    appendLog("\n[Limi AI] Gỡ cài đặt ứng dụng: $targetPkg (${safety.appName})", LogTarget.DEBLOAT)
                    appendLog("$ $cmd", LogTarget.DEBLOAT)

                    executor.execute {
                        val result = ShizukuUtils.execShizukuCommand(cmd)
                        mainHandler.post {
                            if (result.exitCode == 0 || result.stdout.contains("Success", ignoreCase = true)) {
                                appendLog("  -> Gỡ cài đặt thành công: $targetPkg", LogTarget.DEBLOAT)
                                Toast.makeText(this@MainActivity, "✅ Đã gỡ thành công: ${safety.appName}", Toast.LENGTH_SHORT).show()
                                loadDebloatApps()
                                val msgBuilder = StringBuilder()
                                msgBuilder.append("✅ **Đã gỡ cài đặt thành công ứng dụng!**\n\n")
                                msgBuilder.append("• **Tên ứng dụng**: ${safety.appName}\n")
                                msgBuilder.append("• **Package**: `$targetPkg`\n")
                                msgBuilder.append("• **Phân loại**: ${safety.categoryName}\n")
                                if (safety.impactConsequence.isNotBlank()) {
                                    msgBuilder.append("• **Lưu ý**: ${safety.impactConsequence}\n")
                                }
                                msgBuilder.append("\n*(Nếu sau này bạn muốn khôi phục lại, hãy vào tab **Debloat** -> chọn **Khôi phục ứng dụng đã xóa**)*")

                                streamTypewriterMessage(msgBuilder.toString(), listOf(
                                    ChatActionButton("🗑️ Mở Tab Debloat", R.drawable.ic_nav_trash, ChatActionType.OPEN_DEBLOAT_TAB)
                                ))
                            } else {
                                val err = result.stderr.ifEmpty { result.stdout }.ifEmpty { "Exit code ${result.exitCode}" }
                                appendLog("  -> Thất bại: $err", LogTarget.DEBLOAT)
                                Toast.makeText(this@MainActivity, "Gỡ thất bại: $err", Toast.LENGTH_SHORT).show()
                                LimiAiService.recordLastError(
                                    commandName = "[AI Chat] Gỡ cài đặt: $targetPkg (${safety.appName})",
                                    commandText = cmd,
                                    exitCode = result.exitCode,
                                    stderr = result.stderr,
                                    stdout = result.stdout
                                )
                                streamTypewriterMessage("⚠️ **Gỡ cài đặt thất bại**: $err\n\nBạn hãy kiểm tra lại trạng thái quyền Shizuku hoặc ứng dụng có thể đã bị gỡ từ trước.")
                            }
                        }
                    }
                }
                ChatActionType.EXECUTE_UNINSTALL_BLOATWARE -> {
                    if (!ShizukuUtils.hasShizukuPermission()) {
                        Toast.makeText(this, "Cần cấp quyền Shizuku trước!", Toast.LENGTH_SHORT).show()
                        streamTypewriterMessage("⚠️ Không thể thực hiện vì chưa được cấp quyền Shizuku. Bạn hãy kích hoạt Shizuku trước nhé!", listOf(
                            ChatActionButton("⚡ Hướng Dẫn Shizuku", R.drawable.ic_shizuku_warning, ChatActionType.OPEN_SHIZUKU_GUIDE)
                        ))
                        return@onAction
                    }

                    var rawBloatList = LimiAiService.getPendingBloatwareList()
                    if (rawBloatList.isEmpty()) {
                        rawBloatList = LimiAiService.scanInstalledBloatware(this@MainActivity)
                    }

                    val selectedBloat = rawBloatList.filter { it.isSelected }

                    if (selectedBloat.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Bạn chưa chọn ứng dụng nào để gỡ!", Toast.LENGTH_SHORT).show()
                        streamTypewriterMessage("⚠️ **Bạn chưa chọn ứng dụng nào để gỡ!**\n\nHãy tích chọn ít nhất 1 ứng dụng trong danh sách bên trên rồi bấm lại nút **Đồng Ý & Xóa** nhé.")
                        return@onAction
                    }

                    Toast.makeText(this@MainActivity, "🗑️ Đang tiến hành gỡ bỏ ${selectedBloat.size} ứng dụng bloatware...", Toast.LENGTH_SHORT).show()
                    appendLog("\n[Limi AI] Bắt đầu gỡ ${selectedBloat.size} ứng dụng Bloatware đã chọn:", LogTarget.DEBLOAT)

                    executor.execute {
                        var successCount = 0
                        var failCount = 0
                        val successNames = mutableListOf<String>()

                        for (item in selectedBloat) {
                            val pkg = item.packageName
                            val name = item.name
                            val cmd = "pm uninstall --user 0 $pkg"
                            appendLog("$ $cmd", LogTarget.DEBLOAT)
                            val result = ShizukuUtils.execShizukuCommand(cmd)
                            if (result.exitCode == 0 || result.stdout.contains("Success", ignoreCase = true)) {
                                successCount++
                                successNames.add(name)
                                appendLog("  -> ✅ Đã gỡ thành công: $name ($pkg)", LogTarget.DEBLOAT)
                            } else {
                                failCount++
                                val err = result.stderr.ifEmpty { result.stdout }.ifEmpty { "Exit code ${result.exitCode}" }
                                appendLog("  -> ❌ Thất bại: $name ($pkg): $err", LogTarget.DEBLOAT)
                                LimiAiService.recordLastError(
                                    commandName = "[AI Chat] Gỡ Bloatware: $name ($pkg)",
                                    commandText = cmd,
                                    exitCode = result.exitCode,
                                    stderr = result.stderr,
                                    stdout = result.stdout
                                )
                            }
                        }

                        mainHandler.post {
                            loadDebloatApps()
                            LimiAiService.clearPendingBloatwareList()
                            val resultSummary = StringBuilder()
                            resultSummary.append("✅ **Đã hoàn tất tiến trình gỡ bỏ Bloatware Rác Hệ Thống!**\n\n")
                            resultSummary.append("• **Thành công**: $successCount / ${selectedBloat.size} ứng dụng.\n")
                            if (failCount > 0) {
                                resultSummary.append("• **Thất bại hoặc đã gỡ từ trước**: $failCount ứng dụng.\n")
                            }
                            if (successNames.isNotEmpty()) {
                                resultSummary.append("\n📋 **Các ứng dụng đã loại bỏ**:\n")
                                successNames.forEach { n -> resultSummary.append("  - ✅ $n\n") }
                            }
                            resultSummary.append("\n🎉 Điện thoại của bạn đã được dọn sạch rác ngầm, giải phóng RAM và tối ưu hóa thời lượng Pin!")
                            resultSummary.append("\n\n*(Nếu sau này cần khôi phục lại bất kỳ app nào, bạn chỉ cần vào Tab **Debloat** -> chọn **Khôi phục ứng dụng đã xóa**)*")

                            streamTypewriterMessage(resultSummary.toString(), listOf(
                                ChatActionButton("🗑️ Mở Tab Debloat", R.drawable.ic_nav_trash, ChatActionType.OPEN_DEBLOAT_TAB)
                            ))
                        }
                    }
                }
                ChatActionType.OPEN_DEBLOAT_TAB -> {
                    dialog.dismiss()
                    selectTab(R.id.nav_debloat, animated = true)
                    Toast.makeText(this, "🗑️ Đã chuyển sang Tab Debloat (Gỡ Ứng Dụng Rác)", Toast.LENGTH_SHORT).show()
                }
                ChatActionType.OPEN_PERMISSIONS_TAB -> {
                    dialog.dismiss()
                    selectTab(R.id.nav_permissions, animated = true)
                    Toast.makeText(this, "🔑 Đã chuyển sang Tab Cấp Quyền Ứng Dụng", Toast.LENGTH_SHORT).show()
                }
                ChatActionType.CANCEL_PENDING_ACTION -> {
                    sendMessageText("Hủy bỏ tác vụ")
                }
            }
        }

        fun showFeedbackCorrectionDialog(targetQuestion: String, targetAiReply: String, itemPos: Int) {
            val feedbackDialogView = layoutInflater.inflate(R.layout.dialog_feedback_contribution, null)
            val feedbackDialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(feedbackDialogView)
                .setCancelable(true)
                .create()
            feedbackDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            feedbackDialog.window?.attributes?.windowAnimations = R.style.DialogPopAnimation

            val tvQuestion = feedbackDialogView.findViewById<TextView>(R.id.tvFeedbackQuestion)
            val etAnswer = feedbackDialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etFeedbackCorrectionAnswer)
            val btnClose = feedbackDialogView.findViewById<View>(R.id.btnCloseFeedbackDialog)
            val btnCancel = feedbackDialogView.findViewById<View>(R.id.btnCancelFeedback)
            val btnSubmit = feedbackDialogView.findViewById<View>(R.id.btnSubmitFeedbackCorrection)
            val layoutPickImage = feedbackDialogView.findViewById<View>(R.id.layoutPickFeedbackImage)
            val tvImageCount = feedbackDialogView.findViewById<TextView>(R.id.tvFeedbackImageCount)
            val scrollMultiImages = feedbackDialogView.findViewById<android.widget.HorizontalScrollView>(R.id.scrollFeedbackImages)
            val containerMultiImages = feedbackDialogView.findViewById<LinearLayout>(R.id.layoutFeedbackMultiImagesContainer)

            val contributionBitmaps = mutableListOf<android.graphics.Bitmap>()

            fun refreshImageThumbnails() {
                containerMultiImages?.removeAllViews()
                tvImageCount?.text = "${contributionBitmaps.size}/5"
                if (contributionBitmaps.isEmpty()) {
                    scrollMultiImages?.visibility = View.GONE
                    return
                }
                scrollMultiImages?.visibility = View.VISIBLE
                val density = resources.displayMetrics.density
                val thumbSize = (72 * density).toInt()
                val marginEnd = (8 * density).toInt()
                val btnSize = (22 * density).toInt()

                for ((index, bmp) in contributionBitmaps.withIndex()) {
                    val itemFrame = android.widget.FrameLayout(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                            this.marginEnd = marginEnd
                        }
                    }

                    val card = com.google.android.material.card.MaterialCardView(this@MainActivity).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        radius = 10 * density
                        strokeWidth = (1 * density).toInt()
                        strokeColor = Color.parseColor("#3338BDF8")
                        cardElevation = 0f
                        setCardBackgroundColor(Color.parseColor("#0F172A"))
                    }

                    val ivThumb = ImageView(this@MainActivity).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bmp)
                    }
                    card.addView(ivThumb)
                    itemFrame.addView(card)

                    // Nút xóa từng ảnh
                    val btnDel = ImageView(this@MainActivity).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(btnSize, btnSize).apply {
                            gravity = android.view.Gravity.TOP or android.view.Gravity.END
                            topMargin = (2 * density).toInt()
                            rightMargin = (2 * density).toInt()
                        }
                        setImageResource(R.drawable.ic_close_white)
                        val bg = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor("#E0EF4444"))
                        }
                        background = bg
                        setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                        setColorFilter(Color.WHITE)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            if (index in 0 until contributionBitmaps.size) {
                                contributionBitmaps.removeAt(index)
                                refreshImageThumbnails()
                            }
                        }
                    }
                    itemFrame.addView(btnDel)
                    containerMultiImages?.addView(itemFrame)
                }
            }

            layoutPickImage?.let { ViewAnimationExtensions.applySpringTouch(it) }
            layoutPickImage?.setOnClickListener {
                if (contributionBitmaps.size >= 5) {
                    Toast.makeText(this@MainActivity, "Đã chọn tối đa 5 ảnh minh họa!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                pendingImageCallback = { uri ->
                    try {
                        val bmp = LimiVisionEngine.decodeSampledBitmapFromUri(this@MainActivity, uri)
                        if (bmp != null) {
                            if (contributionBitmaps.size < 5) {
                                contributionBitmaps.add(bmp)
                                refreshImageThumbnails()
                            } else {
                                Toast.makeText(this@MainActivity, "Đã chọn tối đa 5 ảnh minh họa!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "Không thể nạp file ảnh này", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Throwable) {
                        Toast.makeText(this@MainActivity, "Lỗi khi nạp ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                pickImageLauncher.launch("image/*")
            }

            tvQuestion.text = targetQuestion.ifBlank { "Câu hỏi liên quan đến câu trả lời này" }
            btnClose?.setOnClickListener { feedbackDialog.dismiss() }
            btnCancel?.setOnClickListener { feedbackDialog.dismiss() }

            btnSubmit?.setOnClickListener {
                val answerText = etAnswer.text?.toString()?.trim() ?: ""
                if (answerText.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Vui lòng nhập câu trả lời chính xác!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val finalBitmaps = ArrayList(contributionBitmaps)
                val firstBmp = finalBitmaps.firstOrNull()
                feedbackDialog.dismiss()

                LimiKnowledgeBase.submitUserContribution(
                    context = this@MainActivity,
                    question = targetQuestion,
                    suggestedAnswer = answerText,
                    source = "in_chat_feedback_button",
                    imageBitmap = firstBmp,
                    imageBitmaps = finalBitmaps,
                    onComplete = { success, msg ->
                        mainHandler.post {
                            if (success) {
                                // Cập nhật trực tiếp lên tin nhắn hiện tại nếu có itemPos hợp lệ
                                if (itemPos in 0 until chatAdapter.itemCount) {
                                    val currentMsg = chatAdapter.getMessageAt(itemPos)
                                    if (currentMsg != null) {
                                        val updatedMsg = currentMsg.copy(
                                            text = answerText,
                                            imageBitmap = firstBmp ?: currentMsg.imageBitmap
                                        )
                                        chatAdapter.updateMessageAt(itemPos, updatedMsg)
                                    }
                                }
                            }
                        }
                    }
                )
            }

            feedbackDialog.show()
            feedbackDialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        chatAdapter.onFeedbackThumbUp = { msg, position ->
            val history = LimiAiService.getHistory()
            var relatedQuestion = "Nội dung câu trả lời của Limi AI"
            for (i in (position - 1) downTo 0) {
                if (i < history.size && history[i].sender == MessageSender.USER) {
                    relatedQuestion = history[i].text
                    break
                }
            }
            LimiKnowledgeBase.submitVote(this@MainActivity, relatedQuestion, msg.text, "like")
        }

        chatAdapter.onFeedbackThumbDown = { msg, position ->
            val history = LimiAiService.getHistory()
            var relatedQuestion = "Nội dung câu trả lời của Limi AI"
            for (i in (position - 1) downTo 0) {
                if (i < history.size && history[i].sender == MessageSender.USER) {
                    relatedQuestion = history[i].text
                    break
                }
            }
            LimiKnowledgeBase.submitVote(this@MainActivity, relatedQuestion, msg.text, "unlike")
        }

        chatAdapter.onFeedbackContribute = { msg, position ->
            val history = LimiAiService.getHistory()
            var relatedQuestion = "Nội dung câu trả lời của Limi AI"
            for (i in (position - 1) downTo 0) {
                if (i < history.size && history[i].sender == MessageSender.USER) {
                    relatedQuestion = history[i].text
                    break
                }
            }
            showFeedbackCorrectionDialog(relatedQuestion, msg.text, position)
        }

        btnSend?.setOnClickListener {
            if (isGenerating) {
                LimiAiService.cancelCurrentRequest()
                pendingOfflineRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingOfflineRunnable = null
                finalizeCurrentTyping()
                layoutTyping?.visibility = View.GONE
                setSendButtonState(false)
                Toast.makeText(this@MainActivity, "Đã dừng yêu cầu", Toast.LENGTH_SHORT).show()
            } else {
                val text = etInput?.text?.toString() ?: ""
                sendMessageText(text)
            }
        }

        etInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                if (!isGenerating) {
                    val text = etInput.text?.toString() ?: ""
                    sendMessageText(text)
                }
                true
            } else {
                false
            }
        }

        val btnTogglePromptChips = dialogView.findViewById<ImageView>(R.id.btnTogglePromptChips)
        val layoutPromptChipsRow = dialogView.findViewById<View>(R.id.layoutPromptChipsRow)

        btnTogglePromptChips?.let { btn ->
            ViewAnimationExtensions.applySpringTouch(btn)
            btn.setOnClickListener {
                val isVisible = layoutPromptChipsRow?.visibility == View.VISIBLE
                if (dialogView is ViewGroup) {
                    android.transition.TransitionManager.beginDelayedTransition(
                        dialogView,
                        android.transition.AutoTransition().apply {
                            duration = 200
                        }
                    )
                }
                if (isVisible) {
                    layoutPromptChipsRow?.visibility = View.GONE
                    btn.setImageResource(R.drawable.ic_arrow_up)
                } else {
                    layoutPromptChipsRow?.visibility = View.VISIBLE
                    btn.setImageResource(R.drawable.ic_arrow_down)
                }
                androidx.core.widget.ImageViewCompat.setImageTintList(
                    btn,
                    ContextCompat.getColorStateList(this@MainActivity, R.color.text_secondary)
                )
                ViewAnimationExtensions.animateMorphIcon(btn, if (isVisible) 180f else -180f)
            }
        }

        val chip1 = dialogView.findViewById<View>(R.id.chipAiPrompt1)
        val chip2 = dialogView.findViewById<View>(R.id.chipAiPrompt2)
        val chip3 = dialogView.findViewById<View>(R.id.chipAiPrompt3)
        val chip4 = dialogView.findViewById<View>(R.id.chipAiPrompt4)
        val chip5 = dialogView.findViewById<View>(R.id.chipAiPrompt5)

        listOf(chip1, chip2, chip3, chip4, chip5).forEach { chip ->
            chip?.let { ViewAnimationExtensions.applySpringTouch(it) }
        }

        chip1?.setOnClickListener { sendMessageText("Hướng dẫn fix trễ thông báo Zalo, Telegram và Facebook Messenger trên HyperOS/MIUI", isInstant = true) }
        chip2?.setOnClickListener { sendMessageText("Lệnh 4 Millet Whitelist là gì? Vì sao cần thêm app vào lệnh này?", isInstant = true) }
        chip3?.setOnClickListener { sendMessageText("Hướng dẫn các bước kích hoạt Shizuku qua Ghép nối Wi-Fi không dây từng bước một", isInstant = true) }
        chip4?.setOnClickListener { sendMessageText("Cách tối ưu hóa pin máy mát mà vẫn nhận thông báo tức thì trên Xiaomi", isInstant = true) }
        chip5?.setOnClickListener { sendMessageText("Những ứng dụng bloatware hệ thống nào của Xiaomi có thể gỡ an toàn trong mục Debloat?", isInstant = true) }

        btnClear?.setOnClickListener {
            btnClear?.let { ViewAnimationExtensions.animateMorphSpin(it) }
            finalizeCurrentTyping()
            LimiAiService.clearHistory()
            val welcomeMsg = LimiChatMessage(
                sender = MessageSender.LIMI,
                text = "Đã làm mới cuộc trò chuyện! Limi sẵn sàng hỗ trợ các câu hỏi mới của bạn về tối ưu hóa Xiaomi / HyperOS."
            )
            LimiAiService.addMessage(welcomeMsg)
            chatAdapter.setMessages(LimiAiService.getHistory())
            Toast.makeText(this, "Đã xóa lịch sử trò chuyện", Toast.LENGTH_SHORT).show()
        }

        btnClose?.setOnClickListener { dialog.dismiss() }

        btnKnowledge?.setOnClickListener {
            showKnowledgeBaseConfigDialog()
        }

        val density = resources.displayMetrics.density

        ViewCompat.setOnApplyWindowInsetsListener(dialogView) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            val topSafe = statusBars.top.coerceAtLeast((28 * density).toInt())
            val bottomSafe = if (ime.bottom > 0) {
                ime.bottom + (6 * density).toInt()
            } else {
                navBars.bottom + (6 * density).toInt()
            }

            layoutHeader?.setPadding(
                (16 * density).toInt(),
                topSafe,
                (16 * density).toInt(),
                (12 * density).toInt()
            )
            layoutInputContainer?.setPadding(
                (10 * density).toInt(),
                (6 * density).toInt(),
                (10 * density).toInt(),
                bottomSafe
            )
            insets
        }

        dialog.setOnDismissListener {
            try { bgAnimator?.cancel() } catch (_: Throwable) {}
            // Nếu có câu trả lời offline đang chờ hiển thị thì thực thi ngay và lưu vào lịch sử
            pendingOfflineRunnable?.let {
                mainHandler.removeCallbacks(it)
                it.run()
            }
            pendingOfflineRunnable = null

            // Nếu hiệu ứng gõ chữ đang chạy thì hoàn tất ngay để lưu trọn vẹn văn bản vào tin nhắn
            finalizeCurrentTyping()

            // Hủy đăng ký listener của dialog này để tránh rò rỉ view của dialog đã đóng
            LimiAiService.unregisterUiListeners()

            // LƯU Ý QUAN TRỌNG: KHÔNG gọi LimiAiService.cancelCurrentRequest() tại đây!
            // Khi người dùng đóng đoạn chat, Limi vẫn tiếp tục suy nghĩ và hoàn thành câu trả lời trong nền.
            // Câu trả lời sẽ được lưu trọn vẹn vào lịch sử trò chuyện.
            // Chỉ khi người dùng bấm nút DỪNG (icon stop) trong giao diện chat thì mới hủy yêu cầu.
            setSendButtonState(false)
        }

        dialog.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.decorView.fitsSystemWindows = false
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.decorView.setPadding(0, 0, 0, 0)
            window.attributes?.windowAnimations = R.style.DialogPopAnimation
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun showKnowledgeBaseConfigDialog() {
        if (isFinishing || isDestroyed) return
        val currentUrl = LimiKnowledgeBase.getKnowledgeBaseUrl(this)
        val customCount = LimiKnowledgeBase.getCustomKnowledgeCount()
        val learnedCount = LimiKnowledgeBase.getLocalLearnedCount()

        val input = EditText(this).apply {
            hint = "Dán link Google Apps Script Web App hoặc Sheet CSV..."
            setText(currentUrl)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            setBackgroundResource(R.drawable.bg_chat_input)
            setPadding(32, 28, 32, 28)
            textSize = 13.5f
            isSingleLine = false
            maxLines = 3
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 16)

            val tvInfo = TextView(this@MainActivity).apply {
                text = "📚 Kho Tri Thức Động (Google Drive / Sheets)\n" +
                        "• Mục tri thức chính thức sẵn có: $customCount mục\n" +
                        "• Câu hỏi AI đã tự học & ghi nhớ: $learnedCount câu\n\n" +
                        "💡 Dán URL Webhook của Google Apps Script để:\n" +
                        "1. App tự đồng bộ dữ liệu bạn nhập trên Drive về máy để trả lời tức thì (không tốn API Key).\n" +
                        "2. Khi gọi Gemini, AI sẽ tự động gửi câu hỏi & trả lời mới ghi vào Google Sheet trên Drive của bạn!"
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 13f
                setLineSpacing(4f, 1.15f)
            }
            addView(tvInfo)

            val space = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24)
            }
            addView(space)
            addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚙️ Cấu Hình Kho Tri Thức Drive / Sheet")
            .setView(container)
            .setPositiveButton("Lưu & Đồng bộ") { _, _ ->
                val newUrl = input.text.toString().trim()
                LimiKnowledgeBase.setKnowledgeBaseUrl(this, newUrl)
                Toast.makeText(this, "Đang đồng bộ với Google Drive...", Toast.LENGTH_SHORT).show()
                LimiKnowledgeBase.syncRemoteKnowledge(this) { success, count, msg ->
                    runOnUiThread {
                        Toast.makeText(this, msg, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNeutralButton("Mã Apps Script Mẫu") { _, _ ->
                showAppsScriptTemplateDialog()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showAppsScriptTemplateDialog() {
        val scriptCode = """
// ==========================================================================
// BỘ CÔNG CỤ GOOGLE APPS SCRIPT CHO TRỢ LÝ AI LIMI & FIX THÔNG BÁO XIAOMI
// ĐỒNG BỘ 100% TẤT CẢ CÁC TAB - TỰ ĐỘNG DUYỆT & TỐI ƯU TỐC ĐỘ PHẢN HỒI
// ==========================================================================

function doGet(e) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var allSheets = ss.getSheets();
  var items = [];

  for (var s = 0; s < allSheets.length; s++) {
    var sheet = allSheets[s];
    var sheetName = sheet.getName().trim();

    var data = sheet.getDataRange().getValues();
    if (data.length <= 1) continue;

    if (sheetName === "AI_HOC_TU_DONG") {
      for (var i = 1; i < data.length; i++) {
        var r = data[i];
        var q = String(r[1] || "").trim();
        var a = String(r[2] || "").trim();
        var status = String(r[5] || "Đã duyệt").trim();

        if (!q || !a || status === "Từ chối" || status === "Bị xóa") continue;

        var kws = [q.toLowerCase()];
        items.push({
          title: "📌 " + q,
          keywords: kws,
          content: a,
          actionButtonTitle: "✨ Limi Tìm Thêm",
          actionType: "SUMMARIZE_WITH_AI",
          payload: q,
          likes: 1,
          unlikes: 0
        });
      }
      continue;
    }

    for (var i = 1; i < data.length; i++) {
      var row = data[i];
      var rawKeywords = String(row[0] || "").trim();
      var title = String(row[1] || "").trim();
      var content = String(row[2] || "").trim();
      var btnTitle = String(row[3] || "").trim();
      var actionType = String(row[4] || "").trim();
      var likes = parseInt(row[5]) || 0;
      var unlikes = parseInt(row[6]) || 0;

      if (!rawKeywords && !title && !content) continue;

      var kws = [];
      if (rawKeywords) {
        var parts = rawKeywords.split(/[;,]/);
        for (var k = 0; k < parts.length; k++) {
          var cleanKw = parts[k].trim().toLowerCase();
          if (cleanKw && kws.indexOf(cleanKw) === -1) {
            kws.push(cleanKw);
          }
        }
      }

      var cleanTitle = title.replace(/^[^\w\s\d]+/, "").trim().toLowerCase();
      if (cleanTitle && kws.indexOf(cleanTitle) === -1) {
        kws.push(cleanTitle);
      }

      var itemObj = {
        keywords: kws,
        title: title || ("📌 " + kws[0]),
        content: content,
        likes: likes,
        unlikes: unlikes
      };

      if (btnTitle) itemObj.actionButtonTitle = btnTitle;
      if (actionType) itemObj.actionType = actionType;

      items.push(itemObj);
    }
  }

  return ContentService.createTextOutput(JSON.stringify(items))
    .setMimeType(ContentService.MimeType.JSON);
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(10000);
    var postData = JSON.parse(e.postData.contents);
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheetByName("AI_HOC_TU_DONG");
    if (!sheet) {
      sheet = ss.insertSheet("AI_HOC_TU_DONG");
      sheet.appendRow(["Thời Gian", "Câu Hỏi Của Người Dùng", "Câu Trả Lời Của Gemini", "Thiết Bị", "Hệ Điều Hành", "Trạng Thái", "Ảnh Google Drive"]);
    }
    
    var q = String(postData.question || "").trim();
    var a = String(postData.answer || "").trim();
    if (!q || !a) {
      return ContentService.createTextOutput(JSON.stringify({ status: "ignored_empty" }))
        .setMimeType(ContentService.MimeType.JSON);
    }

    var driveImageUrl = "";
    if (postData.image_base64) {
      try {
        var folderName = "Limi_Images";
        var folders = DriveApp.getFoldersByName(folderName);
        var folder = folders.hasNext() ? folders.next() : DriveApp.createFolder(folderName);
        
        var decoded = Utilities.base64Decode(postData.image_base64);
        var sanitizedTitle = (postData.image_title || q || "limi_image").replace(/[^a-zA-Z0-9_\-\s]/g, "_").substring(0, 40);
        var fileName = sanitizedTitle + "_" + new Date().getTime() + ".jpg";
        var blob = Utilities.newBlob(decoded, "image/jpeg", fileName);
        var file = folder.createFile(blob);
        file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
        driveImageUrl = file.getUrl();
      } catch (imgErr) {
        driveImageUrl = "Lỗi upload: " + imgErr.toString();
      }
    }

    var data = sheet.getDataRange().getValues();
    var existingRow = -1;
    for (var i = 1; i < data.length; i++) {
      if (String(data[i][1] || "").trim().toLowerCase() === q.toLowerCase()) {
        existingRow = i + 1;
        break;
      }
    }

    if (existingRow > 0) {
      sheet.getRange(existingRow, 1).setValue(new Date().toLocaleString("vi-VN"));
      sheet.getRange(existingRow, 3).setValue(a);
      sheet.getRange(existingRow, 4).setValue(postData.device || "");
      sheet.getRange(existingRow, 5).setValue(postData.os || "");
      sheet.getRange(existingRow, 6).setValue("Đã duyệt");
      if (driveImageUrl) {
        sheet.getRange(existingRow, 7).setValue(driveImageUrl);
      }
    } else {
      sheet.appendRow([
        new Date().toLocaleString("vi-VN"),
        q,
        a,
        postData.device || "",
        postData.os || "",
        "Đã duyệt",
        driveImageUrl
      ]);
    }
    
    return ContentService.createTextOutput(JSON.stringify({ status: "success", autoApproved: true, imageUrl: driveImageUrl }))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ status: "error", message: err.toString() }))
      .setMimeType(ContentService.MimeType.JSON);
  } finally {
    lock.releaseLock();
  }
}
""".trimIndent()

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("LimiAppsScript", scriptCode)
        cm.setPrimaryClip(clip)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📋 Đã Sao Chép Mã Google Apps Script Mới!")
            .setMessage("Mã nguồn đồng bộ đa danh mục (Điện thoại, Xe ô tô SU7, HyperOS, Smarthome) đã được sao chép vào bộ nhớ tạm!\n\n" +
                    "📌 Cách cập nhật trong Google Sheet:\n" +
                    "1. Vào Tiện ích mở rộng (Extensions) -> Apps Script.\n" +
                    "2. Xóa code cũ, dán đoạn mã mới này vào rồi bấm Lưu (Ctrl + S).\n" +
                    "3. Bấm Triển khai (Deploy) -> Quản lý các bản triển khai (Manage deployments) -> Bấm biểu tượng cây bút (Chỉnh sửa) -> Chọn Phiên bản: 'Phiên bản mới' (New version) -> Bấm Triển khai.\n" +
                    "Toàn bộ 39+ thiết bị trên 4 tab sẽ được tự động đồng bộ ngay lập tức!")
            .setPositiveButton("Đã hiểu", null)
            .show()
    }

    private fun showSettingSplashDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings_splash, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.windowAnimations = R.style.DialogPopAnimation

        val btnClose = dialogView.findViewById<View>(R.id.btnDialogSplashClose)
        val switchEnable = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchDialogEnableSplash)
        val switchDynamicBg = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchDialogEnableDynamicBg)
        val btnPreview = dialogView.findViewById<View>(R.id.btnDialogPreviewSplash)
        val btnImport = dialogView.findViewById<View>(R.id.btnDialogImportCustomVideo)
        val btnReset = dialogView.findViewById<TextView>(R.id.btnDialogResetSplashVideo)

        btnClose?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnPreview?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnImport?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnReset?.let { ViewAnimationExtensions.applySpringTouch(it) }

        val prefs = getSharedPreferences(SplashActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val isSplashEnabled = prefs.getBoolean(SplashActivity.KEY_ENABLE_SPLASH, true)
        val useCustomSplash = prefs.getBoolean(SplashActivity.KEY_USE_CUSTOM_SPLASH, false)
        val customFile = File(filesDir, "custom_splash.mp4")

        switchEnable?.isChecked = isSplashEnabled
        switchEnable?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(SplashActivity.KEY_ENABLE_SPLASH, isChecked).apply()
            val msg = if (isChecked) "Đã BẬT hoạt ảnh mở app (10s)" else "Đã TẮT hoạt ảnh mở app (vào thẳng app)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        switchDynamicBg?.isChecked = ThemeUtils.isDynamicBackgroundEnabled(this)
        switchDynamicBg?.setOnCheckedChangeListener { _, isChecked ->
            ThemeUtils.setDynamicBackgroundEnabled(this, isChecked)
            auroraBgAnimator = ThemeUtils.applyBackground(binding.rootView, existingAnimator = auroraBgAnimator)
            val msg = if (isChecked) "Đã BẬT hiệu ứng Dynamic màu nền Aurora" else "Đã TẮT hiệu ứng Dynamic màu nền (dùng màu nền gốc)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnReset?.visibility = if (useCustomSplash && customFile.exists()) View.VISIBLE else View.GONE

        btnPreview?.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, SplashActivity::class.java).apply {
                putExtra(SplashActivity.EXTRA_IS_PREVIEW, true)
            }
            startActivity(intent)
        }

        btnImport?.setOnClickListener {
            dialog.dismiss()
            pickVideoLauncher.launch("video/*")
        }

        btnReset?.setOnClickListener {
            if (customFile.exists()) {
                customFile.delete()
            }
            prefs.edit().putBoolean(SplashActivity.KEY_USE_CUSTOM_SPLASH, false).apply()
            btnReset.visibility = View.GONE
            Toast.makeText(this, "Đã khôi phục video mặc định (khoidong.mp4)", Toast.LENGTH_SHORT).show()
        }

        btnClose?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSettingShortcutsDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings_shortcuts, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.windowAnimations = R.style.DialogPopAnimation

        val btnClose = dialogView.findViewById<View>(R.id.btnDialogShortcutsClose)
        val btnDev = dialogView.findViewById<View>(R.id.btnDialogDevOptions)
        val btnBattery = dialogView.findViewById<View>(R.id.btnDialogBatteryOpt)
        val btnShizuku = dialogView.findViewById<View>(R.id.btnDialogLaunchShizuku)

        btnClose?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnDev?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnBattery?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnShizuku?.let { ViewAnimationExtensions.applySpringTouch(it) }

        btnDev?.setOnClickListener {
            dialog.dismiss()
            openDeveloperOptionsOrDeviceInfo()
        }

        btnBattery?.setOnClickListener {
            dialog.dismiss()
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Throwable) {
                Toast.makeText(this, "Không thể mở cài đặt pin", Toast.LENGTH_SHORT).show()
            }
        }

        btnShizuku?.setOnClickListener {
            dialog.dismiss()
            val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Chưa cài đặt app Shizuku trên máy", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSettingResetDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings_reset, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.windowAnimations = R.style.DialogPopAnimation

        val btnClose = dialogView.findViewById<View>(R.id.btnDialogResetClose)
        val btnResetShizuku = dialogView.findViewById<View>(R.id.btnDialogResetAllShizuku)
        val btnCopyReset = dialogView.findViewById<View>(R.id.btnDialogCopyResetCommands)

        btnClose?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnResetShizuku?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnCopyReset?.let { ViewAnimationExtensions.applySpringTouch(it) }

        btnResetShizuku?.setOnClickListener {
            dialog.dismiss()
            showResetCommandsConfirmationDialog()
        }

        btnCopyReset?.setOnClickListener {
            copyToClipboard("11 Lệnh Hoàn Nguyên Mặc Định", FixCommands.ALL_RESET_COMMANDS_TEXT)
        }

        btnClose?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSettingDeveloperDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings_developer, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.windowAnimations = R.style.DialogPopAnimation

        val btnClose = dialogView.findViewById<View>(R.id.btnDialogDevClose)
        val btnDonate = dialogView.findViewById<View>(R.id.btnDialogDonateDeveloper)
        val btnZalo = dialogView.findViewById<View>(R.id.btnDialogJoinZaloGroup)
        val btnGmail = dialogView.findViewById<View>(R.id.btnDialogContactGmail)

        btnClose?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnDonate?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnZalo?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnGmail?.let { ViewAnimationExtensions.applySpringTouch(it) }

        btnDonate?.setOnClickListener {
            dialog.dismiss()
            showDonateDeveloperDialog()
        }

        btnZalo?.setOnClickListener {
            dialog.dismiss()
            try {
                val zaloIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://zalo.me/g/kgjjkz596"))
                startActivity(zaloIntent)
            } catch (e: Throwable) {
                Toast.makeText(this, "Không thể mở liên kết Zalo", Toast.LENGTH_SHORT).show()
            }
        }

        btnGmail?.setOnClickListener {
            dialog.dismiss()
            val email = "duyih122@gmail.com"
            try {
                val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:$email")
                    putExtra(Intent.EXTRA_SUBJECT, "[Fix Thông Báo Xiaomi] Phản hồi & Góp ý")
                }
                startActivity(emailIntent)
            } catch (e: Throwable) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Email", email))
                Toast.makeText(this, "Đã sao chép Email: $email", Toast.LENGTH_LONG).show()
            }
        }

        btnClose?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showDonateDeveloperDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_donate_developer, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.windowAnimations = R.style.DialogPopAnimation

        val btnClose = dialogView.findViewById<View>(R.id.btnDialogDonateClose)
        val btnCopyBank = dialogView.findViewById<View>(R.id.btnCopyBankNumber)

        btnClose?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnCopyBank?.let { ViewAnimationExtensions.applySpringTouch(it) }

        btnCopyBank?.setOnClickListener {
            val stk = "2601647122"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("STK BIDV", stk))
            Toast.makeText(this, "Đã sao chép STK BIDV: $stk (NGUYEN TAN DUNG)", Toast.LENGTH_LONG).show()
        }

        btnClose?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun importCustomVideo(uri: Uri) {
        try {
            val destFile = File(filesDir, "custom_splash.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val prefs = getSharedPreferences(SplashActivity.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(SplashActivity.KEY_USE_CUSTOM_SPLASH, true).apply()
            Toast.makeText(this, "Đã nhập video khởi động mới thành công (tối đa 10s)!", Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Không thể nhập video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showThemeSelectorPopup(anchor: View) {
        if (activeThemePopupWindow?.isShowing == true) {
            val pView = activeThemePopupView
            val pWindow = activeThemePopupWindow
            if (pView != null && pWindow != null) {
                ViewAnimationExtensions.dismissPopup(pView, pWindow, anchor) {
                    activeThemePopupWindow = null
                    activeThemePopupView = null
                }
            }
            return
        }

        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_theme_selector, null)
        val popupWidth = (200 * resources.displayMetrics.density).toInt()
        val popupWindow = PopupWindow(
            popupView,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = 18f * resources.displayMetrics.density
        popupWindow.isOutsideTouchable = true

        activeThemePopupWindow = popupWindow
        activeThemePopupView = popupView

        popupWindow.setOnDismissListener {
            activeThemePopupWindow = null
            activeThemePopupView = null
        }

        popupWindow.setTouchInterceptor { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchor) {
                    activeThemePopupWindow = null
                    activeThemePopupView = null
                }
                true
            } else {
                false
            }
        }

        val itemSystem = popupView.findViewById<View>(R.id.itemThemeSystem)
        val itemDark = popupView.findViewById<View>(R.id.itemThemeDark)
        val itemLight = popupView.findViewById<View>(R.id.itemThemeLight)

        val tvSystem = popupView.findViewById<TextView>(R.id.tvThemeSystem)
        val tvDark = popupView.findViewById<TextView>(R.id.tvThemeDark)
        val tvLight = popupView.findViewById<TextView>(R.id.tvThemeLight)

        val ivIconSystem = popupView.findViewById<ImageView>(R.id.ivIconSystem)
        val ivIconDark = popupView.findViewById<ImageView>(R.id.ivIconDark)
        val ivIconLight = popupView.findViewById<ImageView>(R.id.ivIconLight)

        val ivCheckSystem = popupView.findViewById<ImageView>(R.id.ivCheckSystem)
        val ivCheckDark = popupView.findViewById<ImageView>(R.id.ivCheckDark)
        val ivCheckLight = popupView.findViewById<ImageView>(R.id.ivCheckLight)

        val currentTheme = ThemeUtils.getSavedTheme(this)
        val activeBg = R.drawable.bg_theme_item_active
        val inactiveBg = R.drawable.bg_theme_item_inactive
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val normalTextColor = ContextCompat.getColor(this, R.color.text_primary)

        val isSystem = currentTheme == ThemeUtils.THEME_SYSTEM
        itemSystem.setBackgroundResource(if (isSystem) activeBg else inactiveBg)
        tvSystem.setTextColor(if (isSystem) primaryColor else normalTextColor)
        ivIconSystem?.setColorFilter(if (isSystem) primaryColor else normalTextColor)
        ivCheckSystem?.let { it.visibility = if (isSystem) View.VISIBLE else View.GONE }

        val isDark = currentTheme == ThemeUtils.THEME_DARK
        itemDark.setBackgroundResource(if (isDark) activeBg else inactiveBg)
        tvDark.setTextColor(if (isDark) primaryColor else normalTextColor)
        ivIconDark?.setColorFilter(if (isDark) primaryColor else normalTextColor)
        ivCheckDark?.let { it.visibility = if (isDark) View.VISIBLE else View.GONE }

        val isLight = currentTheme == ThemeUtils.THEME_LIGHT
        itemLight.setBackgroundResource(if (isLight) activeBg else inactiveBg)
        tvLight.setTextColor(if (isLight) primaryColor else normalTextColor)
        ivIconLight?.setColorFilter(if (isLight) primaryColor else normalTextColor)
        ivCheckLight?.let { it.visibility = if (isLight) View.VISIBLE else View.GONE }

        updateThemesButtonIcon()

        itemSystem.setOnClickListener {
            ThemeUtils.setThemeMode(this, ThemeUtils.THEME_SYSTEM)
            updateThemesButtonIcon(animate = true)
            Toast.makeText(this, "Đã chọn: Theo hệ thống (Mặc định)", Toast.LENGTH_SHORT).show()
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchor)
        }

        itemDark.setOnClickListener {
            ThemeUtils.setThemeMode(this, ThemeUtils.THEME_DARK)
            updateThemesButtonIcon(animate = true)
            Toast.makeText(this, "Đã chọn: Dark Themes", Toast.LENGTH_SHORT).show()
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchor)
        }

        itemLight.setOnClickListener {
            ThemeUtils.setThemeMode(this, ThemeUtils.THEME_LIGHT)
            updateThemesButtonIcon(animate = true)
            Toast.makeText(this, "Đã chọn: White Themes", Toast.LENGTH_SHORT).show()
            ViewAnimationExtensions.dismissPopup(popupView, popupWindow, anchor)
        }

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val anchorX = location[0]
        val anchorY = location[1]

        val screenWidth = resources.displayMetrics.widthPixels
        val marginEnd = (14 * resources.displayMetrics.density).toInt()

        val targetX = (anchorX + anchor.width - popupWidth).coerceIn(marginEnd, (screenWidth - popupWidth - marginEnd).coerceAtLeast(marginEnd))
        val targetY = anchorY + anchor.height + (8 * resources.displayMetrics.density).toInt()

        popupWindow.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, targetX, targetY)
        ViewAnimationExtensions.revealPopupFromAnchor(popupView, anchor)
    }

    private fun updateThemesButtonIcon(animate: Boolean = false) {
        val currentTheme = ThemeUtils.getSavedTheme(this)
        val iconRes = when (currentTheme) {
            ThemeUtils.THEME_LIGHT -> R.drawable.ic_theme_light
            ThemeUtils.THEME_SYSTEM -> R.drawable.ic_theme_system
            else -> R.drawable.ic_theme_dark
        }

        if (animate) {
            // Spin Theme Toggle Transition chuẩn toggles.dev/toggles/spin
            binding.btnThemes.animate()
                .rotationBy(180f)
                .scaleX(0.72f)
                .scaleY(0.72f)
                .alpha(0.6f)
                .setDuration(180)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    binding.btnThemes.setIconResource(iconRes)
                    binding.btnThemes.animate()
                        .rotationBy(180f)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(1.0f)
                        .setDuration(220)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                        .start()
                }
                .start()
        } else {
            binding.btnThemes.setIconResource(iconRes)
        }
        binding.btnThemes.text = ""
    }

    private fun registerShizukuListeners() {
        try {
            Shizuku.addRequestPermissionResultListener(requestPermissionListener)
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun unregisterShizukuListeners() {
        try {
            Shizuku.removeRequestPermissionResultListener(requestPermissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun updateShizukuStatus() {
        mainHandler.post {
            val isAvailable = ShizukuUtils.isShizukuAvailable()
            val hasPermission = ShizukuUtils.hasShizukuPermission()

            if (!isAvailable) {
                binding.cardShizukuStatus.setCardBackgroundColor(getColor(R.color.glass_shizuku_alert_bg))
                binding.cardShizukuStatus.strokeColor = getColor(R.color.glass_shizuku_alert_stroke)
                binding.tvShizukuStatus.text = getString(R.string.shizuku_not_running)
                binding.tvShizukuStatus.setTextColor(getColor(R.color.accent_red))
                binding.imgShizukuStatus.setImageResource(R.drawable.ic_shizuku_error)
                binding.imgShizukuStatus.clearColorFilter()
                binding.btnRequestShizuku.visibility = View.GONE
                setButtonsEnabled(true)
            } else if (!hasPermission) {
                binding.cardShizukuStatus.setCardBackgroundColor(getColor(R.color.glass_shizuku_warn_bg))
                binding.cardShizukuStatus.strokeColor = getColor(R.color.glass_shizuku_warn_stroke)
                binding.tvShizukuStatus.text = getString(R.string.shizuku_permission_denied)
                binding.tvShizukuStatus.setTextColor(getColor(R.color.accent_orange))
                binding.imgShizukuStatus.setImageResource(R.drawable.ic_shizuku_warning)
                binding.imgShizukuStatus.clearColorFilter()
                binding.btnRequestShizuku.visibility = View.VISIBLE
                setButtonsEnabled(true)
            } else {
                binding.cardShizukuStatus.setCardBackgroundColor(getColor(R.color.glass_shizuku_ok_bg))
                binding.cardShizukuStatus.strokeColor = getColor(R.color.glass_shizuku_ok_stroke)
                binding.tvShizukuStatus.text = getString(R.string.shizuku_permission_granted)
                binding.tvShizukuStatus.setTextColor(getColor(R.color.accent_green))
                binding.imgShizukuStatus.setImageResource(R.drawable.ic_shizuku_success)
                binding.imgShizukuStatus.clearColorFilter()
                binding.btnRequestShizuku.visibility = View.GONE
                setButtonsEnabled(true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnRunAll.isEnabled = enabled
        binding.btnGrantAllApps.isEnabled = enabled
        binding.btnRunCmd1.isEnabled = enabled
        binding.btnRunCmd2.isEnabled = enabled
        binding.btnRunCmd3.isEnabled = enabled
        binding.btnRunCmd4.isEnabled = enabled
        binding.btnAddBankPackages.isEnabled = enabled
        binding.btnResetMilletPackages.isEnabled = enabled
        binding.btnRunCustomCommand.isEnabled = enabled
    }

    enum class LogTarget {
        FIX,
        PERMS,
        DEBLOAT,
        CUSTOM,
        CURRENT
    }

    private fun openDeveloperOptionsOrDeviceInfo() {
        val isDevEnabled = try {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Throwable) {
            false
        }
        if (isDevEnabled) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                Toast.makeText(this, "🛠️ Đang mở Tùy chọn nhà phát triển...", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                try {
                    startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                    Toast.makeText(this, "📱 Mở Thông tin thiết bị", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                    Toast.makeText(this, "Không thể mở Cài đặt", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            try {
                startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                Toast.makeText(this, "📱 Chạm 7 lần vào 'Phiên bản OS' để bật Tùy chọn nhà phát triển (Dev)", Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                Toast.makeText(this, "Không thể mở Thông tin thiết bị", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showShizukuRequiredDialog(actionName: String = "thực hiện thao tác này") {
        if (isFinishing || isDestroyed) return
        val isDevEnabled = try {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Throwable) {
            false
        }
        val isAdbEnabled = try {
            Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Throwable) {
            false
        }
        val isWirelessAdbEnabled = try {
            Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: Throwable) {
            false
        }
        val isDebuggingEnabled = isAdbEnabled || isWirelessAdbEnabled
        val isShizukuInstalled = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") != null

        val message: String
        val positiveButtonText: String
        val positiveAction: () -> Unit

        if (!isDevEnabled) {
            message = "Thiết bị chưa bật Tùy chọn nhà phát triển (Developer Options).\n\n👉 Vui lòng chạm vào nút bên dưới để mở Thông tin thiết bị, sau đó chạm liên tục 7 lần vào 'Phiên bản OS / MIUI' để mở khóa Dev Mode trước khi cấu hình Shizuku."
            positiveButtonText = "Mở Thông tin thiết bị (Bật Dev)"
            positiveAction = {
                try {
                    startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                    Toast.makeText(this, "📱 Chạm liên tục 7 lần vào 'Phiên bản OS / MIUI' để bật Tùy chọn nhà phát triển", Toast.LENGTH_LONG).show()
                } catch (e: Throwable) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (_: Throwable) {
                        Toast.makeText(this, "Không thể mở Cài đặt thiết bị", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (!isDebuggingEnabled) {
            message = "Tùy chọn nhà phát triển đã được bật, nhưng bạn chưa kích hoạt **Gỡ lỗi USB (USB Debugging)** hoặc **Gỡ lỗi không dây (Wireless Debugging)**.\n\n👉 Vui lòng chạm vào nút bên dưới để mở thẳng trang Tùy chọn nhà phát triển và bật Gỡ lỗi USB / Gỡ lỗi không dây trước khi khởi chạy Shizuku."
            positiveButtonText = "Mở Tùy chọn nhà phát triển"
            positiveAction = {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    Toast.makeText(this, "🛠️ Vui lòng bật 'Gỡ lỗi USB' hoặc 'Gỡ lỗi không dây' trong Tùy chọn nhà phát triển", Toast.LENGTH_LONG).show()
                } catch (e: Throwable) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (_: Throwable) {
                        Toast.makeText(this, "Không thể mở Cài đặt thiết bị", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (isShizukuInstalled) {
            if (ShizukuUtils.isShizukuAvailable()) {
                message = "Dịch vụ Shizuku đang chạy nhưng Bộ công cụ LIMI chưa được cấp quyền để $actionName.\n\n👉 Vui lòng chạm vào nút bên dưới để cấp quyền thực thi lệnh ADB Shell cho ứng dụng."
                positiveButtonText = "Cấp quyền Shizuku"
                positiveAction = {
                    ShizukuUtils.requestShizukuPermission()
                }
            } else {
                message = "Dịch vụ Shizuku hiện chưa được cấp quyền hoặc chưa chạy.\n\n👉 Vui lòng mở ứng dụng Shizuku, khởi chạy dịch vụ (qua Ghép nối Wi-Fi Debugging hoặc Root) và cấp quyền cho Bộ công cụ LIMI để $actionName."
                positiveButtonText = "Mở ứng dụng Shizuku"
                positiveAction = {
                    val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                        Toast.makeText(this, "🚀 Đang mở Shizuku. Vui lòng bấm 'Bắt đầu' hoặc Ghép nối Wi-Fi", Toast.LENGTH_SHORT).show()
                    } else {
                        showGuideWarningDialog(forceShow = true)
                    }
                }
            }
        } else {
            message = "Ứng dụng Shizuku chưa được cài đặt trên thiết bị của bạn.\n\n👉 Để chạy lệnh hệ thống tự động không cần máy tính, bạn cần cài đặt và khởi chạy ứng dụng Shizuku."
            positiveButtonText = "Xem Hướng dẫn Cài đặt"
            positiveAction = {
                showGuideWarningDialog(forceShow = true)
            }
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_shizuku_required, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.windowAnimations = R.style.DialogPopAnimation

        val tvMessage = dialogView.findViewById<TextView>(R.id.tvShizukuDialogMessage)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btnCloseShizukuDialog)
        val btnPositive = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPositiveShizukuDialog)
        val btnNegative = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNegativeShizukuDialog)

        tvMessage.text = message
        btnPositive.text = positiveButtonText

        listOf(btnPositive, btnNegative, btnClose).forEach {
            it?.let { v -> ViewAnimationExtensions.applySpringTouch(v) }
        }

        btnPositive.setOnClickListener {
            dialog.dismiss()
            positiveAction()
        }

        btnNegative.setOnClickListener { dialog.dismiss() }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun executeCustomCommand() {
        val cmd = binding.etCustomCommand.text?.toString()?.trim() ?: ""
        if (cmd.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập câu lệnh!", Toast.LENGTH_SHORT).show()
            return
        }

        // Ẩn bàn phím ảo
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etCustomCommand.windowToken, 0)

        runSingleCommand(0, "Lệnh Tùy Chỉnh", cmd, target = LogTarget.CUSTOM)
    }

    private fun runGrantAllApps() {
        if (!ShizukuUtils.hasShizukuPermission()) {
            appendLog("[Lỗi] Chưa được cấp quyền Shizuku.", LogTarget.PERMS)
            Toast.makeText(this, "⚠️ Bạn chưa cấp quyền Shizuku để cấp quyền tự động cho các ứng dụng!", Toast.LENGTH_LONG).show()
            showShizukuRequiredDialog("Cấp quyền ứng dụng")
            return
        }

        val packages = appListAdapter?.getSelectedPackages()
            ?: FixCommands.parsePackages(binding.etMilletPackages.text.toString())

        if (packages.isEmpty()) {
            appendLog("[Lỗi] Bạn chưa chọn ứng dụng nào để cấp quyền.", LogTarget.PERMS)
            Toast.makeText(this, "Bạn chưa chọn ứng dụng nào!", Toast.LENGTH_SHORT).show()
            return
        }

        val enableLockscreen = true
        val enablePopup = true
        val enableBackgroundWindow = true
        val enableShortcuts = true
        val enableAutostart = true
        val enableBattery = true
        val enableNotification = true

        setButtonsEnabled(false)
        appendLog("\n========================================", LogTarget.PERMS)
        appendLog("[Bắt đầu] Cấp quyền Màn hình khóa, Pop-up & Nền cho ${packages.size} app...", LogTarget.PERMS)
        appendLog("Cấu hình: Khóa=$enableLockscreen | Pop-up=$enablePopup | Cửa sổ nền=$enableBackgroundWindow | Phím tắt=$enableShortcuts | Autostart=$enableAutostart | Pin=$enableBattery", LogTarget.PERMS)
        appendLog("========================================", LogTarget.PERMS)

        Toast.makeText(this, "Đang cấp quyền cho ${packages.size} ứng dụng...", Toast.LENGTH_SHORT).show()

        executor.execute {
            try {
                // Tự động nạp danh sách app này vào Millet Whitelist của Xiaomi để không bao giờ bị đóng băng ngầm
                val milletCmd = FixCommands.getCmd4(packages.joinToString(", "))
                appendLog("\n[Millet Whitelist] Áp dụng cơ chế chống đóng băng Xiaomi:", LogTarget.PERMS)
                appendLog("$ $milletCmd", LogTarget.PERMS)
                ShizukuUtils.execShizukuCommand(milletCmd)

                val totalApps = packages.size
                for ((idx, pkg) in packages.withIndex()) {
                    appendLog("\n[${idx + 1}/$totalApps] Đang cấu hình quyền: $pkg", LogTarget.PERMS)

                    val cmds = FixCommands.getFullAppPermCommands(
                        pkg = pkg,
                        enableNotification = enableNotification,
                        enableLockscreen = enableLockscreen,
                        enablePopup = enablePopup,
                        enableBackgroundWindow = enableBackgroundWindow,
                        enableShortcuts = enableShortcuts,
                        enableAutostart = enableAutostart,
                        enableBatteryWhitelist = enableBattery
                    )

                    for (cmd in cmds) {
                        ShizukuUtils.execShizukuCommand(cmd)
                    }

                    appendLog("  -> Hoàn tất bật quyền cho: $pkg", LogTarget.PERMS)
                    Thread.sleep(40)
                }

                mainHandler.post {
                    appendLog("\n========================================", LogTarget.PERMS)
                    appendLog("[Hoàn tất] Đã bật toàn bộ quyền màn hình khóa, pop-up và chạy nền!", LogTarget.PERMS)
                    appendLog("[Mẹo Xiaomi] Bạn có thể bấm 2 nút phím tắt bên trên để mở nhanh giao diện Cài đặt Xiaomi nếu cần.", LogTarget.PERMS)
                    appendLog("========================================", LogTarget.PERMS)
                    setButtonsEnabled(true)
                    Toast.makeText(this@MainActivity, "✅ Đã cấp xong quyền cho ${packages.size} ứng dụng!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    appendLog("[Lỗi ngoại lệ] ${e.message}", LogTarget.PERMS)
                    setButtonsEnabled(true)
                    Toast.makeText(this@MainActivity, "Lỗi khi cấp quyền: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun runSingleCommand(
        cmdIndex: Int,
        title: String,
        commandText: String,
        target: LogTarget = if (cmdIndex == 0) LogTarget.CUSTOM else LogTarget.FIX
    ) {
        val displayName = if (cmdIndex == 0) title else "Lệnh $cmdIndex ($title)"

        if (!ShizukuUtils.hasShizukuPermission()) {
            appendLog("[Lỗi] Chưa được cấp quyền Shizuku.", target)
            Toast.makeText(this, "⚠️ Bạn chưa cấp quyền Shizuku để thực thi $displayName!", Toast.LENGTH_LONG).show()
            showShizukuRequiredDialog(displayName)
            return
        }

        setButtonsEnabled(false)
        appendLog("\n----------------------------------------", target)
        appendLog("[Bắt đầu] Đang thực thi $displayName...", target)
        appendLog("$ $commandText", target)

        executor.execute {
            try {
                val result = ShizukuUtils.execShizukuCommand(commandText)

                mainHandler.post {
                    if (result.exitCode == 0) {
                        if (commandText.contains("max_phantom_processes")) {
                            getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                .edit().putBoolean("phantom_killer_fixed", true).apply()
                        }
                        appendLog("[Thành công] $displayName thực thi thành công! (Exit Code: 0)", target)
                        if (result.stdout.isNotEmpty()) {
                            appendLog("Output:\n${result.stdout}", target)
                        }
                        Toast.makeText(this@MainActivity, "✅ $displayName: Thành công!", Toast.LENGTH_SHORT).show()
                    } else {
                        LimiAiService.recordLastError(displayName, commandText, result.exitCode, result.stderr, result.stdout)
                        appendLog("[Thất bại] $displayName thất bại! (Exit Code: ${result.exitCode})", target)
                        if (result.stderr.isNotEmpty()) {
                            appendLog("Error:\n${result.stderr}", target)
                        }
                        if (result.stdout.isNotEmpty()) {
                            appendLog("Output:\n${result.stdout}", target)
                        }
                        Toast.makeText(this@MainActivity, "❌ $displayName: Thất bại (Code ${result.exitCode})", Toast.LENGTH_SHORT).show()
                    }
                    setButtonsEnabled(true)
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    appendLog("[Lỗi ngoại lệ] ${e.localizedMessage ?: "Lỗi không xác định"}", target)
                    setButtonsEnabled(true)
                    Toast.makeText(this@MainActivity, "Lỗi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun runAllCommands() {
        if (!ShizukuUtils.hasShizukuPermission()) {
            appendLog("[Lỗi] Chưa được cấp quyền Shizuku.", LogTarget.FIX)
            Toast.makeText(this, "⚠️ Bạn chưa cấp quyền Shizuku để chạy lệnh!", Toast.LENGTH_LONG).show()
            showShizukuRequiredDialog("Chạy tất cả Lệnh 1 -> 4")
            return
        }

        val packagesString = binding.etMilletPackages.text.toString()
        val cmd4Text = FixCommands.getCmd4(packagesString)

        val commands = listOf(
            Triple(1, "Fix FCM & Heartbeat", FixCommands.CMD_1),
            Triple(2, "Tắt Doze & Standby", FixCommands.CMD_2),
            Triple(3, "Tắt Wi-Fi Power Save & Greezer", FixCommands.CMD_3),
            Triple(4, "Miễn trừ MILLET & Sync", cmd4Text)
        )

        setButtonsEnabled(false)
        binding.tvTerminalOutputFix.text = ""
        appendLog("[Bắt đầu] Chạy quy trình tất cả Lệnh 1 -> 4", LogTarget.FIX)
        appendLog("========================================", LogTarget.FIX)

        Toast.makeText(this, "Đang chạy quy trình Lệnh 1 -> 4...", Toast.LENGTH_SHORT).show()

        executor.execute {
            try {
                var successCount = 0
                var failCount = 0

                for ((index, title, cmdText) in commands) {
                    mainHandler.post {
                        appendLog("\n-> Đang chạy Lệnh $index: $title", LogTarget.FIX)
                        val displayCmd = if (cmdText.length > 120) cmdText.take(117) + "..." else cmdText
                        appendLog("$ $displayCmd", LogTarget.FIX)
                    }

                    val result = ShizukuUtils.execShizukuCommand(cmdText)
                    if (result.exitCode == 0) {
                        successCount++
                    } else {
                        failCount++
                    }

                    mainHandler.post {
                        if (result.exitCode == 0) {
                            appendLog("[Lệnh $index OK] Exit Code: 0", LogTarget.FIX)
                            if (result.stdout.isNotEmpty()) {
                                appendLog("Out:\n${result.stdout}", LogTarget.FIX)
                            }
                        } else {
                            LimiAiService.recordLastError("Lệnh $index: $title", cmdText, result.exitCode, result.stderr, result.stdout)
                            appendLog("[Lệnh $index FAIL] Exit Code: ${result.exitCode}", LogTarget.FIX)
                            if (result.stderr.isNotEmpty()) {
                                appendLog("Err:\n${result.stderr}", LogTarget.FIX)
                            }
                        }
                    }

                    Thread.sleep(300)
                }

                mainHandler.post {
                    appendLog("\n========================================", LogTarget.FIX)
                    if (failCount == 0) {
                        appendLog("[Hoàn tất] Đã thực hiện thành công toàn bộ 4/4 Lệnh!", LogTarget.FIX)
                        appendLog("[Lưu ý] Khởi động lại máy để làm mới quyền và nạp cấu hình hệ thống.", LogTarget.FIX)
                        Toast.makeText(this@MainActivity, "✅ Đã thực hiện xong toàn bộ 4 lệnh thành công!", Toast.LENGTH_LONG).show()
                    } else {
                        appendLog("[Hoàn tất] Kết quả: $successCount lệnh thành công, $failCount lệnh thất bại.", LogTarget.FIX)
                        appendLog("[Lưu ý] Vui lòng kiểm tra lại lỗi các lệnh FAIL ở trên.", LogTarget.FIX)
                        Toast.makeText(this@MainActivity, "⚠️ Hoàn tất: $successCount thành công, $failCount thất bại!", Toast.LENGTH_LONG).show()
                    }
                    appendLog("========================================", LogTarget.FIX)
                    setButtonsEnabled(true)
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    appendLog("[Lỗi ngoại lệ] ${e.localizedMessage ?: "Lỗi không xác định"}", LogTarget.FIX)
                    setButtonsEnabled(true)
                    Toast.makeText(this@MainActivity, "Lỗi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showResetCommandsConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_reset_confirm, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.windowAnimations = R.style.DialogPopAnimation

        val btnClose = dialogView.findViewById<View>(R.id.btnDialogResetConfirmClose)
        val btnConfirm = dialogView.findViewById<View>(R.id.btnConfirmRunResetShizuku)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelResetConfirm)

        btnClose?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnConfirm?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnCancel?.let { ViewAnimationExtensions.applySpringTouch(it) }

        btnClose?.setOnClickListener { dialog.dismiss() }
        btnCancel?.setOnClickListener { dialog.dismiss() }
        btnConfirm?.setOnClickListener {
            dialog.dismiss()
            runResetAllCommands()
        }

        dialog.show()
    }

    private fun runResetAllCommands() {
        if (!ShizukuUtils.hasShizukuPermission()) {
            appendLog("[Lỗi] Chưa được cấp quyền Shizuku.", LogTarget.FIX)
            Toast.makeText(this, "Cần cấp quyền Shizuku trước để khôi phục mặc định!", Toast.LENGTH_LONG).show()
            selectTab(R.id.nav_system, animated = true)
            return
        }

        // Chuyển sang tab Fix để hiển thị nhật ký Terminal
        selectTab(R.id.nav_system, animated = true)

        setButtonsEnabled(false)
        binding.tvTerminalOutputFix.text = ""
        appendLog("[Bắt đầu] Quy trình KHÔI PHỤC TOÀN DIỆN MẶC ĐỊNH NHÀ SẢN XUẤT (${FixCommands.ALL_RESET_COMMANDS.size} LỆNH FULL RESET)", LogTarget.FIX)
        appendLog("========================================", LogTarget.FIX)

        Toast.makeText(this, "Đang khôi phục toàn bộ ${FixCommands.ALL_RESET_COMMANDS.size} câu lệnh về mặc định...", Toast.LENGTH_SHORT).show()

        executor.execute {
            try {
                var successCount = 0
                var failCount = 0

                for (item in FixCommands.ALL_RESET_COMMANDS) {
                    mainHandler.post {
                        appendLog("\n-> Đang chạy: ${item.name}", LogTarget.FIX)
                        val displayCmd = if (item.command.length > 120) item.command.take(117) + "..." else item.command
                        appendLog("$ $displayCmd", LogTarget.FIX)
                    }

                    val result = ShizukuUtils.execShizukuCommand(item.command)
                    if (result.exitCode == 0) {
                        successCount++
                    } else {
                        failCount++
                    }

                    mainHandler.post {
                        if (result.exitCode == 0) {
                            appendLog("[OK] Exit Code: 0", LogTarget.FIX)
                        } else {
                            appendLog("[FAIL] Exit Code: ${result.exitCode}", LogTarget.FIX)
                            if (result.stderr.isNotEmpty()) {
                                appendLog("Err:\n${result.stderr}", LogTarget.FIX)
                            }
                        }
                    }

                    Thread.sleep(250)
                }

                mainHandler.post {
                    appendLog("\n========================================", LogTarget.FIX)
                    if (failCount == 0) {
                        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("phantom_killer_fixed", false).apply()
                        appendLog("[Hoàn tất] Đã khôi phục thành công toàn bộ ${FixCommands.ALL_RESET_COMMANDS.size}/${FixCommands.ALL_RESET_COMMANDS.size} lệnh về Mặc định Nhà sản xuất!", LogTarget.FIX)
                        appendLog("[Lưu ý] Vui lòng khởi động lại điện thoại để hệ thống nạp lại toàn bộ cấu hình gốc.", LogTarget.FIX)
                        Toast.makeText(this@MainActivity, "✅ Đã khôi phục Full toàn bộ lệnh về mặc định thành công!", Toast.LENGTH_LONG).show()
                    } else {
                        appendLog("[Hoàn tất] Kết quả hoàn nguyên: $successCount thành công, $failCount thất bại.", LogTarget.FIX)
                        Toast.makeText(this@MainActivity, "⚠️ Hoàn tất khôi phục: $successCount thành công, $failCount thất bại!", Toast.LENGTH_LONG).show()
                    }
                    appendLog("========================================", LogTarget.FIX)
                    setButtonsEnabled(true)
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    appendLog("\n[Lỗi ngoại lệ]: ${e.message}", LogTarget.FIX)
                    setButtonsEnabled(true)
                    Toast.makeText(this@MainActivity, "Lỗi khi khôi phục: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun appendLog(message: String, target: LogTarget = LogTarget.CURRENT) {
        val actualTarget = if (target == LogTarget.CURRENT) {
            when (currentTabId) {
                R.id.nav_system -> LogTarget.FIX
                R.id.nav_permissions -> LogTarget.PERMS
                R.id.nav_debloat -> LogTarget.DEBLOAT
                R.id.nav_custom -> LogTarget.CUSTOM
                else -> LogTarget.FIX
            }
        } else {
            target
        }

        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMsg = "[$timeStamp] $message\n"
        mainHandler.post {
            try {
                when (actualTarget) {
                    LogTarget.FIX -> {
                        val fixText = binding.tvTerminalOutputFix.text
                        if (fixText != null && fixText.length > 100_000) {
                            val trimmed = fixText.substring(fixText.length - 50_000)
                            binding.tvTerminalOutputFix.text = "... [đã tự động dọn log cũ] ...\n$trimmed"
                        }
                        binding.tvTerminalOutputFix.append(formattedMsg)
                        binding.scrollTerminalFix.post {
                            val child = binding.scrollTerminalFix.getChildAt(0)
                            if (child != null) {
                                val targetY = (child.bottom + binding.scrollTerminalFix.paddingBottom) - binding.scrollTerminalFix.height
                                if (targetY > 0) {
                                    binding.scrollTerminalFix.scrollTo(0, targetY)
                                }
                            }
                        }
                    }
                    LogTarget.PERMS -> {
                        val permsText = binding.tvTerminalOutputPerms.text
                        if (permsText != null && permsText.length > 100_000) {
                            val trimmed = permsText.substring(permsText.length - 50_000)
                            binding.tvTerminalOutputPerms.text = "... [đã tự động dọn log cũ] ...\n$trimmed"
                        }
                        binding.tvTerminalOutputPerms.append(formattedMsg)
                        binding.scrollTerminalPerms.post {
                            val child = binding.scrollTerminalPerms.getChildAt(0)
                            if (child != null) {
                                val targetY = (child.bottom + binding.scrollTerminalPerms.paddingBottom) - binding.scrollTerminalPerms.height
                                if (targetY > 0) {
                                    binding.scrollTerminalPerms.scrollTo(0, targetY)
                                }
                            }
                        }
                    }
                    LogTarget.DEBLOAT -> {
                        val debloatText = binding.tvTerminalOutputDebloat.text
                        if (debloatText != null && debloatText.length > 100_000) {
                            val trimmed = debloatText.substring(debloatText.length - 50_000)
                            binding.tvTerminalOutputDebloat.text = "... [đã tự động dọn log cũ] ...\n$trimmed"
                        }
                        binding.tvTerminalOutputDebloat.append(formattedMsg)
                        binding.scrollTerminalDebloat.post {
                            val child = binding.scrollTerminalDebloat.getChildAt(0)
                            if (child != null) {
                                val targetY = (child.bottom + binding.scrollTerminalDebloat.paddingBottom) - binding.scrollTerminalDebloat.height
                                if (targetY > 0) {
                                    binding.scrollTerminalDebloat.scrollTo(0, targetY)
                                }
                            }
                        }
                    }
                    LogTarget.CUSTOM -> {
                        val currentText = binding.tvTerminalOutput.text
                        if (currentText != null && currentText.length > 100_000) {
                            val trimmed = currentText.substring(currentText.length - 50_000)
                            binding.tvTerminalOutput.text = "... [đã tự động dọn log cũ] ...\n$trimmed"
                        }
                        binding.tvTerminalOutput.append(formattedMsg)
                        binding.scrollTerminal.post {
                            val child = binding.scrollTerminal.getChildAt(0)
                            if (child != null) {
                                val targetY = (child.bottom + binding.scrollTerminal.paddingBottom) - binding.scrollTerminal.height
                                if (targetY > 0) {
                                    binding.scrollTerminal.scrollTo(0, targetY)
                                }
                            }
                        }
                    }
                    else -> {}
                }
            } catch (_: Throwable) {}
        }
    }
}
