// ============================================================================
//  Geluidstest voor app/src/main/faust/voice.dsp (draait op de pc, niet in de app).
//  Start met:  tools\dsp-test\run-dsp-test.ps1
//
//  Controleert: stilte als het uit staat, niveaus per golfvorm, geen ongeldige
//  waarden (NaN/oneindig) en niets boven 1,0, aan/uit zonder klik, nauwkeurigheid
//  van het volume, bandbegrenzing (geen aliasing), toonhoogte (Oct/Cent), de
//  oscilloscoop-uitgang (vóór de Amp-knop), Osc B, Pan en Mix (gelijk vermogen),
//  alle 8 wavetables (geldige waarden, bandbegrenzing, wisselen zonder klik) en de rekentijd.
//  De wavetables komen uit app/src/main/assets/wavetables.bin (pad als eerste argument).
//  Tests 1-9 spelen alleen Osc A (B uit, Mix helemaal op A, Pan midden) en kijken
//  naar het linkerkanaal; Pan midden geeft daar -3 dB (gelijk vermogen).
//  Let op: rekentijd en opbouwtijd zijn gemeten op de pc, niet op de tablet.
// ============================================================================
#include "faust_base.h"
#include "SynthVoice.h"
#include "WavetableData.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace {

struct Zones : UI {
    std::vector<std::pair<std::string, float*>> e;
    void addButton(const char* l, float* z) override { e.push_back({l, z}); }
    void addCheckButton(const char* l, float* z) override { e.push_back({l, z}); }
    void addHorizontalSlider(const char* l, float* z, float, float, float, float) override { e.push_back({l, z}); }
    void addVerticalSlider(const char* l, float* z, float, float, float, float) override { e.push_back({l, z}); }
    void addNumEntry(const char* l, float* z, float, float, float, float) override { e.push_back({l, z}); }
    Soundfile* sound = nullptr;
    void addSoundfile(const char*, const char*, Soundfile** z) override { if (sound) *z = sound; }
    float* find(const char* n) { for (auto& x : e) if (x.first == n) return x.second; return nullptr; }
};

const double PI = 3.14159265358979;
const int SR = 48000;
int gFails = 0;

void check(bool ok, const char* what) {
    printf("  [%s] %s\n", ok ? " OK " : "FOUT", what);
    if (!ok) gFails++;
}

// Amplitude van harmonische h (DFT over een heel aantal perioden).
double harmonic(const std::vector<float>& b, double f0, int h) {
    double re = 0, im = 0;
    for (size_t i = 0; i < b.size(); ++i) {
        const double w = 2 * PI * f0 * h * i / SR;
        re += b[i] * std::cos(w);
        im += b[i] * std::sin(w);
    }
    return 2 * std::sqrt(re * re + im * im) / b.size();
}

// Zelfde meting, maar met een Hann-venster: minder gevoelig voor een minieme afwijking in
// de toonhoogte (de oscillator speelt bv. 3099,9993 Hz i.p.v. 3100 Hz). Zonder venster telt
// zo'n afwijking als "rest" (een zuivere sinus gaf dan al -57 dB).
double hannW(size_t i, size_t n) { return 0.5 - 0.5 * std::cos(2 * PI * i / (n - 1)); }
double harmonicW(const std::vector<float>& b, double f0, int h) {
    double re = 0, im = 0, sw = 0;
    for (size_t i = 0; i < b.size(); ++i) {
        const double w = hannW(i, b.size()), ph = 2 * PI * f0 * h * i / SR;
        re += w * b[i] * std::cos(ph); im += w * b[i] * std::sin(ph); sw += w;
    }
    return 2 * std::sqrt(re * re + im * im) / sw;
}
double rmsW(const std::vector<float>& b) {
    double s = 0, sw = 0;
    for (size_t i = 0; i < b.size(); ++i) { const double w = hannW(i, b.size()); s += w * w * b[i] * b[i]; sw += w * w; }
    return std::sqrt(s / sw);
}

double rms(const std::vector<float>& b) {
    double s = 0;
    for (float v : b) s += double(v) * v;
    return std::sqrt(s / b.size());
}

