package com.xiaomi.fixnotification

import java.util.concurrent.Executors

/**
 * Đo FrameTime từng khung hình + Jank / BigJank + FPS theo chuẩn PerfDog & Scene.
 *
 * Nguồn dữ liệu: `dumpsys SurfaceFlinger --latency <layer game>` (quyền ADB qua Shizuku – giống PerfDog non-root).
 * Đọc cột actualPresent (thời điểm khung hình THỰC SỰ lên panel màn hình) để tính FrameTime và FPS thực tế của engine game.
 *
 * Tối ưu đặc biệt cho HyperOS 3.0 / 3.1 / 3.3 (Android 15 & Android 16):
 *  - Hỗ trợ kiến trúc BLASTBufferQueue (`BLAST SurfaceView[...]#0`, `#1`...)
 *  - Chu kỳ poll 500ms chống tràn bộ đệm 127 dòng của SurfaceFlinger ở 120Hz/144Hz
 *  - Bắt đúng layer Render thực tế của game, lọc bỏ layer UI/DimLayer tĩnh
 *  - Phân biệt Game Pause / Loading màn hình tĩnh vs Drop FPS do giật lag
 *  - Bộ lọc Adaptive Smoothing: làm mượt nhẹ khi ổn định, cập nhật tức thì khi có Drop FPS
 */
object FrameJankTracker {

    data class Snapshot(
        val fps: Float,
        val frames: Int,
        val jank: Int,
        val bigJank: Int,
        val avgFrameTimeMs: Float,
        val maxFrameTimeMs: Float
    )

    private const val JANK_THRESHOLD_NS = 83_333_333L       // > 2 frame ở 24fps
    private const val BIG_JANK_THRESHOLD_NS = 125_000_000L   // > 3 frame ở 24fps
    // Khoảng trống quá 1.0 giây (game pause / menu tĩnh / chuyển cảnh loading) -> chuỗi mới, không tính là Jank
    private const val MAX_VALID_FRAME_NS = 1_000_000_000L
    private const val PENDING_FENCE = Long.MAX_VALUE
    // 500ms lấy mẫu một lần: ở 120 FPS chỉ có ~60 frames, nằm trọn trong 127 dòng của SurfaceFlinger, chống tràn buffer 100%
    private const val POLL_INTERVAL_MS = 500L

    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    @Volatile private var running = false
    @Volatile private var targetPkg: String? = null

    // Trạng thái chuỗi frame (chỉ truy cập trong luồng poll)
    private var layer: String? = null
    private var layerPkg: String? = null
    private var layerResolvedAtMs = 0L
    private var lastNewFrameAtMs = 0L
    private var lastPresentNs = 0L
    private val recentFrameTimes = ArrayDeque<Long>(3)

    // Cửa sổ trượt 1.0 giây lưu mốc thời gian nanosecond để tính FPS chuẩn PerfDog / Scene
    private val slidingWindowNs = ArrayDeque<Long>(256)
    @Volatile private var currentCalculatedFps = 0f
    private var smoothedFps = 0f

    // Bộ tích luỹ giữa 2 lần drain() (truy cập dưới lock)
    private var accFrames = 0
    private var accJank = 0
    private var accBigJank = 0
    private var accSumNs = 0L
    private var accMaxNs = 0L

