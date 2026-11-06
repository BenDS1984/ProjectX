package com.projectx.synth

import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ============================================================================
//  Scherm van v0.10, nagetekend naar design/SVG bestanden GUI/Synth1OscA-v6.svg en
//  Synth1OscB-v1.svg (tab B gekozen: blauw; bredere tabs), met het keuzevenster van de
//  wavetables uit design/GUI onderdelen Claude/wavetable-menu-voorbeeld.svg
//  (menubalken, scrollbalk en keyboard zoals in v4). Sinds v0.10: presetbalk, exit-icoon
//  en DSP-meting links in de bovenbalk (de presets zelf: Presets.kt en PresetScreen.kt).
//  Alle maten hieronder zijn in de eenheden van dat SVG-bestand (508 x 317,5 =
//  1920 x 1200 px). Het geheel schaalt gelijkmatig mee met het scherm; bij een
//  andere verhouding komt er een donkere rand omheen.
//
//  Indeling:
//   - vast: menubalk boven (DSP-meting, oscilloscoop, VU-meter), menubalk links
//     (iconen keyboard en latch) en de scrollbalk rechts;
//   - scrollbaar: het vak daartussen (het roze kader "Maak scrollbaar"). De inhoud
//     staat in dezelfde eenheden als het ontwerp en loopt verder naar beneden dan het
//     scherm (nu met een lege, tijdelijke testruimte onder het paneel). Bovenaan de
//     tabs OSC A en OSC B met daartussen de fader Mix; het paneel eronder toont de
//     knoppen van de gekozen oscillator;
//   - het keyboard onderaan (Keyboard.kt): vast, alleen als het keyboard-icoon aan staat.
//     Dan eindigt het scrollvak boven het keyboard en kun je verder naar beneden scrollen.
//  De oscilloscoop en de VU-meter staan in Meters.kt (eigen tekenvlak).
// ============================================================================

internal const val DESIGN_W = 508f
internal const val DESIGN_H = 317.5f

// Kleuren uit het ontwerp
internal val ColAccent = Color(0xFFFF8A1E)
internal val ColPitch = Color(0xFFFCEB23)      // geel: toonhoogte (Oct, Cent)
private val ColPan = Color(0xFFC36EFF)          // paars: Pan
private val ColTabOff = Color(0xFF999999)       // tab die niet gekozen is (rand en tekst)
internal val ColOscB = Color(0xFF5CA8EE)        // blauw: kleur van Osc B (tab, streep, Mix-fader rechts)
internal val ColTile = Color(0xFF262626)         // tegels van het keuzevenster

/** Kleur van oscillator [i]: A oranje, B blauw. */
internal fun oscColor(i: Int) = if (i == 0) ColAccent else ColOscB
private val ColBg = Color(0xFF666666)
internal val ColPanel = Color(0xFF333333)
internal val ColDark = Color(0xFF1A1A1A)
internal val ColButton = Color(0xFF4D4D4D)
internal val ColTrack = Color(0xFF4A4A4A)
internal val ColKnob = Color(0xFFE6E6E6)
internal val ColKnobEdge = Color(0xFFC8C8C8)
private val ColPointer = Color(0xFF2B2B2B)
internal val ColIcon = Color(0xFFC8C8C8)
internal val ColText = Color(0xFFF9F9F9)
internal val ColWaveGrey = Color(0xFF9A9A9A)

internal val Lato = FontFamily(
    Font(R.font.lato_regular, FontWeight.Normal),
    Font(R.font.lato_bold, FontWeight.Bold),
)

private const val TEXT_SIZE = 11.29f      // 42,67 px in het ontwerp
private const val TITLE_SIZE = 16.93f     // 64 px in het ontwerp

/** Rechthoek in ontwerpeenheden. */
internal data class Area(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
    val cx get() = x + w / 2
    val bottom get() = y + h
}

// --- Vaste delen (scrollen niet mee) ---------------------------------------------
internal const val MENU_EDGE = 37.04f                       // onderkant menubalk boven = rechterkant menubalk links
private val rMenuTop = Area(0f, 0f, DESIGN_W, MENU_EDGE)
private val rMenuLeft = Area(0f, 0f, MENU_EDGE, DESIGN_H)
private val rScrollStrip = Area(481.542f, 34.813f, 26.458f, 282.687f)
private val rDspBox = Area(38.485f, 3.32f, 71.24f, 30.40f)  // binnenkant van het rode kader "Dsp meting" (v6: links)

// Iconen in de menubalk links: plaats en schaal van de groepen in het ontwerp
// (de iconen zelf zijn getekend op een raster van 48 x 48, zie design/GUI onderdelen Claude/).
internal const val ICON_SCALE = 0.61736f
private val iconKeyboard = Offset(3.131f, 256.690f)
private val iconLatch = Offset(4.498f, 283.810f)
private val rIconKeyboard = Area(0f, 250f, MENU_EDGE, 33f)      // aanraakvlak icoon keyboard
private val rIconLatch = Area(0f, 283f, MENU_EDGE, DESIGN_H - 283f)

// --- Scrollvak ----------------------------------------------------------------------
/** Het zichtbare venster op de inhoud (tussen de menubalken en de scrollbalk, en boven het keyboard). */
private fun view(): Area {
    val bottom = if (Keys.visible) KB_TOP else DESIGN_H
    return Area(MENU_EDGE, MENU_EDGE, rScrollStrip.x - MENU_EDGE, bottom - MENU_EDGE)
}
/** Hoogte van de hele inhoud: het ontwerp plus een tijdelijke testruimte eronder. */
private const val CONTENT_H = DESIGN_H + 280f
private val rTestSpace = Area(47.625f, 330f, 423.333f, 255f)
/** Na het tonen/verbergen van het keyboard van buitenaf (bv. bij het laden van een preset). */
internal fun clampScroll() = Scroll.clamp()

// Scrollbalk (design/GUI onderdelen Claude/scrollbalk-voorbeeld.svg, 1 px = 0,26458 eenheden)
private val SB_TRACK_X = rScrollStrip.x + 12.171f
private const val SB_TRACK_W = 2.117f
private val SB_TRACK_TOP = rScrollStrip.y + 5.292f
private const val SB_TRACK_LEN = 271.990f
private val SB_THUMB_X = rScrollStrip.x + 7.408f
private const val SB_THUMB_W = 11.642f
private const val SB_THUMB_MIN = 20f
private const val SB_R = 1.058f

