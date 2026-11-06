// ============================================================================
//  Project X v0.9 - de klank (één stem, monofoon, twee oscillatoren)
// ----------------------------------------------------------------------------
//  Klankopbouw (per oscillator A en B):
//    toonhoogte (toets x Oct x Cent) -> wavetable-oscillator -> zacht aan/uit (5 ms)
//    -> volume (Amp, dB) -> Pan (links/rechts)
//  Daarna:  Mix (overvloeien A <-> B, gelijk vermogen) -> L en R -> uit
//  Derde uitgang (alleen voor de oscilloscoop): de mix van A en B na aan/uit,
//  zónder Amp en zonder Pan.
//
//  Wavetables: elke oscillator kiest er één uit 8 (knop "Wavetable"). Een wavetable is een
//  rij golfvormen (Basic: sinus -> driehoek -> zaagtand -> vierkant; de andere 7 komen uit
//  Surge XT en hebben 16 tot 64 golfvormen). De positie (0..1) vloeit tussen twee buren
//  over (morphen). Wisselen van wavetable gebeurt met een overgang van 10 ms.
//
//  De tabellen worden niet hier uitgerekend: tools/wavetables/make_wavetables.py zet ze
//  vooraf om (app/src/main/assets/wavetables.bin) en de app geeft ze aan Faust als
//  "soundfile" met één deel per wavetable (zie WavetableData.h). Faust leest ze hier met
//  de primitive soundfile(): invoer (deel, plek), uitvoer (lengte, samplefreq., waarde).
//
//  Bandbegrenzing (tegen "aliasing", een metalig gruis bij hoge tonen):
//  elke golfvorm bestaat in 10 versies, één per octaaf, met steeds minder
//  boventonen: 512, 256, 128, ... , 2, 1. De oscillator kiest de versie
//  waarvan alle boventonen onder de helft van de samplefrequentie blijven.
//  Bij 440 Hz en 48 kHz is dat de versie met 32 boventonen (tot ca. 14 kHz).
//
//  "Gelijk vermogen" (Mix en Pan): de twee kanten krijgen cos en sin van dezelfde
//  hoek, zodat cos^2 + sin^2 = 1. In het midden krijgt elke kant 0,707 (-3 dB); de
//  totale luidheid blijft zo gelijk terwijl je draait of schuift.
//
//  Parameters (vanuit de app):
//    freq        : grondtoon in Hz (van het schermkeyboard), voor A en B samen
//    gate        : 1 = toets ingedrukt (of gelatcht), 0 = los
//    mix         : 0 = alleen A, 1 = alleen B (fader "Mix")
//    per oscillator, met A_ of B_ ervoor:
//      on        : 1 = aan, 0 = uit (aan/uit-icoon in de tab)
//      table     : gekozen wavetable 0..7 (keuzevenster van de knop "Wavetable")
//      position  : wavetablepositie 0..1   (fader "Wave", 0-100 %)
//      volume    : volume in dB, -120 .. 0 (knop "Amp"; -120 = uit)
//      octave    : -2, -1, 0, +1 octaaf (knop "Oct")
//      cent      : fijnstemming -100 .. +100 cent (knop "Cent"; 100 cent = halve toon)
//      pan       : -1 = links, 0 = midden, +1 = rechts (knop "Pan")
// ============================================================================

declare name "Project X";

import("stdfaust.lib");

// --- Bediening (gemeenschappelijk) ------------------------------------------
freq = nentry("freq", 440, 20, 20000, 0.01);
gate = checkbox("gate");
mix  = hslider("mix", 0.5, 0, 1, 0.001);

// --- De wavetables -----------------------------------------------------------------
N  = 2048;            // punten per golfvorm-versie
NL = 10;              // aantal octaafversies per golfvorm (512, 256, ... , 1 boventonen)
NT = 8;               // aantal wavetables

// Opbouw van één deel: golfvorm -> octaafversie -> punt, dus
//   plek = (golfvorm * NL + versie) * N + punt
wtFile = soundfile("wavetables", 1);
wtRead(t, i)  = (t, i) : wtFile : (!, !, _);                 // één waarde
wtFrames(t)   = (t, 0) : wtFile : (_, !, !) : /(NL * N) : int : max(2);   // aantal golfvormen

