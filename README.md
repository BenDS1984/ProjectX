# Project X

Een Android-synthesizer voor tablets (liggend, ontworpen voor 1920×1200), gemaakt voor persoonlijk
gebruik. De klank is geschreven in [Faust](https://faust.grame.fr/), de geluidsuitvoer loopt via
[Oboe](https://github.com/google/oboe) en het scherm is getekend in Kotlin met Jetpack Compose.

## Wat hij kan (v0.10)

- Monofoon, met twee wavetable-oscillatoren (Osc A en Osc B), elk met 8 wavetables.
- Per oscillator: Wave (positie in de wavetable), Amp, Oct, Cent en Pan, in stereo.
- Mix tussen A en B met gelijk vermogen.
- Schermkeyboard van 2 octaven met latch.
- Oscilloscoop, VU-meter en DSP-meting.
- Presets: opslaan, overschrijven, hernoemen en verwijderen.

Het verloop van het project staat in [docs/ProjectX-logboek.html](docs/ProjectX-logboek.html).
De commitcodes daarin verwijzen naar de lokale geschiedenis van vóór deze openbare versie;
op GitHub begint de geschiedenis bij v0.10.
De afspraken waarmee het gebouwd wordt staan in [CLAUDE.md](CLAUDE.md).

## Mappen

| Map | Inhoud |
|---|---|
| `app/src/main/faust/` | De klank in Faust (`voice.dsp`) |
| `app/src/main/cpp/` | Geluidsmotor in C++ en de koppeling met Oboe |
| `app/src/main/java/` | Scherm en bediening (Kotlin/Compose) |
| `app/src/main/assets/` | `wavetables.bin`, de wavetables van de app |
| `design/` | GUI-ontwerpen (Inkscape SVG) |
| `tools/dsp-test/` | Geluidstest op de pc |
| `tools/wavetables/` | Omzetter die `wavetables.bin` maakt |
| `docs/` | Logboek |

## Bouwen

Android Studio met de NDK en CMake, en de Faust-compiler op het pad. Daarna:

```
gradlew assembleDebug
```

## Licentie

Project X valt onder de **GNU General Public License versie 3** (zie [LICENSE](LICENSE)),
omdat het wavetablegegevens uit Surge XT bevat.

## Bronnen en dank

- **Wavetables:** zeven van de acht wavetables zijn omgezet uit
  [Surge XT](https://github.com/surge-synthesizer/surge) (GPL-3.0), uit de map
  `resources/data/wavetables/`:
  - `Generated/PWM.wt` (PWM)
  - `Generated/Saw Sync.wt` (Saw Sync)
  - `Generated/Sine PD HQ.wt` (Sine PD)
  - `Generated/Sine FM 2x HQ.wt` (Sine FM 2x)
  - `Generated/Formants.wt` (Formants)
  - `Generated/Sawtooth Reso.wt` (Saw Reso)
  - `Sampled/Vocal O Formant 2.wt` (Vocal O)

  Ze zijn bandbegrensd en genormaliseerd met `tools/wavetables/make_wavetables.py`.
  De wavetable "Basic" is eigen werk.
- **Lettertype:** Lato van Łukasz Dziedzic, SIL Open Font License 1.1
  (zie [licenses/OFL-Lato.txt](licenses/OFL-Lato.txt)).
- **Faust-skill** in `.claude/skills/faust/`: van Julius O. Smith III, MIT-licentie
  (zie het `LICENSE`-bestand in die map).
- **Faust-bibliotheken** en **Oboe** worden bij het bouwen gebruikt onder hun eigen licenties.
