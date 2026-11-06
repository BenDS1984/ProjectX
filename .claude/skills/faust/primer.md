# FAUST Language Primer

Distilled from [Audio Signal Processing in Faust](https://ccrma.stanford.edu/~jos/aspf/)
by Julius O. Smith III, CCRMA, Stanford University.

## Overview

FAUST (Functional Audio Stream) is a functional language for block diagrams
whose signals all run at a single sampling rate. Every program defines `process`:

```faust
process = faust_expression;
```

The compiler translates `.dsp` to C++ (and many other targets), hoisting
constant sub-expressions to init and GUI parameters to block rate.

## Elementary Blocks

| Block | Meaning |
|-------|---------|
| `_` | Wire (identity) |
| `!` | Cut (absorb input, no output) |
| `+` `-` `*` `/` `%` `^` | Arithmetic (2 in, 1 out) |
| `mem` | One-sample delay (= `@(1)`) |
| `@` | Variable delay: `x @ n` |
| `int(x)` | Truncate toward zero |
| `float(x)` | Treat as float |

Constants are zero-input blocks producing constant streams.
Postfix `'` delays by one sample: `impulse = 1 - 1';`

## Composition Operators

| Op | Name | Description |
|----|------|-------------|
| `A : B` | Series | Outputs of A → inputs of B |
| `A , B` | Parallel | Side by side |
| `A <: B` | Split | Fan-out (distribute) |
| `A :> B` | Merge | Fan-in (sum groups) |
| `A ~ B` | Recursive | Feedback with implicit z⁻¹ |

Precedence, loosest→tightest (verified against FAUST 2.85.8):

    <: :>   <   :   <   ,   <   ~   <   infix arithmetic & application

Two consequences that trip up LLMs: `~` binds **tightest** of the five, so
`a : b ~ c` parses as `a : (b ~ c)` (not `(a:b) ~ c`); and `,` binds tighter
than `:`, so `+,* : -` parses as `(+,*) : -`. When in doubt, parenthesize.

## Infix Rewriting to BDNF

| Infix | BDNF |
|-------|------|
| `x + y` | `x,y : +` |
| `x * y` | `x,y : *` |
| `x'` | `x : mem` |
| `f(x)` | `x : f` |
| `f(x,y)` | `x,y : f` |

## Functional Notation

```faust
+(x)  →  _,x : +
*(x)  →  _,x : *
-(x)  →  _,x : -
/(x)  →  _,x : /
```

## Four Notation Styles

For `f(x,y) = x + 2*y`:

| Style | Form |
|-------|------|
| Core | `x,y : f` |
| Applicative | `f(x,y)` |
| Partial application | `y : f(x)` |
| Infix | `x + 2*y` |

Mix styles: core for structure, partial application for parameters, infix for math.

## Statements

1. **Definition**: `f(x) = ...;` (required; must have `process`)
2. **Import**: `import("stdfaust.lib");`
3. **Declare**: `declare author "JOS";`
4. **Documentation**: XML tags

## Functions & Partial Application

```faust
f(x, y) = x + y;               // named parameters
special = general(1);           // bind first arg, rest become inputs
process = _ <: attach(meter);   // preserve side-effects
```

## Signal Types

- `int`: always 32-bit
- `float`: 32-bit default; `-double` / `-quad` flags
- `int(x)` truncates toward zero; auto-promotion to float as needed

## Comparison & Bitwise

```faust
<  <=  >  >=  ==  !=     // yield 0 or 1
&  |  xor  <<  >>        // integer signals
```

## Unary Minus

- `-x` → `0,x : -` (a signal)
- `-(expr)` → block diagram with one input

## Iteration

```faust
par(i, N, f(i))    // f(0) , f(1) , ... , f(N-1)
seq(i, N, f(i))    // f(0) : f(1) : ... : f(N-1)
sum(i, N, f(i))    // f(0) + f(1) + ... + f(N-1)
prod(i, N, f(i))   // f(0) * f(1) * ... * f(N-1)
```

## Foreign Entities

```faust
SR   = fconstant(int fSamplingFreq, <math.h>);       // const at init
BS   = fvariable(int count, <math.h>);               // const per block
tanh = ffunction(float tanhf(float), <math.h>, "");  // C function
```

## Pattern Matching

```faust
fact(0) = 1;
fact(n) = n * fact(n - 1);
```

Order matters: specific before general.

## Scope

```faust
process = out with { a = 0.9; out = + ~ *(a); };

f = library("foo.lib");    // f.symbol access
e = environment { Phi = 0.5*(1+sqrt(5)); };
process = component("other.dsp");   // = library("other.dsp").process
```

## GUI Primitives

| Widget | Syntax |
|--------|--------|
| `nentry` | `nentry("label", init, min, max, step)` |
| `hslider` | `hslider("label", init, min, max, step)` |
| `vslider` | `vslider("label", init, min, max, step)` |
| `button` | `button("label")` |
| `checkbox` | `checkbox("label")` |
| `hbargraph` | `hbargraph("label", min, max)` |
| `vbargraph` | `vbargraph("label", min, max)` |

Groups: `hgroup`, `vgroup`, `tgroup`. Widgets are slow (block-rate).

## White Noise Example

```faust
random  = +(12345) ~ *(1103515245);
noise   = random / 2147483647.0;
```

## Standard Libraries

```faust
import("stdfaust.lib");
```

| Prefix | Library | Contents |
|--------|---------|----------|
| `ma.` | maths | `ma.PI`, `ma.SR` |
| `ba.` | basics | `ba.if`, `ba.count` |
| `fi.` | filters | `fi.lowpass`, `fi.resonbp` |
| `os.` | oscillators | `os.osc`, `os.sawtooth` |
| `no.` | noises | `no.noise` |
| `ef.` | effects | |
| `de.` | delays | |
| `en.` | envelopes | `en.adsr` |
| `an.` | analyzers | |
| `si.` | signals | `si.bus`, `si.smooth` |
| `ve.` | vaeffects | |
| `re.` | reverbs | |
| `co.` | compressors | |
| `ro.` | routes | |

## Common Pitfalls

- **Arity must match.** The compiler's most frequent error. In `A : B`,
  outputs of A must equal inputs of B. In `A <: B`, B's input count must be a
  multiple of A's output count; in `A :> B`, A's output count must be a
  multiple of B's input count. Read the `outputs of A ... inputs of B` message
  and fix the counts.
- **Branch selection: use `select2`, not `ba.if`.** `ba.if(c, t, e)` evaluates
  **both** `t` and `e` every sample (no short-circuit), so it is wasteful and
  unsafe if a branch can fault. `select2(c, e, t)` is the idiom for routing.
- **Budget every sample of loop delay.** `A ~ B` adds one implicit `z⁻¹`;
  filters in the loop add their own group delay. For a tuned resonator the
  *total* loop delay must equal the period — see the Karplus–Strong example.
- **Widgets are block-rate.** A slider/`button` updates once per block, not per
  sample. Don't put one where you need sample-rate (e.g. audio-rate) modulation;
  use a signal there instead.
- **Recursion needs an explicit delay.** A definition that refers to itself
  without a `~`, `mem`, or `@` is an illegal no-delay loop.
- **Tables for wavetables / lookups.** Reach for `rdtable` / `rwtable` /
  `waveform` rather than hand-rolling memory; e.g. `os.osc` is a `rdtable` read.

## CLI

```bash
faust -svg file.dsp          # block diagrams
faust -cn MyClass file.dsp   # C++ with custom class name
faust2jaqt file.dsp          # JACK+Qt app
faust2juce file.dsp          # JUCE plugin
```
