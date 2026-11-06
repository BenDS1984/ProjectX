// Minimale basisklassen die de door Faust gegenereerde C++-code nodig heeft.
// (Vervangt de volledige Faust-architectuurbestanden; we gebruiken alleen wat nodig is.)
#pragma once

#ifndef FAUSTFLOAT
#define FAUSTFLOAT float
#endif

#include <algorithm>
#include <cmath>
#include <cstdint>

// Geheugenblok dat Faust leest met de primitive soundfile() (zie voice.dsp).
// Zelfde velden als in Faust' eigen Soundfile.h, maar zonder het inlezen van audiobestanden:
// wij vullen het zelf met de wavetables (zie WavetableData.h).
// Per deel ("part") P: fLength[P] samples vanaf fOffset[P] in kanaal 0.
constexpr int MAX_SOUNDFILE_PARTS = 64;
struct Soundfile {
    void* fBuffers = nullptr;   // float** (één kanaal)
    int* fLength = nullptr;
    int* fSR = nullptr;
    int* fOffset = nullptr;
    int fChannels = 1;
    int fParts = 0;
    bool fIsDouble = false;
};

// Vervangt een soundfile die (nog) niet geladen is: 64 lege delen van 1 sample (stilte).
inline Soundfile* makeSilentSoundfile() {
    static float zero[1] = {0.f};
    static float* chans[1] = {zero};
    static int len[MAX_SOUNDFILE_PARTS], sr[MAX_SOUNDFILE_PARTS], off[MAX_SOUNDFILE_PARTS];
    static Soundfile sf;
    for (int i = 0; i < MAX_SOUNDFILE_PARTS; ++i) { len[i] = 1; sr[i] = 44100; off[i] = 0; }
    sf.fBuffers = chans; sf.fLength = len; sf.fSR = sr; sf.fOffset = off;
    sf.fChannels = 1; sf.fParts = MAX_SOUNDFILE_PARTS;
    return &sf;
}
inline Soundfile* defaultsound = makeSilentSoundfile();

struct Meta {
    virtual ~Meta() = default;
    virtual void declare(const char*, const char*) {}
};

struct UI {
    virtual ~UI() = default;
    virtual void openTabBox(const char*) {}
    virtual void openHorizontalBox(const char*) {}
    virtual void openVerticalBox(const char*) {}
    virtual void closeBox() {}
    virtual void addButton(const char*, FAUSTFLOAT*) {}
    virtual void addCheckButton(const char*, FAUSTFLOAT*) {}
    virtual void addVerticalSlider(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT) {}
    virtual void addHorizontalSlider(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT) {}
    virtual void addNumEntry(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT, FAUSTFLOAT) {}
    virtual void addHorizontalBargraph(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT) {}
    virtual void addVerticalBargraph(const char*, FAUSTFLOAT*, FAUSTFLOAT, FAUSTFLOAT) {}
    virtual void addSoundfile(const char*, const char*, Soundfile**) {}
    virtual void declare(FAUSTFLOAT*, const char*, const char*) {}
};

struct dsp {
    virtual ~dsp() = default;
};
