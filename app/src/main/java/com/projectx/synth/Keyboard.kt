package com.projectx.synth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.text.TextMeasurer
import kotlin.math.pow

// ============================================================================
//  Schermkeyboard en latch.
//  Ontwerp: design/GUI onderdelen Claude/keyboard-voorbeeld.svg (1656 x 376 px),
//  geplaatst op het roze vak "Plaats keyboard" in Synth1OscA-v4.svg.
//  Het keyboard scrollt niet mee; het keyboard-icoon in de menubalk links toont of
//  verbergt het, het latch-icoon zet latch aan of uit.
//
//  Spelen (de synth speelt één noot tegelijk):
//   - de laatst aangeraakte toets klinkt; laat je die los terwijl je nog een andere
//     toets vasthoudt, dan klinkt die weer;
//   - schuif je met je vinger over de toetsen, dan verspringt de noot mee;
//   - latch aan: de laatst getikte toets blijft klinken na het loslaten. Dezelfde toets
//     nog eens tikken stopt de noot; latch uitzetten stopt hem ook. Verbergen van het
//     keyboard stopt een gelatchte noot niet (wel een noot die je alleen vasthoudt).
// ============================================================================

/** 1 px van het keyboard-ontwerp = zoveel ontwerpeenheden van het scherm. */
private const val PX = 0.2645833f
private const val KB_LEFT = 40.340f               // linkerkant van het roze vak
internal const val KB_TOP = 216.958f              // bovenkant van het roze vak = onderkant scrollvak
/** Donker paneel achter het keyboard: van de menubalk links tot de scrollstrook. */
internal val rKbPanel = Area(MENU_EDGE, KB_TOP, 481.542f - MENU_EDGE, DESIGN_H - KB_TOP)

// Maten in px van het keyboard-ontwerp
private const val N_WHITE = 15
private const val WHITE_X0 = 126f
private const val WHITE_W = 1520f / N_WHITE
private const val KEY_TOP = 10f
private const val KEY_H = 356f
private const val BLACK_W = WHITE_W * 0.6f
private const val BLACK_H = KEY_H * 0.6f
private const val BTN_X = 10f
private const val BTN_W = 104f
private const val BTN_H = 128f
private const val BTN_UP_Y = 10f
private const val BTN_DOWN_Y = 238f

private val ColBlackKey = Color(0xFF3A3A3A)
private val ColKeyLabel = Color(0xFF808080)
private val ColArrowOff = Color(0xFF666666)        // pijl bij het einde van het bereik

/** Halve tonen boven C van de witte toetsen C D E F G A B. */
private val WHITE_SEMI = intArrayOf(0, 2, 4, 5, 7, 9, 11)

private fun u(xPx: Float, yPx: Float) = Offset(KB_LEFT + xPx * PX, KB_TOP + yPx * PX)

/** Linkerkant (px) van de zwarte toets rechts van witte toets [i], of null als daar geen is. */
private fun blackLeft(i: Int): Float? =
    if (i < N_WHITE - 1 && i % 7 in intArrayOf(0, 1, 3, 4, 5)) WHITE_X0 + (i + 1) * WHITE_W - BLACK_W / 2 else null

/**
 * Toestand van keyboard en latch, en welke noot er klinkt. Noten zijn MIDI-nummers
 * (60 = C4, 69 = A4 = 440 Hz). Wordt alleen vanuit de UI-thread aangesproken.
 */
object Keys {
    const val OCT_MIN = 1
    const val OCT_MAX = 5

    var visible by mutableStateOf(false)
        private set
    var latch by mutableStateOf(false)
        private set
    /** Laagste C van het keyboard (C1 .. C5), standaard C3. */
    var octave by mutableIntStateOf(3)
        private set
    /** Noot die nu speelt (oranje toets), of null. */
    var sounding by mutableStateOf<Int?>(null)
        private set
    /** Toonhoogte van de laatst gespeelde noot (ook voor de oscilloscoop). */
    var lastHz = 440f
        private set
    /** Verandert als alle vingers vergeten moeten worden (keyboard verborgen). */
    internal var generation = 0
        private set