// --- Plaatsen in de inhoud (uit het SVG-bestand) -------------------------------------
private val rPanel = Area(47.625f, 83.079f, 423.333f, 206.375f)
private val rTitleLine = Area(47.993f, 77.787f, 422.966f, 3.0f)   // oranje streep onder de tabs
private val rSeparator = Area(258.791f, 100.013f, 2f, 169.333f)   // scheidingslijn tussen Wave en Amp/Pan (v6)
private val rDisplay = Area(57.796f, 127.794f, 143.288f, 94.985f)
private val rWavetableBtn = Area(58.738f, 97.137f, 78.845f, 28.276f)
private const val BTN_RX = 1.88f
private const val BTN_RY = 1.57f
private const val BTN_ACCENT_H = 1.96f
private const val BTN_TEXT_DY = 18.46f     // basislijn van de knoptekst onder de bovenkant van de knop

// Tabs OSC A en OSC B, met het aan/uit-icoon (raster 48 x 48, design/GUI onderdelen Claude/icoon-aan-uit.svg)
private val rTabA = Area(85.725f, 48.397f, 90f, 28.276f)        // breedte 90 sinds Synth1OscB-v1
private val rTabB = Area(338.669f, 48.397f, 90f, 28.276f)
private val tTabA = Offset(146.008f, 69.33f)
private val tTabB = Offset(368.896f, 69.33f)
private const val POWER_SCALE = 0.49687f
private val iconPowerA = Offset(89.633f, 50.297f)
private val iconPowerB = Offset(400.785f, 50.297f)
// Aanraakvlak van het icoon: het deel van de tab aan de kant van het icoon (tot aan de tekst)
private val rPowerA = Area(rTabA.x, rTabA.y, 29.5f, rTabA.h)                 // tot halverwege icoon en tekst
private val rPowerB = Area(399f, rTabB.y, rTabB.x + rTabB.w - 399f, rTabB.h)

// Liggende fader "Mix" (links = alleen A, rechts = alleen B)
private val rMixTrack = Area(186.281f, 60.35f, 142.875f, 3.0f)
private const val MIX_THUMB_W = 11.71f     // de schuif van de fader Wave, een kwartslag gedraaid
private const val MIX_THUMB_H = 16.285f
private const val MIX_LEFT = 192.137f      // middelpunt schuif bij mix 0 (alleen A)
private const val MIX_RIGHT = 323.300f     // middelpunt schuif bij mix 1 (alleen B)
private val rMixTouch = Area(180f, 40f, 156f, 33f)
private val tMix = Offset(258.566f, 48.35f)

// Fader "Wave"
private const val FADER_CX = 226.965f
private val rFaderTrack = Area(225.465f, 114.829f, 3.0f, 142.875f)
private const val FADER_THUMB_W = 16.285f
private const val FADER_THUMB_H = 11.71f
private const val FADER_TOP = 120.686f     // middelpunt schuif bij 100 %
private const val FADER_BOTTOM = 251.846f  // middelpunt schuif bij 0 %
private val rFaderTouch = Area(208f, 107f, 38f, 158f)

// Draaiknoppen (alle even groot)
private const val KNOB_ARC_R = 25.49f
private const val KNOB_ARC_W = 3.82f
private const val KNOB_R = 19.75f
private const val KNOB_EDGE_W = 0.64f
private const val POINTER_IN = 8.28f
private const val POINTER_OUT = 17.21f
private const val POINTER_W = 2.55f
private const val KNOB_TOUCH_R = 34f
private const val KNOB_DRAG_RANGE = 110f   // zoveel eenheden slepen = hele draaibereik (270 graden)
private const val OCT_STEP_DEG = 67.5f     // draai per octaafstap (0 = recht naar boven)

private val cAmp = Offset(312.262f, 135.044f)
private val cOct = Offset(421.248f, 137.224f)
private val cCent = Offset(421.248f, 237.085f)
private val cPan = Offset(cAmp.x, cCent.y)     // recht onder Amp, op de hoogte van Cent

// Teksten (middelpunt x, basislijn y)
private val tWave = Offset(FADER_CX, 101.335f)
private val tWaveValue = Offset(FADER_CX, 278.06f)
private val tAmp = Offset(cAmp.x, 100.81f)
private val tAmpValue = Offset(cAmp.x, 177.29f)
private val tOct = Offset(cOct.x, 101.22f)
private val tOctValue = Offset(cOct.x, 177.58f)
private val tCent = Offset(cCent.x, 201.12f)
private val tCentValue = Offset(cCent.x, 278.19f)
private val tPan = Offset(cPan.x, tCent.y)
private val tPanValue = Offset(cPan.x, tCentValue.y)

/** Omrekening ontwerpeenheden <-> schermpixels. */
internal class Fit(size: IntSize) {
    val s = min(size.width / DESIGN_W, size.height / DESIGN_H)
    val ox = (size.width - DESIGN_W * s) / 2
    val oy = (size.height - DESIGN_H * s) / 2
    fun x(u: Float) = ox + u * s
    fun y(u: Float) = oy + u * s
    fun toUnitX(px: Float) = (px - ox) / s
    fun toUnitY(px: Float) = (px - oy) / s
}

/** Hoe ver de inhoud omhoog geschoven is (ontwerpeenheden, 0 = bovenkant). */
private object Scroll {
    var offset by mutableFloatStateOf(0f)
        private set
    /** Verst mogelijke stand: de onderkant van de inhoud staat dan net boven de onderrand van het venster. */
    fun max() = CONTENT_H - view().bottom
    fun set(v: Float) { offset = v.coerceIn(0f, max()) }
    /** Na het tonen/verbergen van het keyboard: binnen het nieuwe bereik blijven. */
    fun clamp() = set(offset)

    /** Lengte en bovenkant van de schuif van de scrollbalk. */
    fun thumbLen() = max(SB_THUMB_MIN, SB_TRACK_LEN * view().h / CONTENT_H)
    fun thumbTop() = SB_TRACK_TOP + (SB_TRACK_LEN - thumbLen()) * offset / max()
}

