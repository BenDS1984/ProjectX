#pragma once

// ============================================================================
//  Geluidsmotor van Project X (laag 2) + koppeling met de Android-audio (laag 3, Oboe).
//
//  v0.9: één monofone stem met twee oscillatoren (de Faust-DSP uit src/main/faust/voice.dsp),
//  elk met een keuze uit 8 wavetables (ingelezen uit assets/wavetables.bin, zie WavetableData.h).
//  De interface (Kotlin) zet alleen parameters; de audio-callback leest ze.
//  Uitwisseling gaat via atomics: geen locks en geen geheugen aanmaken in de callback.
// ============================================================================

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

#include <oboe/Oboe.h>

#include "faust_base.h"
#include "SynthVoice.h"   // gegenereerd door Faust uit src/main/faust/voice.dsp
#include "WavetableData.h"

// Volgorde moet overeenkomen met SynthParam in Kotlin (NativeSynth.kt).
// Eerst de gemeenschappelijke parameters, dan per oscillator (A, B) een blok van
// OSC_PARAMS waarden in dezelfde volgorde.
enum ParamId {
    PARAM_GATE = 0,    // 1 = toets ingedrukt (of gelatcht), 0 = los
    PARAM_FREQ,        // Hz (grondtoon van het keyboard)
    PARAM_MIX,         // 0 = alleen A .. 1 = alleen B
    PARAM_A_ON,        // 1 = aan, 0 = uit
    PARAM_A_TABLE,     // gekozen wavetable 0..7
    PARAM_A_POSITION,  // wavetablepositie 0..1
    PARAM_A_VOLUME,    // dB, -120..0
    PARAM_A_OCTAVE,    // -2..+1
    PARAM_A_CENT,      // -100..+100
    PARAM_A_PAN,       // -1 (links) .. +1 (rechts)
    PARAM_B_ON,
    PARAM_B_TABLE,
    PARAM_B_POSITION,
    PARAM_B_VOLUME,
    PARAM_B_OCTAVE,
    PARAM_B_CENT,
    PARAM_B_PAN,
    PARAM_COUNT
};
constexpr int OSC_PARAMS = PARAM_B_ON - PARAM_A_ON;

// Volgorde moet overeenkomen met NativeSynth.Stats in Kotlin.
enum StatId {
    STAT_LOAD = 0,     // gemiddelde DSP-belasting (0..1) over de laatste ~0,25 s
    STAT_PEAK,         // hoogste belasting van één audioblok sinds de laatste reset
    STAT_XRUNS,        // aantal dropouts (door Android gemeld)
    STAT_SAMPLE_RATE,  // Hz
    STAT_BUFFER_MS,    // huidige buffergrootte in ms
    STAT_COUNT
};

class SynthEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    SynthEngine();
    ~SynthEngine() override;

    bool start();
    void stop();

    // Eén keer bij het opstarten, vóór start(): de wavetables uit wavetables.bin.
    bool loadWavetables(const uint8_t* data, size_t size);

    // Aanroepen vanaf de UI-thread.
    void setParam(int id, float value);
    void getStats(float* out);   // vult STAT_COUNT waarden
    void resetPeak();

    // Voor de oscilloscoop en de VU-meter (UI-thread).
    // Kopieert de laatste n samples van de mix van A en B vóór Amp en Pan (mono), oudste eerst.
    // Geeft het aantal gekopieerde samples terug (0 als er nog niets is).
    int getScope(float* out, int n);
    // Hoogste niveau per kanaal (L, R; 0..1) sinds de vorige aanroep; zet daarna terug op 0.
    void takeMeterPeaks(float* outLR);
    static constexpr int kScopeSize = 4096;   // ca. 85 ms bij 48 kHz

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStream();
    void initDsp(int sampleRate);

    std::shared_ptr<oboe::AudioStream> mStream;
    std::unique_ptr<oboe::LatencyTuner> mTuner;
    std::mutex mLock;            // alleen buiten de audio-callback gebruikt
    bool mWantRunning = false;

    SynthVoice mDsp;
    WavetableData mWavetables;   // de tabellen zelf (ca. 27 MB), gelezen door Faust
    FAUSTFLOAT* mZones[PARAM_COUNT] = {};
    int mDspSampleRate = 0;      // 0 = nog niet opgebouwd

    std::atomic<float> mParams[PARAM_COUNT];

    // Werkbuffers voor de DSP (eenmalig vast aangemaakt, dus geen geheugen in de callback).
    static constexpr int kChunk = 1024;
    float mLeft[kChunk] = {};       // uitgang 0: links
    float mRight[kChunk] = {};      // uitgang 1: rechts
    float mScopeTmp[kChunk] = {};   // uitgang 2: mix vóór Amp en Pan (oscilloscoop)

    // Stilleggen: zolang er niets klinkt (geen toets, of beide oscillatoren uit), rekent de
    // DSP niet. Eerst nog even doorrekenen, zodat het zachte uitgaan (5 ms) afloopt.
    int64_t mSilentFrames = 0;   // alleen audio-thread: zoveel samples al niets aan

    // DSP-meting (geschreven door de audio-thread, gelezen door de UI).
    int64_t mBusyNs = 0;         // alleen audio-thread
    int64_t mAvailNs = 0;        // alleen audio-thread
    int64_t mSinceStartNs = 0;   // alleen audio-thread (opwarmtijd)
    std::atomic<float> mLoad{0.f};
    std::atomic<float> mPeak{0.f};
    std::atomic<bool> mPeakResetRequest{false};

    // Oscilloscoop: ringbuffer, eenmalig vast aangemaakt. Alleen de audio-thread schrijft;
    // de UI leest. Leest de UI net terwijl er geschreven wordt, dan klopt een stukje van
    // het beeld even niet; dat is voor een weergave onschuldig en vraagt geen lock.
    float mScope[kScopeSize] = {};
    std::atomic<uint32_t> mScopeWrite{0};   // totaal aantal geschreven samples

    // VU-meter: hoogste niveau per kanaal sinds de UI het laatst keek.
    std::atomic<float> mMeterPeak[2] = {};
};
