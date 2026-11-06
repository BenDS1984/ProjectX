#include "SynthEngine.h"

#include <android/log.h>
#include <cmath>
#include <cstring>
#include <ctime>
#include <vector>

#define LOG_TAG "ProjectX"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Namen van de Faust-parameters (labels in voice.dsp), in de volgorde van ParamId.
const char* kParamNames[PARAM_COUNT] = {
    "gate", "freq", "mix",
    "A_on", "A_table", "A_position", "A_volume", "A_octave", "A_cent", "A_pan",
    "B_on", "B_table", "B_position", "B_volume", "B_octave", "B_cent", "B_pan",
};
const float kParamDefaults[PARAM_COUNT] = {
    0.f, 440.f, 0.5f,
    0.f, 0.f, 0.f, -12.f, 0.f, 0.f, 0.f,
    0.f, 0.f, 0.f, -12.f, 0.f, 0.f, 0.f,
};

// Zo lang (in ms) na "niets aan" nog doorrekenen voordat de DSP stilgelegd wordt:
// het zachte uitgaan duurt 5 ms, dit is ruim daarboven.
constexpr int kSilenceMs = 10;

// Latentie-middenweg: de buffer begint op 2 "bursts" (Oboe-standaard) en wordt bij
// dropouts automatisch groter, tot hoogstens ca. 25 ms.
constexpr double kMaxBufferMs = 25.0;

// De eerste halve seconde na het starten telt niet mee voor de piek:
// dan is alles nog "koud" (caches, kloksnelheid) en zou de piek misleidend hoog zijn.
constexpr int64_t kWarmupNs = 500'000'000;

// Verzamelt de adressen van de Faust-parameters op naam (buiten de audio-callback).
struct ZoneCollector : public UI {
    struct Entry { const char* label; FAUSTFLOAT* zone; };
    std::vector<Entry> entries;
    Soundfile* sound = nullptr;   // de wavetables (Faust vraagt ze op via addSoundfile)
    void addSoundfile(const char*, const char*, Soundfile** zone) override { if (sound) *zone = sound; }

    void add(const char* label, FAUSTFLOAT* zone) { entries.push_back({label, zone}); }
    void addButton(const char* l, FAUSTFLOAT* z) override { add(l, z); }
    void addCheckButton(const char* l, FAUSTFLOAT* z) override { add(l, z); }
    void addVerticalSlider(const char* l, FAUSTFLOAT* z, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT) override { add(l, z); }
    void addHorizontalSlider(const char* l, FAUSTFLOAT* z, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT) override { add(l, z); }
    void addNumEntry(const char* l, FAUSTFLOAT* z, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT) override { add(l, z); }

    FAUSTFLOAT* find(const char* label) const {
        for (auto& e : entries) if (std::strcmp(e.label, label) == 0) return e.zone;
        return nullptr;
    }
};

// Monotone klok in nanoseconden. clock_gettime is realtime-veilig (geen systeemaanroep
// die blokkeert, geen geheugen).
inline int64_t nowNs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return int64_t(ts.tv_sec) * 1'000'000'000 + ts.tv_nsec;
}

// Atomisch "onthoud het grootste": zonder lock, ook als de UI tegelijk terugzet.
inline void atomicMax(std::atomic<float>& a, float v) {
    float cur = a.load(std::memory_order_relaxed);
    while (v > cur && !a.compare_exchange_weak(cur, v, std::memory_order_relaxed)) {}
}

}  // namespace

SynthEngine::SynthEngine() {
    for (int i = 0; i < PARAM_COUNT; ++i) mParams[i].store(kParamDefaults[i]);
}

SynthEngine::~SynthEngine() { stop(); }

void SynthEngine::initDsp(int sampleRate) {
    // init() bouwt ook de wavetables op (eenmalig, duurt even). Alleen opnieuw doen
    // als de samplefrequentie verandert.
    if (sampleRate == mDspSampleRate) return;
    mDsp.init(sampleRate);
    ZoneCollector zc;
    zc.sound = mWavetables.soundfile();   // zonder tabellen: stilte (defaultsound)
    mDsp.buildUserInterface(&zc);
    for (int p = 0; p < PARAM_COUNT; ++p) {
        mZones[p] = zc.find(kParamNames[p]);
        if (!mZones[p]) LOGE("Faust-parameter '%s' niet gevonden", kParamNames[p]);
    }
    mDspSampleRate = sampleRate;
}

