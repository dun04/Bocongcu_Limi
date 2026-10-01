package com.xiaomi.fixnotification

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class InstalledAppItem(
    val name: String,
    val packageName: String,
    val category: String,
    val icon: Drawable?,
    var isSelected: Boolean = true
)

object AppScanner {

    val POPULAR_APPS_CATEGORY_MAP = mapOf(
        "com.zing.zalo" to Pair("Zalo", "Chat"),
        "com.facebook.orca" to Pair("Messenger", "Chat"),
        "com.facebook.katana" to Pair("Facebook", "MXH"),
        "com.facebook.lite" to Pair("Facebook Lite", "MXH"),
        "org.telegram.messenger" to Pair("Telegram", "Chat"),
        "org.thunderdog.challegram" to Pair("Telegram X", "Chat"),
        "com.instagram.android" to Pair("Instagram", "MXH"),
        "com.instagram.barcelona" to Pair("Threads", "MXH"),
        "com.ss.android.ugc.trill" to Pair("TikTok", "Video"),
        "com.zhiliaoapp.musically" to Pair("TikTok Global", "Video"),
        "com.whatsapp" to Pair("WhatsApp", "Chat"),
        "com.viber.voip" to Pair("Viber", "Chat"),
        "com.discord" to Pair("Discord", "Chat"),
        "com.skype.raider" to Pair("Skype", "Chat"),
        "com.google.android.gm" to Pair("Gmail", "Email"),
        "com.microsoft.office.outlook" to Pair("Outlook", "Email"),
        "com.google.android.gms" to Pair("Dịch vụ Google (GMS)", "GMS"),
        "app.revanced.android.gms" to Pair("MicroG Revanced", "GMS"),
        "app.revanced.android.youtube" to Pair("YouTube Revanced", "Video"),
        "com.google.android.youtube" to Pair("YouTube", "Video"),
        "com.vnpay.bidv" to Pair("BIDV SmartBanking", "Ngân hàng"),
        "com.bidv.smartbanking" to Pair("BIDV", "Ngân hàng"),
        "com.mbmobile" to Pair("MB Bank", "Ngân hàng"),
        "com.VCB" to Pair("Vietcombank (VCB)", "Ngân hàng"),
        "com.techcombank.mobile" to Pair("Techcombank Mobile", "Ngân hàng"),
        "tcbbank.mobile" to Pair("Techcombank", "Ngân hàng"),
        "vn.com.vietinbank.ipay" to Pair("VietinBank iPay", "Ngân hàng"),
        "com.vietinbank.ipay" to Pair("VietinBank", "Ngân hàng"),
        "com.vnpay.vpbankonline" to Pair("VPBank NEO", "Ngân hàng"),
        "com.vnpay.vpbank" to Pair("VPBank", "Ngân hàng"),
        "vn.tnex.consumer" to Pair("TNEX - Ngân hàng số", "Ngân hàng"),
        "com.tpb.mb.gprsandroid" to Pair("TPBank Mobile", "Ngân hàng"),
        "com.vnpay.agribank" to Pair("Agribank E-Mobile", "Ngân hàng"),
        "mobile.acb.com.vn" to Pair("ACB ONE", "Ngân hàng"),
        "com.vnpay.sacombank" to Pair("Sacombank mBanking", "Ngân hàng"),
        "com.mservice.momopay" to Pair("Ví MoMo", "Ví điện tử"),
        "vn.com.vng.zalopay" to Pair("ZaloPay", "Ví điện tử"),
        "com.airpay" to Pair("ShopeePay", "Ví điện tử"),
        "com.shopee.vn" to Pair("Shopee", "Mua sắm"),
        "com.lazada.android" to Pair("Lazada", "Mua sắm"),
        "com.grabtaxi.passenger" to Pair("Grab", "Dịch vụ"),
        "xyz.be.customer" to Pair("Be", "Dịch vụ")
    )

