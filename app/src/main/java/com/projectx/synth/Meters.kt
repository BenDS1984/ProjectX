package com.projectx.synth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntSize
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

// ============================================================================
//  Oscilloscoop en VU-meter in de bovenste menubalk (Synth1OscA-v3.svg, zelfde plaats als v2:
//  groen kader = oscilloscoop, geel kader = VU-meter; de kaders zelf worden niet getekend).
//  Ze hebben een eigen tekenvlak, zodat alleen dit stukje ca. 30 keer per seconde
//  ververst wordt en niet het hele scherm met de wavetable.
//  Kleuren naar de inspiratie (Bitwig): blauw zoals "Pluck", groen zoals "Sallen-Key".
// ============================================================================

private val ColScope = Color(0xFF8CC8FF)   // lichter blauw dan Osc B (#5CA8EE), sinds v0.9
private val ColMeter = Color(0xFF22D9A0)
private val ColMeterTrack = Color(0xFF2B2B2B)
private val ColClip = Color(0xFFE53935)

// Binnenkant van de kaders uit het ontwerp (de lijndikte van het kader eraf).
private val rScope = Area(358.11f, 3.03f, 71.24f, 30.40f)
private val rVu = Area(434.21f, 3.14f, 71.25f, 30.18f)

private const val REFRESH_NS = 33_000_000L     // ca. 30 keer per seconde
private const val SCOPE_READ = 2048            // zoveel samples ophalen (ca. 43 ms)
private const val SCOPE_POINTS = 160           // punten in de lijn
private const val DEFAULT_SAMPLE_RATE = 48000f
private const val SCOPE_RANGE = 1.4142f        // volle hoogte van de oscilloscoop

private const val VU_MIN_DB = -60f
private const val VU_FALL_DB_PER_S = 24f       // zo snel zakt de balk terug
private const val HOLD_S = 1.0f                // piekstreepje blijft zo lang staan
private const val HOLD_FALL_DB_PER_S = 20f     // en zakt daarna zo snel
private const val CLIP_S = 1.0f                // rood blijft zo lang branden
private const val CLIP_LEVEL = 0.999f          // vanaf hier telt het als 0 dB

/** Toestand van de meters; wordt alleen op de UI-thread gebruikt. */
private class MeterState {
    val scopeBuf = FloatArray(SCOPE_READ)
    var scopeCount = 0
    val peakLR = FloatArray(2)
    val levelDb = floatArrayOf(VU_MIN_DB, VU_MIN_DB)
    val holdDb = floatArrayOf(VU_MIN_DB, VU_MIN_DB)
    val holdAge = floatArrayOf(0f, 0f)
    val clipAge = floatArrayOf(CLIP_S, CLIP_S)

    fun update(dt: Float) {
        scopeCount = NativeSynth.readScope(scopeBuf)
        NativeSynth.takeMeterPeaks(peakLR)
        for (c in 0..1) {
            val p = peakLR[c]
            val db = if (p > 1e-6f) (20f * log10(p)).coerceIn(VU_MIN_DB, 0f) else VU_MIN_DB
            // snel omhoog, rustig terug
            levelDb[c] = max(db, levelDb[c] - VU_FALL_DB_PER_S * dt)
            // piekstreepje
            if (db >= holdDb[c]) { holdDb[c] = db; holdAge[c] = 0f }
            else {
                holdAge[c] += dt
                if (holdAge[c] > HOLD_S) holdDb[c] = max(VU_MIN_DB, holdDb[c] - HOLD_FALL_DB_PER_S * dt)
            }
            // 0 dB geraakt
            clipAge[c] = if (p >= CLIP_LEVEL) 0f else min(CLIP_S, clipAge[c] + dt)
        }
    }
}

@Composable
internal fun MetersLayer(measurer: TextMeasurer) {
    val state = remember { MeterState() }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                if (now - last >= REFRESH_NS) {
                    state.update(((now - last) / 1e9f).coerceAtMost(0.2f))
                    last = now
                    frame++
                }
            }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        if (frame < 0L) return@Canvas   // "frame" lezen = opnieuw tekenen bij elke verversing
        val fit = Fit(IntSize(size.width.toInt(), size.height.toInt()))
        drawScope(fit, state)
        drawVu(fit, measurer, state)
    }
}