bool SynthEngine::openStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(48000)
        // Liever geen omrekening door Oboe zelf; als het toestel echt geen 48 kHz kan,
        // nemen we de frequentie die het toestel geeft (de DSP past zich aan).
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
        ->setUsage(oboe::Usage::Game)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("openStream mislukt: %s", oboe::convertToText(result));
        mStream.reset();
        return false;
    }

    initDsp(mStream->getSampleRate());

    const int32_t maxFrames = static_cast<int32_t>(kMaxBufferMs * mStream->getSampleRate() / 1000.0);
    mTuner = std::make_unique<oboe::LatencyTuner>(*mStream, maxFrames);
    mBusyNs = mAvailNs = mSinceStartNs = 0;
    mLoad.store(0.f);

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart mislukt: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        mTuner.reset();
        return false;
    }
    mPeak.store(0.f);
    mPeakResetRequest.store(false);
    LOGI("Audio gestart: %d Hz, burst %d frames, buffer %d frames, AAudio %d",
         mStream->getSampleRate(), mStream->getFramesPerBurst(),
         mStream->getBufferSizeInFrames(), mStream->usesAAudio() ? 1 : 0);
    return true;
}

bool SynthEngine::loadWavetables(const uint8_t* data, size_t size) {
    std::lock_guard<std::mutex> lock(mLock);
    if (mWavetables.loaded()) return true;            // al gedaan
    if (mStream) return false;                        // alleen vóór het starten
    const bool ok = mWavetables.load(data, size);
    if (ok) {
        LOGI("Wavetables ingelezen: %d tabellen, %.1f MB", mWavetables.count(), mWavetables.bytes() / 1e6);
        mDspSampleRate = 0;   // DSP opnieuw koppelen bij de volgende start
    } else {
        LOGE("Wavetables konden niet ingelezen worden");
    }
    return ok;
}

bool SynthEngine::start() {
    std::lock_guard<std::mutex> lock(mLock);
    mWantRunning = true;
    if (mStream) return true;
    return openStream();
}

void SynthEngine::stop() {
    std::lock_guard<std::mutex> lock(mLock);
    mWantRunning = false;
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
    mTuner.reset();
}

void SynthEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // Bijvoorbeeld: koptelefoon in- of uitgeplugd. Open de stream opnieuw.
    LOGI("Audiostream gesloten (%s), opnieuw openen", oboe::convertToText(error));
    std::lock_guard<std::mutex> lock(mLock);
    mStream.reset();
    mTuner.reset();
    if (mWantRunning) openStream();
}

void SynthEngine::setParam(int id, float value) {
    if (id >= 0 && id < PARAM_COUNT) mParams[id].store(value, std::memory_order_relaxed);
}

void SynthEngine::resetPeak() { mPeakResetRequest.store(true, std::memory_order_relaxed); }

int SynthEngine::getScope(float* out, int n) {
    if (n > kScopeSize) n = kScopeSize;
    const uint32_t w = mScopeWrite.load(std::memory_order_acquire);
    if (w < static_cast<uint32_t>(n)) return 0;
    for (int i = 0; i < n; ++i) out[i] = mScope[(w - n + i) % kScopeSize];
    return n;
}

void SynthEngine::takeMeterPeaks(float* outLR) {
    for (int c = 0; c < 2; ++c) outLR[c] = mMeterPeak[c].exchange(0.f, std::memory_order_relaxed);
}

void SynthEngine::getStats(float* out) {
    out[STAT_LOAD] = mLoad.load(std::memory_order_relaxed);
    out[STAT_PEAK] = mPeak.load(std::memory_order_relaxed);
    out[STAT_XRUNS] = 0.f;
    out[STAT_SAMPLE_RATE] = 0.f;
    out[STAT_BUFFER_MS] = 0.f;
    std::lock_guard<std::mutex> lock(mLock);   // UI-thread, niet de audio-callback
    if (mStream) {
        auto x = mStream->getXRunCount();
        out[STAT_XRUNS] = x ? static_cast<float>(x.value()) : -1.f;   // -1 = niet beschikbaar
        const int sr = mStream->getSampleRate();
        out[STAT_SAMPLE_RATE] = static_cast<float>(sr);
        if (sr > 0) out[STAT_BUFFER_MS] = 1000.f * mStream->getBufferSizeInFrames() / sr;
    }
}

// ---------------------------------------------------------------------------
// Audio-callback: geen geheugen aanmaken, geen locks, geen logging.
// ---------------------------------------------------------------------------

