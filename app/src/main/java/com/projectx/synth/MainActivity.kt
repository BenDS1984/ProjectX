package com.projectx.synth

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Toestand van één oscillator (A of B). [index] 0 = A, 1 = B (zo heet hij ook in de geluidsmotor).
 * Elke wijziging gaat meteen naar de geluidsmotor.
 */
class OscState(val index: Int) {
    var on by mutableStateOf(false)
        private set
    var table by mutableIntStateOf(0)                          // gekozen wavetable (0 = Basic)
        private set
    var position by mutableFloatStateOf(0f)                    // 0..1 (fader Wave)
        private set
    var knob by mutableFloatStateOf(SynthState.KNOB_DEFAULT)    // 0..1 (knop Amp)
        private set
    var octave by mutableIntStateOf(0)                         // -2..+1 (knop Oct)
        private set
    var cent by mutableFloatStateOf(0f)                        // -100..+100, hele cents (knop Cent)
        private set
    var pan by mutableFloatStateOf(0f)                         // -100 (L) .. +100 (R), hele stappen (knop Pan)
        private set

    /** Aan/uit-icoon in de tab. Geluid klinkt alleen zolang er een toets speelt (zie Keys). */
    fun toggleOn() {
        on = !on
        NativeSynth.setOn(index, on)
        Keys.refreshGate()
    }

    fun updatePosition(p: Float) {
        position = p.coerceIn(0f, 1f)
        NativeSynth.setPosition(index, position)
    }

    /** Andere wavetable kiezen (de klank vloeit in 10 ms over, zie voice.dsp). */
    fun updateTable(t: Int) {
        table = t.coerceIn(0, maxOf(0, Wavetables.count - 1))
        NativeSynth.setTable(index, table)
    }

    fun updateKnob(k: Float) {
        knob = k.coerceIn(0f, 1f)
        NativeSynth.setVolumeDb(index, SynthState.knobToDb(knob))
    }

    fun updateOctave(o: Int) {
        val v = o.coerceIn(-2, 1)
        if (v != octave) { octave = v; NativeSynth.setOctave(index, v) }
    }

    /** Cent wordt op hele cents afgerond: wat je ziet is wat je hoort. */
    fun updateCent(c: Float) {
        val v = Math.round(c.coerceIn(-100f, 100f)).toFloat()
        if (v != cent) { cent = v; NativeSynth.setCent(index, v) }
    }

    /** Pan in hele stappen van -100 (L) tot +100 (R). */
    fun updatePan(p: Float) {
        val v = Math.round(p.coerceIn(-100f, 100f)).toFloat()
        if (v != pan) { pan = v; NativeSynth.setPan(index, v / 100f) }
    }

    /** Toonhoogte van deze oscillator in Hz: laatst gespeelde noot x Oct x Cent. */
    fun pitchHz(): Float = Keys.lastHz * Math.pow(2.0, octave + cent / 1200.0).toFloat()

    fun octaveText(): String = when {
        octave > 0 -> "+$octave"
        octave < 0 -> "−${-octave}"
        else -> "0"
    }

    fun centText(): String {
        val c = cent.toInt()
        return when {
            c > 0 -> "+$c ct"
            c < 0 -> "−${-c} ct"
            else -> "0 ct"
        }
    }

    fun panText(): String {
        val p = pan.toInt()
        return when {
            p > 0 -> "R $p"
            p < 0 -> "L ${-p}"
            else -> "C"
        }
    }

    fun volumeText(): String =
        if (knob <= 0.0005f) "−∞ dB"
        else String.format(Locale.US, "%.1f dB", SynthState.knobToDb(knob)).replace('-', '−')

    /** Huidige instellingen, voor een preset. */
    fun snapshot() = OscData(on, table, position, knob, octave, cent, pan)

    /** Instellingen uit een preset overnemen en doorgeven aan de geluidsmotor. */
    fun apply(d: OscData) {
        on = d.on
        table = d.table.coerceIn(0, maxOf(0, Wavetables.count - 1))
        position = d.position.coerceIn(0f, 1f)
        knob = d.knob.coerceIn(0f, 1f)
        octave = d.octave.coerceIn(-2, 1)
        cent = Math.round(d.cent.coerceIn(-100f, 100f)).toFloat()
        pan = Math.round(d.pan.coerceIn(-100f, 100f)).toFloat()
        push()
    }

    fun push() {
        NativeSynth.setOn(index, on)
        NativeSynth.setTable(index, table)
        NativeSynth.setPosition(index, position)
        NativeSynth.setVolumeDb(index, SynthState.knobToDb(knob))
        NativeSynth.setOctave(index, octave)
        NativeSynth.setCent(index, cent)
        NativeSynth.setPan(index, pan / 100f)
    }
}

/**
 * Toestand van de bediening. Blijft bestaan zolang de app draait (ook als je even
 * naar een andere app gaat); bij een nieuwe start gelden de beginwaarden.
 */
