#pragma once

// ============================================================================
//  Wavetables inlezen uit wavetables.bin (gemaakt door tools/wavetables/make_wavetables.py)
//  en klaarzetten als Faust-"soundfile": één deel (part) per wavetable.
//
//  Gebruikt door de app (SynthEngine) en door de geluidstest op de pc.
//  Wordt één keer bij het opstarten aangeroepen, nooit in de audio-callback.
// ============================================================================

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "faust_base.h"

class WavetableData {
public:
    static constexpr int kMaxTables = 16;

    /** Leest het bestand uit het geheugen. Geeft false bij een fout (dan blijft alles leeg). */
    bool load(const uint8_t* data, size_t size) {
        mSamples.clear(); mNames.clear(); mFrames.clear();
        if (size < 20 || std::memcmp(data, "PXWT", 4) != 0) return false;
        uint32_t version, n, nl, count;
        std::memcpy(&version, data + 4, 4);
        std::memcpy(&n, data + 8, 4);
        std::memcpy(&nl, data + 12, 4);
        std::memcpy(&count, data + 16, 4);
        if (version != 1 || count == 0 || count > kMaxTables) return false;
        size_t pos = 20;
        size_t total = 0;
        // eerst de maten nagaan, dan pas geheugen aanmaken
        std::vector<uint32_t> frames(count);
        for (uint32_t t = 0; t < count; ++t) {
            if (pos + 36 > size) return false;
            uint32_t f; std::memcpy(&f, data + pos + 32, 4);
            const size_t vals = size_t(f) * nl * n;
            if (f < 2 || pos + 36 + vals * 2 > size) return false;
            frames[t] = f;
            total += vals;
            pos += 36 + vals * 2;
        }
        mSamples.resize(total);
        pos = 20;
        size_t off = 0;
        for (uint32_t t = 0; t < count; ++t) {
            char name[33] = {};
            std::memcpy(name, data + pos, 32);
            mNames.emplace_back(name);
            const size_t vals = size_t(frames[t]) * nl * n;
            const uint8_t* src = data + pos + 36;
            for (size_t i = 0; i < vals; ++i) {
                int16_t v; std::memcpy(&v, src + 2 * i, 2);
                mSamples[off + i] = v / 32767.0f;
            }
            mLength[t] = int(vals);
            mOffset[t] = int(off);
            mSR[t] = 48000;
            off += vals;
            pos += 36 + vals * 2;
        }
        // delen die niet bestaan: verwijzen naar de eerste tabel (zo kan een te hoge keuze
        // nooit buiten het geheugen lezen)
        for (int t = int(count); t < MAX_SOUNDFILE_PARTS; ++t) {
            mLength[t] = mLength[0]; mOffset[t] = mOffset[0]; mSR[t] = 48000;
        }
        mFrames = frames;
        mChan[0] = mSamples.data();
        mSf.fBuffers = mChan;
        mSf.fLength = mLength;
        mSf.fSR = mSR;
        mSf.fOffset = mOffset;
        mSf.fChannels = 1;
        mSf.fParts = int(count);
        mN = int(n); mNL = int(nl);
        return true;
    }

    bool loaded() const { return !mSamples.empty(); }
    Soundfile* soundfile() { return loaded() ? &mSf : defaultsound; }
    int count() const { return int(mNames.size()); }
    const std::string& name(int t) const { return mNames[t]; }
    int frames(int t) const { return int(mFrames[t]); }
    size_t bytes() const { return mSamples.size() * sizeof(float); }

private:
    std::vector<float> mSamples;
    std::vector<std::string> mNames;
    std::vector<uint32_t> mFrames;
    float* mChan[1] = {nullptr};
    int mLength[MAX_SOUNDFILE_PARTS] = {};
    int mOffset[MAX_SOUNDFILE_PARTS] = {};
    int mSR[MAX_SOUNDFILE_PARTS] = {};
    Soundfile mSf;
    int mN = 0, mNL = 0;
};
