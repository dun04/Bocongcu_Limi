package com.xiaomi.fixnotification.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlin.math.min

/**
 * Utility for reading and parsing text files (.txt, .log, .md, .json, .csv)
 * and document files (.docx, .pdf) for Limi AI.
 * Zero external libraries required.
 */
object LimiDocumentReader {

    data class DocumentAttachment(
        val uri: Uri,
        val fileName: String,
        val fileSizeFormatted: String,
        val isDocx: Boolean = false,
        val isPdf: Boolean = false,
        val isText: Boolean = false,
        val extractedText: String? = null,
        val pageBitmaps: List<Bitmap> = emptyList()
    )

    fun readDocumentFromUri(context: Context, uri: Uri): DocumentAttachment? {
        val fileName = getFileName(context, uri) ?: "tài_liệu_${System.currentTimeMillis()}"
        val fileSize = getFileSize(context, uri)
        val sizeFormatted = formatFileSize(fileSize)
        val lowerName = fileName.lowercase()

        return try {
            when {
                lowerName.endsWith(".docx") -> {
                    val text = readDocxText(context, uri)
                    DocumentAttachment(
                        uri = uri,
                        fileName = fileName,
                        fileSizeFormatted = sizeFormatted,
                        isDocx = true,
                        extractedText = text
                    )
                }
                lowerName.endsWith(".pdf") -> {
                    val bitmaps = renderPdfToBitmaps(context, uri, maxPages = 3)
                    DocumentAttachment(
                        uri = uri,
                        fileName = fileName,
                        fileSizeFormatted = sizeFormatted,
                        isPdf = true,
                        pageBitmaps = bitmaps,
                        extractedText = "[Tài liệu PDF: $fileName gồm ${bitmaps.size} trang ảnh phân tích]"
                    )
                }
                else -> {
                    // Plain text (.txt, .log, .md, .json, .csv, .xml, .yaml, .prop, etc.)
                    val text = readPlainText(context, uri)
                    DocumentAttachment(
                        uri = uri,
                        fileName = fileName,
                        fileSizeFormatted = sizeFormatted,
                        isText = true,
                        extractedText = text
                    )
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private fun readPlainText(context: Context, uri: Uri, maxChars: Int = 45000): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
                val sb = StringBuilder()
                val buffer = CharArray(2048)
                var read: Int
                while (reader.read(buffer).also { read = it } != -1) {
                    sb.append(buffer, 0, read)
                    if (sb.length >= maxChars) {
                        sb.append("\n...[Đã cắt bớt nội dung vì vượt quá giới hạn $maxChars ký tự]...")
                        break
                    }
                }
                sb.toString()
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun readDocxText(context: Context, uri: Uri, maxChars: Int = 45000): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val zip = ZipInputStream(stream)
                var entry = zip.nextEntry
                var docXml: String? = null
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val reader = BufferedReader(InputStreamReader(zip, StandardCharsets.UTF_8))
                        val sb = StringBuilder()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            sb.append(line).append(" ")
                            if (sb.length > maxChars * 2) break
                        }
                        docXml = sb.toString()
                        break
                    }
                    entry = zip.nextEntry
                }

                if (docXml != null) {
                    cleanXmlToText(docXml, maxChars)
                } else null
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun cleanXmlToText(xml: String, maxChars: Int): String {
        val text = xml
            .replace(Regex("<w:p.*?>"), "\n")
            .replace(Regex("<w:tab.*?>"), "\t")
            .replace(Regex("<w:br.*?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .trim()

        return if (text.length > maxChars) {
            text.substring(0, maxChars) + "\n...[Đã cắt bớt vì tài liệu quá dài]..."
        } else text
    }

    private fun renderPdfToBitmaps(context: Context, uri: Uri, maxPages: Int = 3): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                renderer = PdfRenderer(pfd)
                val pageCount = min(renderer.pageCount, maxPages)
                for (i in 0 until pageCount) {
                    val page = renderer.openPage(i)
                    val width = (page.width * 1.5f).toInt()
                    val height = (page.height * 1.5f).toInt()
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmaps.add(bmp)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
        return bitmaps
    }

    fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (_: Throwable) {}
        return name ?: uri.lastPathSegment
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size: Long = 0
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Throwable) {}
        return size
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes <= 0 -> "0 B"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
        }
    }
}