// Toonhoogte uit de tijd tussen de eerste en laatste opgaande nuldoorgang
// (met interpolatie tussen twee samples). Geschikt voor de sinus.
double measureFreq(const std::vector<float>& b) {
    double first = -1, last = -1; int n = 0;
    for (size_t i = 1; i < b.size(); ++i) {
        if (b[i - 1] < 0 && b[i] >= 0) {
            const double t = (i - 1) + b[i - 1] / double(b[i - 1] - b[i]);
            if (first < 0) first = t; else n++;
            last = t;
        }
    }
    return n > 0 ? n * SR / (last - first) : 0;
}

}  // namespace

int main(int argc, char** argv) {
    // Wavetables inlezen, net als de app (WavetableData.h)
    const char* wtPath = argc > 1 ? argv[1] : "app/src/main/assets/wavetables.bin";
    std::ifstream in(wtPath, std::ios::binary);
    std::vector<uint8_t> file((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    static WavetableData wt;
    auto t0 = std::chrono::steady_clock::now();
    if (!wt.load(file.data(), file.size())) { printf("FOUT: %s niet (goed) ingelezen\n", wtPath); return 1; }
    auto t1 = std::chrono::steady_clock::now();
    printf("Wavetables ingelezen: %d tabellen, %.1f MB geheugen, %.0f ms op deze pc\n",
           wt.count(), wt.bytes() / 1e6, std::chrono::duration<double, std::milli>(t1 - t0).count());
    for (int t = 0; t < wt.count(); ++t) printf("  %d %-12s %3d golfvormen\n", t, wt.name(t).c_str(), wt.frames(t));
    printf("\n");

    SynthVoice d;
    d.init(SR);
    Zones z;
    z.sound = wt.soundfile();
    d.buildUserInterface(&z);
    float *freq = z.find("freq"), *gate = z.find("gate"), *mix = z.find("mix");
    // Osc A (dezelfde namen als vóór v0.8, zodat tests 1-9 gelijk blijven) en Osc B
    float *pos = z.find("A_position"), *vol = z.find("A_volume"), *oct = z.find("A_octave"),
          *cent = z.find("A_cent"), *onA = z.find("A_on"), *panA = z.find("A_pan");
    float *tblA = z.find("A_table"), *tblB = z.find("B_table");
    float *posB = z.find("B_position"), *volB = z.find("B_volume"), *octB = z.find("B_octave"),
          *centB = z.find("B_cent"), *onB = z.find("B_on"), *panB = z.find("B_pan");
    for (float* p : {freq, gate, mix, pos, vol, oct, cent, onA, panA, posB, volB, octB, centB, onB, panB, tblA, tblB})
        if (!p) { printf("FOUT: een parameter ontbreekt in voice.dsp\n"); return 1; }
    *onA = 1; *onB = 0; *mix = 0; *panA = 0; *panB = 0; *volB = 0; *posB = 0; *octB = 0; *centB = 0;
    const double PANC = std::sqrt(0.5);   // Pan midden: 0,707 per kant (-3 dB)

    if (d.getNumOutputs() != 3) { printf("FOUT: voice.dsp moet 3 uitgangen hebben (L, R, oscilloscoop)\n"); return 1; }
    std::vector<float> buf(SR);     // uitgang 1: links
    std::vector<float> bufR(SR);    // uitgang 2: rechts
    std::vector<float> scope(SR);   // uitgang 3: mix vóór Amp en Pan (oscilloscoop)
    float* outs[3] = {buf.data(), bufR.data(), scope.data()};
    auto run = [&](int n) { d.compute(n, nullptr, outs); };
    // si.smoo begint op 0: eerst een seconde laten inlopen, dan meten.
    auto settle = [&]() { run(SR); run(SR); };

    // 1. Uit = stil ------------------------------------------------------------
    printf("1. Uit = stil\n");
    *gate = 0; *vol = 0; *pos = 0; *freq = 440;
    run(4800);
    float mx = 0;
    for (int i = 0; i < 4800; ++i) mx = std::max(mx, std::fabs(buf[i]));
    check(mx == 0.f, "geen geluid als hij uit staat");

    // 2. Golfvormen -------------------------------------------------------------
    printf("\n2. Golfvormen bij 440 Hz, 0 dB\n");
    const char* names[] = {"sinus", "driehoek", "zaagtand", "vierkant"};
    const float positions[] = {0.f, 1.f / 3, 2.f / 3, 1.f};
    for (int s = 0; s < 4; ++s) {
        d.instanceClear();
        *gate = 1; *vol = 0; *pos = positions[s]; *freq = 440;
        settle();
        double pk = 0; int bad = 0;
        for (float v : buf) { if (!std::isfinite(v)) bad++; pk = std::max(pk, double(std::fabs(v))); }
        std::vector<float> w(buf.begin(), buf.begin() + 24000);   // precies 220 perioden
        printf("  %-9s piek %.3f  niveau %5.1f dBFS  grondtoon %.3f  boventoon 2/3/32: %.3f %.3f %.4f\n",
               names[s], pk, 20 * std::log10(rms(buf)), harmonic(w, 440, 1), harmonic(w, 440, 2),
               harmonic(w, 440, 3), harmonic(w, 440, 32));
        check(bad == 0 && pk <= 1.0, "geldige waarden, niet boven 1,0");
    }

    // 3. Aan/uit zonder klik ---------------------------------------------------
    printf("\n3. Aan/uit zonder klik (sinus)\n");
    d.instanceClear(); *vol = 0; *pos = 0; *gate = 0;
    run(SR);
    *gate = 1; run(2400);
    const double maxStep = 2 * PI * 440 / SR * 1.02;   // grootste sprong van de sinus zelf (+2 %)
    double jump = 0;
    for (int i = 1; i < 2400; ++i) jump = std::max(jump, double(std::fabs(buf[i] - buf[i - 1])));
    check(std::fabs(buf[0]) < 0.01 && jump <= maxStep, "aanzetten begint bij 0 en springt niet");
    run(SR);
    *gate = 0; run(2400);
    jump = 0;
    float tail = 0;
    for (int i = 1; i < 2400; ++i) jump = std::max(jump, double(std::fabs(buf[i] - buf[i - 1])));
    for (int i = 300; i < 2400; ++i) tail = std::max(tail, std::fabs(buf[i]));
    check(jump <= maxStep && tail == 0.f, "uitzetten springt niet en is na 6 ms stil");

    // 4. Volume -----------------------------------------------------------------
    printf("\n4. Volume (sinus, links; verwacht = volume - 3,01 dB, en nog eens -3,01 dB voor Pan midden)\n");
    for (float db : {0.f, -12.f, -60.f, -120.f}) {
        d.instanceClear(); *gate = 1; *pos = 0; *vol = db; *freq = 440;
        settle();
        const double got = 20 * std::log10(rms(buf) + 1e-30);
        char msg[96];
        snprintf(msg, sizeof msg, "%6.1f dB -> %7.2f dBFS (verwacht %7.2f)", db, got, db - 6.02);
        check(std::fabs(got - (db - 6.02)) < 0.1, msg);
    }

    // 5. Hele toonbereik --------------------------------------------------------
    printf("\n5. Toonbereik 20 Hz - 20 kHz, 9 posities\n");
    int rangeFails = 0;
    for (float f = 20; f <= 20000; f *= 1.07f) {
        for (int p = 0; p <= 8; ++p) {
            d.instanceClear(); *freq = f; *pos = p / 8.f; *vol = 0; *gate = 1;
            run(9600);
            for (int i = 0; i < 9600; ++i)
                if (!std::isfinite(buf[i]) || std::fabs(buf[i]) > 1.0f) { rangeFails++; break; }
        }
    }
    check(rangeFails == 0, "overal geldige waarden en niet boven 1,0");

    // 6. Bandbegrenzing ---------------------------------------------------------
    // Toonhoogtes zonder geheel aantal samples per periode, zodat gespiegelde boventonen
    // (aliasing) tussen de echte boventonen vallen. 1 s = een geheel aantal perioden.
    // Rest = alles wat geen boventoon is. 9100 Hz test de hoogste octaafversies,
    // waar zaagtand en vierkant het verst uitschieten (afkappen zou hier gruis geven).
    printf("\n6. Bandbegrenzing (0 dB)\n");
    for (double f0 : {3100.0, 9100.0}) {
        for (int s = 2; s <= 3; ++s) {
            d.instanceClear(); *freq = float(f0); *pos = positions[s]; *vol = 0; *gate = 1;
            settle();
            const double total = rms(buf);
            double harm2 = 0;
            for (int k = 1; f0 * k < SR / 2.0; ++k) { const double a = harmonic(buf, f0, k); harm2 += a * a / 2; }
            const double rest = std::sqrt(std::max(0.0, total * total - harm2));
            const double restDb = 20 * std::log10(rest / total + 1e-12);
            char msg[112];
            snprintf(msg, sizeof msg, "%-8s %5.0f Hz: rest (aliasing + ruis) %.1f dB onder het signaal",
                     names[s], f0, -restDb);
            check(restDb < -50, msg);
        }
    }

    // 7. Toonhoogte: Oct en Cent -------------------------------------------------
    printf("\n7. Toonhoogte (sinus, 440 Hz-basis)\n");
    for (int o = -2; o <= 1; ++o) {
        for (float c : {-100.f, 0.f, 37.f, 100.f}) {
            d.instanceClear(); *freq = 440; *pos = 0; *vol = 0; *gate = 1; *oct = float(o); *cent = c;
            settle();
            const double want = 440.0 * std::pow(2.0, o + c / 1200.0);
            const double got = measureFreq(buf);
            const double errCent = 1200 * std::log2(got / want);
            char msg[112];
            snprintf(msg, sizeof msg, "Oct %+d  Cent %+4.0f -> %8.2f Hz (verwacht %8.2f, afwijking %+.2f cent)",
                     o, c, got, want, errCent);
            check(std::fabs(errCent) < 0.5, msg);
        }
    }
    // Cent in één keer van -100 naar +100: het gladstrijken moet een sprong voorkomen.
    // Een sinus van 0 dB kan per sample hoogstens 2*pi*f/SR veranderen (f = hoogste toon).
    {
        d.instanceClear(); *freq = 440; *pos = 0; *vol = 0; *gate = 1; *oct = 0; *cent = -100;
        settle();
        *cent = 100; run(4800);
        double jump = 0; bool okVals = true;
        for (int i = 1; i < 4800; ++i) {
            jump = std::max(jump, double(std::fabs(buf[i] - buf[i - 1])));
            if (!std::isfinite(buf[i])) okVals = false;
        }
        const double maxStep = 2 * PI * 440 * std::pow(2.0, 100 / 1200.0) / SR * 1.02;
        check(okVals && jump <= maxStep, "Cent -100 -> +100 in één keer: geen sprong in het signaal");
        run(SR);
        const double got = measureFreq(buf);
        check(std::fabs(1200 * std::log2(got / (440 * std::pow(2.0, 100 / 1200.0)))) < 0.5,
              "daarna precies op +100 cent");
    }
    // Oct en Cent snel wisselen bij zaagtand: geen ongeldige waarden, niet boven 1,0
    {
        d.instanceClear(); *freq = 440; *pos = positions[2]; *vol = 0; *gate = 1; *oct = -2; *cent = 0;
        settle();
        bool ok = true;
        for (int r = 0; r < 8; ++r) {
            *oct = float((r % 4) - 2); *cent = (r % 2) ? 100.f : -100.f;
            run(4800);
            for (int i = 0; i < 4800; ++i) if (!std::isfinite(buf[i]) || std::fabs(buf[i]) > 1.0f) ok = false;
        }
        check(ok, "Oct en Cent snel wisselen (zaagtand): geldige waarden, niet boven 1,0");
        *oct = 0; *cent = 0;
    }

    // 8. Oscilloscoop-uitgang ---------------------------------------------------
    // Moet het signaal vóór de Amp-knop zijn: onafhankelijk van het volume, en
    // geluid (links) = oscilloscoop x volume x 0,707 (Pan midden). Uit = vlakke lijn.
    printf("\n8. Oscilloscoop-uitgang (vóór de Amp-knop)\n");
    {
        d.instanceClear(); *freq = 440; *pos = 2.f / 3; *gate = 1; *oct = 0; *cent = 0;
        bool ok = true; double maxErr = 0; double pk0 = 0, pk1 = 0;
        for (float db : {0.f, -12.f, -60.f, -120.f}) {
            *vol = db;
            settle();
            const double g = std::pow(10.0, db / 20.0);
            double pk = 0;
            for (int i = 0; i < SR; ++i) {
                if (!std::isfinite(scope[i]) || std::fabs(scope[i]) > 1.0f) ok = false;
                pk = std::max(pk, double(std::fabs(scope[i])));
                maxErr = std::max(maxErr, std::fabs(buf[i] - g * PANC * scope[i]));
            }
            if (db == 0.f) pk0 = pk; else pk1 = pk;
        }
        check(ok, "geldige waarden, niet boven 1,0");
        char msg[112];
        snprintf(msg, sizeof msg, "zelfde grootte bij 0 dB en -120 dB (piek %.3f en %.3f)", pk0, pk1);
        check(pk0 > 0.5 && std::fabs(pk0 - pk1) < 1e-4, msg);
        snprintf(msg, sizeof msg, "geluid = oscilloscoop x volume x Pan (grootste afwijking %.1e)", maxErr);
        check(maxErr < 1e-4, msg);
        *gate = 0; *vol = 0; run(SR);
        float mx = 0;
        for (int i = 300; i < SR; ++i) mx = std::max(mx, std::fabs(scope[i]));   // na de 5 ms uitloop
        check(mx == 0.f, "uit = vlakke lijn (na de uitloop van 5 ms)");
    }

    // 9. Keyboard: toonsprongen tijdens het spelen ----------------------------------
    // Het keyboard zet freq meteen op een nieuwe noot terwijl de toon klinkt (schuiven,
    // meerdere vingers, latch). Bereik: C1 (octaafknoppen laagst, Oct -2 = 8,2 Hz) tot
    // C7 (octaafknoppen hoogst, Oct +1 = 4186 Hz).
    printf("\n9. Keyboard: toonsprongen tijdens het spelen\n");
    {
        auto midiHz = [](int n) { return 440.0 * std::pow(2.0, (n - 69) / 12.0); };
        bool ok = true;
        for (int s = 0; s < 4; ++s) {
            for (int o : {-2, 0, 1}) {
                d.instanceClear(); *pos = positions[s]; *vol = 0; *gate = 1; *oct = float(o); *cent = 0;
                *freq = float(midiHz(24)); settle();
                for (int r = 0; r < 60; ++r) {
                    *freq = float(midiHz(24 + (r * 37) % 73));   // springt door C1 .. C7
                    run(480);                                     // 10 ms per noot
                    for (int i = 0; i < 480; ++i) if (!std::isfinite(buf[i]) || std::fabs(buf[i]) > 1.0f) ok = false;
                }
            }
        }
        check(ok, "60 snelle notenwissels, 4 golfvormen, Oct -2/0/+1: geldige waarden, niet boven 1,0");
        // Klopt de toonhoogte per toets? (sinus, Oct 0)
        bool pitchOk = true; double worst = 0;
        for (int n : {24, 48, 57, 60, 69, 72, 84, 96}) {
            d.instanceClear(); *pos = 0; *vol = 0; *gate = 1; *oct = 0; *cent = 0; *freq = float(midiHz(n));
            settle();
            const double err = 1200 * std::log2(measureFreq(buf) / midiHz(n));
            worst = std::max(worst, std::fabs(err));
            if (std::fabs(err) > 0.5) pitchOk = false;
        }
        char msg[112];
        snprintf(msg, sizeof msg, "toonhoogte C1 .. C7 klopt (grootste afwijking %.2f cent)", worst);
        check(pitchOk, msg);
    }

    // 10. Osc B, Pan en Mix ----------------------------------------------------------
    // A = sinus 440 Hz, B = sinus 880 Hz (Oct +1), zodat je ze uit elkaar kunt meten
    // met de DFT (1 s = een geheel aantal perioden van beide).
    printf("\n10. Osc B, Pan en Mix (gelijk vermogen)\n");
    {
        auto dB = [](double a) { return 20 * std::log10(a + 1e-12); };
        auto reset = [&]() {
            d.instanceClear(); *freq = 440; *gate = 1;
            *onA = 1; *pos = 0; *vol = 0; *oct = 0; *cent = 0; *panA = 0;
            *onB = 1; *posB = 0; *volB = 0; *octB = 1; *centB = 0; *panB = 0;
        };
        char msg[160];

        // a. Pan van Osc A: links + rechts samen blijft even sterk; uiterst = andere kant stil.
        reset(); *onB = 0; *mix = 0;
        bool panOk = true; double worst = 0;
        for (float p : {-1.f, -0.5f, 0.f, 0.5f, 1.f}) {
            *panA = p; settle();
            const double l = harmonic(buf, 440, 1), r = harmonic(bufR, 440, 1);
            const double err = std::fabs(dB(std::sqrt(l * l + r * r)));   // l^2 + r^2 moet 1 zijn
            worst = std::max(worst, err);
            if (err > 0.1) panOk = false;
            if (p == -1.f && dB(r) > -100) panOk = false;
            if (p == 1.f && dB(l) > -100) panOk = false;
            if (p == 0.f && std::fabs(dB(l) - dB(PANC)) > 0.1) panOk = false;
        }
        snprintf(msg, sizeof msg, "Pan L..R: totaal gelijk (grootste afwijking %.3f dB), midden -3 dB, uiterst = andere kant stil", worst);
        check(panOk, msg);

        // b. Mix: A^2 + B^2 blijft gelijk; mix 0 = alleen A, mix 1 = alleen B.
        bool mixOk = true; worst = 0;
        for (float m : {0.f, 0.25f, 0.5f, 0.75f, 1.f}) {
            reset(); *mix = m; settle();
            const double a = harmonic(buf, 440, 1) / PANC, b = harmonic(buf, 880, 1) / PANC;
            const double err = std::fabs(dB(std::sqrt(a * a + b * b)));
            worst = std::max(worst, err);
            if (err > 0.1) mixOk = false;
            if (m == 0.f && dB(b) > -100) mixOk = false;
            if (m == 1.f && dB(a) > -100) mixOk = false;
            if (m == 0.5f && (std::fabs(dB(a) - dB(PANC)) > 0.1 || std::fabs(dB(b) - dB(PANC)) > 0.1)) mixOk = false;
        }
        snprintf(msg, sizeof msg, "Mix A..B: totaal gelijk (grootste afwijking %.3f dB), midden elk -3 dB, uiteinden = andere weg", worst);
        check(mixOk, msg);

        // c. Aan/uit per oscillator.
        reset(); *mix = 1; *onB = 0; settle();
        float mx = 0;
        for (int i = 0; i < SR; ++i) mx = std::max(mx, std::max(std::fabs(buf[i]), std::fabs(bufR[i])));
        snprintf(msg, sizeof msg, "B uit en Mix helemaal op B: stil (hoogste waarde %.0f dB)", dB(mx));
        check(dB(mx) < -120, msg);
        reset(); *mix = 0.5f; *onA = 0; settle();
        snprintf(msg, sizeof msg, "A uit: geen A meer te horen (%.0f dB), B wel (%.1f dB, verwacht -6,0)",
                 dB(harmonic(buf, 440, 1)), dB(harmonic(buf, 880, 1)));
        check(dB(harmonic(buf, 440, 1)) < -100 && std::fabs(dB(harmonic(buf, 880, 1)) - dB(0.5)) < 0.1, msg);

        // d. Osc B heeft eigen knoppen: toonhoogte (Oct/Cent), Amp en golfvorm los van A.
        reset(); *onA = 0; *mix = 1; *octB = -1; *centB = 50; *volB = -12; settle();
        const double wantB = 440 * std::pow(2.0, -1 + 50 / 1200.0);
        const double errB = 1200 * std::log2(measureFreq(buf) / wantB);
        const double lvB = 20 * std::log10(rms(buf));
        snprintf(msg, sizeof msg, "B: Oct -1, Cent +50 -> afwijking %+.2f cent; Amp -12 dB -> %.2f dBFS (verwacht %.2f)",
                 errB, lvB, -12 - 6.02);
        check(std::fabs(errB) < 0.5 && std::fabs(lvB - (-12 - 6.02)) < 0.1, msg);
        reset(); *mix = 0.5f; *posB = 2.f / 3; *octB = 0; settle();   // A sinus, B zaagtand, zelfde toon
        check(harmonic(buf, 440, 2) > 0.05, "B zaagtand naast A sinus: B's boventonen zijn er (eigen Wave-fader)");

        // e. Oscilloscoop: mix vóór Amp en Pan, dus onafhankelijk van Amp en Pan.
        reset(); *mix = 0.3f; settle();
        std::vector<float> ref(scope);
        *vol = -40; *volB = -6; *panA = -1; *panB = 0.7f; settle();
        // Vergelijk de sterkte van A en B in de oscilloscoop (niet sample voor sample: de fase
        // schuift in 2 s een fractie op door afronding, dat zegt niets over Amp of Pan).
        const double sa = harmonic(ref, 440, 1), sb = harmonic(ref, 880, 1);
        const double diff = std::max(std::fabs(harmonic(scope, 440, 1) - sa), std::fabs(harmonic(scope, 880, 1) - sb));
        snprintf(msg, sizeof msg, "oscilloscoop = cos/sin-mix van A en B (%.3f / %.3f), los van Amp en Pan (afwijking %.1e)",
                 sa, sb, diff);
        check(diff < 1e-4 && std::fabs(sa - std::cos(0.3 * PI / 2)) < 0.01 && std::fabs(sb - std::sin(0.3 * PI / 2)) < 0.01, msg);

        // f. Pan en Mix in één keer van uiterst naar uiterst: geen sprong.
        const double maxStep = 2 * PI * 440 / SR * 1.05;
        reset(); *onB = 0; *mix = 0; *panA = -1; settle();
        *panA = 1; run(4800);
        double jump = 0;
        for (int i = 1; i < 4800; ++i) jump = std::max(jump, double(std::fabs(buf[i] - buf[i - 1])));
        snprintf(msg, sizeof msg, "Pan L -> R in één keer: geen sprong (grootste stap %.4f, sinus zelf %.4f)", jump, maxStep / 1.05);
        check(jump <= maxStep, msg);
        reset(); *octB = 0; *mix = 0; settle();
        *mix = 1; run(4800);
        jump = 0;
        for (int i = 1; i < 4800; ++i) jump = std::max(jump, double(std::fabs(buf[i] - buf[i - 1])));
        snprintf(msg, sizeof msg, "Mix A -> B in één keer: geen sprong (grootste stap %.4f)", jump);
        check(jump <= maxStep, msg);

        // g. Beide op 0 dB, zaagtand + vierkant, uiterst naar dezelfde kant: begrensd.
        reset(); *pos = 2.f / 3; *posB = 1; *octB = 0; *panA = 1; *panB = 1; *mix = 0.5f;
        bool ok = true;
        for (int r = 0; r < 3; ++r) {
            run(SR);
            for (int i = 0; i < SR; ++i) {
                for (float v : {buf[i], bufR[i]}) if (!std::isfinite(v) || std::fabs(v) > 1.0f) ok = false;
                if (!std::isfinite(scope[i]) || std::fabs(scope[i]) > 1.415f) ok = false;
            }
        }
        check(ok, "A en B samen op 0 dB, zelfde kant: L en R niet boven 1,0, oscilloscoop niet boven 1,41");
    }

    // 11. Alle wavetables -----------------------------------------------------------
    printf("\n11. Alle wavetables (Osc A alleen, 0 dB)\n");
    {
        auto setA = [&]() { *onA = 1; *onB = 0; *mix = 0; *panA = 0; *vol = 0; *oct = 0; *cent = 0; *gate = 1; };
        for (int t = 0; t < wt.count(); ++t) {
            // a. hele toonbereik, 9 posities: geldige waarden, niet boven 1,0
            int bad = 0; double peak = 0;
            for (float f = 20; f <= 20000; f *= 1.19f) {
                for (int p = 0; p <= 8; ++p) {
                    d.instanceClear(); setA(); *tblA = float(t); *freq = f; *pos = p / 8.f;
                    run(4800);
                    for (int i = 0; i < 4800; ++i) {
                        if (!std::isfinite(buf[i]) || std::fabs(buf[i]) > 1.0f) bad++;
                        peak = std::max(peak, double(std::fabs(buf[i])));
                    }
                }
            }
            // b. bandbegrenzing bij 3100 en 9100 Hz, op 3 posities (zoals test 6).
            //    Gemeten met een Hann-venster (zie harmonicW). Sommige standen klinken heel zacht
            //    (bv. PWM met een heel smalle puls); daar is de verhouding rest/signaal minder
            //    zinvol. Daarom: rest moet 50 dB onder het signaal liggen, óf onder -70 dBFS
            //    (dan is het onhoorbaar naast het signaal).
            double worst = -999, worstAbs = -999; bool bandOk = true;
            for (double f0 : {3100.0, 9100.0}) {
                for (float p : {0.f, 0.5f, 1.f}) {
                    d.instanceClear(); setA(); *tblA = float(t); *freq = float(f0); *pos = p;
                    settle();
                    const double total = rmsW(buf);
                    if (total < 1e-4) continue;
                    double harm2 = 0;
                    for (int k = 1; f0 * k < SR / 2.0; ++k) { const double a = harmonicW(buf, f0, k); harm2 += a * a / 2; }
                    const double rest = std::sqrt(std::max(0.0, total * total - harm2));
                    const double relDb = 20 * std::log10(rest / total + 1e-12);
                    const double absDb = 20 * std::log10(rest + 1e-12);
                    worst = std::max(worst, relDb);
                    worstAbs = std::max(worstAbs, absDb);
                    if (relDb > -50 && absDb > -70) bandOk = false;
                }
            }
            char msg[200];
            snprintf(msg, sizeof msg, "%-12s geldig en <= 1,0 (piek %.3f); rest (aliasing + ruis) ten minste %.1f dB onder het signaal, ten hoogste %.0f dBFS",
                     wt.name(t).c_str(), peak, -worst, worstAbs);
            check(bad == 0 && bandOk, msg);
        }

        // c. Wisselen tijdens het spelen: de overgang van 10 ms mag niet steiler zijn dan de
        //    golfvormen zelf (plus de kleine helling van het overvloeien).
        bool ok = true; double worstJump = 0, worstRef = 0;
        for (int t = 1; t < wt.count(); ++t) {
            for (float p : {0.f, 0.6f}) {
                auto maxStep = [&](int from, int to) {
                    double m = 0; for (int i = from + 1; i < to; ++i) m = std::max(m, double(std::fabs(buf[i] - buf[i - 1]))); return m; };
                d.instanceClear(); setA(); *tblA = 0; *freq = 220; *pos = p; settle();
                const double refOld = maxStep(0, SR);
                *tblA = float(t); run(SR);                 // wisselen naar t
                const double jump = maxStep(0, 960);       // de eerste 20 ms
                const double refNew = maxStep(4800, SR);   // later: alleen de nieuwe
                const double ref = std::max(refOld, refNew) + 1.0 / 480;
                worstJump = std::max(worstJump, jump - ref); worstRef = std::max(worstRef, ref);
                if (jump > ref * 1.02) ok = false;
                for (int i = 0; i < SR; ++i) if (!std::isfinite(buf[i]) || std::fabs(buf[i]) > 1.0f) ok = false;
                *tblA = 0; run(SR);                        // en terug
                if (maxStep(0, 960) > std::max(refOld, refNew) * 1.02 + 1.0 / 480) ok = false;
            }
        }
        check(ok, "wisselen van Basic naar elke wavetable en terug, tijdens het spelen: geen sprong, geldige waarden");

        // d. Na de overgang klinkt alleen de nieuwe wavetable (vergelijken met meteen de nieuwe).
        d.instanceClear(); setA(); *tblA = 5; *freq = 440; *pos = 0.3f; settle();
        std::vector<float> direct(buf.begin(), buf.end());
        d.instanceClear(); setA(); *tblA = 0; *freq = 440; *pos = 0.3f; run(SR);
        *tblA = 5; run(SR); run(SR);   // tweede seconde: de overgang is dan voorbij
        const double diff = std::fabs(harmonic(buf, 440, 1) - harmonic(direct, 440, 1));
        char msg[140];
        snprintf(msg, sizeof msg, "na het wisselen klinkt alleen de nieuwe wavetable (verschil grondtoon %.1e)", diff);
        check(diff < 1e-3, msg);
        *tblA = 0;
    }

    // 12. Rekentijd --------------------------------------------------------------
    printf("\n12. Rekentijd (A en B aan, verschillende wavetables, blokken van 192 samples, op deze pc)\n");
    d.instanceClear(); *freq = 440; *gate = 1; *vol = -12;
    *onA = 1; *onB = 1; *volB = -12; *octB = 0; *mix = 0.5f; *panA = -0.3f; *panB = 0.3f;
    *tblA = 2; *tblB = 6;
    auto c0 = std::chrono::steady_clock::now();
    for (int r = 0; r < 100; ++r) {
        *pos = (r % 10) / 9.f;
        *cent = float((r % 7) * 30 - 90);
        *posB = 1 - *pos;
        for (int b = 0; b < SR / 192; ++b) run(192);
    }
    auto c1 = std::chrono::steady_clock::now();
    const double ms = std::chrono::duration<double, std::milli>(c1 - c0).count() / 100;
    printf("  %.3f ms per seconde audio = %.3f %% van een pc-kern\n", ms, ms / 10);

    printf("\nRESULTAAT: %s\n", gFails ? "FOUTEN GEVONDEN" : "alles OK");
    return gFails ? 1 : 0;
}