    fun start() {
        if (running) return
        running = true
        executor.execute {
            while (running) {
                try {
                    pollOnce()
                } catch (_: Throwable) {
                    layer = null
                }
                try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: Throwable) {}
            }
        }
    }

    fun stop() {
        running = false
        targetPkg = null
        synchronized(lock) {
            currentCalculatedFps = 0f
            smoothedFps = 0f
            slidingWindowNs.clear()
        }
    }

    /** Package game đang ở foreground (null = không ở trong game). */
    fun setTarget(pkg: String?) {
        targetPkg = pkg
    }

    /** Lấy FPS thời gian thực hiện tại của game (đã qua bộ lọc thích ứng). */
    fun getCurrentFps(): Float? {
        val f = currentCalculatedFps
        return if (f >= 5f) f else null
    }

    /** Lấy số liệu tích luỹ kể từ lần drain trước rồi xoá về 0. */
    fun drain(): Snapshot = synchronized(lock) {
        val snap = Snapshot(
            fps = currentCalculatedFps,
            frames = accFrames,
            jank = accJank,
            bigJank = accBigJank,
            avgFrameTimeMs = if (accFrames > 0) (accSumNs / accFrames) / 1_000_000f else 0f,
            maxFrameTimeMs = accMaxNs / 1_000_000f
        )
        accFrames = 0; accJank = 0; accBigJank = 0; accSumNs = 0L; accMaxNs = 0L
        snap
    }

    private fun resetChain() {
        lastPresentNs = 0L
        recentFrameTimes.clear()
        slidingWindowNs.clear()
    }

    private fun pollOnce() {
        val pkg = targetPkg
        if (pkg.isNullOrBlank() || !ShizukuUtils.hasShizukuPermission()) {
            if (layer != null) {
                layer = null
                layerPkg = null
                resetChain()
                currentCalculatedFps = 0f
                smoothedFps = 0f
            }
            return
        }

        val now = System.currentTimeMillis()
        if (pkg != layerPkg) {
            layer = null
            layerPkg = pkg
            resetChain()
            currentCalculatedFps = 0f
            smoothedFps = 0f
        }

        // Tìm lại layer nếu chưa có hoặc layer hiện tại không ra frame mới quá 800ms (game đổi Surface khi vào trận trên HyperOS)
        val needResolve = layer == null ||
                (now - lastNewFrameAtMs > 800L && now - layerResolvedAtMs > 800L)
        if (needResolve) {
            val resolved = resolveLayer(pkg)
            layerResolvedAtMs = now
            if (resolved != layer) {
                layer = resolved
                resetChain()
            }
        }

        val l = layer ?: return
        val timestamps = readPresentTimestamps(l)
        if (timestamps.isNullOrEmpty()) {
            // Không có timestamp mới: nếu đã quá 1.2s không có frame mới -> game đang pause/loading
            if (now - lastNewFrameAtMs > 1200L) {
                currentCalculatedFps = 0f
                smoothedFps = 0f
            }
            return
        }
        processTimestamps(timestamps, now)
    }

    private fun processTimestamps(timestamps: List<Long>, nowMs: Long) {
        // Nếu frame cũ nhất trong buffer vẫn mới hơn mốc trước -> đã lỡ frame ở giữa, reset chuỗi để không nối sai
        if (lastPresentNs > 0L && timestamps.first() > lastPresentNs) {
            resetChain()
        }

        var frames = 0
        var jank = 0
        var bigJank = 0
        var sumNs = 0L
        var maxNs = 0L
        var sawNew = false

        for (t in timestamps) {
            if (t <= lastPresentNs) continue
            sawNew = true

            // Thêm vào cửa sổ trượt 1.0 giây để tính FPS chuẩn
            slidingWindowNs.addLast(t)

            if (lastPresentNs > 0L) {
                val ft = t - lastPresentNs
                if (ft in 1..MAX_VALID_FRAME_NS) {
                    frames++
                    sumNs += ft
                    if (ft > maxNs) maxNs = ft

                    if (recentFrameTimes.size == 3) {
                        val avg3 = recentFrameTimes.sum() / 3
                        if (ft > avg3 * 2) {
                            if (ft > BIG_JANK_THRESHOLD_NS) {
                                bigJank++
                                jank++
                            } else if (ft > JANK_THRESHOLD_NS) {
                                jank++
                            }
                        }
                    }
                    recentFrameTimes.addLast(ft)
                    if (recentFrameTimes.size > 3) recentFrameTimes.removeFirst()
                } else {
                    // ft > 1.0s: Game pause hoặc loading chuyển cảnh -> reset chuỗi frameTime, không phạt jank
                    recentFrameTimes.clear()
                }
            }
            lastPresentNs = t
        }

        if (sawNew) {
            lastNewFrameAtMs = nowMs

            // Xoá các mốc cũ hơn 1.0 giây trong cửa sổ trượt
            val windowCutoff = lastPresentNs - 1_000_000_000L
            while (slidingWindowNs.isNotEmpty() && slidingWindowNs.first() < windowCutoff) {
                slidingWindowNs.removeFirst()
            }

            // Tính toán FPS thực tế từ cửa sổ trượt
            if (slidingWindowNs.size >= 2) {
                val spanSec = (slidingWindowNs.last() - slidingWindowNs.first()) / 1_000_000_000.0
                val rawFps = if (spanSec in 0.35..1.5) {
                    ((slidingWindowNs.size - 1) / spanSec).toFloat()
                } else {
                    slidingWindowNs.size.toFloat()
                }

                if (rawFps in 5f..240f) {
                    // Adaptive Smoothing (Bộ lọc thích ứng):
                    // - Dao động nhẹ (|diff| <= 2.5): Làm mượt nhẹ để hiển thị êm ái
                    // - Drop FPS mạnh hoặc tăng vọt (|diff| > 2.5): Cập nhật tức thì không trễ
                    smoothedFps = if (smoothedFps <= 0f || Math.abs(rawFps - smoothedFps) > 2.5f) {
                        rawFps
                    } else {
                        smoothedFps * 0.65f + rawFps * 0.35f
                    }
                    currentCalculatedFps = (Math.round(smoothedFps * 10f) / 10f).coerceIn(5f, 240f)
                }
            }
        } else {
            // Không có frame mới nào trong lượt đọc này
            if (nowMs - lastNewFrameAtMs > 1200L) {
                currentCalculatedFps = 0f
                smoothedFps = 0f
            }
        }

        if (frames > 0) {
            synchronized(lock) {
                accFrames += frames
                accJank += jank
                accBigJank += bigJank
                accSumNs += sumNs
                if (maxNs > accMaxNs) accMaxNs = maxNs
            }
        }
    }

    /** Đọc cột actualPresent (cột 1) của layer, fallback sang col0 (desired) hoặc col2 (ready), bỏ frame đang chờ fence (INT64_MAX) và dòng rỗng. */
    private fun readPresentTimestamps(layerName: String): List<Long>? {
        val res = ShizukuUtils.execShizukuCommandArgs(arrayOf("dumpsys", "SurfaceFlinger", "--latency", layerName))
        if (res.exitCode != 0 || res.stdout.isBlank()) return null
        val lines = res.stdout.lines()
        if (lines.size < 2) return null

        val out = ArrayList<Long>(128)
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val parts = line.split(WHITESPACE)
            if (parts.size < 2) continue
            val col0 = parts[0].toLongOrNull() ?: 0L
            val col1 = parts[1].toLongOrNull() ?: 0L
            val col2 = if (parts.size >= 3) parts[2].toLongOrNull() ?: 0L else 0L

            // Ưu tiên col1 (actualPresentTime: thời điểm thực tế HWC render lên màn hình)
            val t = when {
                col1 in 1_000_000L..(PENDING_FENCE - 1) -> col1
                col0 in 1_000_000L..(PENDING_FENCE - 1) -> col0
                col2 in 1_000_000L..(PENDING_FENCE - 1) -> col2
                else -> 0L
            }
            if (t <= 0L) continue
            out.add(t)
        }
        if (out.size < 2) return null
        out.sort()
        // Loại trùng lặp (duplicate frames do VSYNC lặp)
        val dedup = ArrayList<Long>(out.size)
        for (t in out) if (dedup.isEmpty() || dedup.last() != t) dedup.add(t)
        return dedup
    }

    /**
     * Tự động định vị CHÍNH XÁC layer thuộc về Game trên HyperOS 3.0 / 3.1 / 3.3 và Android 14/15/16.
     * Xử lý trọn vẹn: BLAST SurfaceView, SurfaceView[...#0], alias của Liên Quân, Genshin, PUBG, FreeFire, Unity, Unreal.
     */
    private fun resolveLayer(pkg: String): String? {
        val listRes = ShizukuUtils.execShizukuCommandArgs(arrayOf("dumpsys", "SurfaceFlinger", "--list"))
        if (listRes.stdout.isBlank()) return null
        val lastPart = if (pkg.contains(".")) pkg.substringAfterLast(".") else pkg
        val allLayers = listRes.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Bộ alias nhận diện game phổ biến khi tên Layer khác tên package
        val isAov = pkg.contains("kgvn", ignoreCase = true) || pkg.contains("lienquan", ignoreCase = true)
        val isPubg = pkg.contains("pubg", ignoreCase = true)
        val isFreefire = pkg.contains("freefire", ignoreCase = true)
        val isGenshin = pkg.contains("genshin", ignoreCase = true) || pkg.contains("yuanshen", ignoreCase = true) || pkg.contains("mihoyo", ignoreCase = true)

        val gameLayers = allLayers.filter { line ->
            val matchPkg = line.contains(pkg, ignoreCase = true) ||
                    (lastPart.length >= 3 && line.contains(lastPart, ignoreCase = true)) ||
                    (isAov && (line.contains("sgame", ignoreCase = true) || line.contains("tencent", ignoreCase = true))) ||
                    (isPubg && (line.contains("ue4", ignoreCase = true) || line.contains("epicgames", ignoreCase = true))) ||
                    (isFreefire && line.contains("dts", ignoreCase = true)) ||
                    (isGenshin && (line.contains("mihoyo", ignoreCase = true) || line.contains("cognosphere", ignoreCase = true)))

            matchPkg &&
            !line.contains("Background for", ignoreCase = true) &&
            !line.contains("SnapshotStartingWindow", ignoreCase = true) &&
            !line.contains("DimLayer", ignoreCase = true) &&
            !line.contains("InputConsumer", ignoreCase = true) &&
            !line.contains("color-fade", ignoreCase = true)
        }

        val candidates = if (gameLayers.isNotEmpty()) gameLayers else {
            allLayers.filter { line ->
                (line.contains("SurfaceView", ignoreCase = true) || line.contains("BLAST", ignoreCase = true)) &&
                !line.contains("Background for", ignoreCase = true) &&
                !line.contains("SnapshotStartingWindow", ignoreCase = true) &&
                !line.contains("DimLayer", ignoreCase = true) &&
                !line.contains("InputConsumer", ignoreCase = true) &&
                !line.contains("color-fade", ignoreCase = true) &&
                !line.contains("systemui", ignoreCase = true) &&
                !line.contains("miui.home", ignoreCase = true) &&
                !line.contains("launcher", ignoreCase = true) &&
                !line.contains("fixnotification", ignoreCase = true) &&
                !line.contains("StatusBar", ignoreCase = true) &&
                !line.contains("NavigationBar", ignoreCase = true)
            }
        }.sortedWith(
            // Ưu tiên 1: SurfaceView (Layer render 3D game)
            compareByDescending<String> { it.contains("SurfaceView", ignoreCase = true) }
                // Ưu tiên 2: BLASTBufferQueue trên HyperOS 3
                .thenByDescending { it.contains("BLAST", ignoreCase = true) }
                // Ưu tiên 3: Layer có index buffer queue (#0, #1...)
                .thenByDescending { it.contains("#") }
                // Ưu tiên 4: Chứa Activity
                .thenByDescending { it.contains("Activity", ignoreCase = true) }
        ).take(5)

        if (candidates.isEmpty()) return null

        // Thử latency: Chọn layer có timestamp mới nhất và ĐANG THỰC SỰ RENDER
        var best: String? = null
        var bestNewest = 0L
        for (c in candidates) {
            val ts = readPresentTimestamps(c) ?: continue
            val newest = ts.lastOrNull() ?: continue
            if (ts.size >= 2 && newest > bestNewest) {
                bestNewest = newest
                best = c
            }
        }
        return best ?: candidates.firstOrNull()
    }

    private val WHITESPACE = "\\s+".toRegex()
}