private enum class Target {
    NONE, POWER_A, POWER_B, TAB_A, TAB_B, MIX, WT_BUTTON, DISPLAY, FADER, AMP, OCT, CENT, PAN,
    DSP_LINE, SCROLLBAR, SCROLL_BG, KB_ICON, LATCH_ICON, PRESET_NAME, PRESET_SAVE, EXIT,
}

private fun near(ux: Float, uy: Float, c: Offset) = hypot(ux - c.x, uy - c.y) <= KNOB_TOUCH_R

/** [ux], [uy] = plek op het scherm in ontwerpeenheden. */
private fun hitTest(ux: Float, uy: Float): Target {
    if (rDspBox.contains(ux, uy)) return Target.DSP_LINE
    if (rPresetName.contains(ux, uy)) return Target.PRESET_NAME
    if (rPresetSave.contains(ux, uy)) return Target.PRESET_SAVE
    if (rExit.contains(ux, uy)) return Target.EXIT
    if (rScrollStrip.contains(ux, uy) && uy >= MENU_EDGE) return Target.SCROLLBAR
    if (rIconKeyboard.contains(ux, uy)) return Target.KB_ICON
    if (rIconLatch.contains(ux, uy)) return Target.LATCH_ICON
    if (!view().contains(ux, uy)) return Target.NONE     // menubalken en keyboard (dat heeft een eigen laag)
    val cy = uy + Scroll.offset                          // plek in de inhoud
    return when {
        rPowerA.contains(ux, cy) -> Target.POWER_A
        rPowerB.contains(ux, cy) -> Target.POWER_B
        rTabA.contains(ux, cy) -> Target.TAB_A
        rTabB.contains(ux, cy) -> Target.TAB_B
        rMixTouch.contains(ux, cy) -> Target.MIX
        rWavetableBtn.contains(ux, cy) -> Target.WT_BUTTON
        rDisplay.contains(ux, cy) -> Target.DISPLAY
        rFaderTouch.contains(ux, cy) -> Target.FADER
        near(ux, cy, cAmp) -> Target.AMP
        near(ux, cy, cOct) -> Target.OCT
        near(ux, cy, cCent) -> Target.CENT
        near(ux, cy, cPan) -> Target.PAN
        else -> Target.SCROLL_BG                         // lege plek: vegen = scrollen
    }
}

@Composable
fun SynthScreen() {
    val measurer = rememberTextMeasurer()
    val uiScope = rememberCoroutineScope()   // voor het uitlopen van het scrollen (loopt mee met de beeldverversing)

    // DSP-meting ca. 4 keer per seconde ophalen.
    LaunchedEffect(Unit) {
        while (true) {
            SynthState.stats = NativeSynth.readStats()
            delay(250)
        }
    }
    // FPS: aantal getekende beelden in de afgelopen seconde.
    LaunchedEffect(Unit) {
        FrameCounter.frames.set(0)
        var last = System.nanoTime()
        while (true) {
            delay(1000)
            val now = System.nanoTime()
            SynthState.fps = Math.round(FrameCounter.frames.getAndSet(0) * 1e9f / (now - last))
            last = now
        }
    }

    // Het scherm bestaat uit lagen die elk alleen opnieuw getekend worden als hún inhoud
    // verandert. Bij scrollen wordt de inhoudslaag niet opnieuw getekend, maar alleen
    // verschoven (dat doet de grafische chip); alleen de scrollbalk tekent opnieuw.
    Box(Modifier.fillMaxSize().pointerInput(Unit) { handleTouches(uiScope) }) {
        // 1. Achtergrond + venster waarbinnen de inhoud zichtbaar is
        Box(
            Modifier.fillMaxSize().drawWithContent {
                drawRect(ColDark)   // rand buiten het ontwerp (bij een andere schermverhouding)
                val fit = Fit(IntSize(size.width.toInt(), size.height.toInt()))
                val v = view()
                clipRect(fit.x(v.x), fit.y(v.y), fit.x(v.x + v.w), fit.y(v.bottom)) {
                    this@drawWithContent.drawContent()
                }
            },
        ) {
            // 2. Scrollbare inhoud: wordt één keer getekend en daarna alleen verschoven
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    translationY = -Scroll.offset * Fit(IntSize(size.width.toInt(), size.height.toInt())).s
                },
            ) {
                drawContent(Fit(IntSize(size.width.toInt(), size.height.toInt())), measurer)
            }
        }
        // 3. Vaste delen: menubalken, iconen, scrollstrook, DSP-meting
        Canvas(Modifier.fillMaxSize()) {
            drawChrome(Fit(IntSize(size.width.toInt(), size.height.toInt())), measurer, SynthState.stats, SynthState.fps)
        }
        // 4. Scrollbalk (tekent opnieuw tijdens het scrollen, maar is klein)
        Canvas(Modifier.fillMaxSize()) {
            drawScrollbar(Fit(IntSize(size.width.toInt(), size.height.toInt())))
        }
        // 5. Oscilloscoop en VU-meter: ca. 30 keer per seconde ververst.
        MetersLayer(measurer)
        // 5b. Keuzevenster van de wavetables (alleen als het open is). Het keyboard ligt
        //     erboven en blijft bespeelbaar, zodat je een wavetable meteen kunt beluisteren.
        Canvas(Modifier.fillMaxSize()) {
            if (SynthState.menuOpen) drawWavetableMenu(Fit(IntSize(size.width.toInt(), size.height.toInt())), measurer)
        }
        // 6. Keyboard: tekent alleen opnieuw als er een andere toets klinkt. Eigen aanraaklaag
        //    voor meerdere vingers tegelijk; aanrakingen buiten het keyboard gaan door naar 1.
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) { handleKeyboard() }) {
            drawKeyboard(Fit(IntSize(size.width.toInt(), size.height.toInt())), measurer)
        }
        // 7. Presetlijst en presetvensters: bovenop alles; vangt alleen aanrakingen als er
        //    een venster open is (dan is ook het keyboard even niet bespeelbaar).
        PresetLayer(measurer)
    }
}

// ----------------------------------------------------------------------------
//  Bediening
// ----------------------------------------------------------------------------

