package com.projectx.synth

/**
 * Brug naar de geluidsmotor (C++ / Faust / Oboe).
 * De interface zet hier alleen waarden; de klank zelf wordt in Faust berekend.
 */
object NativeSynth {
    init {
        System.loadLibrary("projectx")
    }

    fun start(): Boolean = nativeStart()
    /** Eén keer bij het opstarten, vóór [start]: de wavetables (inhoud van assets/wavetables.bin). */
    fun loadWavetables(data: ByteArray): Boolean = nativeLoadWavetables(data)
    fun stop() = nativeStop()

    /** Toets ingedrukt (of gelatcht). Of er echt iets klinkt, hangt ook af van aan/uit per oscillator. */
    fun setGate(on: Boolean) = nativeSetParam(SynthParam.GATE.ordinal, if (on) 1f else 0f)
    fun setFrequency(hz: Float) = nativeSetParam(SynthParam.FREQ.ordinal, hz)
    /** Mix 0 = alleen A .. 1 = alleen B */
    fun setMix(m: Float) = nativeSetParam(SynthParam.MIX.ordinal, m.coerceIn(0f, 1f))

    // Per oscillator: [osc] 0 = A, 1 = B.
    fun setOn(osc: Int, on: Boolean) = osc(osc, OscParam.ON, if (on) 1f else 0f)
    /** Gekozen wavetable 0..7 */
    fun setTable(osc: Int, t: Int) = osc(osc, OscParam.TABLE, t.coerceIn(0, 7).toFloat())
    /** Wavetablepositie 0..1 */
    fun setPosition(osc: Int, p: Float) = osc(osc, OscParam.POSITION, p.coerceIn(0f, 1f))
    /** Volume in dB; -120 betekent uit. */
    fun setVolumeDb(osc: Int, db: Float) = osc(osc, OscParam.VOLUME, db.coerceIn(-120f, 0f))
    /** Octaaf -2..+1 */
    fun setOctave(osc: Int, oct: Int) = osc(osc, OscParam.OCTAVE, oct.coerceIn(-2, 1).toFloat())
    /** Fijnstemming in cent, -100..+100 */
    fun setCent(osc: Int, cent: Float) = osc(osc, OscParam.CENT, cent.coerceIn(-100f, 100f))
    /** Pan -1 (links) .. +1 (rechts) */
    fun setPan(osc: Int, pan: Float) = osc(osc, OscParam.PAN, pan.coerceIn(-1f, 1f))

    private fun osc(osc: Int, p: OscParam, value: Float) =
        nativeSetParam(SynthParam.A_ON.ordinal + osc * OSC_PARAMS + p.ordinal, value)

    /** Meetwaarden van de audio (zie [Stats]). */
    fun readStats(): Stats {
        val a = FloatArray(5)
        nativeGetStats(a)
        return Stats(load = a[0], peak = a[1], dropouts = a[2].toInt(), sampleRate = a[3].toInt(), bufferMs = a[4])
    }

    fun resetPeak() = nativeResetPeak()

    /** Laatste samples van de mix van A en B vóór Amp en Pan (mono, oudste eerst) in [out]; geeft het aantal terug. */
    fun readScope(out: FloatArray): Int = nativeGetScope(out)
    /** Hoogste niveau per kanaal (L, R; 0..1) sinds de vorige aanroep, in [outLR]. */
    fun takeMeterPeaks(outLR: FloatArray) = nativeTakeMeterPeaks(outLR)

    private external fun nativeStart(): Boolean
    private external fun nativeLoadWavetables(data: ByteArray): Boolean
    private external fun nativeStop()
    private external fun nativeSetParam(id: Int, value: Float)
    private external fun nativeGetStats(out: FloatArray)
    private external fun nativeResetPeak()
    private external fun nativeGetScope(out: FloatArray): Int
    private external fun nativeTakeMeterPeaks(out: FloatArray)
}

/**
 * Volgorde moet overeenkomen met ParamId in SynthEngine.h: eerst de gemeenschappelijke
 * parameters, dan per oscillator een blok in de volgorde van [OscParam] (A_ON = begin van A).
 */
enum class SynthParam { GATE, FREQ, MIX, A_ON }

/** Parameters van één oscillator, in de volgorde van SynthEngine.h. */
enum class OscParam { ON, TABLE, POSITION, VOLUME, OCTAVE, CENT, PAN }

private val OSC_PARAMS = OscParam.values().size

/**
 * Gemeten op het toestel zelf:
 * [load]/[peak] = deel van de beschikbare tijd per audioblok dat de klankberekening gebruikt (0..1);
 * [dropouts] = haperingen volgens Android (-1 = niet beschikbaar).
 */
data class Stats(val load: Float, val peak: Float, val dropouts: Int, val sampleRate: Int, val bufferMs: Float)
