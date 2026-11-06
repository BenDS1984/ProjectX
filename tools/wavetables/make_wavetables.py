# ============================================================================
#  Zet de wavetables om naar het formaat van Project X  (draait op de pc, niet in de app).
#
#  Gebruik (vanuit de projectmap):   python tools/wavetables/make_wavetables.py
#  Resultaat:                        app/src/main/assets/wavetables.bin
#
#  Elke golfvorm (frame) wordt opgeslagen in 10 versies, één per octaaf, met steeds
#  minder boventonen: 512, 256, ... , 2, 1 (tegen aliasing; dezelfde opbouw als de
#  tabel die tot v0.8 in voice.dsp werd uitgerekend). De oscillator in Faust kiest de
#  versie die bij de toonhoogte past en vloeit tussen buur-golfvormen over.
#
#  Bronnen:
#   - "Basic": eigen tabel (sinus -> driehoek -> zaagtand -> vierkant), met dezelfde
#     Fourierreeksen en sterktes als voice.dsp tot v0.8.
#   - de andere 7: wavetables uit Surge XT (map Open source projects/surge-main,
#     resources/data/wavetables; GPL-3). Alleen de geluidsdata wordt gebruikt, geen code.
#     Hun golfvormen hebben 128 of 512 punten, dus hoogstens 63 of 255 boventonen.
#
#  Bestandsformaat (little-endian):
#   'PXWT', u32 versie (1), u32 N (punten per versie), u32 NL (octaafversies),
#   u32 aantal tabellen; per tabel: naam (32 bytes UTF-8, aangevuld met nullen),
#   u32 aantal golfvormen F, dan F * NL * N waarden int16 (32767 = 1,0), in de volgorde
#   golfvorm -> octaafversie -> punt.
# ============================================================================
import cmath, math, os, struct, sys, time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SURGE = os.path.join(ROOT, 'Open source projects', 'surge-main', 'resources', 'data', 'wavetables')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'wavetables.bin')

N = 2048        # punten per versie
NL = 10         # octaafversies
KMAX = 512      # meeste boventonen (versie 0)
PEAK = 0.95     # piekniveau na het normaliseren (Surge-tabellen)

# (naam in de app, bestand in Surge of None voor Basic)
TABLES = [
    ('Basic', None),
    ('PWM', 'Generated/PWM.wt'),
    ('Saw Sync', 'Generated/Saw Sync.wt'),
    ('Sine PD', 'Generated/Sine PD HQ.wt'),
    ('Sine FM 2x', 'Generated/Sine FM 2x HQ.wt'),
    ('Formants', 'Generated/Formants.wt'),
    ('Saw Reso', 'Generated/Sawtooth Reso.wt'),
    ('Vocal O', 'Sampled/Vocal O Formant 2.wt'),
]