private const val DOUBLE_TAP_MS = 300L
private var lastTapTarget = Target.NONE
private var lastTapTime = 0L
private var flingJob: Job? = null

private suspend fun PointerInputScope.handleTouches(scope: CoroutineScope) {
    awaitEachGesture {
        val fit = Fit(size)   // actuele grootte van het scherm
        val down = awaitFirstDown()
        flingJob?.cancel()    // een nieuwe aanraking stopt het uitlopen van het scrollen
        val ux = fit.toUnitX(down.position.x)
        val uy = fit.toUnitY(down.position.y)

        // Keuzevenster open: alleen tikken telt (kiezen of sluiten); vegen doet niets.
        // Het keyboard blijft bespeelbaar (dat heeft zijn eigen laag).
        if (SynthState.menuOpen) {
            down.consume()
            var moved = 0f
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                moved += abs(change.positionChange().x) + abs(change.positionChange().y)
                change.consume()
            }
            if (moved < 3f * fit.s) menuTap(ux, uy)
            return@awaitEachGesture
        }

        val target = hitTest(ux, uy)
        if (target == Target.NONE) return@awaitEachGesture
        down.consume()

        // Scrollbalk: tik je naast de schuif, dan springt de schuif met zijn midden naar je vinger.
        if (target == Target.SCROLLBAR) {
            val top = Scroll.thumbTop()
            val len = Scroll.thumbLen()
            if (uy < top || uy > top + len) {
                Scroll.set((uy - len / 2 - SB_TRACK_TOP) / (SB_TRACK_LEN - len) * Scroll.max())
            }
        }
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, down.position)

        // De knoppen in het paneel horen bij de gekozen tab.
        val osc = SynthState.sel
        // Oct klikt in stappen; Cent en Pan in hele stappen: tijdens het slepen een
        // tussenwaarde bijhouden en afronden.
        var octRaw = osc.octave.toFloat()
        var centRaw = osc.cent
        var panRaw = osc.pan
        var moved = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            velocity.addPosition(change.uptimeMillis, change.position)
            val d = change.positionChange()
            moved += abs(d.x) + abs(d.y)
            val dragFrac = -d.y / (KNOB_DRAG_RANGE * fit.s)   // omhoog = meer
            when (target) {
                Target.FADER -> {
                    val travel = (FADER_BOTTOM - FADER_TOP) * fit.s
                    osc.updatePosition(osc.position - d.y / travel)
                }
                Target.MIX -> SynthState.updateMix(SynthState.mix + d.x / ((MIX_RIGHT - MIX_LEFT) * fit.s))
                Target.AMP -> osc.updateKnob(osc.knob + dragFrac)
                Target.OCT -> {
                    octRaw = (octRaw + dragFrac * 270f / OCT_STEP_DEG).coerceIn(-2f, 1f)
                    osc.updateOctave(octRaw.roundToInt())
                }
                Target.CENT -> {
                    centRaw = (centRaw + dragFrac * 200f).coerceIn(-100f, 100f)
                    osc.updateCent(centRaw)
                }
                Target.PAN -> {
                    panRaw = (panRaw + dragFrac * 200f).coerceIn(-100f, 100f)
                    osc.updatePan(panRaw)
                }
                // Vegen: de inhoud volgt de vinger.
                Target.SCROLL_BG -> Scroll.set(Scroll.offset - d.y / fit.s)
                // Schuif van de scrollbalk: omlaag slepen = inhoud omhoog.
                Target.SCROLLBAR ->
                    Scroll.set(Scroll.offset + d.y / fit.s * Scroll.max() / (SB_TRACK_LEN - Scroll.thumbLen()))
                else -> {}
            }
            change.consume()
        }

        // Na het vegen loopt het scrollen nog even uit (zoals je gewend bent op een tablet).
        if (target == Target.SCROLL_BG && moved >= 3f * fit.s) {
            val vy = velocity.calculateVelocity().y          // pixels per seconde
            if (abs(vy) > 50f) {
                val s = fit.s
                flingJob = scope.launch {
                    animateDecay(Scroll.offset * s, -vy, FloatExponentialDecaySpec()) { value, _ ->
                        Scroll.set(value / s)
                    }
                }
            }
        }

        // Tikken (nauwelijks bewogen)
        if (moved < 3f * fit.s) {
            val now = System.currentTimeMillis()
            val isDouble = lastTapTarget == target && now - lastTapTime < DOUBLE_TAP_MS
            lastTapTarget = target
            lastTapTime = now
            when (target) {
                Target.POWER_A -> SynthState.a.toggleOn()
                Target.POWER_B -> SynthState.b.toggleOn()
                Target.TAB_A -> SynthState.tab = 0
                Target.TAB_B -> SynthState.tab = 1
                Target.WT_BUTTON -> SynthState.menuOpen = true
                Target.MIX -> if (isDouble) SynthState.updateMix(SynthState.MIX_DEFAULT)
                Target.DISPLAY -> SynthState.show3d = !SynthState.show3d
                Target.DSP_LINE -> NativeSynth.resetPeak()
                Target.FADER -> if (isDouble) osc.updatePosition(0f)
                Target.AMP -> if (isDouble) osc.updateKnob(SynthState.KNOB_DEFAULT)
                Target.OCT -> if (isDouble) osc.updateOctave(0)
                Target.CENT -> if (isDouble) osc.updateCent(0f)
                Target.PAN -> if (isDouble) osc.updatePan(0f)
                Target.KB_ICON -> { Keys.toggleVisible(); Scroll.clamp() }
                Target.LATCH_ICON -> Keys.toggleLatch()
                Target.PRESET_NAME -> presetBarTap("name")
                Target.PRESET_SAVE -> presetBarTap("save")
                Target.EXIT -> presetBarTap("exit")
                Target.SCROLLBAR, Target.SCROLL_BG, Target.NONE -> {}
            }
        } else {
            lastTapTarget = Target.NONE
        }
    }
}

// ----------------------------------------------------------------------------
//  Tekenen
// ----------------------------------------------------------------------------

internal fun DrawScope.rect(fit: Fit, r: Area, color: Color) =
    drawRect(color, Offset(fit.x(r.x), fit.y(r.y)), Size(r.w * fit.s, r.h * fit.s))

