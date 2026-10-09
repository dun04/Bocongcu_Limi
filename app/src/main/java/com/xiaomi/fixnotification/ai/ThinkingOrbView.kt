package com.xiaomi.fixnotification.ai

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * Native Android implementation of ThinkingOrb from Libraries.dev / thinking-orbs.
 * Replaces traditional spinners with 9 hand-tuned animated 3D dot states on Canvas.
 * Zero external dependencies, 60-120fps hardware accelerated.
 */
class ThinkingOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class OrbState(val rawValue: String, val label: String, internal val mode: OrbMode) {
        WORKING("working", "Working…", OrbMode.ORBITS),
        SEARCHING("searching", "Searching…", OrbMode.GLOBE),
        SOLVING("solving", "Solving…", OrbMode.RUBIK),
        LISTENING("listening", "Listening…", OrbMode.WAVE),
        CONNECTING("connecting", "Connecting…", OrbMode.WEB),
        WEAVING("weaving", "Weaving…", OrbMode.BRAID),
        COMPOSING("composing", "Composing…", OrbMode.RIBBON),
        BREATHING("breathing", "Thinking…", OrbMode.RING),
        SHAPING("shaping", "Shaping…", OrbMode.MORPH);

        companion object {
            fun fromString(value: String): OrbState {
                return entries.firstOrNull { it.rawValue.equals(value, ignoreCase = true) } ?: WORKING
            }
        }
    }

    enum class OrbSize(val value: Double) {
        PX64(64.0),
        PX20(20.0)
    }

    internal enum class OrbMode {
        ORBITS, GLOBE, RUBIK, WAVE, WEB, BRAID, RIBBON, RING, MORPH
    }

    // Properties matching thinking-orbs props
    var state: OrbState = OrbState.SEARCHING
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var orbSizePreset: OrbSize = OrbSize.PX64
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var speed: Double = 1.0
        set(value) {
            field = value
            invalidate()
        }

    var paused: Boolean = false
        set(value) {
            field = value
            if (!value) invalidate()
        }

    var isDark: Boolean? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Optional tint color to blend with the dots (e.g. Limi primary cyan #00D2FF).
     * If null, renders in clean monochrome pearl/silver white as tuned in Libraries.dev.
     */
    var tintColor: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val startClockTime = SystemClock.uptimeMillis()

    fun setOrbStateByName(name: String) {
        state = OrbState.fromString(name)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE && !paused) {
            postInvalidateOnAnimation()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && !paused) {
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val side = min(w, h).toDouble()
        // Automatically choose the closer preset if not explicitly forced
        val chosenPreset = if (side <= 36.0) OrbSize.PX20 else OrbSize.PX64
        val targetSize = chosenPreset.value

        val resolved = resolvePreset(state, chosenPreset)
        val effSpeed = resolved.speed * speed

        val elapsedSec = (SystemClock.uptimeMillis() - startClockTime) / 1000.0
        val t = elapsedSec * effSpeed

        val frame = generateOrbFrame(resolved, targetSize, t)

        // Center and scale to fill View canvas smoothly
        val scale = side / targetSize
        val cx = w / 2f
        val cy = h / 2f

        canvas.save()
        canvas.translate(cx - (targetSize.toFloat() * scale.toFloat()) / 2f, cy - (targetSize.toFloat() * scale.toFloat()) / 2f)
        canvas.scale(scale.toFloat(), scale.toFloat())

        val darkTheme = isDark ?: isSystemInDarkTheme()

        // 1. Draw Lines (edges)
        for (l in frame.lines) {
            linePaint.color = calculateInkColor(l.white, l.a, darkTheme, tintColor)
            linePaint.strokeWidth = l.w.toFloat()
            canvas.drawLine(l.x1.toFloat(), l.y1.toFloat(), l.x2.toFloat(), l.y2.toFloat(), linePaint)
        }

        // 2. Draw Dots (z-sorted)
        for (d in frame.dots) {
            dotPaint.color = calculateInkColor(d.white, d.a, darkTheme, tintColor)
            canvas.drawCircle(d.x.toFloat(), d.y.toFloat(), d.r.toFloat(), dotPaint)
        }

        canvas.restore()

        // Request next frame for fluid animation
        if (visibility == VISIBLE && isAttachedToWindow && !paused) {
            postInvalidateOnAnimation()
        }
    }

    private fun isSystemInDarkTheme(): Boolean {
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    private fun calculateInkColor(white: Double, alpha: Double, isDarkTheme: Boolean, tint: Int?): Int {
        val w = min(1.0, max(0.0, white))
        val g = if (isDarkTheme) 1.0 - w else w
        val grayByte = (g * 255.0).roundToInt().coerceIn(0, 255)
        val aByte = (alpha * 255.0).roundToInt().coerceIn(0, 255)

        return if (tint != null) {
            val tr = Color.red(tint)
            val tg = Color.green(tint)
            val tb = Color.blue(tint)
            // Blend gray intensity with tint color
            val factor = grayByte / 255.0
            val r = (tr * factor).roundToInt().coerceIn(0, 255)
            val gCol = (tg * factor).roundToInt().coerceIn(0, 255)
            val b = (tb * factor).roundToInt().coerceIn(0, 255)
            Color.argb(aByte, r, gCol, b)
        } else {
            Color.argb(aByte, grayByte, grayByte, grayByte)
        }
    }

    // ---------------- ENGINE CORE PRIMITIVES ----------------

    internal data class Dot(
        val x: Double,
        val y: Double,
        val z: Double,
        var r: Double,
        val white: Double,
        val a: Double = 1.0
    )

    internal data class Line(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val white: Double,
        val a: Double = 1.0,
        val w: Double = 1.0
    )

    internal data class OrbFrame(
        val dots: List<Dot>,
        val lines: List<Line>
    )

    internal data class ResolvedPreset(
        val mode: OrbMode,
        val speed: Double,
        val opts: Map<String, Double>
    )

    companion object {
        private val cacheLock = Any()
        private val presetCache = mutableMapOf<String, ResolvedPreset>()

        private fun hashD(a: Double, b: Double): Double {
            val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
            return h - floor(h)
        }

        private fun vnoise(x: Double, y: Double): Double {
            val xi = floor(x)
            val yi = floor(y)
            var fx = x - xi
            var fy = y - yi
            fx = fx * fx * (3.0 - 2.0 * fx)
            fy = fy * fy * (3.0 - 2.0 * fy)
            val a = hashD(xi, yi)
            val b = hashD(xi + 1.0, yi)
            val c = hashD(xi, yi + 1.0)
            val d = hashD(xi + 1.0, yi + 1.0)
            return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
        }

        private fun fibDir(i: Int, n: Int): Triple<Double, Double, Double> {
            val golden = PI * (3.0 - sqrt(5.0))
            val y = 1.0 - (2.0 * (i.toDouble() + 0.5)) / n.toDouble()
            val rad = sqrt(max(0.0, 1.0 - y * y))
            val a = i.toDouble() * golden
            return Triple(rad * cos(a), y, rad * sin(a))
        }

        private fun angleDelta(a: Double, b: Double): Double {
            return atan2(sin(a - b), cos(a - b))
        }

        private fun lerp(a: Double, b: Double, f: Double): Double = a + (b - a) * f
        private fun frac(x: Double): Double = x - floor(x)

        private class Projector(yaw: Double, tilt: Double, val cx: Double, val cy: Double, val scale: Double) {
            val st = sin(tilt); val ct = cos(tilt)
            val sy = sin(yaw); val cyw = cos(yaw)

            operator fun invoke(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
                val x1 = x * cyw + z * sy
                val z1 = -x * sy + z * cyw
                val y1 = y * ct - z1 * st
                val z2 = y * st + z1 * ct
                return Triple(cx + x1 * scale, cy - y1 * scale, z2)
            }
        }

        private fun radiusScale(size: Double, pow: Double): Double {
            return (size / 300.0).pow(pow)
        }

        private fun finalizeFrame(dots: List<Dot>, lines: List<Line>, rMin: Double = 0.3): OrbFrame {
            val visible = ArrayList<Dot>(dots.size)
            for (d in dots) {
                if (d.a >= 0.02) {
                    val clampedR = max(rMin, d.r)
                    visible.add(d.copy(r = clampedR))
                }
            }
            // Stable sort by Z depth (far to near)
            val sorted = visible.withIndex()
                .sortedWith { a, b ->
                    if (a.value.z != b.value.z) a.value.z.compareTo(b.value.z)
                    else a.index.compareTo(b.index)
                }
                .map { it.value }

            return OrbFrame(sorted, lines.filter { it.a >= 0.02 })
        }

        // ---------------- SPEC PRESETS ----------------

        private val baseProfiles: Map<OrbMode, Map<String, Double>> = mapOf(
            OrbMode.ORBITS to mapOf("orbitN" to 12.0, "ghostN" to 40.0, "ghostR" to 0.9, "ghostA" to 0.5, "particles" to 3.0, "partR" to 1.2, "partRDepth" to 1.6, "rsPow" to 0.6, "rMin" to 0.3),
            OrbMode.GLOBE to mapOf("latRings" to 17.0, "lonDensity" to 44.0, "rBase" to 0.6, "rDepth" to 1.7, "rBoost" to 1.0, "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3),
            OrbMode.RUBIK to mapOf("latRings" to 15.0, "lonDensity" to 40.0, "moveCount" to 14.0, "rBase" to 0.6, "rDepth" to 1.7, "rActive" to 0.3, "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3),
            OrbMode.WAVE to mapOf("rings" to 15.0, "lonDensity" to 40.0, "rBase" to 0.6, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3),
            OrbMode.WEB to mapOf("nodeN" to 30.0, "thr" to 0.72, "signals" to 5.0, "nodeR" to 1.4, "nodeRDepth" to 1.8, "lineW" to 0.8, "rsPow" to 0.6, "rMin" to 0.3),
            OrbMode.BRAID to mapOf("strandN" to 52.0, "turns" to 3.0, "ghostN" to 150.0, "rBase" to 1.2, "rDepth" to 1.8, "rsPow" to 0.6, "rMin" to 0.3),
            OrbMode.RIBBON to mapOf("lanes" to 5.0, "segs" to 88.0, "ghostN" to 150.0, "rBase" to 1.1, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3),
            OrbMode.RING to mapOf("lanes" to 5.0, "segs" to 88.0, "ghostN" to 0.0, "faceOn" to 1.0, "rBase" to 1.1, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3),
            OrbMode.MORPH to mapOf("rDot" to 0.021, "iconD" to 1.0, "rMin" to 0.25)
        )

        private data class ModePreset(val speed: Double, val count: Double, val size: Double, val extra: Map<String, Double> = emptyMap())

        private val presets: Map<OrbMode, Map<OrbSize, ModePreset>> = mapOf(
            OrbMode.ORBITS to mapOf(
                OrbSize.PX64 to ModePreset(1.885, 1.0, 1.0),
                OrbSize.PX20 to ModePreset(3.9, 0.238, 2.4)
            ),
            OrbMode.GLOBE to mapOf(
                OrbSize.PX64 to ModePreset(2.015, 0.42, 1.15, mapOf("scanMul" to 4.08, "dimBase" to 0.45)),
                OrbSize.PX20 to ModePreset(2.665, 0.105, 1.75, mapOf("scanMul" to 4.335, "dimBase" to 0.45))
            ),
            OrbMode.RUBIK to mapOf(
                OrbSize.PX64 to ModePreset(1.82, 0.35, 1.05),
                OrbSize.PX20 to ModePreset(1.95, 0.088, 1.9)
            ),
            OrbMode.WAVE to mapOf(
                OrbSize.PX64 to ModePreset(4.388, 0.341, 1.0),
                OrbSize.PX20 to ModePreset(3.998, 0.105, 1.6)
            ),
            OrbMode.WEB to mapOf(
                OrbSize.PX64 to ModePreset(3.315, 1.35, 0.95),
                OrbSize.PX20 to ModePreset(6.63, 0.25, 1.52)
            ),
            OrbMode.BRAID to mapOf(
                OrbSize.PX64 to ModePreset(1.625, 0.5, 1.0),
                OrbSize.PX20 to ModePreset(2.75, 0.1125, 1.36)
            ),
            OrbMode.RIBBON to mapOf(
                OrbSize.PX64 to ModePreset(2.34, 0.25, 0.85, mapOf("spin" to 0.0, "bandMul" to 3.9, "wobMul" to 1.0)),
                OrbSize.PX20 to ModePreset(3.12, 0.051, 1.073, mapOf("spin" to 0.0, "bandMul" to 4.94, "wobMul" to 1.0))
            ),
            OrbMode.RING to mapOf(
                OrbSize.PX64 to ModePreset(3.24, 0.25, 0.956, mapOf("spin" to 0.0, "bandMul" to 3.627, "wobMul" to 0.368)),
                OrbSize.PX20 to ModePreset(3.78, 0.028, 1.622, mapOf("spin" to 0.0, "bandMul" to 3.968, "wobMul" to 0.565))
            ),
            OrbMode.MORPH to mapOf(
                OrbSize.PX64 to ModePreset(2.405, 0.702, 0.395, mapOf("spread" to 1.45)),
                OrbSize.PX20 to ModePreset(2.08, 0.53, 1.011, mapOf("spread" to 1.45))
            )
        )

        private val countPairs = listOf(
            "latRings" to "lonDensity",
            "rings" to "lonDensity",
            "lanes" to "segs"
        )
        private val countKeys = listOf("orbitN", "ghostN", "nodeN", "strandN", "signals")
        private val iconDensityKeys = listOf("iconD")
        private val radiusKeys = listOf("rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth", "nodeR", "nodeRDepth")

        private fun scaleCounts(opts: Map<String, Double>, scale: Double): Map<String, Double> {
            val out = opts.toMutableMap()
            val done = mutableSetOf<String>()
            val rt = sqrt(scale)
            for ((a, b) in countPairs) {
                val va = out[a]
                val vb = out[b]
                if (va != null && vb != null && !done.contains(a) && !done.contains(b)) {
                    out[a] = max(2.0, (va * rt).roundToInt().toDouble())
                    out[b] = max(2.0, (vb * rt).roundToInt().toDouble())
                    done.add(a); done.add(b)
                }
            }
            for (k in countKeys) {
                val v = out[k]
                if (v != null && v != 0.0 && !done.contains(k)) {
                    out[k] = max(1.0, (v * scale).roundToInt().toDouble())
                }
            }
            for (k in iconDensityKeys) {
                val v = out[k]
                if (v != null) { out[k] = max(0.02, v * scale) }
            }
            return out
        }

        private fun scaleRadii(opts: Map<String, Double>, scale: Double): Map<String, Double> {
            val out = opts.toMutableMap()
            for (k in radiusKeys) {
                val v = out[k]
                if (v != null) { out[k] = v * scale }
            }
            out["rSizeMul"] = (out["rSizeMul"] ?: 1.0) * scale
            return out
        }

        internal fun resolvePreset(state: OrbState, size: OrbSize): ResolvedPreset {
            val key = "${state.rawValue}-${size.value}"
            synchronized(cacheLock) {
                presetCache[key]?.let { return it }

                val mode = state.mode
                val preset = presets[mode]!![size]!!
                var opts = baseProfiles[mode]!!
                if (preset.count != 1.0) opts = scaleCounts(opts, preset.count)
                if (preset.size != 1.0) opts = scaleRadii(opts, preset.size)
                opts = opts.toMutableMap().apply { putAll(preset.extra) }

                val resolved = ResolvedPreset(mode = mode, speed = preset.speed, opts = opts)
                presetCache[key] = resolved
                return resolved
            }
        }

        // ---------------- MODES GEOMETRY ----------------

        private fun generateOrbFrame(preset: ResolvedPreset, size: Double, t: Double): OrbFrame {
            val o = preset.opts
            return when (preset.mode) {
                OrbMode.ORBITS -> frameOrbits(size, t, o)
                OrbMode.GLOBE -> frameGlobe(size, t, o)
                OrbMode.RUBIK -> frameRubik(size, t, o)
                OrbMode.WAVE -> frameWave(size, t, o)
                OrbMode.WEB -> frameWeb(size, t, o)
                OrbMode.BRAID -> frameBraid(size, t, o)
                OrbMode.RIBBON, OrbMode.RING -> frameRibbon(size, t, o)
                OrbMode.MORPH -> frameMorph(size, t, o)
            }
        }

        // 1. Orbits (working)
        private fun frameOrbits(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
            val cx = size / 2.0
            val cy = size / 2.0
            val rVal = (size / 2.0) * 0.82
            val pt = Projector(yaw = t * 0.12, tilt = 0.3, cx = cx, cy = cy, scale = 1.0)
            val rs = radiusScale(size, pow = o["rsPow"] ?: 0.6)

            val dots = mutableListOf<Dot>()
            val orbitN = (o["orbitN"] ?: 12.0).toInt()
            val ghostN = (o["ghostN"] ?: 40.0).toInt()
            val particles = (o["particles"] ?: 3.0).toInt()

            for (orb in 0 until orbitN) {
                val h1 = hashD(orb.toDouble(), 1.7)
                val h2 = hashD(orb.toDouble(), 5.2)
                val h3 = hashD(orb.toDouble(), 8.9)
                val ro = rVal * (0.45 + 0.52 * h1)
                val th = h1 * 2.0 * PI
                val phi = acos(2.0 * h2 - 1.0)
                val nx = sin(phi) * cos(th)
                val ny = cos(phi)
                val nz = sin(phi) * sin(th)
                var ux = -ny
                var uy = nx
                val uz = 0.0
                val ul = max(1e-6, sqrt(ux * ux + uy * uy))
                ux /= ul
                uy /= ul
                val vx = ny * uz - nz * uy
                val vy = nz * ux - nx * uz
                val vz = nx * uy - ny * ux
                val sp = (0.25 + 0.55 * h3) * (if (h3 > 0.5) 1.0 else -1.0)

                for (k in 0 until ghostN) {
                    val a = (k.toDouble() / ghostN.toDouble()) * 2.0 * PI
                    val (px, py, z) = pt(
                        (ux * cos(a) + vx * sin(a)) * ro,
                        (uy * cos(a) + vy * sin(a)) * ro,
                        (uz * cos(a) + vz * sin(a)) * ro
                    )
                    val depth = (z / ro + 1.0) / 2.0
                    dots.add(Dot(
                        x = px, y = py, z = z,
                        r = (o["ghostR"] ?: 0.9) * rs,
                        white = 0.72,
                        a = (o["ghostA"] ?: 0.5) * (0.4 + 0.6 * depth)
                    ))
                }

                for (m in 0 until particles) {
                    val a = t * sp + (m.toDouble() / particles.toDouble()) * 2.0 * PI + h2 * 6.0
                    val (px, py, z) = pt(
                        (ux * cos(a) + vx * sin(a)) * ro,
                        (uy * cos(a) + vy * sin(a)) * ro,
                        (uz * cos(a) + vz * sin(a)) * ro
                    )
                    val depth = (z / ro + 1.0) / 2.0
                    dots.add(Dot(
                        x = px, y = py, z = z,
                        r = ((o["partR"] ?: 1.2) + (o["partRDepth"] ?: 1.6) * depth) * rs,
                        white = 0.3 - 0.22 * depth
                    ))
                }
            }
            return finalizeFrame(dots, emptyList(), rMin = o["rMin"] ?: 0.3)
        }

        // 2. Globe (searching)
        private fun frameGlobe(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
            val spin = 0.5
            val cx = size / 2.0
            val cy = size / 2.0
            val radius = (size / 2.0) * 0.82
            val tilt = 0.4 + 0.06 * sin(t * 0.35)
            val pt = Projector(yaw = t * spin, tilt = tilt, cx = cx, cy = cy, scale = radius)
            val scan = t * (spin + (1.7 - spin) * (o["scanMul"] ?: 1.0))
            val rs = radiusScale(size, pow = o["rsPow"] ?: 0.6)
            val dimBase = o["dimBase"] ?: 1.0

            val dots = mutableListOf<Dot>()
            val latRings = (o["latRings"] ?: 17.0).toInt()
            val lonDensity = o["lonDensity"] ?: 44.0
            for (li in 0..latRings) {
                val lat = -PI / 2.0 + (li.toDouble() / latRings.toDouble()) * PI
                val cosLat = cos(lat)
                val sinLat = sin(lat)
                val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
                for (lj in 0 until lonCount) {
                    val lon = (lj.toDouble() / lonCount.toDouble()) * 2.0 * PI
                    val (px, py, z) = pt(cosLat * cos(lon), sinLat, cosLat * sin(lon))
                    val depth = (z + 1.0) / 2.0
                    val d = angleDelta(lon + t * spin, scan)
                    val boost = exp(-(d * d) / 0.18) * max(0.0, z)
                    dots.add(Dot(
                        x = px, y = py, z = z,
                        r = ((o["rBase"] ?: 0.6) + (o["rDepth"] ?: 1.7) * depth + (o["rBoost"] ?: 1.0) * boost) * rs,
                        white = (o["inkFar"] ?: 0.62) - (o["inkSpan"] ?: 0.54) * depth,
                        a = dimBase + (1.0 - dimBase) * min(1.0, boost)
                    ))
                }
            }
            return finalizeFrame(dots, emptyList(), rMin = o["rMin"] ?: 0.3)
        }

        // 3. Rubik (solving)
        private data class RubikMove(val axis: Int, val lo: Double, val hi: Double, val ang: Double)
        private data class RubikSolveCycle(val amount: DoubleArray, val active: Int)

        private fun solveCycle(time: Double, count: Int, slotDur: Double, rest: Double): RubikSolveCycle {
            val cyc = 2.0 * count.toDouble() * slotDur + rest
            val tc = time % cyc
            val amount = DoubleArray(count)
            var active = -1
            if (tc < 2.0 * count.toDouble() * slotDur) {
                val slot = floor(tc / slotDur).toInt()
                val p = (tc - slot.toDouble() * slotDur) / slotDur
                val cl = min(1.0, p / 0.7)
                val ep = 1.0 - (1.0 - cl).pow(3.0)
                if (slot < count) {
                    for (i in 0 until slot) amount[i] = 1.0
                    amount[slot] = ep
                    active = slot
                } else {
                    val u = 2 * count - 1 - slot
                    for (i in 0 until u) amount[i] = 1.0
                    amount[u] = 1.0 - ep
                    active = u
                }
            }
            return RubikSolveCycle(amount, active)
        }

        private fun makeRubikMoves(count: Int): List<RubikMove> {
            val moves = ArrayList<RubikMove>(count)
            for (i in 0 until count) {
                val axis = min(2, floor(hashD(i.toDouble(), 2.3) * 3.0).toInt())
                val lo = -1.0 + 0.5 * min(3, floor(hashD(i.toDouble(), 5.9) * 4.0).toInt()).toDouble()
                val dir = if (hashD(i.toDouble(), 7.7) < 0.5) 1.0 else -1.0
                moves.add(RubikMove(axis = axis, lo = lo, hi = lo + 0.5, ang = dir * PI / 2.0))
            }
            return moves
        }

        private fun frameRubik(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
            val cx = size / 2.0
            val cy = size / 2.0
            val rVal = (size / 2.0) * 0.82
            val pt = Projector(yaw = t * 0.55, tilt = 0.35 + 0.1 * sin(t * 0.9), cx = cx, cy = cy, scale = rVal)
            val rs = radiusScale(size, pow = o["rsPow"] ?: 0.6)
            val moveCount = (o["moveCount"] ?: 14.0).toInt()
            val moves = makeRubikMoves(moveCount)
            val sc = solveCycle(t, moveCount, 0.42, 1.2)

            val dots = mutableListOf<Dot>()
            val latRings = (o["latRings"] ?: 15.0).toInt()
            val lonDensity = o["lonDensity"] ?: 40.0

            for (li in 0..latRings) {
                val lat = -PI / 2.0 + (li.toDouble() / latRings.toDouble()) * PI
                val cosLat = cos(lat)
                val sinLat = sin(lat)
                val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
                for (lj in 0 until lonCount) {
                    val lon = (lj.toDouble() / lonCount.toDouble()) * 2.0 * PI
                    var x = cosLat * cos(lon)
                    var y = sinLat
                    var z = cosLat * sin(lon)
                    var inActive = false

                    for (i in moves.indices) {
                        if (sc.amount[i] <= 0.0) continue
                        val mv = moves[i]
                        val coord = when (mv.axis) {
                            0 -> x
                            1 -> y
                            else -> z
                        }
                        if (coord < mv.lo || coord >= mv.hi) continue
                        if (i == sc.active) inActive = true
                        val a = mv.ang * sc.amount[i]
                        val ca = cos(a)
                        val sa = sin(a)
                        if (mv.axis == 0) {
                            val y2 = y * ca - z * sa
                            z = y * sa + z * ca
                            y = y2
                        } else if (mv.axis == 1) {
                            val x2 = x * ca + z * sa
                            z = -x * sa + z * ca
                            x = x2
                        } else {
                            val x2 = x * ca - y * sa
                            y = x * sa + y * ca
                            x = x2
                        }
                    }

                    val (px, py, zr) = pt(x, y, z)
                    val depth = (zr + 1.0) / 2.0
                    dots.add(Dot(
                        x = px, y = py, z = zr,
                        r = ((o["rBase"] ?: 0.6) + (o["rDepth"] ?: 1.7) * depth + (if (inActive) (o["rActive"] ?: 0.3) else 0.0)) * rs,
                        white = (o["inkFar"] ?: 0.62) - (o["inkSpan"] ?: 0.54) * depth - (if (inActive) 0.14 else 0.0)
                    ))
                }
            }
            return finalizeFrame(dots, emptyList(), rMin = o["rMin"] ?: 0.3)
        }

        // 4. Wave (listening)
        private fun frameWave(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
            val cx = size / 2.0
            val cy = size / 2.0
            val rVal = (size / 2.0) * 0.874
            val pt = Projector(yaw = t * 0.18, tilt = 0.38, cx = cx, cy = cy, scale = 1.0)
            val rs = radiusScale(size, pow = o["rsPow"] ?: 0.6)

            val dots = mutableListOf<Dot>()
            val rings = (o["rings"] ?: 15.0).toInt()
            val lonDensity = o["lonDensity"] ?: 40.0
            for (ri in 0..rings) {
                val lat = -PI / 2.0 + (ri.toDouble() / rings.toDouble()) * PI
                val cosLat = cos(lat)
                val sinLat = sin(lat)
                val w = 0.62 * sin(t * 2.1 - ri.toDouble() * 0.52) + 0.38 * sin(t * 1.27 + ri.toDouble() * 0.83)
                val rr = rVal * (0.88 + 0.105 * w)
                val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
                for (lj in 0 until lonCount) {
                    val lon = (lj.toDouble() / lonCount.toDouble()) * 2.0 * PI
                    val (px, py, z) = pt(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
                    val depth = (z / rVal + 1.0) / 2.0
                    val crest = max(0.0, w)
                    dots.add(Dot(
                        x = px, y = py, z = z,
                        r = ((o["rBase"] ?: 0.6) + (o["rDepth"] ?: 1.7) * depth) * (1.0 + 0.4 * crest) * rs,
                        white = 0.66 - 0.56 * depth - 0.1 * crest
                    ))
                }
            }
            return finalizeFrame(dots, emptyList(), rMin = o["rMin"] ?: 0.3)
        }

        // 5. Web (connecting)
        private fun frameWeb(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
            val cx = size / 2.0
            val cy = size / 2.0
            val rVal = (size / 2.0) * 0.8 * (o["spread"] ?: 1.0)
            val pt = Projector(yaw = t * 0.12, tilt = 0.32, cx = cx, cy = cy, scale = rVal)
            val rs = radiusScale(size, pow = o["rsPow"] ?: 0.6)

            val nodeN = (o["nodeN"] ?: 30.0).toInt()
            val thr = o["thr"] ?: 0.72
            val nodeR = o["nodeR"] ?: 1.4
            val nodeRDepth = o["nodeRDepth"] ?: 1.8

            val nodes = ArrayList<Triple<Double, Double, Double>>(nodeN)
            for (i in 0 until nodeN) {
                val d = fibDir(i, nodeN)
                val x = d.first + 0.3 * (vnoise(i.toDouble() * 0.31 + 9.0, t * 0.24) - 0.5) * 2.0
                val y = d.second + 0.3 * (vnoise(i.toDouble() * 0.53 + 27.0, t * 0.21) - 0.5) * 2.0
                val z = d.third + 0.3 * (vnoise(i.toDouble() * 0.77 + 55.0, t * 0.27) - 0.5) * 2.0
                val l = sqrt(x * x + y * y + z * z)
                nodes.add(Triple(x / l, y / l, z / l))
            }

            val lines = mutableListOf<Line>()
            val dots = mutableListOf<Dot>()

            for (i in 0 until nodeN) {
                for (j in (i + 1) until nodeN) {
                    val dx = nodes[i].first - nodes[j].first
                    val dy = nodes[i].second - nodes[j].second
                    val dz = nodes[i].third - nodes[j].third
                    val dist = sqrt(dx * dx + dy * dy + dz * dz)
                    if (dist >= thr) continue
                    val (x1, y1, z1) = pt(nodes[i].first, nodes[i].second, nodes[i].third)
                    val (x2, y2, z2) = pt(nodes[j].first, nodes[j].second, nodes[j].third)
                    val depth = ((z1 + z2) / 2.0 + 1.0) / 2.0
                    lines.add(Line(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        white = 0.42,
                        a = (1.0 - dist / thr) * (0.3 + 0.55 * depth),
                        w = max(0.6, (o["lineW"] ?: 0.8) * rs)
                    ))
                }
            }

            for (i in 0 until nodeN) {
                val (px, py, z) = pt(nodes[i].first, nodes[i].second, nodes[i].third)
                val depth = (z + 1.0) / 2.0
                val pulse = 1.0 + 0.25 * sin(t * 1.4 + i.toDouble() * 2.7)
                dots.add(Dot(
                    x = px, y = py, z = z,
                    r = (nodeR + nodeRDepth * depth) * pulse * rs,
                    white = 0.55 - 0.45 * depth
                ))
            }

            val signals = (o["signals"] ?: 5.0).toInt()
            for (s in 0 until signals) {
                val seg = floor(t * 0.55 + s.toDouble() * 7.31)
                val a = floor(hashD(seg, s.toDouble() * 3.1 + 1.7) * nodeN.toDouble()).toInt().coerceIn(0, nodeN - 1)
                val b = floor(hashD(seg, s.toDouble() * 5.7 + 4.2) * nodeN.toDouble()).toInt().coerceIn(0, nodeN - 1)
                if (a == b) continue
                val f = frac(t * 0.55 + s.toDouble() * 7.31)
                val x = lerp(nodes[a].first, nodes[b].first, f)
                val y = lerp(nodes[a].second, nodes[b].second, f)
                val z = lerp(nodes[a].third, nodes[b].third, f)
                val l = max(1e-6, sqrt(x * x + y * y + z * z))
                val (px, py, zr) = pt(x / l, y / l, z / l)
                val depth = (zr + 1.0) / 2.0
                dots.add(Dot(
                    x = px, y = py, z = zr,
                    r = (nodeR * 1.5 + nodeRDepth * depth) * rs,
                    white = 0.05,
                    a = 0.5 + 0.5 * depth
                ))
            }

            return finalizeFrame(dots, lines, rMin = o["rMin"] ?: 0.3)
        }

        // 6. Braid (weaving)
        private fun frameBraid(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
            val cx = size / 2.0
            val cy = size / 2.0
            val rVal = (size / 2.0) * 0.76
            val pt = Projector(yaw = t * 0.4, tilt = 0.3, cx = cx, cy = cy, scale = 1.0)
            val rs = radiusScale(size, pow = o["rsPow"] ?: 0.6)

            val dots = mutableListOf<Dot>()
            val ghostN = (o["ghostN"] ?: 150.0).toInt()
            for (i in 0 until ghostN) {
                val d = fibDir(i, ghostN)
                val (px, py, z) = pt(d.first * rVal, d.second * rVal, d.third * rVal)
                val depth = (z / rVal + 1.0) / 2.0
                dots.add(Dot(x = px, y = py, z = z, r = 0.8 * rs, white = 0.78, a = 0.1 + 0.22 * depth))
            }

            val strandN = (o["strandN"] ?: 52.0).toInt()
            val turns = (o["turns"] ?: 3.0)
            for (s in 0 until 3) {
                val phase = (s.toDouble() / 3.0) * 2.0 * PI
                for (i in 0 until strandN) {
                    val u = (frac(i.toDouble() / strandN.toDouble() + t * 0.045) * 2.0 - 1.0) * 0.96
                    val surf = sqrt(max(0.0, 1.0 - u * u))
                    val endFade = min(1.0, (1.0 - abs(u)) / 0.1)
                    val a = u * PI * turns + phase
                    val weave = 1.0 + 0.075 * sin(u * PI * turns * 2.0 + phase * 2.0 + t * 0.8)
                    val rr = surf * rVal * weave
                    val (px, py, zr) = pt(cos(a) * rr, u * rVal * weave, sin(a) * rr)
                    val depth = (zr / rVal + 1.0) / 2.0
                    dots.add(Dot(
                        x = px, y = py, z = zr,
                        r = ((o["rBase"] ?: 1.2) + (o["rDepth"] ?: 1.8) * depth) * rs,
                        white = 0.55 - 0.45 * depth,
                        a = endFade * (0.45 + 0.55 * depth)
                    ))
                }
            }
            return finalizeFrame(dots, emptyList(), rMin = o["rMin"] ?: 0.3)
        }

        // 7. Ribbon & Ring (composing & breathing)
        private fun frameRibbon(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
            val cx = size / 2.0
            val cy = size / 2.0
            val rVal = (size / 2.0) * 0.78
            val spin = o["spin"] ?: 1.0
            val camTilt = 0.3
            val faceOn = (o["faceOn"] ?: 0.0) != 0.0
            val pt = Projector(yaw = t * 0.1 * spin, tilt = camTilt, cx = cx, cy = cy, scale = 1.0)
            val rs = radiusScale(size, pow = o["rsPow"] ?: 0.6)

            val dots = mutableListOf<Dot>()
            val ghostN = (o["ghostN"] ?: 150.0).toInt()
            if (ghostN > 0) {
                for (i in 0 until ghostN) {
                    val d = fibDir(i, ghostN)
                    val (px, py, z) = pt(d.first * rVal, d.second * rVal, d.third * rVal)
                    val depth = (z / rVal + 1.0) / 2.0
                    dots.add(Dot(x = px, y = py, z = z, r = 0.8 * rs, white = 0.78, a = 0.1 + 0.22 * depth))
                }
            }

            val ya = t * 0.24 * spin
            val ta = if (faceOn) -camTilt else 0.55 + 0.3 * sin(t * 0.18) * spin
            val ux = cos(ya)
            val uy = 0.0
            val uz = sin(ya)
            val vx = -uz * sin(ta)
            val vy = cos(ta)
            val vz = ux * sin(ta)
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx

            val wobMul = o["wobMul"] ?: 1.0
            val wobAmp = 0.23 * wobMul
            val baseR = if (faceOn) rVal / (1.0 + 0.85 * wobAmp) else rVal

            val baseLanes = o["lanes"] ?: 5.0
            val segs = (o["segs"] ?: 88.0).toInt()
            val lanes = max(1, (baseLanes * (o["bandMul"] ?: 1.0)).roundToInt())

            for (w in 0 until lanes) {
                val laneOff = (w.toDouble() - (lanes - 1).toDouble() / 2.0) * 0.075
                val edge = abs(w.toDouble() - (lanes - 1).toDouble() / 2.0) / max(1.0, (lanes - 1).toDouble() / 2.0)
                for (k in 0 until segs) {
                    val a = (k.toDouble() / segs.toDouble()) * 2.0 * PI
                    val wob = (0.16 * sin(a * 3.0 - t * 1.7 + w.toDouble() * 0.22) + 0.07 * sin(a * 5.0 + t * 1.1)) * wobMul
                    val radial = if (faceOn) 1.0 + wob else 1.0
                    val off = if (faceOn) laneOff else laneOff + wob
                    val x = ux * cos(a) + vx * sin(a) + nx * off
                    val y = uy * cos(a) + vy * sin(a) + ny * off
                    val z = uz * cos(a) + vz * sin(a) + nz * off
                    val l = sqrt(x * x + y * y + z * z)
                    val rr = baseR * radial
                    val (px, py, zr) = pt((x / l) * rr, (y / l) * rr, (z / l) * rr)
                    val depth = (zr / rVal + 1.0) / 2.0
                    dots.add(Dot(
                        x = px, y = py, z = zr,
                        r = ((o["rBase"] ?: 1.1) + (o["rDepth"] ?: 1.7) * depth) * (1.0 - 0.25 * edge) * rs,
                        white = 0.52 - 0.44 * depth + 0.18 * edge,
                        a = 0.4 + 0.6 * depth
                    ))
                }
            }
            return finalizeFrame(dots, emptyList(), rMin = o["rMin"] ?: 0.3)
        }

        // 8. Morph (shaping)
        private fun smoothE(x: Double): Double = x * x * (3.0 - 2.0 * x)

        private class PolyPath(val verts: List<Pair<Double, Double>>) {
            val segLengths: DoubleArray
            val total: Double

            init {
                val lengths = DoubleArray(verts.size)
                var sum = 0.0
                for (i in verts.indices) {
                    val a = verts[i]
                    val b = verts[(i + 1) % verts.size]
                    val l = sqrt((b.first - a.first) * (b.first - a.first) + (b.second - a.second) * (b.second - a.second))
                    lengths[i] = l
                    sum += l
                }
                segLengths = lengths
                total = sum
            }

            fun point(f: Double): Pair<Double, Double> {
                var target = f * total
                var i = 0
                while (i < verts.size - 1 && target > segLengths[i]) {
                    target -= segLengths[i]
                    i++
                }
                val a = verts[i]
                val b = verts[(i + 1) % verts.size]
                val ff = if (segLengths[i] != 0.0) min(1.0, target / segLengths[i]) else 0.0
                return Pair(a.first + (b.first - a.first) * ff, a.second + (b.second - a.second) * ff)
            }
        }

        private sealed class MorphShape {
            object Circle : MorphShape()
            class Poly(val path: PolyPath) : MorphShape()

            fun point(f: Double): Pair<Double, Double> {
                return when (this) {
                    is Circle -> {
                        val a = -PI / 2.0 + f * 2.0 * PI
                        Pair(cos(a) * 0.24, sin(a) * 0.24)
                    }
                    is Poly -> path.point(f)
                }
            }
        }

        private val morphTriangle = PolyPath(listOf(Pair(0.0, -0.26), Pair(0.24, 0.16), Pair(-0.24, 0.16)))
        private val morphSquare = PolyPath(listOf(Pair(0.0, -0.2), Pair(0.2, -0.2), Pair(0.2, 0.2), Pair(-0.2, 0.2), Pair(-0.2, -0.2)))
        private val morphCycle: List<MorphShape> = listOf(MorphShape.Circle, MorphShape.Poly(morphTriangle), MorphShape.Poly(morphSquare))

        private const val MORPH_HOLD = 1.4
        private const val MORPH_DUR = 0.9
        private const val MORPH_SEG = MORPH_HOLD + MORPH_DUR

        private fun frameMorph(size: Double, t: Double, o: Map<String, Double>): OrbFrame {
            val kCount = morphCycle.size
            val tc = t % (MORPH_SEG * kCount.toDouble())
            val k = floor(tc / MORPH_SEG).toInt()
            val local = tc - k.toDouble() * MORPH_SEG
            val m = if (local > MORPH_HOLD) smoothE((local - MORPH_HOLD) / MORPH_DUR) else 0.0
            val sprd = o["spread"] ?: 1.0

            val pA = morphCycle[k]
            val pB = morphCycle[(k + 1) % kCount]
            val mSample = 160
            val pts = ArrayList<Pair<Double, Double>>(mSample)
            for (i in 0 until mSample) {
                val f = i.toDouble() / mSample.toDouble()
                val a = pA.point(f)
                val b = pB.point(f)
                pts.add(Pair((a.first + (b.first - a.first) * m) * sprd, (a.second + (b.second - a.second) * m) * sprd))
            }

            val lLengths = DoubleArray(mSample)
            var total = 0.0
            for (i in 0 until mSample) {
                val a = pts[i]
                val b = pts[(i + 1) % mSample]
                val l = sqrt((b.first - a.first) * (b.first - a.first) + (b.second - a.second) * (b.second - a.second))
                lLengths[i] = l
                total += l
            }

            val iconD = o["iconD"] ?: 1.0
            val n = max(6, (34.0 * iconD).roundToInt())
            val re = (o["rDot"] ?: 0.021) * 1.35 * sprd
            val pulse = 1.0 + 0.02 * sin(local * 3.1)

            val dots = ArrayList<Dot>(n)
            val c2 = size / 2.0
            var seg = 0
            var acc = 0.0
            for (k2 in 0 until n) {
                val target = (k2.toDouble() / n.toDouble()) * total
                while (seg < mSample - 1 && acc + lLengths[seg] < target) {
                    acc += lLengths[seg]
                    seg++
                }
                val a = pts[seg]
                val b = pts[(seg + 1) % mSample]
                val f = if (lLengths[seg] != 0.0) min(1.0, (target - acc) / lLengths[seg]) else 0.0
                val x = (a.first + (b.first - a.first) * f) * pulse
                val y = (a.second + (b.second - a.second) * f) * pulse
                dots.add(Dot(
                    x = c2 + x * size,
                    y = c2 + y * size,
                    z = 0.0,
                    r = max(0.35, re * size),
                    white = 0.1
                ))
            }
            return finalizeFrame(dots, emptyList(), rMin = o["rMin"] ?: 0.3)
        }
    }
}
