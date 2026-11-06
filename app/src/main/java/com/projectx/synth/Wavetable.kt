package com.projectx.synth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * De wavetables voor de weergave (zwarte scherm en keuzevenster).
 * Komen uit hetzelfde bestand als de klank (assets/wavetables.bin, gemaakt door
 * tools/wavetables/make_wavetables.py). Voor het tekenen volstaat per golfvorm een
 * versie met 64 boventonen, op 128 punten.
 */
object Wavetables {
    const val PREVIEW_POINTS = 128
    private const val PREVIEW_LEVEL = 3     // octaafversie met 64 boventonen

    /** Verandert zodra de tabellen ingelezen zijn (zodat het scherm opnieuw tekent). */
    var version by mutableIntStateOf(0)
        private set

    @Volatile private var names: List<String> = emptyList()
    @Volatile private var shapes: Array<Array<FloatArray>> = emptyArray()   // tabel -> golfvorm -> punten

    val count get() = names.size
    fun name(t: Int): String = names.getOrElse(t) { "Basic" }
    fun frames(t: Int): Int = shapes.getOrNull(t)?.size ?: 0

    /** Leest het bestand (zie make_wavetables.py voor de opbouw). Geeft false bij een fout. */
    fun load(bytes: ByteArray): Boolean {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 20 || String(bytes, 0, 4, Charsets.US_ASCII) != "PXWT") return false
        b.position(4)
        if (b.int != 1) return false
        val n = b.int
        val nl = b.int
        val count = b.int
        val newNames = ArrayList<String>()
        val newShapes = ArrayList<Array<FloatArray>>()
        var pos = 20
        val step = n / PREVIEW_POINTS
        repeat(count) {
            val name = String(bytes, pos, 32, Charsets.UTF_8).trimEnd('\u0000')
            val f = b.getInt(pos + 32)
            val data = pos + 36
            newNames.add(name)
            newShapes.add(Array(f) { s ->
                val base = data + 2 * ((s * nl + PREVIEW_LEVEL) * n)
                FloatArray(PREVIEW_POINTS) { i -> b.getShort(base + 2 * i * step) / 32767f }
            })
            pos = data + 2 * f * nl * n
        }
        names = newNames
        shapes = newShapes.toTypedArray()
        version++
        return true
    }

    /** Waarde van wavetable [t] op [position] (0..1) en fase [phase], met overvloeien zoals de DSP. */
    fun sample(t: Int, position: Float, phase: Float): Float {
        val tab = shapes.getOrNull(t) ?: return 0f
        val f = tab.size
        if (f == 0) return 0f
        val y = position.coerceIn(0f, 1f) * (f - 1)
        val s0 = y.toInt().coerceIn(0, maxOf(0, f - 2))
        val s1 = minOf(s0 + 1, f - 1)
        val mix = y - s0
        val p = (phase - floor(phase)) * PREVIEW_POINTS
        val i0 = p.toInt().coerceIn(0, PREVIEW_POINTS - 1)
        val i1 = (i0 + 1) % PREVIEW_POINTS
        val fr = p - i0
        fun at(s: Int) = tab[s][i0] + (tab[s][i1] - tab[s][i0]) * fr
        return (1f - mix) * at(s0) + mix * at(s1)
    }
}

private const val POINTS = 160          // punten per lijn
private const val FRAMES_3D = 24        // lijnen in de 3D-stapel

/**
 * Tekent wavetable [table] in het rechthoekige scherm (left, top, width, height in pixels).
 * [unit] = grootte van één ontwerpeenheid in pixels (voor lijndiktes).
 * [lines] = aantal grijze lijnen in de 3D-stapel (kleiner voor de tegels van het keuzevenster).
 */
fun DrawScope.drawWavetable(
    left: Float, top: Float, width: Float, height: Float,
    table: Int, position: Float, show3d: Boolean, unit: Float,
    accent: Color, grey: Color, lines: Int = FRAMES_3D,
) {
    clipRect(left, top, left + width, top + height) {
        if (show3d) draw3d(left, top, width, height, table, position, unit, accent, grey, lines)
        else draw2d(left, top, width, height, table, position, unit, accent, grey)
    }
}