internal fun DrawScope.text(
    fit: Fit, measurer: TextMeasurer, text: String, at: Offset,
    sizeUnits: Float, bold: Boolean, color: Color, maxWidthUnits: Float = Float.MAX_VALUE,
) {
    var px = sizeUnits * fit.s
    var style = TextStyle(
        fontFamily = Lato,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = px.toSp(),
    )
    var layout = measurer.measure(text, style)
    val maxPx = maxWidthUnits * fit.s
    if (layout.size.width > maxPx) {                 // te breed: kleiner maken tot het past
        px *= maxPx / layout.size.width
        style = style.copy(fontSize = px.toSp())
        layout = measurer.measure(text, style)
    }
    drawText(
        layout, color = color,
        topLeft = Offset(fit.x(at.x) - layout.size.width / 2f, fit.y(at.y) - layout.firstBaseline),
    )
}

/** Vaste delen: scrollstrook, menubalken, iconen en DSP-meting (scrollen niet mee). */
private fun DrawScope.drawChrome(fit: Fit, measurer: TextMeasurer, stats: Stats?, fps: Int) {
    rect(fit, rScrollStrip, ColPanel)
    rect(fit, rMenuTop, ColDark)
    rect(fit, rMenuLeft, ColDark)
    // Icoon oranje = aan (zoals de tekst On/Off)
    drawKeyboardIcon(fit, iconKeyboard, if (Keys.visible) ColAccent else ColIcon)
    drawLatchIcon(fit, iconLatch, if (Keys.latch) ColAccent else ColIcon)
    drawDspBox(fit, measurer, stats, fps)
    drawExitIcon(fit)
    drawPresetBar(fit, measurer)
}

/** Alles in het scrollvak, in ontwerpeenheden (de inhoud loopt door tot [CONTENT_H]). */
private fun DrawScope.drawContent(fit: Fit, measurer: TextMeasurer) {
    val s = fit.s
    val osc = SynthState.sel   // het paneel toont de gekozen oscillator
    rect(fit, Area(0f, 0f, DESIGN_W, CONTENT_H), ColBg)
    rect(fit, rPanel, ColPanel)

    // --- Tabs OSC A / OSC B en de fader Mix ------------------------------------
    // Gekozen tab: rand en tekst in de kleur van de oscillator (A oranje, B blauw); andere
    // tab grijs. Icoon oranje = die oscillator staat aan.
    for (i in 0..1) {
        val chosen = SynthState.tab == i
        val col = if (chosen) oscColor(i) else ColTabOff
        drawButton(fit, if (i == 0) rTabA else rTabB, accent = col)
        text(fit, measurer, if (i == 0) "OSC A" else "OSC B", if (i == 0) tTabA else tTabB, TITLE_SIZE, true, col)
        drawPowerIcon(fit, if (i == 0) iconPowerA else iconPowerB,
            if (SynthState.oscs[i].on) ColAccent else ColIcon)
    }
    text(fit, measurer, "Mix", tMix, TEXT_SIZE, true, ColText)
    val trackR = CornerRadius(1.47f * s, 1.5f * s)
    val mixX = MIX_LEFT + SynthState.mix * (MIX_RIGHT - MIX_LEFT)
    // links van de schuif oranje (het deel van A), rechts blauw (het deel van B)
    drawRoundRect(ColAccent, Offset(fit.x(rMixTrack.x), fit.y(rMixTrack.y)),
        Size((mixX - rMixTrack.x) * s, rMixTrack.h * s), trackR)
    drawRoundRect(ColOscB, Offset(fit.x(mixX), fit.y(rMixTrack.y)),
        Size((rMixTrack.x + rMixTrack.w - mixX) * s, rMixTrack.h * s), trackR)
    val mixCy = rMixTrack.y + rMixTrack.h / 2
    val mixTl = Offset(fit.x(mixX - MIX_THUMB_W / 2), fit.y(mixCy - MIX_THUMB_H / 2))
    val mixSz = Size(MIX_THUMB_W * s, MIX_THUMB_H * s)
    val mixR = CornerRadius(1.46f * s, 1.11f * s)
    drawRoundRect(ColKnob, mixTl, mixSz, mixR)
    drawRoundRect(ColKnobEdge, mixTl, mixSz, mixR, style = Stroke(0.7f * s))
    drawLine(ColPointer, Offset(fit.x(mixX), fit.y(mixCy - 4.85f)), Offset(fit.x(mixX), fit.y(mixCy + 4.85f)),
        strokeWidth = 1.2f * s, cap = StrokeCap.Round)

    drawRoundRect(   // scheidingslijn in het paneel, ook in de kleur van de gekozen oscillator
        oscColor(SynthState.tab), Offset(fit.x(rSeparator.x), fit.y(rSeparator.y)),
        Size(rSeparator.w * s, rSeparator.h * s), CornerRadius(1f * s),
    )
    drawRoundRect(   // streep onder de tabs, in de kleur van de gekozen oscillator
        oscColor(SynthState.tab), Offset(fit.x(rTitleLine.x), fit.y(rTitleLine.y)),
        Size(rTitleLine.w * s, rTitleLine.h * s), CornerRadius(1.5f * s),
    )

    // --- Knop Wavetable: toont de naam van de gekozen wavetable; tik = keuzevenster ----
    if (Wavetables.version < 0) return   // lezen = opnieuw tekenen zodra de tabellen er zijn
    drawButton(fit, rWavetableBtn)
    text(fit, measurer, Wavetables.name(osc.table), Offset(rWavetableBtn.cx, rWavetableBtn.y + BTN_TEXT_DY),
        TEXT_SIZE, true, ColText, maxWidthUnits = rWavetableBtn.w - 6f)

    // --- Wavetable-scherm ----------------------------------------------------
    rect(fit, rDisplay, ColDark)
    drawWavetable(
        fit.x(rDisplay.x), fit.y(rDisplay.y), rDisplay.w * s, rDisplay.h * s,
        osc.table, osc.position, SynthState.show3d, s, ColAccent, ColWaveGrey,
    )

    // --- Fader Wave ----------------------------------------------------------
    text(fit, measurer, "Wave", tWave, TEXT_SIZE, true, ColText)
    val faderR = CornerRadius(1.5f * s, 1.47f * s)
    drawRoundRect(
        ColTrack, Offset(fit.x(rFaderTrack.x), fit.y(rFaderTrack.y)),
        Size(rFaderTrack.w * s, rFaderTrack.h * s), faderR,
    )
    val pos = osc.position
    val thumbY = FADER_BOTTOM - pos * (FADER_BOTTOM - FADER_TOP)
    drawRoundRect(
        ColAccent, Offset(fit.x(rFaderTrack.x), fit.y(thumbY)),
        Size(rFaderTrack.w * s, (rFaderTrack.bottom - thumbY) * s), faderR,
    )
    val thumbTopLeft = Offset(fit.x(FADER_CX - FADER_THUMB_W / 2), fit.y(thumbY - FADER_THUMB_H / 2))
    val thumbSize = Size(FADER_THUMB_W * s, FADER_THUMB_H * s)
    val thumbR = CornerRadius(1.11f * s, 1.46f * s)
    drawRoundRect(ColKnob, thumbTopLeft, thumbSize, thumbR)
    drawRoundRect(ColKnobEdge, thumbTopLeft, thumbSize, thumbR, style = Stroke(0.7f * s))
    drawLine(
        ColPointer, Offset(fit.x(FADER_CX - 4.85f), fit.y(thumbY)), Offset(fit.x(FADER_CX + 4.85f), fit.y(thumbY)),
        strokeWidth = 1.2f * s, cap = StrokeCap.Round,
    )
    text(fit, measurer, String.format(Locale.US, "%d %%", Math.round(pos * 100)), tWaveValue,
        TEXT_SIZE, false, ColText)

    // --- Knop Amp: boog vanaf linksonder -------------------------------------
    text(fit, measurer, "Amp", tAmp, TEXT_SIZE, true, ColText)
    drawKnob(fit, cAmp, -135f + 270f * osc.knob, arcFromDeg = -135f, ColAccent)
    text(fit, measurer, osc.volumeText(), tAmpValue, TEXT_SIZE, false, ColText)

    // --- Knop Pan: paarse boog vanaf boven (C = recht omhoog) -----------------
    text(fit, measurer, "Pan", tPan, TEXT_SIZE, true, ColText)
    drawKnob(fit, cPan, 135f * osc.pan / 100f, arcFromDeg = 0f, ColPan)
    text(fit, measurer, osc.panText(), tPanValue, TEXT_SIZE, false, ColText)

    // --- Knoppen Oct en Cent: gele boog vanaf boven (0 = recht omhoog) -------
    text(fit, measurer, "Oct", tOct, TEXT_SIZE, true, ColText)
    drawKnob(fit, cOct, OCT_STEP_DEG * osc.octave, arcFromDeg = 0f, ColPitch)
    text(fit, measurer, osc.octaveText(), tOctValue, TEXT_SIZE, false, ColText)

    text(fit, measurer, "Cent", tCent, TEXT_SIZE, true, ColText)
    drawKnob(fit, cCent, 135f * osc.cent / 100f, arcFromDeg = 0f, ColPitch)
    text(fit, measurer, osc.centText(), tCentValue, TEXT_SIZE, false, ColText)

    // --- Tijdelijke testruimte (om het scrollen te proberen) ------------------
    rect(fit, rTestSpace, ColPanel)
    text(fit, measurer, "Test space (temporary)", Offset(rTestSpace.cx, rTestSpace.y + rTestSpace.h / 2 + 4f),
        TEXT_SIZE, false, ColWaveGrey)
}