// --- Eén oscillator met aan/uit, Amp en Pan ------------------------------------
// Faust-labels moeten letterlijke tekst zijn; daarom krijgt de functie de knoppen
// van A of B als signalen mee (zie onderaan).
//   uit: (sig, links, rechts)   sig = na aan/uit, vóór Amp (voor de oscilloscoop)
voice(onSl, tblSl, posSl, volSl, octSl, centSl, panSl) = sig, sig * volume * panL, sig * volume * panR
with {
    position = posSl : si.smoo;                          // glijdt ca. 20 ms
    volume   = volSl : ba.db2linear : si.smoo;

    // Toonhoogte. Oct verspringt meteen (zoals bij een echte synth); Cent wordt
    // gladgestreken, zodat draaien niet kraakt. De machtsverheffingen
    // (semi2ratio = 2^(halve tonen/12)) hangen alleen van de knoppen af en worden
    // daarom maar één keer per audioblok uitgerekend.
    octRatio  = ba.semi2ratio(12 * octSl);
    centRatio = ba.semi2ratio(centSl / 100);
    // Gladgestreken wordt (verhouding - 1), niet de verhouding zelf: in 32-bit-getallen
    // blijft si.smoo net naast zijn doel steken (tot ca. 0,1 cent te laag). Bij Cent = 0
    // is het doel 0, en dat haalt hij exact, zodat de grondstemming zuiver blijft.
    pitch    = freq * octRatio * (1 + (centRatio - 1 : si.smoo));   // werkelijke toonhoogte (Hz)
    pitchSel = freq * octRatio * centRatio;   // zonder gladstrijken, alleen voor de keuze
                                              // van de octaafversie (per blok)

    // Octaafversie kiezen: aantal boventonen H = 2^(9-level) moet <= SR/(2*toonhoogte).
    level = int(ceil(9 - log(ma.SR / (2 * pitchSel)) / log(2))) : max(0) : min(NL - 1);

    // Fase 0..1 en lineaire interpolatie tussen twee tabelpunten.
    phase = os.lf_sawpos(pitch);
    fpos  = phase * N;
    i0    = int(fpos) : min(N - 1);
    i1    = (i0 + 1) % N;
    fr    = fpos - i0;

    // Uitgang van wavetable t: golfvormpaar en mengverhouding uit de positie.
    wave(t) = (1 - mixFr) * readShape(s0) + mixFr * readShape(s0 + 1)
    with {
        F     = wtFrames(t);
        y     = position * (F - 1);
        s0    = int(y) : min(F - 2) : max(0);
        mixFr = y - s0;
        readShape(s) = (1 - fr) * wtRead(t, base + i0) + fr * wtRead(t, base + i1)
        with { base = (s * NL + level) * N; };
    };

    // Wisselen van wavetable: 10 ms overvloeien van de vorige naar de nieuwe.
    // Buiten die overgang wordt alleen de nieuwe gelezen (select2 wordt in C++ een
    // "als ... dan"-keuze), zodat het gewone spelen niet zwaarder wordt.
    t     = int(tblSl) : max(0) : min(NT - 1);
    chg   = t != t';                                   // net gewisseld?
    tOld  = t' : ba.sAndH(chg);                        // de vorige wavetable vasthouden
    count = (+(1) : *(1 - chg)) ~ _;                   // samples sinds het wisselen
    fade  = min(1, count / (0.01 * ma.SR));            // 0 -> 1 in 10 ms
    oscOut = select2(fade < 1, wave(t), fade * wave(t) + (1 - fade) * wave(tOld));

    // Aan/uit: alleen als er een toets klinkt én deze oscillator aan staat.
    // 5 ms op en af, zonder klik.
    sig = oscOut * en.asr(0.005, 1, 0.005, gate * onSl);

    // Pan, gelijk vermogen: hoek 0 (links) .. pi/2 (rechts). cos/sin per blok,
    // daarna gladgestreken.
    angle = (panSl + 1) * ma.PI / 4;
    panL  = cos(angle) : max(0) : si.smoo;   // max(0): cos(pi/2) is in 32 bit net onder 0
    panR  = sin(angle) : si.smoo;
};

// --- De twee oscillatoren ------------------------------------------------------
A = voice(checkbox("A_on"), nentry("A_table", 0, 0, 7, 1), hslider("A_position", 0, 0, 1, 0.001),
          hslider("A_volume", -12, -120, 0, 0.1), nentry("A_octave", 0, -2, 1, 1),
          hslider("A_cent", 0, -100, 100, 0.1), hslider("A_pan", 0, -1, 1, 0.01));
B = voice(checkbox("B_on"), nentry("B_table", 0, 0, 7, 1), hslider("B_position", 0, 0, 1, 0.001),
          hslider("B_volume", -12, -120, 0, 0.1), nentry("B_octave", 0, -2, 1, 1),
          hslider("B_cent", 0, -100, 100, 0.1), hslider("B_pan", 0, -1, 1, 0.01));

// --- Mix (gelijk vermogen) en uitgangen ----------------------------------------
gA = cos(mix * ma.PI / 2) : max(0) : si.smoo;   // mix 0 -> A volledig, B weg
gB = sin(mix * ma.PI / 2) : si.smoo;   // mix 1 -> B volledig, A weg

clip = max(-1.0, min(1.0));

// Drie uitgangen:
//   1. links, 2. rechts (het geluid), begrensd op -1 .. +1
//   3. voor de oscilloscoop: mix van A en B vóór Amp en Pan (alleen om te tonen).
//      Niet begrensd op 1: als A en B allebei klinken, kan de som tot sqrt(2) = 1,41
//      gaan (gelijk vermogen); de oscilloscoop is op dat bereik geschaald.
process = A, B : mixer
with {
    // A en B leveren elk (sig, links, rechts)
    mixer(sA, lA, rA, sB, lB, rB) = clip(gA * lA + gB * lB), clip(gA * rA + gB * rB),
                                    gA * sA + gB * sB;
};