private fun wavePath(table: Int, position: Float, x0: Float, width: Float, yMid: Float, amp: Float): Path {
    val path = Path()
    for (i in 0..POINTS) {
        val ph = i.toFloat() / POINTS
        val x = x0 + ph * width
        val y = yMid - amp * Wavetables.sample(table, position, ph)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

private fun DrawScope.waveLine(
    table: Int, position: Float, x0: Float, width: Float, yMid: Float, amp: Float,
    color: Color, strokeWidth: Float,
) = drawPath(wavePath(table, position, x0, width, yMid, amp), color,
    style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))

/**
 * De grijze lijnen van de 3D-stapel hangen niet af van de positie, alleen van de wavetable
 * en de plek en grootte van het scherm. Ze worden daarom per wavetable één keer uitgerekend
 * en onthouden; alleen de oranje lijn (de huidige positie) wordt telkens opnieuw opgebouwd.
 */
private object Stack3d {
    private data class Key(val table: Int, val version: Int, val left: Float, val top: Float,
                           val width: Float, val height: Float, val lines: Int)
    private val cache = HashMap<Key, Array<Path>>()

    fun get(table: Int, left: Float, top: Float, width: Float, height: Float, lines: Int,
            x0: (Float) -> Float, yMid: (Float) -> Float, frameW: Float, amp: Float): Array<Path> {
        val key = Key(table, Wavetables.version, left, top, width, height, lines)
        return cache.getOrPut(key) {
            if (cache.size > 64) cache.clear()   // bv. na het draaien van het scherm
            Array(lines) { j ->
                val t = j.toFloat() / (lines - 1)
                wavePath(table, t, x0(t), frameW, yMid(t), amp)
            }
        }
    }
}

private fun DrawScope.draw2d(
    left: Float, top: Float, width: Float, height: Float,
    table: Int, position: Float, unit: Float, accent: Color, grey: Color,
) {
    val x0 = left + width * 0.06f
    val w = width * 0.88f
    val yMid = top + height * 0.44f
    val amp = height * 0.32f
    // middenlijn
    drawLine(grey.copy(alpha = 0.5f), Offset(x0, yMid), Offset(x0 + w, yMid), strokeWidth = 0.4f * unit)
    waveLine(table, position, x0, w, yMid, amp, accent, 1.3f * unit)

    // onderaan: waar je zit in de rij golfvormen (streepjes per golfvorm als het er weinig zijn)
    val yBar = top + height * 0.88f
    drawLine(grey.copy(alpha = 0.6f), Offset(x0, yBar), Offset(x0 + w, yBar), strokeWidth = 0.4f * unit)
    val f = Wavetables.frames(table)
    val ticks = if (f in 2..8) f else 2
    for (s in 0 until ticks) {
        val x = x0 + w * s / (ticks - 1)
        drawLine(grey, Offset(x, yBar - 1.6f * unit), Offset(x, yBar + 1.6f * unit), strokeWidth = 0.5f * unit)
    }
    drawCircle(accent, radius = 1.5f * unit, center = Offset(x0 + w * position.coerceIn(0f, 1f), yBar))
}

private fun DrawScope.draw3d(
    left: Float, top: Float, width: Float, height: Float,
    table: Int, position: Float, unit: Float, accent: Color, grey: Color, lines: Int,
) {
    // Positie 0 ligt vooraan (linksonder), positie 1 achteraan (rechtsboven).
    val frameW = width * 0.62f
    val amp = height * 0.15f
    fun x0(t: Float) = left + width * 0.05f + t * width * 0.29f
    fun yMid(t: Float) = top + height * 0.74f - t * height * 0.50f

    // achterste lijn eerst, zodat de voorste er overheen valt
    val stack = Stack3d.get(table, left, top, width, height, lines, ::x0, ::yMid, frameW, amp)
    val greyStroke = Stroke(0.45f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (j in lines - 1 downTo 0) {
        val t = j.toFloat() / (lines - 1)
        drawPath(stack[j], grey.copy(alpha = 0.75f - 0.45f * t), style = greyStroke)
    }
    val t = position.coerceIn(0f, 1f)
    waveLine(table, t, x0(t), frameW, yMid(t), amp, accent, 1.3f * unit)
}