// ----------------------------------------------------------------------------
//  Keuzevenster van de wavetables (design/GUI onderdelen Claude/wavetable-menu-voorbeeld.svg)
//  Staat vast op het scherm (scrollt niet mee), over het paneel. Is het keyboard zichtbaar,
//  dan wordt het venster lager en schuift het omhoog, zodat het boven het keyboard past.
// ----------------------------------------------------------------------------
private const val MENU_COLS = 4
private const val MENU_ROWS = 2
private const val MENU_TITLE_H = 24f
private const val MENU_PAD = 8f
private const val MENU_GAP = 6f

internal fun menuWindow(): Area {
    val x = rPanel.x + 6f
    val w = rPanel.w - 12f
    return if (Keys.visible) Area(x, MENU_EDGE + 6f, w, KB_TOP - 6f - (MENU_EDGE + 6f))
    else Area(x, rPanel.y + 6f, w, rPanel.h - 12f)
}

private fun menuTile(k: Int): Area {
    val win = menuWindow()
    val tw = (win.w - 2 * MENU_PAD - (MENU_COLS - 1) * MENU_GAP) / MENU_COLS
    val th = (win.h - MENU_TITLE_H - MENU_PAD - (MENU_ROWS - 1) * MENU_GAP) / MENU_ROWS
    val c = k % MENU_COLS
    val r = k / MENU_COLS
    return Area(win.x + MENU_PAD + c * (tw + MENU_GAP), win.y + MENU_TITLE_H + r * (th + MENU_GAP), tw, th)
}

private fun menuClose(): Area {
    val win = menuWindow()
    return Area(win.x + win.w - MENU_PAD - 14f, win.y, MENU_PAD + 14f, MENU_TITLE_H)   // ruim rond het kruisje
}

/** Tik in het geopende keuzevenster: tegel = kiezen en sluiten; kruisje of ernaast = sluiten. */
private fun menuTap(ux: Float, uy: Float) {
    val win = menuWindow()
    if (!win.contains(ux, uy) || menuClose().contains(ux, uy)) { SynthState.menuOpen = false; return }
    for (k in 0 until minOf(Wavetables.count, MENU_COLS * MENU_ROWS)) {
        if (menuTile(k).contains(ux, uy)) {
            SynthState.sel.updateTable(k)
            SynthState.menuOpen = false
            return
        }
    }
}

