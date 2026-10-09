package com.xiaomi.fixnotification.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.View
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import android.widget.Toast
import com.xiaomi.fixnotification.R
import java.util.Locale
import java.util.regex.Pattern

/**
 * Trình định dạng văn bản Markdown sang Spanned cho giao diện Chat AI Limi.
 * Chuyển đổi các thẻ tiêu đề (###, ##), danh sách gạch đầu dòng (*, -),
 * bảng so sánh Markdown (| col1 | col2 | ...), in đậm (**text**), in nghiêng (*text*),
 * code (`code`) thành văn bản giàu định dạng (Rich Text) đẹp mắt, rõ ràng và hiện đại.
 */
object LimiMarkdownFormatter {

    // Regex patterns
    private val BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__")
    private val ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|(?<!_)_([^_\\n]+)_(?!_)")
    private val INLINE_CODE_PATTERN = Pattern.compile("`([^`\\n]+)`")

    // Colors (High-contrast and beautiful on both Light & Dark modes)
    private val COLOR_HEADER = Color.parseColor("#0284C7") // Ocean Blue
    private val COLOR_BULLET = Color.parseColor("#0284C7") // Accent Blue
    private val COLOR_CODE = Color.parseColor("#0369A1")   // Monospace Blue

    /**
     * Định dạng chuỗi văn bản Markdown thô thành SpannableStringBuilder hoàn chỉnh.
     */
    fun format(context: Context, rawText: String?): CharSequence {
        if (rawText.isNullOrBlank()) return ""

        val lines = rawText.lines()
        val formattedLines = mutableListOf<LineElement>()
        var lineIdx = 0

        while (lineIdx < lines.size) {
            val line = lines[lineIdx]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                formattedLines.add(LineElement.Empty)
                lineIdx++
                continue
            }

            // Kiểm tra Bảng Markdown (| Header 1 | Header 2 | ...)
            if (trimmed.contains("|") && lineIdx + 1 < lines.size) {
                val nextTrimmed = lines[lineIdx + 1].trim()
                val isDivider = isTableDivider(nextTrimmed)
                if (isDivider) {
                    val rawHeaders = parseTableRow(trimmed)
                    if (rawHeaders.isNotEmpty()) {
                        lineIdx += 2 // Bỏ qua Header và Divider
                        val tableRows = mutableListOf<List<String>>()
                        while (lineIdx < lines.size) {
                            val rowLine = lines[lineIdx].trim()
                            if (rowLine.isEmpty() || !rowLine.contains("|") || isTableDivider(rowLine)) {
                                break
                            }
                            val parsedRow = parseTableRow(rowLine)
                            if (parsedRow.isNotEmpty()) {
                                tableRows.add(parsedRow)
                            }
                            lineIdx++
                        }
                        formattedLines.add(LineElement.Table(rawHeaders, tableRows))
                        continue
                    }
                }
            }

            // 1. Kiểm tra Tiêu đề Markdown (###, ##, #)
            when {
                trimmed.startsWith("#### ") -> {
                    val content = trimmed.substring(5).trim()
                    formattedLines.add(LineElement.Heading(content, level = 4))
                }
                trimmed.startsWith("### ") -> {
                    val content = trimmed.substring(4).trim()
                    formattedLines.add(LineElement.Heading(content, level = 3))
                }
                trimmed.startsWith("## ") -> {
                    val content = trimmed.substring(3).trim()
                    formattedLines.add(LineElement.Heading(content, level = 2))
                }
                trimmed.startsWith("# ") -> {
                    val content = trimmed.substring(2).trim()
                    formattedLines.add(LineElement.Heading(content, level = 1))
                }
                // Đường kẻ ngang --- hoặc ***
                trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                    formattedLines.add(LineElement.Divider)
                }
                // Danh sách gạch đầu dòng (*, -, +)
                trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("+ ") -> {
                    val content = trimmed.substring(2).trim()
                    formattedLines.add(LineElement.Bullet(content))
                }
                // Danh sách số (1. 2. 3. ...)
                trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> {
                    val dotIdx = trimmed.indexOf('.')
                    val num = trimmed.substring(0, dotIdx + 1)
                    val content = trimmed.substring(dotIdx + 1).trim()
                    formattedLines.add(LineElement.Numbered(num, content))
                }
                else -> {
                    formattedLines.add(LineElement.Normal(line))
                }
            }
            lineIdx++
        }

        val builder = SpannableStringBuilder()
        var prevWasEmpty = false

        for (i in formattedLines.indices) {
            val el = formattedLines[i]

            if (el is LineElement.Empty) {
                if (!prevWasEmpty && builder.isNotEmpty()) {
                    builder.append("\n")
                    prevWasEmpty = true
                }
                continue
            }
            prevWasEmpty = false

            if (builder.isNotEmpty()) {
                builder.append("\n")
            }

            when (el) {
                is LineElement.Heading -> {
                    if (builder.isNotEmpty() && !builder.endsWith("\n\n")) {
                        builder.insert(builder.length - 1, "\n")
                    }

                    val hasCustomIconOrNumber = el.text.matches(Regex("^[0-9\\.\\-\\)\\s]*[\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}\\p{Cs}\\p{Cn}\\uD83C-\\uDBFF\\uDC00-\\uDFFF].*")) || el.text.matches(Regex("^\\d+[\\.\\)].*"))
                    val icon = if (hasCustomIconOrNumber) "" else when (el.level) {
                        1 -> "📌 "
                        2 -> "🔹 "
                        3 -> "✨ "
                        else -> "▫️ "
                    }

                    val start = builder.length
                    builder.append(icon)
                    appendInlineStyledText(builder, el.text)
                    val end = builder.length

                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(COLOR_HEADER), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val scale = if (el.level <= 2) 1.18f else 1.10f
                    builder.setSpan(RelativeSizeSpan(scale), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                is LineElement.Bullet -> {
                    val start = builder.length
                    builder.append("  • ")
                    val bulletEnd = builder.length
                    builder.setSpan(ForegroundColorSpan(COLOR_BULLET), start, bulletEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, bulletEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                    appendInlineStyledText(builder, el.text)
                }

                is LineElement.Numbered -> {
                    val start = builder.length
                    builder.append("  ${el.num} ")
                    val numEnd = builder.length
                    builder.setSpan(ForegroundColorSpan(COLOR_BULLET), start, numEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, numEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                    appendInlineStyledText(builder, el.text)
                }

                is LineElement.Divider -> {
                    val start = builder.length
                    builder.append("────────────────────────")
                    val end = builder.length
                    builder.setSpan(ForegroundColorSpan(Color.parseColor("#334155")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                is LineElement.Table -> {
                    renderTableElement(builder, el)
                }

                is LineElement.Normal -> {
                    appendInlineStyledText(builder, el.text)
                }

                else -> {}
            }
        }

        
        // Parse Markdown Links: [text](url)
        parseLinksAndIcons(context, builder)

        return builder
    }

    private fun isTableDivider(line: String): Boolean {
        if (!line.contains("-") || !line.contains("|")) return false
        val parts = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        return parts.all { it.matches(Regex("^:?-+:?$")) }
    }

    private fun parseTableRow(line: String): List<String> {
        val trimmed = line.trim()
        val stripped = trimmed.removePrefix("|").removeSuffix("|")
        return stripped.split("|").map { it.trim() }
    }

    private fun renderTableElement(builder: SpannableStringBuilder, table: LineElement.Table) {
        val headers = table.headers
        val rows = table.rows
        if (headers.isEmpty()) return

        val colorBorder = Color.parseColor("#38BDF8") // Vibrant Cyan/Blue Border
        val colorHeaderBg = Color.parseColor("#0284C7")
        val colorCellKey = Color.parseColor("#38BDF8")
        val colorDivider = Color.parseColor("#1E293B")

        val title = if (headers.size >= 2) {
            "📊 BẢNG SO SÁNH: ${headers.drop(1).joinToString(" VS ")}"
        } else {
            "📊 BẢNG THÔNG SỐ CHI TIẾT"
        }

        // Top Border Header
        val startTop = builder.length
        builder.append("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓\n")
        val endTop = builder.length
        builder.setSpan(ForegroundColorSpan(colorBorder), startTop, endTop, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Header Title
        val startTitle = builder.length
        builder.append("┃ $title\n")
        val endTitle = builder.length
        builder.setSpan(StyleSpan(Typeface.BOLD), startTitle, endTitle, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(ForegroundColorSpan(Color.parseColor("#E0F2FE")), startTitle, endTitle, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Header Divider
        val startHDiv = builder.length
        builder.append("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫\n")
        val endHDiv = builder.length
        builder.setSpan(ForegroundColorSpan(colorBorder), startHDiv, endHDiv, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Table Rows
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            val feature = row.getOrNull(0) ?: ""
            if (feature.isBlank() && row.all { it.isBlank() }) continue

            // Feature / Criteria Row
            if (feature.isNotBlank()) {
                val fStart = builder.length
                builder.append("┃ 🔹 ")
                appendInlineStyledText(builder, feature)
                builder.append("\n")
                val fEnd = builder.length
                builder.setSpan(StyleSpan(Typeface.BOLD), fStart, fEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(colorCellKey), fStart, fEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            // Columns Comparison
            if (headers.size == 2) {
                val valCol = row.getOrNull(1) ?: ""
                if (valCol.isNotBlank()) {
                    builder.append("┃   • ")
                    appendInlineStyledText(builder, valCol)
                    builder.append("\n")
                }
            } else {
                for (colIdx in 1 until headers.size) {
                    val targetName = headers[colIdx]
                    val targetVal = row.getOrNull(colIdx) ?: "-"

                    builder.append("┃   • ")
                    val hStart = builder.length
                    appendInlineStyledText(builder, targetName)
                    val hEnd = builder.length
                    builder.setSpan(StyleSpan(Typeface.BOLD), hStart, hEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(Color.parseColor("#94A3B8")), hStart, hEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                    builder.append(": ")
                    appendInlineStyledText(builder, targetVal)
                    builder.append("\n")
                }
            }

            if (rowIndex < rows.size - 1) {
                val dStart = builder.length
                builder.append("┠┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┨\n")
                val dEnd = builder.length
                builder.setSpan(ForegroundColorSpan(Color.parseColor("#334155")), dStart, dEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // Bottom Border
        val startBottom = builder.length
        builder.append("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛")
        val endBottom = builder.length
        builder.setSpan(ForegroundColorSpan(colorBorder), startBottom, endBottom, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * Xử lý định dạng nội dòng (Inline): In đậm (**), In nghiêng (*), Code inline (`...`)
     */
    private fun appendInlineStyledText(builder: SpannableStringBuilder, text: String) {
        var cursor = 0
        while (cursor < text.length) {
            val nextBold = text.indexOf("**", cursor)
            val nextCode = text.indexOf("`", cursor)
            
            val candidates = mutableListOf<Pair<Int, String>>()
            if (nextBold != -1) candidates.add(Pair(nextBold, "**"))
            if (nextCode != -1) candidates.add(Pair(nextCode, "`"))

            if (candidates.isEmpty()) {
                val normalText = text.substring(cursor)
                builder.append(normalText)
                break
            }

            candidates.sortBy { it.first }
            val (tokenPos, tokenType) = candidates.first()

            if (tokenPos > cursor) {
                builder.append(text.substring(cursor, tokenPos))
            }

            when (tokenType) {
                "**" -> {
                    val closeBold = text.indexOf("**", tokenPos + 2)
                    if (closeBold != -1) {
                        val boldContent = text.substring(tokenPos + 2, closeBold)
                        val start = builder.length
                        builder.append(boldContent)
                        val end = builder.length
                        if (start < end) {
                            builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        cursor = closeBold + 2
                    } else {
                        // Đang gõ typewriter chưa có dấu đóng "**"
                        // Áp dụng định dạng In Đậm ngay cho phần văn bản đang gõ mà không in dấu "**" thô ra màn hình
                        // Tránh tình trạng khi dấu đóng xuất hiện làm mất "**" đột ngột gây nhảy giật chữ
                        val boldContent = text.substring(tokenPos + 2)
                        val start = builder.length
                        builder.append(boldContent)
                        val end = builder.length
                        if (start < end) {
                            builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        cursor = text.length
                    }
                }
                "`" -> {
                    val closeCode = text.indexOf("`", tokenPos + 1)
                    if (closeCode != -1) {
                        val codeContent = text.substring(tokenPos + 1, closeCode)
                        val start = builder.length
                        builder.append(codeContent)
                        val end = builder.length
                        if (start < end) {
                            builder.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            builder.setSpan(ForegroundColorSpan(COLOR_CODE), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        cursor = closeCode + 1
                    } else {
                        // Đang gõ typewriter chưa có dấu đóng "`"
                        // Áp dụng monospace ngay cho phần code đang gõ dở, không chèn dấu "`" thô
                        val codeContent = text.substring(tokenPos + 1)
                        val start = builder.length
                        builder.append(codeContent)
                        val end = builder.length
                        if (start < end) {
                            builder.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            builder.setSpan(ForegroundColorSpan(COLOR_CODE), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        cursor = text.length
                    }
                }
                else -> {
                    builder.append(text[tokenPos].toString())
                    cursor = tokenPos + 1
                }
            }
        }
    }

    private sealed class LineElement {
        data class Heading(val text: String, val level: Int) : LineElement()
        data class Bullet(val text: String) : LineElement()
        data class Numbered(val num: String, val text: String) : LineElement()
        data class Normal(val text: String) : LineElement()
        data class Table(val headers: List<String>, val rows: List<List<String>>) : LineElement()
        object Divider : LineElement()
        object Empty : LineElement()
    }
}

    fun openUrl(context: Context, rawUrl: String) {
        var url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("geo:") && !url.startsWith("tel:") && !url.startsWith("mailto:")) {
            url = "https://$url"
        }

        val uri = Uri.parse(url)

        // 1. Google Maps / Vị trí bản đồ địa chỉ
        val isMap = url.contains("maps.google") || url.contains("google.com/maps") || url.contains("goo.gl/maps") || url.contains("maps.app.goo.gl") || url.startsWith("geo:")
        if (isMap) {
            try {
                val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(mapIntent)
                return
            } catch (e: Exception) {
                // Không có Google Maps app chính thức hoặc lỗi package, tiếp tục mở bằng trình duyệt
            }
        }

        // 2. Nhóm Zalo / Chat Zalo
        if (url.contains("zalo.me")) {
            try {
                val zaloIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.zing.zalo")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(zaloIntent)
                return
            } catch (e: Exception) {
                // Không có app Zalo, tiếp tục mở bằng trình duyệt
            }
        }

        // 3. Mở liên kết mặc định với trình duyệt hoặc ứng dụng hỗ trợ
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        } catch (e: Exception) {
            try {
                val chooser = Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Mở liên kết").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e2: Exception) {
                Toast.makeText(context, "Không thể mở liên kết: ${e2.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getAppIconSpan(context: Context, url: String): ImageSpan? {
        val lower = url.lowercase(Locale.ROOT)
        var pkgName: String? = null
        var fallbackDrawableRes: Int? = null

        when {
            lower.contains("zalo.me") -> pkgName = "com.zing.zalo"
            lower.contains("m.me") || lower.contains("messenger.com") -> pkgName = "com.facebook.orca"
            lower.contains("maps.google") || lower.contains("google.com/maps") || lower.contains("goo.gl/maps") || lower.contains("maps.app.goo.gl") || lower.startsWith("geo:") -> {
                pkgName = "com.google.android.apps.maps"
                fallbackDrawableRes = R.drawable.ic_location_pin
            }
            lower.contains("tiktok.com") -> pkgName = "com.zhiliaoapp.musically"
            lower.contains("facebook.com") -> pkgName = "com.facebook.katana"
            lower.contains("youtube.com") || lower.contains("youtu.be") -> pkgName = "com.google.android.youtube"
            lower.contains("telegram.me") || lower.contains("t.me") -> pkgName = "org.telegram.messenger"
        }

        var drawable: android.graphics.drawable.Drawable? = null
        if (pkgName != null) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(pkgName, 0)
                drawable = pm.getApplicationIcon(appInfo)
            } catch (e: Exception) {
                // App không cài trên máy
            }
        }

        if (drawable == null && fallbackDrawableRes != null) {
            drawable = ContextCompat.getDrawable(context, fallbackDrawableRes)
        }

        if (drawable != null) {
            val size = (16f * context.resources.displayMetrics.density).toInt()
            drawable.setBounds(0, 0, size, size)
            return ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
        }
        return null
    }

    private fun parseLinksAndIcons(context: Context, builder: SpannableStringBuilder) {
        // 1. Phân tích Markdown Links: [Tiêu đề](URL)
        val matcher = Pattern.compile("""\[(.*?)\]\((.*?)\)""").matcher(builder.toString())
        var offset = 0
        while (matcher.find()) {
            val fullMatch = matcher.group(0)
            val title = matcher.group(1) ?: ""
            val url = matcher.group(2) ?: ""

            val start = matcher.start() - offset
            val end = matcher.end() - offset

            val iconSpan = getAppIconSpan(context, url)
            val replacement = if (iconSpan != null) " $title " else title
            builder.replace(start, end, replacement)

            val newEnd = start + replacement.length

            if (iconSpan != null) {
                builder.setSpan(iconSpan, start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openUrl(context, url)
                }
            }
            builder.setSpan(clickableSpan, start, newEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(Typeface.BOLD), start, newEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(ForegroundColorSpan(Color.parseColor("#38BDF8")), start, newEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            offset += (fullMatch.length - replacement.length)
        }

        // 2. Phân tích các URL trần độc lập (https://... không nằm trong thẻ markdown [])
        val rawUrlMatcher = Pattern.compile("""(https?://[^\s\)\"\'<>]+)""").matcher(builder.toString())
        val rawMatches = mutableListOf<Triple<Int, Int, String>>()
        while (rawUrlMatcher.find()) {
            val start = rawUrlMatcher.start()
            val end = rawUrlMatcher.end()
            val url = rawUrlMatcher.group(1) ?: ""
            val existingSpans = builder.getSpans(start, end, ClickableSpan::class.java)
            if (existingSpans.isEmpty()) {
                rawMatches.add(Triple(start, end, url))
            }
        }

        for ((start, end, url) in rawMatches) {
            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openUrl(context, url)
                }
            }
            builder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(ForegroundColorSpan(Color.parseColor("#38BDF8")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

/**
 * Trợ thủ xử lý sự kiện cảm ứng trên TextView:
 * Hỗ trợ đồng thời BÔI ĐEN CHỌN VĂN BẢN (Text Selection) VÀ NHẤN MỞ LINK (ClickableSpan).
 */
object LimiLinkTouchHelper {

    fun setup(textView: TextView) {
        textView.setTextIsSelectable(true)
        textView.linksClickable = true

        var downX = 0f
        var downY = 0f
        val touchSlop = ViewConfiguration.get(textView.context).scaledTouchSlop

        textView.setOnTouchListener { v, event ->
            val tv = v as? TextView ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.x - downX)
                    val dy = Math.abs(event.y - downY)
                    // Nếu là cú chạm nhấp nhanh (không phải thao tác vuốt bôi đen) và không có vùng chọn text đang kích hoạt
                    if (dx < touchSlop && dy < touchSlop && !tv.hasSelection()) {
                        val x = event.x.toInt() - tv.totalPaddingLeft + tv.scrollX
                        val y = event.y.toInt() - tv.totalPaddingTop + tv.scrollY
                        val layout = tv.layout
                        if (layout != null) {
                            val line = layout.getLineForVertical(y)
                            val off = layout.getOffsetForHorizontal(line, x.toFloat())
                            val spanned = tv.text as? android.text.Spanned
                            if (spanned != null) {
                                val spans = spanned.getSpans(off, off, ClickableSpan::class.java)
                                if (spans.isNotEmpty()) {
                                    spans[0].onClick(tv)
                                    return@setOnTouchListener true
                                }
                            }
                        }
                    }
                    false
                }
                else -> false
            }
        }
    }
}