oboe::DataCallbackResult SynthEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                   int32_t numFrames) {
    const int64_t t0 = nowNs();

    if (mTuner) mTuner->tune();   // buffer vergroten na dropouts (Oboe, realtime-veilig)

    auto* out = static_cast<float*>(audioData);
    const int channels = stream->getChannelCount();

    for (int p = 0; p < PARAM_COUNT; ++p)
        if (mZones[p]) *mZones[p] = mParams[p].load(std::memory_order_relaxed);

    // Klinkt er iets? Alleen als er een toets speelt én minstens één oscillator aan staat.
    // Zo niet, dan na kSilenceMs de DSP overslaan en alleen stilte sturen (bespaart de
    // rekenkracht). Bij de volgende toets rekent de DSP gewoon verder waar hij was.
    const bool active = mParams[PARAM_GATE].load(std::memory_order_relaxed) > 0.5f &&
                        (mParams[PARAM_A_ON].load(std::memory_order_relaxed) > 0.5f ||
                         mParams[PARAM_B_ON].load(std::memory_order_relaxed) > 0.5f);
    const int64_t silenceFrames = int64_t(stream->getSampleRate()) * kSilenceMs / 1000;
    if (active) mSilentFrames = 0;
    const bool skip = !active && mSilentFrames >= silenceFrames;
    if (!active && !skip) mSilentFrames += numFrames;

    // De DSP rekent in stukjes van hoogstens kChunk samples, in drie vaste buffers:
    // uitgang 0/1 = links/rechts, uitgang 2 = mix vóór Amp en Pan (oscilloscoop).
    // De VU-meter kijkt naar het geluid zelf (einde van de keten).
    float pk[2] = {0.f, 0.f};
    uint32_t w = mScopeWrite.load(std::memory_order_relaxed);
    float* outs[3] = {mLeft, mRight, mScopeTmp};
    for (int done = 0; done < numFrames;) {
        const int n = numFrames - done < kChunk ? numFrames - done : kChunk;
        if (skip) {
            std::memset(mLeft, 0, sizeof(float) * n);
            std::memset(mRight, 0, sizeof(float) * n);
            std::memset(mScopeTmp, 0, sizeof(float) * n);
        } else {
            mDsp.compute(n, nullptr, outs);
        }
        for (int i = 0; i < n; ++i) {
            float l = mLeft[i], r = mRight[i];
            if (!(std::fabs(l) <= 1.f)) l = 0.f;   // vangnet: ongeldige waarde (NaN) -> stilte
            if (!(std::fabs(r) <= 1.f)) r = 0.f;
            float* f = out + (done + i) * channels;
            if (channels >= 2) {
                f[0] = l; f[1] = r;
                for (int c = 2; c < channels; ++c) f[c] = 0.f;
            } else {
                f[0] = 0.5f * (l + r);
            }
            if (std::fabs(l) > pk[0]) pk[0] = std::fabs(l);
            if (std::fabs(r) > pk[1]) pk[1] = std::fabs(r);
            float sc = mScopeTmp[i];
            if (!(std::fabs(sc) <= 1.5f)) sc = 0.f;   // som van A en B kan tot 1,41 gaan
            mScope[(w + static_cast<uint32_t>(done + i)) % kScopeSize] = sc;
        }
        done += n;
    }
    mScopeWrite.store(w + static_cast<uint32_t>(numFrames), std::memory_order_release);
    atomicMax(mMeterPeak[0], pk[0]);
    atomicMax(mMeterPeak[1], pk[1]);

    // --- DSP-meting --------------------------------------------------------
    const int64_t busy = nowNs() - t0;
    const int sr = stream->getSampleRate();
    const int64_t avail = sr > 0 ? int64_t(numFrames) * 1'000'000'000 / sr : 0;
    if (avail > 0) {
        const float blockLoad = static_cast<float>(busy) / static_cast<float>(avail);
        if (mPeakResetRequest.exchange(false, std::memory_order_relaxed)) mPeak.store(0.f);
        if (mSinceStartNs >= kWarmupNs) {
            if (blockLoad > mPeak.load(std::memory_order_relaxed))
                mPeak.store(blockLoad, std::memory_order_relaxed);
        } else {
            mSinceStartNs += avail;
        }
        mBusyNs += busy;
        mAvailNs += avail;
        if (mAvailNs >= 250'000'000) {   // elke ~0,25 s een nieuw gemiddelde
            mLoad.store(static_cast<float>(mBusyNs) / static_cast<float>(mAvailNs),
                        std::memory_order_relaxed);
            mBusyNs = mAvailNs = 0;
        }
    }
    return oboe::DataCallbackResult::Continue;
}