    private var held: Int? = null      // noot onder de vingers (laatst aangeraakt)
    private var latched: Int? = null   // vastgehouden noot bij latch

    fun lowestNote() = 12 * (octave + 1)

    fun toggleVisible() {
        visible = !visible
        if (!visible) { held = null; generation++ }
        update()
    }

    fun toggleLatch() {
        latch = !latch
        latched = if (latch) held else null   // aanzetten tijdens het spelen: die noot blijft
        update()
    }

    /** Bij het laden van een preset: keyboard dicht en latch uit (de noot stopt). */
    fun reset() {
        if (visible) { visible = false; generation++ }
        latch = false
        latched = null
        held = null
        update()
    }

    fun shiftOctave(d: Int) { octave = (octave + d).coerceIn(OCT_MIN, OCT_MAX) }

    /** Een vinger tikt toets [n]. */
    internal fun press(n: Int) {
        if (latch) latched = if (latched == n) null else n
    }

    /** Een vinger schuift naar toets [n]. */
    internal fun slideTo(n: Int) {
        if (latch) latched = n
    }

    /** Noot onder de laatst aangeraakte vinger die nog op het keyboard ligt (of null). */
    internal fun fingers(n: Int?) {
        held = n
        update()
    }

    /** On/Off is veranderd: alleen het aan/uit-signaal opnieuw doorgeven. */
    fun refreshGate() = NativeSynth.setGate(sounding != null && SynthState.on)

    /** Zet de toestand in de geluidsmotor na een herstart van de audio. */
    fun push() {
        NativeSynth.setFrequency(lastHz)
        refreshGate()
    }

    private fun update() {
        val n = if (latch) latched else held
        if (n != sounding) {
            sounding = n
            if (n != null) {
                lastHz = 440f * 2f.pow((n - 69) / 12f)
                NativeSynth.setFrequency(lastHz)   // eerst de toonhoogte, dan pas aan
            }
        }
        refreshGate()
    }
}

private sealed interface KbHit
private object OctUp : KbHit
private object OctDown : KbHit
private data class Key(val note: Int) : KbHit

/** Wat ligt er op plek ([ux], [uy]) in ontwerpeenheden? null = niets (buiten de toetsen). */
private fun kbHit(ux: Float, uy: Float): KbHit? {
    val px = (ux - KB_LEFT) / PX
    val py = (uy - KB_TOP) / PX
    if (px < WHITE_X0 - 6f) return if (py < 188f) OctUp else OctDown
    return keyAtPx(px, py)?.let { Key(it) }
}

private fun keyAtPx(px: Float, py: Float): Int? {
    if (px < WHITE_X0 || px > WHITE_X0 + N_WHITE * WHITE_W || py < 0f) return null
    val base = Keys.lowestNote()
    if (py < KEY_TOP + BLACK_H) {
        for (i in 0 until N_WHITE) {
            val l = blackLeft(i) ?: continue
            if (px >= l && px <= l + BLACK_W) return base + 12 * (i / 7) + WHITE_SEMI[i % 7] + 1
        }
    }
    val i = ((px - WHITE_X0) / WHITE_W).toInt().coerceIn(0, N_WHITE - 1)
    return base + 12 * (i / 7) + WHITE_SEMI[i % 7]
}

/**
 * Aanrakingen op het keyboard, met meerdere vingers tegelijk. Vingers die buiten het
 * keyboard beginnen, laat deze laag ongemoeid (die gaan naar de rest van het scherm).
 */
