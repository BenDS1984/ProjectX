# Tekent het scherm van v0.10 als SVG, met dezelfde maten en tekenwijze als
# SynthScreen.kt, Keyboard.kt, Wavetable.kt en Meters.kt. Zet het resultaat in de HTML-pagina.
# Gebruik:  python teken_scherm.py ProjectX-logboek.html
import math, sys, re

# ---- voorbeeldstand ----
TAB = 0             # gekozen tab: 0 = OSC A, 1 = OSC B (het paneel toont die oscillator)
ON_A, ON_B = True, True
MIX = 0.35          # fader Mix: 0 = alleen A, 1 = alleen B
POS = 0.40          # fader Wave (van de gekozen oscillator)
POS_B = 0.80        # Wave van Osc B (alleen voor de oscilloscoop)
KNOB = 0.80         # Amp = -12 dB (standaard)
OCT = 0
CENT = 25
PAN = -30           # Pan: L 30
OCT_B = 1           # Osc B een octaaf hoger (alleen voor de oscilloscoop)
SHOW3D = True
KEYBOARD = True     # keyboard zichtbaar
LATCH = True        # latch aan
KB_OCT = 3          # laagste noot C3
NOTE = 52           # E3 klinkt (MIDI-nummer)
KB_TOP = 216.958     # bovenkant keyboard

W, H = 508.0, 317.5
ACC, PITCH, PANC, TABOFF, ICON = "#FF8A1E", "#FCEB23", "#C36EFF", "#999999", "#C8C8C8"
BG, PANEL, DARK, BTN = "#666666", "#333333", "#1A1A1A", "#4D4D4D"
TRACK, KNOBC, EDGE, PTR = "#4A4A4A", "#E6E6E6", "#C8C8C8", "#2B2B2B"
TXT, GREY = "#F9F9F9", "#9A9A9A"
OSCB = "#5CA8EE"
SCOPE, METER, MTRACK = "#8CC8FF", "#22D9A0", "#2B2B2B"

def f(v): return f"{v:.2f}".rstrip("0").rstrip(".")
out = []
def rect(x, y, w, h, c, extra=""): out.append(f'<rect x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" fill="{c}" {extra}/>')
def text(s, cx, base, size, bold, c):
    out.append(f'<text x="{f(cx)}" y="{f(base)}" text-anchor="middle" font-size="{f(size)}" '
               f'font-weight="{700 if bold else 400}" fill="{c}" font-family="Lato, \'Segoe UI\', sans-serif">{s}</text>')

def shape(s, p):
    p = p - math.floor(p)
    if s == 0: return math.sin(2 * math.pi * p)
    if s == 1: return 4*p if p < .25 else (2 - 4*p if p < .75 else 4*p - 4)
    if s == 2: return 0.85 * (2*p if p < .5 else 2*p - 2)
    return 0.78 * (1 if p < .5 else -1)
def morph(pos, p):
    y = min(max(pos, 0), 1) * 3
    s0 = min(int(y), 2); fr = y - s0
    return (1 - fr) * shape(s0, p) + fr * shape(s0 + 1, p)

def wave_path(pos, x0, w, ymid, amp, c, sw, opacity=1.0):
    pts = []
    for i in range(161):
        ph = i / 160
        pts.append(f"{f(x0 + ph*w)} {f(ymid - amp*morph(pos, ph))}")
    op = f' stroke-opacity="{f(opacity)}"' if opacity < 1 else ""
    out.append(f'<path d="M{" L".join(pts)}" fill="none" stroke="{c}" stroke-width="{f(sw)}"{op} '
               f'stroke-linecap="round" stroke-linejoin="round"/>')

def arc(cx, cy, r, start, sweep, c, sw):
    # Compose: 0 graden = 3 uur, met de klok mee; SVG idem (y omlaag)
    a0, a1 = math.radians(start), math.radians(start + sweep)
    x0, y0 = cx + r*math.cos(a0), cy + r*math.sin(a0)
    x1, y1 = cx + r*math.cos(a1), cy + r*math.sin(a1)
    large = 1 if abs(sweep) > 180 else 0
    sweepflag = 1 if sweep > 0 else 0
    out.append(f'<path d="M{f(x0)} {f(y0)} A{f(r)} {f(r)} 0 {large} {sweepflag} {f(x1)} {f(y1)}" '
               f'fill="none" stroke="{c}" stroke-width="{f(sw)}" stroke-linecap="round"/>')