private fun DrawScope.drawWavetableMenu(fit: Fit, measurer: TextMeasurer) {
    val s = fit.s
    val oscI = SynthState.tab
    val osc = SynthState.sel
    val col = oscColor(oscI)
    val v = view()
    // scrollvak erachter dimmen (zwart, 45 %)
    drawRect(Color.Black.copy(alpha = 0.45f), Offset(fit.x(v.x), fit.y(v.y)), Size(v.w * s, v.h * s))
    val win = menuWindow()
    val tl = Offset(fit.x(win.x), fit.y(win.y))
    val sz = Size(win.w * s, win.h * s)
    drawRoundRect(ColDark, tl, sz, CornerRadius(2f * s))
    drawRoundRect(ColTrack, tl, sz, CornerRadius(2f * s), style = Stroke(0.4f * s))
    drawRoundRect(col, tl, Size(win.w * s, 2.6f * s), CornerRadius(1.3f * s))   // streepje bovenaan
    // titel "Wavetable · OSC A": eerst het witte deel meten, dan het gekleurde erachter
    val title = "Wavetable "
    val sub = "· OSC " + (if (oscI == 0) "A" else "B")
    val style = TextStyle(fontFamily = Lato, fontWeight = FontWeight.Bold, fontSize = (TEXT_SIZE * s).toSp())
    val l1 = measurer.measure(title, style)
    val l2 = measurer.measure(sub, style)
    val base = fit.y(win.y + 16.5f)
    drawText(l1, ColText, Offset(fit.x(win.x + MENU_PAD), base - l1.firstBaseline))
    drawText(l2, col, Offset(fit.x(win.x + MENU_PAD) + l1.size.width, base - l2.firstBaseline))
    // kruisje
    val x1 = win.x + win.w - MENU_PAD
    val stroke = 1.6f * s
    drawLine(ColIcon, Offset(fit.x(x1 - 8f), fit.y(win.y + 8f)), Offset(fit.x(x1), fit.y(win.y + 16f)), stroke, StrokeCap.Round)
    drawLine(ColIcon, Offset(fit.x(x1), fit.y(win.y + 8f)), Offset(fit.x(x1 - 8f), fit.y(win.y + 16f)), stroke, StrokeCap.Round)
    // tegels
    for (k in 0 until minOf(Wavetables.count, MENU_COLS * MENU_ROWS)) {
        val r = menuTile(k)
        val chosen = k == osc.table
        val rtl = Offset(fit.x(r.x), fit.y(r.y))
        val rsz = Size(r.w * s, r.h * s)
        drawRoundRect(ColTile, rtl, rsz, CornerRadius(1.6f * s))
        drawRoundRect(if (chosen) col else ColTrack, rtl, rsz, CornerRadius(1.6f * s),
            style = Stroke((if (chosen) 0.8f else 0.3f) * s))
        // voorvertoning: kleine 3D-stapel; bij de gekozen tegel in de kleur van de oscillator
        // op de huidige Wave-stand, bij de andere de voorste golfvorm in lichtgrijs
        drawWavetable(
            fit.x(r.x + 4f), fit.y(r.y + 3f), (r.w - 8f) * s, (r.h - 18f) * s,
            k, if (chosen) osc.position else 0f, true, s * 0.7f,
            if (chosen) col else ColIcon, ColWaveGrey, lines = 12,
        )
        text(fit, measurer, Wavetables.name(k), Offset(r.cx, r.y + r.h - 5f), 9f, false,
            if (chosen) ColText else ColKnobEdge, maxWidthUnits = r.w - 6f)
    }
}

/** Scrollbalk rechts: donkere baan met een grijze schuif in de kleur van de iconen (drie grijplijntjes). */
private fun DrawScope.drawScrollbar(fit: Fit) {
    val s = fit.s
    drawRoundRect(
        ColTrack, Offset(fit.x(SB_TRACK_X), fit.y(SB_TRACK_TOP)),
        Size(SB_TRACK_W * s, SB_TRACK_LEN * s), CornerRadius(SB_R * s),
    )
    val top = Scroll.thumbTop()
    val len = Scroll.thumbLen()
    val tl = Offset(fit.x(SB_THUMB_X), fit.y(top))
    val sz = Size(SB_THUMB_W * s, len * s)
    drawRoundRect(ColIcon, tl, sz, CornerRadius(SB_R * s))
    val mid = top + len / 2
    val x0 = fit.x(rScrollStrip.x + 10.583f)
    val x1 = fit.x(rScrollStrip.x + 15.875f)
    for (k in -1..1) {
        val y = fit.y(mid + k * 2.778f)
        drawLine(ColPointer, Offset(x0, y), Offset(x1, y), strokeWidth = 0.794f * s, cap = StrokeCap.Round)
    }
}

/** Tekent op een raster van 48 x 48 (zoals de icoon-SVG's), op plek [at], met de schaal uit het ontwerp. */
internal inline fun DrawScope.icon(fit: Fit, at: Offset, scale: Float = ICON_SCALE, block: DrawScope.() -> Unit) {
    val k = scale * fit.s
    withTransform({
        translate(fit.x(at.x), fit.y(at.y))
        scale(k, k, pivot = Offset.Zero)
    }, block)
}

/** Icoon keyboard (design/GUI onderdelen Claude/icoon-keyboard.svg). Tik = keyboard tonen/verbergen. */
private fun DrawScope.drawKeyboardIcon(fit: Fit, at: Offset, col: Color) = icon(fit, at) {
    drawRoundRect(col, Offset(5f, 11f), Size(38f, 26f), CornerRadius(3f), style = Stroke(3f))
    for (x in floatArrayOf(12.6f, 20.2f, 35.4f)) drawLine(col, Offset(x, 24f), Offset(x, 36f), 2f)
    drawLine(col, Offset(27.8f, 12f), Offset(27.8f, 36f), 2f)
    for (x in floatArrayOf(10.1f, 17.7f, 32.9f)) drawRoundRect(col, Offset(x, 12f), Size(5f, 13f), CornerRadius(1f))
}

/** Aan/uit-icoon (design/GUI onderdelen Claude/icoon-aan-uit.svg): cirkel met opening bovenaan en een streepje. */
private fun DrawScope.drawPowerIcon(fit: Fit, at: Offset, col: Color) = icon(fit, at, POWER_SCALE) {
    val st = Stroke(3f, cap = StrokeCap.Round)
    // middelpunt (24, 26), straal 13; opening van -40 tot +40 graden vanaf boven
    // (in Compose: 0 graden = rechts, met de klok mee; boven = -90)
    drawArc(col, -90f + 40f, 280f, false, Offset(11f, 13f), Size(26f, 26f), style = st)
    drawLine(col, Offset(24f, 8f), Offset(24f, 22f), strokeWidth = 3f, cap = StrokeCap.Round)
}