object SynthState {
    const val KNOB_DEFAULT = 0.8f          // = -12 dB
    const val MIX_DEFAULT = 0.5f

    val oscs = listOf(OscState(0), OscState(1))
    val a get() = oscs[0]
    val b get() = oscs[1]

    /** Welke tab gekozen is (0 = OSC A, 1 = OSC B): het paneel toont die oscillator. */
    var tab by mutableIntStateOf(0)
    val sel get() = oscs[tab]

    var mix by mutableFloatStateOf(MIX_DEFAULT)   // 0 = alleen A .. 1 = alleen B (fader Mix)
        private set

    /** Staat er minstens één oscillator aan? (Keys gebruikt dit voor het aan/uit-signaal.) */
    val on get() = a.on || b.on

    var show3d by mutableStateOf(true)
    /** Keuzevenster van de wavetables open (voor de gekozen tab)? */
    var menuOpen by mutableStateOf(false)
    var stats by mutableStateOf<Stats?>(null)
    /** Beelden per seconde die Android echt op het scherm zette (-1 = nog niet gemeten). */
    var fps by mutableIntStateOf(-1)

    fun updateMix(m: Float) {
        mix = m.coerceIn(0f, 1f)
        NativeSynth.setMix(mix)
    }

    /**
     * Toonhoogte voor de oscilloscoop (om het beeld stil te zetten): die van de oscillator
     * die het sterkst in de mix zit en aan staat.
     */
    fun pitchHz(): Float {
        val wA = if (a.on) Math.cos(mix * Math.PI / 2) else 0.0
        val wB = if (b.on) Math.sin(mix * Math.PI / 2) else 0.0
        return if (wB > wA) b.pitchHz() else a.pitchHz()
    }

    /**
     * Knop Amp: helemaal links = uit (-inf), daarna gelijk verdeeld in dB van -60 tot 0 dB.
     * Naar de DSP gaat "uit" als -120 dB.
     */
    fun knobToDb(k: Float): Float = if (k <= 0.0005f) -120f else -60f + 60f * k

    /** Alle klankinstellingen samen (voor de presets). */
    fun snapshot() = PresetData(a.snapshot(), b.snapshot(), mix)

    /** Een preset instellen. De vloeiende overgangen in de DSP voorkomen klikken. */
    fun apply(d: PresetData) {
        a.apply(d.a)
        b.apply(d.b)
        updateMix(d.mix)
        Keys.refreshGate()
    }

    fun pushAll() {
        Keys.push()   // toonhoogte en aan/uit
        NativeSynth.setMix(mix)
        oscs.forEach { it.push() }
    }
}

/**
 * Telt de beelden die Android op het scherm zet (meting voor het FPS-getal).
 * Android meldt elk getekend beeld op een eigen thread; de UI haalt het aantal elke seconde op.
 * Let op: als er niets verandert, tekent Android niets; dan zie je alleen de ca. 30 beelden
 * per seconde van de oscilloscoop en VU-meter. Tijdens het scrollen hoort het 60 te zijn.
 */
object FrameCounter {
    val frames = AtomicInteger(0)
}

class MainActivity : ComponentActivity() {
    private val frameThread = HandlerThread("frame-teller").apply { start() }
    private val frameListener = Window.OnFrameMetricsAvailableListener { _, _, _ ->
        FrameCounter.frames.incrementAndGet()
    }

    /** Starten en stoppen van de audio gebeurt op een eigen thread: het opbouwen van de
     *  wavetables bij de eerste start duurt even en mag het scherm niet laten haperen. */
    private val audioThread = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        window.addOnFrameMetricsAvailableListener(frameListener, Handler(frameThread.looper))
        Presets.init(filesDir)
        PresetUi.exitApp = { finishAndRemoveTask() }
        setContent { SynthScreen() }
    }

    override fun onDestroy() {
        PresetUi.exitApp = null
        window.removeOnFrameMetricsAvailableListener(frameListener)
        frameThread.quitSafely()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Volledig scherm: status- en knoppenbalk verbergen (terughalen door vanaf de rand te vegen). */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        audioThread.execute {
            loadWavetablesOnce()
            SynthState.pushAll()
            NativeSynth.start()
        }
    }

    /** Wavetables één keer inlezen (ca. 13 MB uit de app), voor het scherm en de geluidsmotor. */
    private fun loadWavetablesOnce() {
        if (Wavetables.count > 0) return
        try {
            val bytes = assets.open("wavetables.bin").use { it.readBytes() }
            Wavetables.load(bytes)
            NativeSynth.loadWavetables(bytes)
        } catch (e: java.io.IOException) {
            android.util.Log.e("ProjectX", "wavetables.bin niet gevonden", e)
        }
    }

    override fun onStop() {
        // Naar de achtergrond: geluid stopt; bij terugkomen gaat het verder zoals het was.
        audioThread.execute { NativeSynth.stop() }
        super.onStop()
    }
}