// ----------------------------------------------------------------------------
//  Oscilloscoop: stilstaand beeld van ca. 2 perioden.
//  Het beeld begint telkens waar de golf van laag naar hoog door het midden gaat
//  (de "trigger"); daardoor staat het stil in plaats van voorbij te lopen.
//  De oscilloscoop kijkt naar de mix van A en B vóór Amp en Pan (v0.8), dus het beeld
//  blijft even groot als je Amp of Pan draait; alleen Mix en aan/uit tellen mee.
//  Vaste schaal: de volle hoogte is 1,41 (sqrt 2), het hoogste dat A + B samen kunnen
//  halen bij Mix in het midden. Eén oscillator alleen vult zo ca. 71 % van de hoogte.
// ----------------------------------------------------------------------------
private fun DrawScope.drawScope(fit: Fit, st: MeterState) {
    val s = fit.s
    val left = fit.x(rScope.x); val top = fit.y(rScope.y)
    val w = rScope.w * s; val h = rScope.h * s
    val yMid = top + h / 2
    val amp = h * 0.46f / SCOPE_RANGE

    clipRect(left, top, left + w, top + h) {
        drawLine(ColWaveGrey.copy(alpha = 0.35f), Offset(left, yMid), Offset(left + w, yMid), strokeWidth = 0.3f * s)

        val n = st.scopeCount
        val buf = st.scopeBuf
        val sr = SynthState.stats?.sampleRate?.takeIf { it > 0 }?.toFloat() ?: DEFAULT_SAMPLE_RATE
        val window = (2f * sr / SynthState.pitchHz()).coerceIn(16f, SCOPE_READ / 2f)
        if (n < window + 2) return@clipRect

        // Laatste opgaande nuldoorgang zoeken waarna nog een heel venster past.
        var start = n - window - 1f
        var i = (n - window - 1).toInt()
        while (i >= 1) {
            if (buf[i - 1] < 0f && buf[i] >= 0f) {
                start = (i - 1) + buf[i - 1] / (buf[i - 1] - buf[i])   // tussen twee samples
                break
            }
            i--
        }

        val path = Path()
        for (k in 0..SCOPE_POINTS) {
            val t = start + window * k / SCOPE_POINTS
            val j = t.toInt().coerceIn(0, n - 2)
            val fr = t - j
            val v = (buf[j] + (buf[j + 1] - buf[j]) * fr).coerceIn(-SCOPE_RANGE, SCOPE_RANGE)
            val x = left + w * k / SCOPE_POINTS
            val y = yMid - amp * v
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ColScope, style = Stroke(0.9f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ----------------------------------------------------------------------------
//  VU-meter: twee balken (L boven, R onder), -60 .. 0 dB.
// ----------------------------------------------------------------------------
private fun DrawScope.drawVu(fit: Fit, measurer: TextMeasurer, st: MeterState) {
    val s = fit.s
    val labelW = 7f                       // ruimte voor "L" en "R"
    val barH = 8f
    val gap = 4f
    val barX = rVu.x + labelW
    val barW = rVu.w - labelW - 1f
    val y0 = rVu.y + (rVu.h - 2 * barH - gap) / 2
    val clipW = 2.2f                      // rood blokje aan het einde (0 dB)

    for (c in 0..1) {
        val y = y0 + c * (barH + gap)
        text(fit, measurer, if (c == 0) "L" else "R", Offset(rVu.x + labelW / 2 - 0.5f, y + barH - 1.2f),
            7f, true, ColWaveGrey)

        val clip = st.clipAge[c] < CLIP_S
        drawRect(ColMeterTrack, Offset(fit.x(barX), fit.y(y)), Size(barW * s, barH * s))

        val frac = ((st.levelDb[c] - VU_MIN_DB) / -VU_MIN_DB).coerceIn(0f, 1f)
        if (frac > 0f) drawRect(ColMeter, Offset(fit.x(barX), fit.y(y)), Size(barW * frac * s, barH * s))
        if (clip) drawRect(ColClip, Offset(fit.x(barX + barW - clipW), fit.y(y)), Size(clipW * s, barH * s))

        val holdFrac = ((st.holdDb[c] - VU_MIN_DB) / -VU_MIN_DB).coerceIn(0f, 1f)
        if (holdFrac > 0f) {
            val hx = barX + (barW - 0.6f) * holdFrac
            drawLine(
                if (clip) ColClip else ColMeter,
                Offset(fit.x(hx), fit.y(y)), Offset(fit.x(hx), fit.y(y + barH)),
                strokeWidth = 1.0f * s,
            )
        }
    }
}