    /**
     * Quét toàn bộ 100% ứng dụng người dùng và ứng dụng có giao diện Launcher trên máy,
     * tự động chọn sẵn các app nhắn tin/MXH/ngân hàng gợi ý và đưa lên đầu danh sách.
     */
    fun loadInstalledAppsWithIcons(context: Context): List<InstalledAppItem> {
        val pm = context.packageManager
        val popularList = mutableListOf<InstalledAppItem>()
        val otherUserApps = mutableListOf<InstalledAppItem>()
        val seenPackages = mutableSetOf<String>()
        val candidatePackages = linkedSetOf<String>()

        // Bỏ qua package của chính ứng dụng này
        seenPackages.add(context.packageName)

        // 1. Quét qua Shizuku Shell: `pm list packages -3` (100% ứng dụng bên thứ 3 / người dùng cài đặt)
        try {
            if (ShizukuUtils.hasShizukuPermission()) {
                val cmd3 = ShizukuUtils.execShizukuCommand("pm list packages -3")
                if (cmd3.exitCode == 0 && cmd3.stdout.isNotEmpty()) {
                    cmd3.stdout.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("package:")) {
                            val pkg = trimmed.removePrefix("package:").trim()
                            if (pkg.isNotEmpty()) candidatePackages.add(pkg)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. Quét qua Local Process Shell: `pm list packages -3`
        if (candidatePackages.isEmpty()) {
            try {
                val process = Runtime.getRuntime().exec("pm list packages -3")
                val reader = process.inputStream.bufferedReader()
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package:")) {
                        val pkg = trimmed.removePrefix("package:").trim()
                        if (pkg.isNotEmpty()) candidatePackages.add(pkg)
                    }
                }
                process.waitFor()
            } catch (_: Throwable) {}
        }

        // 3. Quét tất cả ứng dụng có biểu tượng Launcher (Mọi app có icon trên màn hình chính)
        try {
            val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val launcherApps = pm.queryIntentActivities(mainIntent, 0)
            for (resolveInfo in launcherApps) {
                val pkg = resolveInfo.activityInfo?.packageName
                if (!pkg.isNullOrEmpty()) {
                    candidatePackages.add(pkg)
                }
            }
        } catch (_: Throwable) {}

        // 4. Quét qua PackageManager thông thường
        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                if (app.packageName.isNotEmpty()) {
                    val isUserApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                            (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isUserApp) {
                        candidatePackages.add(app.packageName)
                    }
                }
            }
        } catch (_: Throwable) {}

        // 5. Thêm tất cả các package phổ biến đã định nghĩa trước
        candidatePackages.addAll(POPULAR_APPS_CATEGORY_MAP.keys)