/** Icoon latch (design/GUI onderdelen Claude/icoon-latch.svg), uitsparing in de kleur van de menubalk. Tik = latch aan/uit. */
private fun DrawScope.drawLatchIcon(fit: Fit, at: Offset, col: Color) = icon(fit, at) {
    // klavier met vier witte toetsen, twee zwarte, en de vastgehouden toets (E) gevuld
    drawRoundRect(col, Offset(3f, 17f), Size(32f, 24f), CornerRadius(3f), style = Stroke(3f))
    drawLine(col, Offset(11f, 29f), Offset(11f, 40f), 2f)
    drawLine(col, Offset(19f, 29f), Offset(19f, 40f), 2f)
    drawLine(col, Offset(27f, 18f), Offset(27f, 40f), 2f)
    drawRoundRect(col, Offset(9f, 18f), Size(4f, 11f), CornerRadius(1f))
    drawRoundRect(col, Offset(17f, 18f), Size(4f, 11f), CornerRadius(1f))
    val heldKey = Path().apply {
        moveTo(22f, 18.5f); lineTo(26f, 18.5f); lineTo(26f, 39.5f)
        lineTo(20f, 39.5f); lineTo(20f, 31f); lineTo(22f, 31f); close()
    }
    drawPath(heldKey, col)
    // slotje: eerst een rand in de achtergrondkleur, dan het slotje zelf
    val shackle = Path().apply {
        moveTo(33.5f, 13f); lineTo(33.5f, 9.5f)
        arcTo(androidx.compose.ui.geometry.Rect(33.5f, 5.5f, 41.5f, 13.5f), 180f, 180f, false)
        lineTo(41.5f, 13f)
    }
    drawRoundRect(ColDark, Offset(28f, 11f), Size(19f, 16f), CornerRadius(4f))
    drawPath(shackle, ColDark, style = Stroke(7f, join = StrokeJoin.Round))
    drawPath(shackle, col, style = Stroke(3f))
    drawRoundRect(col, Offset(30f, 13f), Size(15f, 12f), CornerRadius(2f))
    drawCircle(ColPointer, 1.6f, Offset(37.5f, 19f))
}

/**
 * Draaiknop. Hoeken in graden vanaf recht omhoog, met de klok mee positief
 * (bereik -135 .. +135). De gekleurde boog loopt van [arcFromDeg] tot de wijzer.
 */
private fun DrawScope.drawKnob(fit: Fit, center: Offset, pointerDeg: Float, arcFromDeg: Float, arcColor: Color) {
    val s = fit.s
    val c = Offset(fit.x(center.x), fit.y(center.y))
    val arcTopLeft = Offset(c.x - KNOB_ARC_R * s, c.y - KNOB_ARC_R * s)
    val arcSize = Size(2 * KNOB_ARC_R * s, 2 * KNOB_ARC_R * s)
    val arcStroke = Stroke(KNOB_ARC_W * s, cap = StrokeCap.Round)
    // In Compose is 0 graden = rechts (3 uur); recht omhoog = -90.
    drawArc(ColTrack, 135f, 270f, false, arcTopLeft, arcSize, style = arcStroke)
    val sweep = pointerDeg - arcFromDeg
    if (abs(sweep) > 0.01f) {
        drawArc(arcColor, arcFromDeg - 90f, sweep, false, arcTopLeft, arcSize, style = arcStroke)
    }
    drawCircle(ColKnob, KNOB_R * s, c)
    drawCircle(ColKnobEdge, KNOB_R * s, c, style = Stroke(KNOB_EDGE_W * s))
    rotate(pointerDeg, pivot = c) {
        drawLine(
            ColPointer, Offset(c.x, c.y - POINTER_IN * s), Offset(c.x, c.y - POINTER_OUT * s),
            strokeWidth = POINTER_W * s, cap = StrokeCap.Round,
        )
    }
}

internal fun DrawScope.drawButton(fit: Fit, r: Area, accent: Color = ColAccent) {
    val s = fit.s
    val corner = CornerRadius(BTN_RX * s, BTN_RY * s)
    drawRoundRect(ColButton, Offset(fit.x(r.x), fit.y(r.y)), Size(r.w * s, r.h * s), corner)
    val accentPath = Path().apply {
        addRoundRect(
            RoundRect(
                left = fit.x(r.x), top = fit.y(r.y),
                right = fit.x(r.x + r.w), bottom = fit.y(r.y + BTN_ACCENT_H),
                topLeftCornerRadius = corner, topRightCornerRadius = corner,
                bottomRightCornerRadius = CornerRadius.Zero, bottomLeftCornerRadius = CornerRadius.Zero,
            )
        )
    }
    drawPath(accentPath, accent)
}

/** DSP-meting in de bovenste menubalk, op drie regels (het vak is daar smal). Tik = piek terugzetten. */
private fun DrawScope.drawDspBox(fit: Fit, measurer: TextMeasurer, st: Stats?, fps: Int) {
    val fpsText = if (fps < 0) "FPS –" else "FPS $fps"
    val lines = if (st == null || st.sampleRate <= 0) {
        listOf("DSP –   Peak –", "Dropouts –   $fpsText", "– kHz   Buf – ms")
    } else {
        val khz = if (st.sampleRate % 1000 == 0) "${st.sampleRate / 1000}"
        else String.format(Locale.US, "%.1f", st.sampleRate / 1000f)
        val drops = if (st.dropouts < 0) "n/a" else "${st.dropouts}"
        listOf(
            String.format(Locale.US, "DSP %d %%   Peak %d %%", Math.round(st.load * 100), Math.round(st.peak * 100)),
            "Dropouts $drops   $fpsText",
            String.format(Locale.US, "%s kHz   Buf %.1f ms", khz, st.bufferMs),
        )
    }
    lines.forEachIndexed { i, line ->
        text(fit, measurer, line, Offset(rDspBox.cx, rDspBox.y + 8.4f + i * 9.6f), 7.5f, false, ColText,
            maxWidthUnits = rDspBox.w)
    }
}