# --- snelle Fourier-transformatie (radix 2), zonder extra pakketten -----------------
_tw = {}
def fft(a, inverse=False):
    n = len(a)
    a = list(a)
    j = 0
    for i in range(1, n):                      # bits omkeren
        bit = n >> 1
        while j & bit:
            j ^= bit; bit >>= 1
        j |= bit
        if i < j: a[i], a[j] = a[j], a[i]
    size = 2
    while size <= n:
        key = (size, inverse)
        if key not in _tw:
            s = 2j if inverse else -2j
            _tw[key] = [cmath.exp(s * math.pi * k / size) for k in range(size // 2)]
        w = _tw[key]
        half = size // 2
        for start in range(0, n, size):
            for k in range(half):
                u = a[start + k]; v = a[start + k + half] * w[k]
                a[start + k] = u + v; a[start + k + half] = u - v
        size <<= 1
    return a

def synth(spec, n):
    """Golfvorm van n punten uit boventonen spec[k] (k >= 1), als complexe getallen
    a + jb zodat waarde = sum(a cos + b sin)... hier: x[p] = sum Re(spec[k] e^{j2pi kp/n})."""
    buf = [0j] * n
    for k, c in spec.items():
        buf[k] = c
    out = fft(buf, inverse=True)
    return [v.real for v in out]

def levels_for(spec):
    """10 octaafversies: versie L houdt de boventonen k <= 512 / 2^L."""
    res = []
    for L in range(NL):
        H = KMAX >> L
        res.append(synth({k: c for k, c in spec.items() if k <= H}, N))
    return res

# --- Basic: zelfde Fourierreeksen als voice.dsp tot v0.8 -----------------------------
def basic_frames():
    def c(shape, k):
        if shape == 0: return 1.0 if k == 1 else 0.0
        if shape == 1: return (k % 2 == 1) * 8 / math.pi ** 2 * (1 - 2 * (((k - 1) // 2) % 2)) / (k * k)
        if shape == 2: return 0.85 * 2 / math.pi * (1 - 2 * ((k + 1) % 2)) / k
        return (k % 2 == 1) * 0.78 * 4 / math.pi / k
    frames = []
    for shape in range(4):
        # sin(2 pi k p / N) = Re(-j e^{j...}) -> complexe sterkte -j*c
        spec = {k: -1j * c(shape, k) for k in range(1, KMAX + 1) if c(shape, k) != 0}
        frames.append(levels_for(spec))
    return frames

# --- Surge-wavetable inlezen (formaat: zie WT fileformat.txt in Surge) -----------------
def read_wt(path):
    b = open(path, 'rb').read()
    assert b[:4] == b'vawt', path
    ws, wc, fl = struct.unpack('<IHH', b[4:12])
    n = ws * wc
    if fl & 4:
        data = struct.unpack(f'<{n}h', b[12:12 + 2 * n])
        scale = 32768.0 if fl & 8 else 16384.0
        data = [v / scale for v in data]
    else:
        data = list(struct.unpack(f'<{n}f', b[12:12 + 4 * n]))
    return ws, [data[i * ws:(i + 1) * ws] for i in range(wc)]

def surge_frames(rel):
    ws, src = read_wt(os.path.join(SURGE, rel))
    frames = []
    for fr in src:
        X = fft([complex(v) for v in fr])
        # boventonen 1 .. ws/2 - 1 (geen gelijkspanning, geen Nyquist), amplitude 2/ws
        spec = {k: 2 * X[k] / ws for k in range(1, ws // 2)}
        frames.append(levels_for(spec))
    pk = max(abs(v) for f in frames for lv in f for v in lv) or 1.0
    g = PEAK / pk
    return [[[v * g for v in lv] for lv in f] for f in frames], ws

def main():
    t0 = time.time()
    tables = []
    for name, rel in TABLES:
        if rel is None:
            fr, info = basic_frames(), 'eigen formules'
        else:
            if not os.path.exists(os.path.join(SURGE, rel)):
                sys.exit(f'Niet gevonden: {os.path.join(SURGE, rel)}')
            fr, ws = surge_frames(rel)
            info = f'Surge XT {rel}, {ws} punten per golfvorm'
        pk = max(abs(v) for f in fr for lv in f for v in lv)
        # Basic: de zaagtand met 512 boventonen schiet door het Gibbs-verschijnsel net boven 1
        # uit (1,0005; die versie klinkt alleen onder ca. 23 Hz). Dat was in voice.dsp ook zo;
        # bij het opslaan wordt het op 1,0 begrensd.
        assert pk <= 1.001, (name, pk)
        tables.append((name, fr))
        print(f'  {name:12s} {len(fr):3d} golfvormen, piek {pk:.3f}  ({info})')
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'wb') as fo:
        fo.write(b'PXWT' + struct.pack('<IIII', 1, N, NL, len(tables)))
        for name, fr in tables:
            fo.write(name.encode('utf-8').ljust(32, b'\0')[:32])
            fo.write(struct.pack('<I', len(fr)))
            for f in fr:
                for lv in f:
                    fo.write(struct.pack(f'<{N}h', *[max(-32767, min(32767, round(v * 32767))) for v in lv]))
    print(f'Klaar: {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB) in {time.time() - t0:.0f} s')

if __name__ == '__main__':
    main()