        // 6. Xử lý các ứng dụng ưu tiên phổ biến (Zalo, Messenger, Telegram, Ngân hàng, Gmail, GMS...)
        for ((pkg, info) in POPULAR_APPS_CATEGORY_MAP) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0) ?: pm.getPackageInfo(pkg, 0)?.applicationInfo
                if (appInfo != null) {
                    val rawLabel = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Throwable) { "" }
                    val appName = if (rawLabel.isNotEmpty() && !rawLabel.equals(pkg, ignoreCase = true)) rawLabel else info.first
                    val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Throwable) { null }
                    popularList.add(
                        InstalledAppItem(
                            name = appName,
                            packageName = pkg,
                            category = info.second,
                            icon = icon,
                            isSelected = true
                        )
                    )
                    seenPackages.add(pkg)
                }
            } catch (_: Throwable) {}
        }

        // 7. Xử lý toàn bộ các ứng dụng còn lại
        for (pkg in candidatePackages) {
            if (seenPackages.contains(pkg) || pkg == context.packageName) continue

            try {
                val appInfo = pm.getApplicationInfo(pkg, 0) ?: pm.getPackageInfo(pkg, 0)?.applicationInfo ?: continue
                val rawName = try { pm.getApplicationLabel(appInfo).toString().trim() } catch (_: Throwable) { "" }
                val appName = if (rawName.isNotEmpty() && !rawName.equals(pkg, ignoreCase = true)) rawName else getReadablePackageName(pkg)
                val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Throwable) { null }

                val pkgLower = pkg.lowercase()
                val nameLower = appName.lowercase()

                // Phân loại danh mục tự động thông minh
                val category = when {
                    pkgLower.contains("bank") || pkgLower.contains("vnpay") || pkgLower.contains("ipay") ||
                            pkgLower.contains("smartbanking") || nameLower.contains("ngân hàng") || nameLower.contains("bank") -> "Ngân hàng"
                    pkgLower.contains("pay") || pkgLower.contains("momo") || pkgLower.contains("wallet") || nameLower.contains("ví") -> "Ví điện tử"
                    pkgLower.contains("zalo") || pkgLower.contains("chat") || pkgLower.contains("messeng") ||
                            pkgLower.contains("telegram") || pkgLower.contains("viber") || pkgLower.contains("discord") ||
                            pkgLower.contains("whatsapp") || pkgLower.contains("skype") -> "Chat"
                    pkgLower.contains("facebook") || pkgLower.contains("instagram") || pkgLower.contains("tiktok") ||
                            pkgLower.contains("threads") || pkgLower.contains("twitter") || pkgLower.contains("social") -> "MXH"
                    pkgLower.contains("mail") || pkgLower.contains("gmail") || pkgLower.contains("outlook") -> "Email"
                    pkgLower.contains("shopee") || pkgLower.contains("lazada") || pkgLower.contains("tiki") || pkgLower.contains("sendo") -> "Mua sắm"
                    pkgLower.contains("grab") || pkgLower.contains("be") || pkgLower.contains("gojek") -> "Dịch vụ"
                    pkgLower.contains("game") || pkgLower.contains("play") -> "Game"
                    else -> "Ứng dụng"
                }

                otherUserApps.add(
                    InstalledAppItem(
                        name = appName,
                        packageName = pkg,
                        category = category,
                        icon = icon,
                        isSelected = false
                    )
                )
                seenPackages.add(pkg)
            } catch (_: Throwable) {}
        }

        // Sắp xếp các app người dùng khác theo thứ tự alphabet A-Z
        otherUserApps.sortBy { it.name.lowercase() }

        // Kết hợp: App ưu tiên (được chọn sẵn) lên ĐẦU, sau đó là toàn bộ các app người dùng khác
        return popularList + otherUserApps
    }

    /**
     * Quét và lấy danh sách các ứng dụng ngân hàng thực tế đang được cài đặt trên máy người dùng.
     * Trả về danh sách Pair(packageName, appName).
     */
    fun scanInstalledBankApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val result = mutableListOf<Pair<String, String>>()
        val seenPkgs = mutableSetOf<String>()

        // 1. Quét các app trong danh mục ngân hàng định nghĩa sẵn
        for ((pkg, info) in POPULAR_APPS_CATEGORY_MAP) {
            if (info.second == "Ngân hàng") {
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(appInfo).toString().ifEmpty { info.first }
                    result.add(Pair(pkg, label))
                    seenPkgs.add(pkg)
                } catch (_: Throwable) {}
            }
        }

        // 2. Quét các ứng dụng cài thêm khác của người dùng có dấu hiệu là app ngân hàng
        try {
            val installedApps = loadInstalledAppsWithIcons(context)
            for (app in installedApps) {
                if (!seenPkgs.contains(app.packageName)) {
                    val pkgLower = app.packageName.lowercase()
                    val labelLower = app.name.lowercase()

                    val isBank = app.category == "Ngân hàng" ||
                            pkgLower.contains("bank") ||
                            pkgLower.contains("vnpay") ||
                            pkgLower.contains("ipay") ||
                            pkgLower.contains("tnex") ||
                            pkgLower.contains("timo") ||
                            pkgLower.contains("cake") ||
                            pkgLower.contains("smartbanking") ||
                            labelLower.contains("ngân hàng") ||
                            labelLower.contains("bank")

                    if (isBank) {
                        result.add(Pair(app.packageName, app.name))
                        seenPkgs.add(app.packageName)
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        return result
    }

    /**
     * Quét và nạp toàn bộ danh sách 100% ứng dụng ĐANG CÀI ĐẶT (Cả ứng dụng Hệ thống, Bloatware, Apex và Người dùng)
     * Tự động loại trừ các ứng dụng đã bị gỡ bỏ/xóa cài đặt.
     */
    fun loadAllAppsForDebloat(context: Context): List<DebloatAppItem> {
        val pm = context.packageManager
        val allDiscoveredPkgs = linkedSetOf<String>()

        // 1. Quét qua Shizuku Shell: `pm list packages -a` (Lấy toàn bộ package hệ thống & người dùng đang cài đặt)
        try {
            if (ShizukuUtils.hasShizukuPermission()) {
                val cmdResult = ShizukuUtils.execShizukuCommand("pm list packages -a")
                if (cmdResult.exitCode == 0 && cmdResult.stdout.isNotEmpty()) {
                    cmdResult.stdout.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("package:")) {
                            val pkg = trimmed.removePrefix("package:").trim()
                            if (pkg.isNotEmpty()) allDiscoveredPkgs.add(pkg)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. Quét qua Local Process Shell: `pm list packages -a` / `pm list packages`
        if (allDiscoveredPkgs.size < 200) {
            try {
                val process = Runtime.getRuntime().exec("pm list packages -a")
                val reader = process.inputStream.bufferedReader()
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package:")) {
                        val pkg = trimmed.removePrefix("package:").trim()
                        if (pkg.isNotEmpty()) allDiscoveredPkgs.add(pkg)
                    }
                }
                process.waitFor()
            } catch (_: Throwable) {}
        }

        // 3. Quét qua PackageManager với cờ hỗ trợ Apex & Components đang tồn tại trên máy
        val activeMatchFlags = PackageManager.GET_META_DATA or
                PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or
                0x40000000    // MATCH_APEX

        try {
            val installedPkgs = pm.getInstalledPackages(activeMatchFlags)
            for (pkgInfo in installedPkgs) {
                if (pkgInfo.packageName.isNotEmpty()) {
                    allDiscoveredPkgs.add(pkgInfo.packageName)
                }
            }
        } catch (_: Throwable) {
            try {
                val basicPkgs = pm.getInstalledPackages(0)
                for (pkgInfo in basicPkgs) {
                    if (pkgInfo.packageName.isNotEmpty()) allDiscoveredPkgs.add(pkgInfo.packageName)
                }
            } catch (_: Throwable) {}
        }

        try {
            val installedApps = pm.getInstalledApplications(activeMatchFlags)
            for (app in installedApps) {
                if (app.packageName.isNotEmpty()) {
                    allDiscoveredPkgs.add(app.packageName)
                }
            }
        } catch (_: Throwable) {
            try {
                val basicApps = pm.getInstalledApplications(0)
                for (app in basicApps) {
                    if (app.packageName.isNotEmpty()) allDiscoveredPkgs.add(app.packageName)
                }
            } catch (_: Throwable) {}
        }

        val resultList = mutableListOf<DebloatAppItem>()
        val seenPackages = mutableSetOf<String>()

        for (pkg in allDiscoveredPkgs) {
            if (pkg.isBlank() || seenPackages.contains(pkg)) continue

            val appInfo = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: Throwable) {
                try {
                    pm.getPackageInfo(pkg, 0)?.applicationInfo
                } catch (_: Throwable) {
                    null
                }
            }

            // Bỏ qua các app không tồn tại hoặc đã bị xóa cài đặt trên máy
            if (appInfo == null) continue

            val rawName = try { pm.getApplicationLabel(appInfo).toString().trim() } catch (_: Throwable) { "" }

            val appName = when {
                rawName.isNotEmpty() && !rawName.equals(pkg, ignoreCase = true) -> rawName
                POPULAR_APPS_CATEGORY_MAP.containsKey(pkg) -> POPULAR_APPS_CATEGORY_MAP[pkg]!!.first
                DebloatAppItem.KNOWN_BLOATWARE_MAP.containsKey(pkg) -> {
                    val desc = DebloatAppItem.KNOWN_BLOATWARE_MAP[pkg]!!
                    desc.substringBefore(" (")
                }
                else -> getReadablePackageName(pkg)
            }

            val icon = if (appInfo != null) {
                try {
                    appInfo.loadIcon(pm)
                } catch (_: Throwable) {
                    try {
                        pm.getApplicationIcon(appInfo)
                    } catch (_: Throwable) {
                        null
                    }
                }
            } else {
                try {
                    pm.getApplicationIcon(pkg)
                } catch (_: Throwable) {
                    null
                }
            }

            val isSystem = if (appInfo != null) {
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            } else {
                !pkg.startsWith("com.zing.zalo") &&
                        !pkg.startsWith("com.facebook") &&
                        !pkg.startsWith("org.telegram") &&
                        !pkg.startsWith("com.instagram") &&
                        !pkg.startsWith("com.ss.android.ugc.trill") &&
                        !pkg.startsWith("com.shopee") &&
                        !pkg.startsWith("com.lazada") &&
                        !pkg.startsWith("com.grab")
            }

            val pkgLower = pkg.lowercase()
            val isKnownBloat = DebloatAppItem.KNOWN_BLOATWARE_MAP.containsKey(pkg) ||
                    pkgLower.contains("analytics") ||
                    pkgLower.contains("daemon") ||
                    pkgLower.contains("joyose") ||
                    pkgLower.contains("hybrid") ||
                    pkgLower.contains("yellowpage") ||
                    pkgLower.contains("cleanmaster") ||
                    pkgLower.contains("msa") ||
                    pkgLower.contains("statprovider") ||
                    pkgLower.contains("bugreport") ||
                    pkgLower.contains("tracking") ||
                    pkgLower.contains("adservice") ||
                    pkgLower.contains("midrop") ||
                    pkgLower.contains("mishare") ||
                    pkgLower.contains("micoin") ||
                    pkgLower.contains("gamecenter") ||
                    pkgLower.contains("carlink") ||
                    pkgLower.contains("carwith") ||
                    pkgLower.contains("mis.service") ||
                    appName.contains("CarWith", ignoreCase = true) ||
                    appName.contains("汽车互联")

            val appType = when {
                isKnownBloat -> AppType.BLOATWARE
                pkg.startsWith("com.google.android") || pkg.startsWith("com.android.vending") -> AppType.GOOGLE
                isSystem -> AppType.SYSTEM
                else -> AppType.USER
            }

            val description = DebloatAppItem.KNOWN_BLOATWARE_MAP[pkg] ?: ""

            resultList.add(
                DebloatAppItem(
                    name = appName,
                    packageName = pkg,
                    icon = icon,
                    isSystemApp = isSystem,
                    appType = appType,
                    description = description
                )
            )
            seenPackages.add(pkg)
        }

        // Sắp xếp: Bloatware lên đầu, rồi tới Google, rồi tới Hệ thống, rồi tới Người dùng
        resultList.sortWith(
            compareBy(
                { it.appType != AppType.BLOATWARE },
                { it.appType != AppType.GOOGLE },
                { !it.isSystemApp },
                { it.name.lowercase() }
            )
        )

        return resultList
    }

    /**
     * Quét và lấy danh sách các ứng dụng hệ thống / bloatware ĐÃ BỊ GỠ BỎ hoặc BỊ VÔ HIỆU HÓA để khôi phục.
     */
    fun loadUninstalledAppsForRestore(context: Context): List<DebloatAppItem> {
        val pm = context.packageManager
        val uninstalledCandidatePkgs = linkedSetOf<String>()

        // 1. Quét qua Shizuku: `pm list packages -u -a` (Liệt kê tất cả package kể cả đã gỡ cho user 0)
        try {
            if (ShizukuUtils.hasShizukuPermission()) {
                val cmdResult = ShizukuUtils.execShizukuCommand("pm list packages -u -a")
                if (cmdResult.exitCode == 0 && cmdResult.stdout.isNotEmpty()) {
                    cmdResult.stdout.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("package:")) {
                            val pkg = trimmed.removePrefix("package:").trim()
                            if (pkg.isNotEmpty()) uninstalledCandidatePkgs.add(pkg)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. Quét qua Local Process Shell: `pm list packages -u`
        if (uninstalledCandidatePkgs.size < 20) {
            try {
                val process = Runtime.getRuntime().exec("pm list packages -u")
                val reader = process.inputStream.bufferedReader()
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package:")) {
                        val pkg = trimmed.removePrefix("package:").trim()
                        if (pkg.isNotEmpty()) uninstalledCandidatePkgs.add(pkg)
                    }
                }
                process.waitFor()
            } catch (_: Throwable) {}
        }

        // 3. Thêm các package Bloatware đã biết vào tập ứng viên đối chiếu
        for (pkg in DebloatAppItem.KNOWN_BLOATWARE_MAP.keys) {
            uninstalledCandidatePkgs.add(pkg)
        }

        val resultList = mutableListOf<DebloatAppItem>()
        val seenPackages = mutableSetOf<String>()

        for (pkg in uninstalledCandidatePkgs) {
            if (pkg.isBlank() || seenPackages.contains(pkg) || pkg == context.packageName) continue

            // Kiểm tra trạng thái hiện tại trên user 0
            val appInfo = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: Throwable) {
                null
            }

            val isInstalledAndEnabled = appInfo != null && appInfo.enabled

            // Chỉ lấy các ứng dụng CHƯA CÀI ĐẶT hoặc ĐÃ BỊ TẮT (Disabled)
            if (!isInstalledAndEnabled) {
                val isKnownBloat = DebloatAppItem.KNOWN_BLOATWARE_MAP.containsKey(pkg)
                val bloatDesc = DebloatAppItem.KNOWN_BLOATWARE_MAP[pkg] ?: ""
                val rawName = if (isKnownBloat) bloatDesc.substringBefore(" (") else ""

                val appName = when {
                    rawName.isNotEmpty() -> rawName
                    POPULAR_APPS_CATEGORY_MAP.containsKey(pkg) -> POPULAR_APPS_CATEGORY_MAP[pkg]!!.first
                    else -> getReadablePackageName(pkg)
                }

                val icon = try {
                    if (appInfo != null) pm.getApplicationIcon(appInfo) else pm.getApplicationIcon(pkg)
                } catch (_: Throwable) {
                    null
                }

                val isSystem = !pkg.startsWith("com.zing.zalo") &&
                        !pkg.startsWith("com.facebook") &&
                        !pkg.startsWith("org.telegram") &&
                        !pkg.startsWith("com.shopee")

                val pkgLower = pkg.lowercase()
                val isBloat = isKnownBloat ||
                        pkgLower.contains("analytics") ||
                        pkgLower.contains("daemon") ||
                        pkgLower.contains("joyose") ||
                        pkgLower.contains("yellowpage") ||
                        pkgLower.contains("cleanmaster") ||
                        pkgLower.contains("msa")

                val appType = when {
                    isBloat -> AppType.BLOATWARE
                    pkg.startsWith("com.google.android") || pkg.startsWith("com.android.vending") -> AppType.GOOGLE
                    isSystem -> AppType.SYSTEM
                    else -> AppType.USER
                }

                val description = if (bloatDesc.isNotEmpty()) bloatDesc else if (appInfo != null && !appInfo.enabled) "Ứng dụng đang bị vô hiệu hóa (Tắt)" else "Ứng dụng hệ thống đã bị gỡ bỏ"

                resultList.add(
                    DebloatAppItem(
                        name = appName,
                        packageName = pkg,
                        icon = icon,
                        isSystemApp = isSystem,
                        appType = appType,
                        description = description,
                        isSelected = false
                    )
                )
                seenPackages.add(pkg)
            }
        }

        // Sắp xếp: Bloatware -> Google -> Hệ thống -> Tên A-Z
        resultList.sortWith(
            compareBy(
                { it.appType != AppType.BLOATWARE },
                { it.appType != AppType.GOOGLE },
                { !it.isSystemApp },
                { it.name.lowercase() }
            )
        )

        return resultList
    }

    private fun getReadablePackageName(pkg: String): String {
        val parts = pkg.split(".")
        return if (parts.isNotEmpty()) {
            val last = parts.last()
            if (last.length > 2) {
                last.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            } else {
                pkg
            }
        } else {
            pkg
        }
    }
}