def knob(cx, cy, pdeg, fromdeg, c):
    R, AW, KR = 25.49, 3.82, 19.75
    arc(cx, cy, R, 135, 270, TRACK, AW)
    if abs(pdeg - fromdeg) > .01: arc(cx, cy, R, fromdeg - 90, pdeg - fromdeg, c, AW)
    out.append(f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{KR}" fill="{KNOBC}" stroke="{EDGE}" stroke-width="0.64"/>')
    a = math.radians(pdeg)
    def pt(d): return cx + d*math.sin(a), cy - d*math.cos(a)
    (xa, ya), (xb, yb) = pt(8.28), pt(17.21)
    out.append(f'<line x1="{f(xa)}" y1="{f(ya)}" x2="{f(xb)}" y2="{f(yb)}" stroke="{PTR}" stroke-width="2.55" stroke-linecap="round"/>')

def button(x, y, w, h, acc=ACC):
    out.append(f'<rect x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="1.88" ry="1.57" fill="{BTN}"/>')
    out.append(f'<path d="M{f(x)} {f(y+1.96)} V{f(y+1.57)} A1.88 1.57 0 0 1 {f(x+1.88)} {f(y)} H{f(x+w-1.88)} '
               f'A1.88 1.57 0 0 1 {f(x+w)} {f(y+1.57)} V{f(y+1.96)} Z" fill="{acc}"/>')

def power_icon(x, y, c):
    # icoon-aan-uit.svg op een raster van 48 x 48, geschaald zoals in Synth1OscA-v5.svg
    out.append(f'<g transform="translate({x} {y}) scale(0.49687)" fill="none" stroke="{c}" stroke-width="3" stroke-linecap="round">'
               '<path d="M32.36 16.04A13 13 0 1 1 15.64 16.04"/><path d="M24 8V22"/></g>')

# ---- achtergrond en vakken (v0.10, Synth1OscA-v6.svg) ----
rect(0, 0, W, H, BG)
rect(47.625, 83.079, 423.333, 206.375, PANEL)
rect(481.542, 34.813, 26.458, 282.687, PANEL)

# tabs OSC A / OSC B met aan/uit-icoon, en daartussen de fader Mix
for i, (tx, bx, ix, on) in enumerate(((146.008, 85.725, 89.633, ON_A), (368.896, 338.669, 400.785, ON_B))):
    col = (ACC if i == 0 else OSCB) if TAB == i else TABOFF
    button(bx, 48.397, 90, 28.276, col)
    text("OSC A" if i == 0 else "OSC B", tx, 69.33, 16.93, True, col)
    power_icon(ix, 50.297, ACC if on else ICON)
text("Mix", 258.566, 48.35, 11.29, True, TXT)
mx = 192.137 + MIX * (323.300 - 192.137)
# links van de schuif oranje (A), rechts blauw (B)
out.append(f'<rect x="186.281" y="60.35" width="{f(mx-186.281)}" height="3" rx="1.47" ry="1.5" fill="{ACC}"/>')
out.append(f'<rect x="{f(mx)}" y="60.35" width="{f(186.281+142.875-mx)}" height="3" rx="1.47" ry="1.5" fill="{OSCB}"/>')
out.append(f'<rect x="{f(mx-11.71/2)}" y="{f(61.85-16.285/2)}" width="11.71" height="16.285" rx="1.46" ry="1.11" '
           f'fill="{KNOBC}" stroke="{EDGE}" stroke-width="0.7"/>')
out.append(f'<line x1="{f(mx)}" y1="{f(61.85-4.85)}" x2="{f(mx)}" y2="{f(61.85+4.85)}" stroke="{PTR}" stroke-width="1.2" stroke-linecap="round"/>')
TABC = ACC if TAB == 0 else OSCB
out.append(f'<rect x="47.99" y="77.79" width="422.97" height="3" rx="1.5" fill="{TABC}"/>')
# scheidingslijn tussen het wavetable-deel en Amp/Pan (v6)
out.append(f'<rect x="258.79" y="100.01" width="2" height="169.33" rx="1" fill="{TABC}"/>')

button(58.738, 97.137, 78.845, 28.276)
text("Basic", 58.738 + 78.845/2, 97.137 + 18.46, 11.29, True, TXT)

# ---- wavetable-scherm (3D) ----
dx, dy, dw, dh = 57.796, 127.794, 143.288, 94.985
rect(dx, dy, dw, dh, DARK)
out.append(f'<clipPath id="wtclip"><rect x="{dx}" y="{dy}" width="{dw}" height="{dh}"/></clipPath><g clip-path="url(#wtclip)">')
fw, amp = dw * .62, dh * .15
x0 = lambda t: dx + dw*.05 + t*dw*.29
ym = lambda t: dy + dh*.74 - t*dh*.50
for j in range(23, -1, -1):
    t = j / 23
    wave_path(t, x0(t), fw, ym(t), amp, GREY, .45, .75 - .45*t)
wave_path(POS, x0(POS), fw, ym(POS), amp, ACC, 1.3)
out.append('</g>')

# ---- fader Wave ----
FCX, FT, FB = 226.965, 120.686, 251.846
text("Wave", FCX, 101.335, 11.29, True, TXT)
out.append(f'<rect x="225.465" y="114.829" width="3" height="142.875" rx="1.5" ry="1.47" fill="{TRACK}"/>')
ty = FB - POS * (FB - FT)
out.append(f'<rect x="225.465" y="{f(ty)}" width="3" height="{f(114.829+142.875-ty)}" rx="1.5" ry="1.47" fill="{ACC}"/>')
out.append(f'<rect x="{f(FCX-16.285/2)}" y="{f(ty-11.71/2)}" width="16.285" height="11.71" rx="1.11" ry="1.46" '
           f'fill="{KNOBC}" stroke="{EDGE}" stroke-width="0.7"/>')
out.append(f'<line x1="{f(FCX-4.85)}" y1="{f(ty)}" x2="{f(FCX+4.85)}" y2="{f(ty)}" stroke="{PTR}" stroke-width="1.2" stroke-linecap="round"/>')
text(f"{round(POS*100)} %", FCX, 278.06, 11.29, False, TXT)

# ---- knoppen (Pan recht onder Amp, op de hoogte van Cent) ----
db = -60 + 60*KNOB
text("Amp", 312.262, 100.81, 11.29, True, TXT)
knob(312.262, 135.044, -135 + 270*KNOB, -135, ACC)
text(f"{db:.1f} dB".replace("-", "−"), 312.262, 177.29, 11.29, False, TXT)

text("Pan", 312.262, 201.12, 11.29, True, TXT)
knob(312.262, 237.085, 135*PAN/100, 0, PANC)
text("C" if PAN == 0 else (f"R {PAN}" if PAN > 0 else f"L {-PAN}"), 312.262, 278.19, 11.29, False, TXT)

text("Oct", 421.248, 101.22, 11.29, True, TXT)
knob(421.248, 137.224, 67.5*OCT, 0, PITCH)
text("0" if OCT == 0 else (f"+{OCT}" if OCT > 0 else f"−{-OCT}"), 421.248, 177.58, 11.29, False, TXT)

text("Cent", 421.248, 201.12, 11.29, True, TXT)
knob(421.248, 237.085, 135*CENT/100, 0, PITCH)
text("0 ct" if CENT == 0 else (f"+{CENT} ct" if CENT > 0 else f"−{-CENT} ct"), 421.248, 278.19, 11.29, False, TXT)

# ---- keyboard (v0.7, keyboard-voorbeeld.svg; 1 px = 0,264583 eenheden) ----
if KEYBOARD:
    PX, KL = 0.2645833, 40.34
    U = lambda x, y: (KL + x*PX, KB_TOP + y*PX)
    rect(37.04, KB_TOP, 481.542 - 37.04, H - KB_TOP, DARK)
    for by, up, ok in ((10, True, KB_OCT < 5), (238, False, KB_OCT > 1)):
        bx0, by0 = U(10, by)
        button(bx0, by0, 104*PX, 128*PX)
        cx, cy, d = 62, by + 66, (7 if up else -7)
        pts = [U(cx-14, cy+d), U(cx, cy-d), U(cx+14, cy+d)]
        out.append(f'<path d="M{f(pts[0][0])} {f(pts[0][1])} L{f(pts[1][0])} {f(pts[1][1])} L{f(pts[2][0])} {f(pts[2][1])}" '
                   f'fill="none" stroke="{EDGE if ok else BG}" stroke-width="{f(6*PX)}" stroke-linecap="round" stroke-linejoin="round"/>')
    tx, ty_ = U(62, 201)
    text(f"C{KB_OCT}", tx, ty_, 38*PX, True, TXT)
    WW, BW, KH, BH = 1520/15, 1520/15*0.6, 356, 356*0.6
    SEMI = [0, 2, 4, 5, 7, 9, 11]
    base = 12 * (KB_OCT + 1)
    for i in range(15):
        n = base + 12*(i//7) + SEMI[i % 7]
        x, y = U(126 + i*WW + 2, 10)
        out.append(f'<rect x="{f(x)}" y="{f(y)}" width="{f((WW-4)*PX)}" height="{f(KH*PX)}" rx="{f(5*PX)}" fill="{ACC if n == NOTE else KNOBC}"/>')
        if i % 7 == 0:
            lx, ly = U(126 + i*WW + WW/2, 10 + KH - 18)
            text(f"C{KB_OCT + i//7}", lx, ly, 26*PX, False, "#808080")
    for i in range(14):
        if i % 7 not in (0, 1, 3, 4, 5): continue
        l = 126 + (i+1)*WW - BW/2
        n = base + 12*(i//7) + SEMI[i % 7] + 1
        x, y = U(l - 3, 10)
        out.append(f'<rect x="{f(x)}" y="{f(y)}" width="{f((BW+6)*PX)}" height="{f((BH+3)*PX)}" rx="{f(5*PX)}" fill="{DARK}"/>')
        x, y = U(l, 10)
        out.append(f'<rect x="{f(x)}" y="{f(y)}" width="{f(BW*PX)}" height="{f(BH*PX)}" rx="{f(4*PX)}" fill="{ACC if n == NOTE else "#3A3A3A"}"/>')

# ---- vaste delen (v0.5/v0.6): menubalken, scrollbalk, iconen ----
MENU = 37.04
rect(0, 0, W, MENU, DARK)
rect(0, 0, MENU, H, DARK)
# scrollbalk, bovenaan (inhoud 597,5 hoog, venster 280,46)
SX, SY = 481.542, 34.813
out.append(f'<rect x="{f(SX+12.171)}" y="{f(SY+5.292)}" width="2.117" height="271.99" rx="1.058" fill="{TRACK}"/>')
view_bottom = KB_TOP if KEYBOARD else H
tl = max(20, 271.99 * (view_bottom - MENU) / (H + 280))
ttop = SY + 5.292
out.append(f'<rect x="{f(SX+7.408)}" y="{f(ttop)}" width="11.642" height="{f(tl)}" rx="1.058" '
           f'fill="#C8C8C8"/>')
for k in (-1, 0, 1):
    gy = ttop + tl/2 + k*2.778
    out.append(f'<line x1="{f(SX+10.583)}" y1="{f(gy)}" x2="{f(SX+15.875)}" y2="{f(gy)}" stroke="{PTR}" '
               f'stroke-width="0.794" stroke-linecap="round"/>')
# iconen keyboard en latch (raster 48 x 48, geschaald zoals in het ontwerp)
IC = ACC if KEYBOARD else "#C8C8C8"
out.append(f'<g transform="translate(3.131 256.69) scale(0.61736)" fill="none" stroke="{IC}">'
           '<rect x="5" y="11" width="38" height="26" rx="3" stroke-width="3"/>'
           '<path d="M12.6 24V36M20.2 24V36M35.4 24V36M27.8 12V36" stroke-width="2"/>'
           f'<g fill="{IC}" stroke="none"><rect x="10.1" y="12" width="5" height="13" rx="1"/>'
           '<rect x="17.7" y="12" width="5" height="13" rx="1"/><rect x="32.9" y="12" width="5" height="13" rx="1"/></g></g>')
IC = ACC if LATCH else "#C8C8C8"
out.append(f'<g transform="translate(4.498 283.81) scale(0.61736)" fill="none" stroke="{IC}">'
           '<rect x="3" y="17" width="32" height="24" rx="3" stroke-width="3"/>'
           '<path d="M11 29V40M19 29V40M27 18V40" stroke-width="2"/>'
           f'<g fill="{IC}" stroke="none"><rect x="9" y="18" width="4" height="11" rx="1"/>'
           '<rect x="17" y="18" width="4" height="11" rx="1"/><path d="M22 18.5H26V39.5H20V31H22Z"/></g>'
           f'<rect x="28" y="11" width="19" height="16" rx="4" fill="{DARK}" stroke="none"/>'
           f'<path d="M33.5 13V9.5A4 4 0 0 1 41.5 9.5V13" stroke="{DARK}" stroke-width="7" stroke-linejoin="round"/>'
           '<path d="M33.5 13V9.5A4 4 0 0 1 41.5 9.5V13" stroke-width="3"/>'
           f'<rect x="30" y="13" width="15" height="12" rx="2" fill="{IC}" stroke="none"/>'
           f'<circle cx="37.5" cy="19" r="1.6" fill="{PTR}" stroke="none"/></g>')

# ---- DSP-meting in de bovenbalk, drie regels (waarden: gemeten op de SM-T580) ----
# (v6: links in de bovenbalk, naast het exit-icoon; DSP en piek afgelezen bij v0.9/v0.10)
for i, line in enumerate(["DSP 8 %   Peak 17 %", "Dropouts 0   FPS 60", "48 kHz   Buf 5.3 ms"]):
    text(line.replace(" ", "&#160;"), 38.485 + 71.24/2, 3.32 + 8.4 + i*9.6, 7.5, False, TXT)

# ---- exit-icoon linksboven (icoon-sluiten, raster 48 x 48) ----
out.append(f'<g transform="translate(2.474 3.93) scale(0.63139)" fill="none" stroke="{ICON}" stroke-width="3" '
           'stroke-linecap="round" stroke-linejoin="round"><path d="M23 9H12A3 3 0 0 0 9 12V36A3 3 0 0 0 12 39H23"/>'
           '<path d="M19 24H39"/><path d="M32 17L39 24L32 31"/></g>')

# ---- presetbalk (v0.10, preset-lijst-voorbeeld.svg) ----
PRESET = "Warm Pad *"
out.append(f'<rect x="202.5" y="6.2" width="85" height="24.6" rx="1.6" fill="{PANEL}"/>')
out.append(f'<text x="207.5" y="22.5" font-size="11.29" font-weight="400" fill="{TXT}" '
           f'font-family="Lato, Segoe UI, sans-serif">{PRESET}</text>')
out.append(f'<path d="M277 17.2L280 20.2L283 17.2" fill="none" stroke="{ICON}" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>')
out.append(f'<rect x="291.2" y="6.2" width="22.6" height="24.6" rx="1.6" fill="{PANEL}"/>')
out.append(f'<g transform="translate({f(302.5-24*0.36)} {f(18.5-24*0.36)}) scale(0.36)" fill="none" stroke="{ICON}" '
           'stroke-width="3.5" stroke-linejoin="round" stroke-linecap="round">'
           '<path d="M11 8H31L40 17V37A3 3 0 0 1 37 40H11A3 3 0 0 1 8 37V11A3 3 0 0 1 11 8Z"/>'
           '<path d="M15 8V16H29V8"/><path d="M14 40V27H34V40"/></g>')

# ---- oscilloscoop (v0.8: mix van A en B vóór Amp en Pan, schaal tot 1,41) ----
gA = math.cos(MIX * math.pi / 2) if ON_A else 0
gB = math.sin(MIX * math.pi / 2) if ON_B else 0
sx, sy, sw_, sh = 358.11, 3.03, 71.24, 30.40
smid, samp = sy + sh/2, sh * .46 / 1.4142
out.append(f'<line x1="{sx}" y1="{f(smid)}" x2="{f(sx+sw_)}" y2="{f(smid)}" stroke="{GREY}" stroke-opacity="0.35" stroke-width="0.3"/>')
# twee perioden van A (A zit hier het sterkst in de mix), beginnend bij de nuldoorgang
pts = []
for k in range(161):
    ph = 2 * k / 160
    v = gA * morph(POS, ph) + gB * morph(POS_B, ph * 2 ** OCT_B)
    v = max(-1.4142, min(1.4142, v))
    pts.append(f"{f(sx + sw_*k/160)} {f(smid - samp*v)}")
out.append(f'<path d="M{" L".join(pts)}" fill="none" stroke="{SCOPE}" stroke-width="0.9" stroke-linecap="round" stroke-linejoin="round"/>')

# ---- VU-meter (wat je hoort: na Amp, Pan en Mix; beide oscillatoren op -12 dB) ----
gain = 10 ** (db / 20)
def chan_peak(left):
    pa = math.cos((PAN/100 + 1) * math.pi / 4) if left else math.sin((PAN/100 + 1) * math.pi / 4)
    pb = math.sqrt(0.5)   # Osc B op C
    return max(abs(gA*pa*gain*morph(POS, i/1000) + gB*pb*gain*morph(POS_B, (i/1000) * 2 ** OCT_B))
               for i in range(2000))
vx, vy, vw, vh = 434.21, 3.14, 71.25, 30.18
bx, bw = vx + 7, vw - 7 - 1
y0 = vy + (vh - 2*8 - 4) / 2
lv = []
for c in range(2):
    peak = chan_peak(c == 0)
    lvl = max(-60, min(0, 20*math.log10(peak))) if peak > 1e-6 else -60
    lv.append(round(lvl, 1))
    frac = (lvl + 60) / 60
    y = y0 + c * 12
    text("L" if c == 0 else "R", vx + 3.5 - .5, y + 8 - 1.2, 7, True, GREY)
    rect(bx, y, bw, 8, MTRACK)
    rect(bx, y, bw*frac, 8, METER)
    hx = bx + (bw - .6) * frac
    out.append(f'<line x1="{f(hx)}" y1="{f(y)}" x2="{f(hx)}" y2="{f(y+8)}" stroke="{METER}" stroke-width="1"/>')

svg = ('<svg viewBox="0 0 508 317.5" role="img" aria-label="Het scherm van Project X v0.10: bovenaan de tabs OSC A '
       '(gekozen) en OSC B, elk met een aan/uit-icoon (beide aan), en daartussen de fader Mix; daaronder het paneel van '
       'Osc A met de knop Wavetable (Basic), de 3D-weergave van de wavetable, fader Wave en de knoppen Amp, Pan, Oct en Cent; bovenbalk '
       'met exit-icoon, DSP-meting, presetbalk (Warm Pad, gewijzigd), oscilloscoop en VU-meter; menubalk links met de iconen keyboard en latch (aan); keyboard onderaan '
       'met E3 ingedrukt; scrollbalk rechts.">\n'
       + "\n".join(out) + "\n</svg>")

html_path = sys.argv[1]
html = open(html_path, encoding="utf-8").read()
start = html.index('<svg viewBox="0 0 508 317.5"')
end = html.index("</svg>", start) + len("</svg>")
html = html[:start] + svg + html[end:]
open(html_path, "w", encoding="utf-8").write(html)
print("VU-niveau L/R", lv, "dB; schuif op", round(ty, 1))
