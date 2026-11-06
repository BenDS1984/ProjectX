# FAUST Idiomatic Examples

Canonical, compile-clean exemplars to establish a strong prior for
LLM-generated Faust. Each covers a different **structural pattern**; the
prose around the code names the pattern so the model learns *why*, not
just *what*. Prefer adapting these to reinventing primitives.

Verify any of these with:

```bash
faust example.dsp >/dev/null   # syntax/type check
faust -svg example.dsp         # emit the block diagram to audit topology
```

---

## 1. Parametric EQ — *feedforward chain* (`:`)

A serial chain of shelving and peaking filters. Each band defaults to
0 dB (flat), so the unconfigured program is a clean bypass. Note the
idiomatic `with {}` scoping of each band's controls and the
`[n] Name [unit:Hz]` metadata that drives UI ordering and units.

```faust
import("stdfaust.lib");

lowshelf = fi.lowshelf(1, lg, lf)
with {
    lf = hslider("[0] Low Freq [unit:Hz]", 120, 20, 500, 1);
    lg = hslider("[1] Low Gain [unit:dB]",   0, -24, 24, 0.1);
};

peaking = fi.peak_eq(pg, pf, pb)
with {
    pf = hslider("[2] Mid Freq [unit:Hz]", 1000, 200, 8000, 1);
    pg = hslider("[3] Mid Gain [unit:dB]",    0,  -24,  24, 0.1);
    pb = hslider("[4] Mid BW   [unit:Hz]",  500,   50, 4000, 1);
};

highshelf = fi.highshelf(1, hg, hf)
with {
    hf = hslider("[5] High Freq [unit:Hz]", 4000, 1000, 16000, 1);
    hg = hslider("[6] High Gain [unit:dB]",    0,  -24,    24, 0.1);
};

process = lowshelf : peaking : highshelf;
```

**Pattern:** `A : B : C` — outputs feed inputs, left to right. The whole
signal path reads as one line.

---

## 2. Modal Resonator Bank — *parallel bank* (`<: par(...) :>`)

A struck, bell/bar-like tone built as a sum of decaying resonant
bandpasses. The standard Faust idiom is `par(i, N, ...)` over an index,
pulling per-mode constants from parallel lists with `ba.take(i+1, ...)`
(1-based). One impulse excites every mode in parallel; `:>` sums them.

```faust
import("stdfaust.lib");

f0    = hslider("freq [unit:Hz]", 220, 50, 2000, 0.1);
gate  = button("strike");

// Per-mode (ratio, gain, t60-seconds). Inharmonic, bar-like ratios.
ratios = (1.0, 2.756, 5.404, 8.933);
gains  = (1.0, 0.6,   0.4,   0.25);
t60s   = (2.0, 1.2,   0.7,   0.45);
nModes = 4;

mode(i) = fi.resonbp(fr, q, g)
with {
    fr  = f0 * ba.take(i+1, ratios);
    g   =      ba.take(i+1, gains);
    t60 =      ba.take(i+1, t60s);
    q   = 0.455 * fr * t60;     // t60 = 6.91/(pi*B), B = fr/Q  =>  Q ~= 0.455*fr*t60
};

excite  = gate : ba.impulsify;  // one-sample impulse on each strike
process = excite <: par(i, nModes, mode(i)) :> _;
```

**Pattern:** `_ <: par(i,N,f(i)) :> _` — split one signal to N parallel
processors, then sum. The canonical "bank of things" shape (modal banks,
filterbanks, additive partials).

---

## 3. Plucked String (Karplus–Strong) — *feedback loop* (`~`)

A delay line with a one-zero lowpass in its feedback path: the classic
plucked-string model. This is the pattern LLMs most often get wrong, so
the delay accounting is spelled out. `A ~ B` closes the loop and adds
**one implicit sample** of delay; the averaging lowpass adds ~0.5; the
fractional delay `de.fdelay` makes up the rest so the *total* loop delay
equals the period `P`.

```faust
import("stdfaust.lib");

freq  = hslider("freq [unit:Hz]", 220, 50, 1000, 0.1);
decay = hslider("decay", 0.996, 0.9, 0.999, 0.001);
pluck = button("pluck");

P     = ma.SR / freq;            // loop period in samples
lp(x) = 0.5 * (x + x');          // one-zero averaging lowpass (~0.5-sample delay)

// Total loop delay must equal P:  fdelay + 0.5 (lp) + 1 (the `~`) = P
string = (+ : de.fdelay(4096, P - 1.5)) ~ (lp : *(decay));

// Impulse excitation keeps it terse; a one-period noise burst is more realistic.
process = pluck : ba.impulsify : string <: _,_;
```

**Pattern:** `(forward) ~ (feedback)` — the feedback expression's output
returns to the forward expression's input through an implicit `z^-1`.
Always budget that extra sample (plus any filter group delay) when tuning
a resonant loop.

---

## How to read these as a model

- **Chain (`:`)** = "then". **Parallel (`,` / `<:` / `:>`)** = "at the
  same time" / "fan out" / "sum". **Feedback (`~`)** = "with memory".
- Reach for `stdfaust.lib` primitives (`fi.`, `os.`, `en.`, `de.`, `ba.`)
  before hand-rolling. If you typed a biquad's coefficients, you probably
  wanted `fi.lowpass` / `fi.peak_eq`.
- Put control declarations in a `with {}` next to where they are used.
- When a loop is involved, account for every sample of delay explicitly.
