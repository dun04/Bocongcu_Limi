package com.xiaomi.fixnotification

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuUtils {

    const val SHIZUKU_REQ_CODE = 1001

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            if (Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Throwable) {
            false
        }
    }

    fun requestShizukuPermission() {
        try {
            if (isShizukuAvailable() && !hasShizukuPermission()) {
                Shizuku.requestPermission(SHIZUKU_REQ_CODE)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    fun execShizukuCommand(command: String): CommandResult {
        if (!hasShizukuPermission()) {
            return CommandResult(-1, "", "Lỗi: Chưa được cấp quyền Shizuku (ADB).")
        }

        return try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            // Đọc đồng thời cả 2 luồng stdout và stderr trong thread riêng biệt để chống deadlock tràn pipe buffer
            val stdoutThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    var count = 0
                    while (reader.readLine().also { line = it } != null) {
                        if (count < 10000) {
                            stdoutBuilder.append(line).append("\n")
                            count++
                        }
                    }
                    reader.close()
                } catch (_: Throwable) {}
            }

            val stderrThread = Thread {
                try {
                    val errReader = BufferedReader(InputStreamReader(process.errorStream))
                    var line: String?
                    var count = 0
                    while (errReader.readLine().also { line = it } != null) {
                        if (count < 10000) {
                            stderrBuilder.append(line).append("\n")
                            count++
                        }
                    }
                    errReader.close()
                } catch (_: Throwable) {}
            }

            stdoutThread.start()
            stderrThread.start()

            var exitCode = -1
            var threadError: Throwable? = null
            val waitThread = Thread {
                try {
                    exitCode = process.waitFor()
                } catch (e: Throwable) {
                    threadError = e
                }
            }
            waitThread.start()
            waitThread.join(10000) // Timeout an toàn 10s

            if (waitThread.isAlive) {
                try { process.destroy() } catch (_: Throwable) {}
                try { waitThread.interrupt() } catch (_: Throwable) {}
                return CommandResult(-1, stdoutBuilder.toString().trim(), "Lỗi: Lệnh chạy quá thời gian chờ (Timeout 10s).")
            }

            stdoutThread.join(600)
            stderrThread.join(600)

            // Khi waitThread đã kết thúc, tiến trình chắc chắn đã hoàn tất -> exitValue() gọi an toàn 100%
            if (exitCode == -1 && threadError == null) {
                exitCode = try { process.exitValue() } catch (_: Throwable) { 0 }
            }

            CommandResult(exitCode, stdoutBuilder.toString().trim(), stderrBuilder.toString().trim())
        } catch (e: Throwable) {
            CommandResult(-1, "", "Exception: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }
}