internal suspend fun PointerInputScope.handleKeyboard() {
    val fingers = LinkedHashMap<PointerId, Int>()   // vinger -> noot, oudste aanraking eerst
    var gen = Keys.generation
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (gen != Keys.generation) { fingers.clear(); gen = Keys.generation }
            val fit = Fit(size)
            var changed = false
            for (c in event.changes) {
                val ux = fit.toUnitX(c.position.x)
                val uy = fit.toUnitY(c.position.y)
                if (c.pressed && !c.previousPressed) {
                    if (!Keys.visible || !rKbPanel.contains(ux, uy)) continue
                    c.consume()
                    when (val hit = kbHit(ux, uy)) {
                        OctUp -> Keys.shiftOctave(+1)
                        OctDown -> Keys.shiftOctave(-1)
                        is Key -> { fingers[c.id] = hit.note; Keys.press(hit.note); changed = true }
                        null -> {}
                    }
                } else if (c.id in fingers) {
                    c.consume()
                    if (!c.pressed) {
                        fingers.remove(c.id); changed = true
                    } else {
                        val n = keyAtPx((ux - KB_LEFT) / PX, (uy - KB_TOP) / PX)
                        if (n != null && n != fingers[c.id]) {
                            fingers.remove(c.id); fingers[c.id] = n   // geldt nu als nieuwste aanraking
                            Keys.slideTo(n); changed = true
                        }
                    }
                }
            }
            if (changed) Keys.fingers(fingers.values.lastOrNull())
        }
    }
}

/** Tekent het keyboard (alleen als het zichtbaar is). */
internal fun DrawScope.drawKeyboard(fit: Fit, measurer: TextMeasurer) {
    if (!Keys.visible) return
    val s = fit.s
    val k = PX * s
    rect(fit, rKbPanel, ColDark)

    // --- Octaafknoppen met daartussen de laagste noot -------------------------
    fun arrow(yPx: Float, up: Boolean, enabled: Boolean) {
        drawButton(fit, Area(KB_LEFT + BTN_X * PX, KB_TOP + yPx * PX, BTN_W * PX, BTN_H * PX))
        val cx = BTN_X + BTN_W / 2
        val cy = yPx + BTN_H / 2 + 2f
        val dy = if (up) 7f else -7f
        val path = Path().apply {
            u(cx - 14f, cy + dy).let { moveTo(fit.x(it.x), fit.y(it.y)) }
            u(cx, cy - dy).let { lineTo(fit.x(it.x), fit.y(it.y)) }
            u(cx + 14f, cy + dy).let { lineTo(fit.x(it.x), fit.y(it.y)) }
        }
        drawPath(path, if (enabled) ColKnobEdge else ColArrowOff,
            style = Stroke(6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    arrow(BTN_UP_Y, up = true, enabled = Keys.octave < Keys.OCT_MAX)
    arrow(BTN_DOWN_Y, up = false, enabled = Keys.octave > Keys.OCT_MIN)
    text(fit, measurer, "C${Keys.octave}", u(BTN_X + BTN_W / 2, 376f / 2 + 13f), 38f * PX, true, ColText)

    // --- Toetsen --------------------------------------------------------------
    val base = Keys.lowestNote()
    val playing = Keys.sounding
    for (i in 0 until N_WHITE) {
        val note = base + 12 * (i / 7) + WHITE_SEMI[i % 7]
        val tl = u(WHITE_X0 + i * WHITE_W + 2f, KEY_TOP)
        drawRoundRect(
            if (note == playing) ColAccent else ColKnob, Offset(fit.x(tl.x), fit.y(tl.y)),
            Size((WHITE_W - 4f) * k, KEY_H * k), CornerRadius(5f * k),
        )
        if (i % 7 == 0) {
            text(fit, measurer, "C${Keys.octave + i / 7}", u(WHITE_X0 + i * WHITE_W + WHITE_W / 2, KEY_TOP + KEY_H - 18f),
                26f * PX, false, ColKeyLabel)
        }
    }
    for (i in 0 until N_WHITE) {
        val l = blackLeft(i) ?: continue
        val note = base + 12 * (i / 7) + WHITE_SEMI[i % 7] + 1
        val edge = u(l - 3f, KEY_TOP)
        drawRoundRect(ColDark, Offset(fit.x(edge.x), fit.y(edge.y)), Size((BLACK_W + 6f) * k, (BLACK_H + 3f) * k),
            CornerRadius(5f * k))
        val tl = u(l, KEY_TOP)
        drawRoundRect(if (note == playing) ColAccent else ColBlackKey, Offset(fit.x(tl.x), fit.y(tl.y)),
            Size(BLACK_W * k, BLACK_H * k), CornerRadius(4f * k))
    }
}
