# Music bed and sound effects for the Hommaterial promo, timed on the same timeline as promo.html.
import numpy as np
from scipy.signal import butter, sosfilt, fftconvolve
import wave, sys

SR = 48000
DUR = 44.0
N = int(SR * DUR)
rng = np.random.default_rng(7)

music = np.zeros((N, 2))   # dry music bus
fx = np.zeros((N, 2))      # dry effects bus
send = np.zeros((N, 2))    # reverb send

def mtof(m): return 440.0 * 2 ** ((m - 69) / 12)
def lp(x, f, o=2): return sosfilt(butter(o, f, 'low', fs=SR, output='sos'), x, axis=0)
def hp(x, f, o=2): return sosfilt(butter(o, f, 'high', fs=SR, output='sos'), x, axis=0)
def bp(x, lo, hi, o=2): return sosfilt(butter(o, [lo, hi], 'band', fs=SR, output='sos'), x, axis=0)

def put(bus, t, sig, gain=1.0, pan=0.0, rev=0.0):
    """Adds a mono or stereo signal at time t; pan -1..1; rev = amount also sent to the reverb."""
    i = int(t * SR)
    if i >= N: return
    if sig.ndim == 1:
        l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
        sig = np.stack([sig * l, sig * r], axis=1) * 1.414
    n = min(len(sig), N - i)
    bus[i:i + n] += sig[:n] * gain
    if rev: send[i:i + n] += sig[:n] * gain * rev

def env(n, a, d):
    t = np.arange(n) / SR
    return np.minimum(1, t / max(a, 1e-4)) * np.exp(-t / d)

# ---------------- instruments ----------------
def pad_note(f, dur, a=1.2, rel=1.6):
    n = int((dur + rel) * SR); t = np.arange(n) / SR
    out = np.zeros((n, 2))
    for ch, det in enumerate((-0.12, 0.12)):
        s = np.zeros(n)
        for d in (det, -det * 0.5, det * 1.7):
            ff = f * 2 ** (d / 12)
            ph = rng.uniform(0, 2 * np.pi)
            for h in range(1, 9):
                s += np.sin(2 * np.pi * ff * h * t + ph * h) / h ** 1.6
        out[:, ch] = s
    e = np.minimum(1, t / a) * np.where(t < dur, 1, np.exp(-(t - dur) / (rel / 3)))
    out = lp(out, 1500) * e[:, None]
    return out * 0.05

def pluck(f, d=0.35, bright=1.0):
    n = int((d * 6) * SR); t = np.arange(n) / SR
    s = np.sin(2 * np.pi * f * t) + 0.35 * bright * np.sin(2 * np.pi * 2 * f * t) + 0.12 * bright * np.sin(2 * np.pi * 3 * f * t)
    return s * env(n, 0.003, d)

def bell(f, d=0.9):
    n = int(d * 5 * SR); t = np.arange(n) / SR
    s = np.sin(2 * np.pi * f * t) * np.exp(-t / d) + 0.4 * np.sin(2 * np.pi * f * 2.76 * t) * np.exp(-t / (d * 0.35)) \
        + 0.2 * np.sin(2 * np.pi * f * 5.4 * t) * np.exp(-t / (d * 0.15))
    return s * np.minimum(1, t / 0.002)

def kick(g=1.0):
    n = int(0.5 * SR); t = np.arange(n) / SR
    f = 45 + 85 * np.exp(-t / 0.035)
    s = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.22)
    click = hp(rng.standard_normal(n), 3000) * np.exp(-t / 0.004) * 0.15
    return np.tanh((s + click) * 1.4) * g

def hat(d=0.035):
    n = int(0.2 * SR); t = np.arange(n) / SR
    return hp(rng.standard_normal(n), 7000) * np.exp(-t / d)

def snap():
    n = int(0.4 * SR); t = np.arange(n) / SR
    return bp(rng.standard_normal(n), 1200, 5000) * np.exp(-t / 0.09) + 0.3 * np.sin(2 * np.pi * 220 * t) * np.exp(-t / 0.04)

def bass(f, d):
    n = int((d + 0.05) * SR); t = np.arange(n) / SR
    s = np.sin(2 * np.pi * f * t) + 0.25 * np.sin(2 * np.pi * 2 * f * t)
    e = np.minimum(1, t / 0.008) * np.where(t < d, np.exp(-t / 0.5), 0)
    return np.tanh(s * 1.3) * e

def noise_sweep(dur, f0, f1, shape='hump'):
    """Noise through a band moving from f0 to f1: whooshes and risers."""
    n = int(dur * SR); x = rng.standard_normal(n); out = np.zeros(n)
    blk = 512; zi = None
    for i in range(0, n, blk):
        p = i / n
        fc = f0 * (f1 / f0) ** p
        sos = butter(2, [fc * 0.6, min(fc * 1.6, SR / 2 - 100)], 'band', fs=SR, output='sos')
        if zi is None: zi = np.zeros((sos.shape[0], 2))
        out[i:i + blk], zi = sosfilt(sos, x[i:i + blk], zi=zi)
    t = np.arange(n) / n
    e = np.sin(np.pi * t) ** 1.5 if shape == 'hump' else (t ** 2.2 if shape == 'rise' else (1 - t) ** 2)
    return out * e

def whoosh(t0, dur=0.6, up=True, g=0.5, pan_from=-0.6, pan_to=0.6):
    s = noise_sweep(dur, 300 if up else 3500, 3500 if up else 300)
    n = len(s); p = np.linspace(pan_from, pan_to, n)
    st = np.stack([s * np.cos((p + 1) * np.pi / 4), s * np.sin((p + 1) * np.pi / 4)], axis=1) * 1.414
    put(fx, t0, st, g, rev=0.25)

def click(t0, g=0.5, f=2400, pan=0.25):
    n = int(0.06 * SR); tt = np.arange(n) / SR
    s = hp(rng.standard_normal(n), 2500) * np.exp(-tt / 0.0018) * 0.6 + np.sin(2 * np.pi * f * tt) * np.exp(-tt / 0.012)
    put(fx, t0, s, g, pan, rev=0.15)

def thump(t0, g=0.5):
    n = int(0.12 * SR); tt = np.arange(n) / SR
    put(fx, t0, np.sin(2 * np.pi * 90 * tt) * np.exp(-tt / 0.03), g, 0.2)

def chime(t0, notes, g=0.35, gap=0.09, pan=0.3):
    for k, m in enumerate(notes):
        put(fx, t0 + k * gap, bell(mtof(m), 0.7), g * (0.85 ** k), pan, rev=0.5)

def pop(t0, g=0.4, f0=900, f1=300, pan=0.0):
    n = int(0.12 * SR); tt = np.arange(n) / SR
    f = f1 + (f0 - f1) * np.exp(-tt / 0.02)
    put(fx, t0, np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt / 0.04), g, pan, rev=0.3)

# ---------------- music ----------------
BPM = 100; BEAT = 60 / BPM; BAR = 4 * BEAT
GRID = 1.0                       # bar 3 lands on 8.2 (phone in), bar 16 on 39.4 (end card)
CHORDS = [  # two bars each
    (50, [50, 57, 61, 64, 66]),   # Dmaj9
    (47, [47, 54, 57, 61, 62]),   # Bm9
    (43, [43, 50, 54, 57, 59]),   # Gmaj9
    (45, [45, 52, 55, 59, 62]),   # A9sus
]
def bar_t(b): return GRID + b * BAR
def chord_at(b): return CHORDS[(b // 2) % 4] if b >= 0 else CHORDS[0]

DRUMS_IN, DRUMS_OUT = 8.2, 39.4
# pads: one per two bars, the first one from the very start
for b in range(-1, 16, 2):
    start = 0.0 if b < 0 else bar_t(b)
    end = bar_t(b + 2) if b >= 0 else bar_t(1)
    root, notes = chord_at(max(b, 0))
    g = 0.55 + 0.45 * (start >= 4.2)
    for m in notes:
        put(music, start, pad_note(mtof(m + 12 * (m < 52)), end - start + 0.3), g, rng.uniform(-.4, .4), rev=0.6)
# final chord
for m in CHORDS[0][1] + [69, 73]:
    put(music, DRUMS_OUT, pad_note(mtof(m + 12 * (m < 52)), 3.2, a=0.05, rel=2.5), 1.1, rng.uniform(-.5, .5), rev=0.8)

# arpeggio: eighths from the Alexa scene, sixteenths-ish sparkle in the finale
ARP = [0, 2, 3, 4, 2, 3, 1, 4]
step = BEAT / 2
for k in range(int((DRUMS_OUT - GRID) / step)):
    t = GRID + k * step
    if t < 4.2: continue
    b = int((t - GRID) // BAR)
    root, notes = chord_at(b)
    m = notes[ARP[k % 8]] + 24 - (12 if notes[ARP[k % 8]] > 60 else 0)
    vel = 0.10 if t < DRUMS_IN else 0.13
    if t > 36.9: vel = 0.16
    put(music, t, pluck(mtof(m), 0.28), vel * (1 - 0.3 * (k % 2)), 0.45 * np.sin(k * 0.9), rev=0.45)

# drums and bass from the phone entrance to the end card
b = 0
while bar_t(b) < DRUMS_OUT - 0.01:
    t0 = bar_t(b)
    if t0 >= DRUMS_IN - 0.01:
        root, _ = chord_at(b)
        for q in range(4):
            tb = t0 + q * BEAT
            if q in (0, 2): put(music, tb, kick(), 0.42)
            if q in (1, 3): put(music, tb, snap(), 0.10, 0.1, rev=0.3)
            for e in (0, 0.5):
                put(music, tb + e * BEAT, hat(), 0.045 if e else 0.03, 0.35)
            for e in (0, 0.5) if q != 3 else (0,):
                put(music, tb + e * BEAT, bass(mtof(root - 12 + 12 * (root < 45)), BEAT * 0.45), 0.16)
    b += 1
# lead-in riser to the drops
put(fx, DRUMS_IN - 1.6, noise_sweep(1.6, 200, 6000, 'rise'), 0.10, rev=0.3)
put(fx, DRUMS_OUT - 1.6, noise_sweep(1.6, 200, 7000, 'rise'), 0.12, rev=0.3)

# ---------------- effects on the timeline ----------------
# logo
put(fx, 0.0, noise_sweep(1.2, 150, 2500, 'rise'), 0.12, rev=0.4)
pop(0.25, 0.35, 700, 160)
whoosh(0.5, 0.5, False, 0.18, 0, 0)
gl = [62, 66, 69, 74, 78]
for i, m in enumerate(gl): put(fx, 1.15 + i * 0.14, pluck(mtof(m + 12), 0.2), 0.08, -0.4 + i * 0.2, rev=0.6)
chime(1.9, [81, 86], 0.32, 0.07, 0)
put(fx, 1.9, kick(), 0.35)
whoosh(2.25, 0.8, True, 0.18)
whoosh(3.75, 0.8, False, 0.25, 0.4, -0.4)
# alexa
pop(4.3, 0.35, 800, 220)
for i, m in enumerate([62, 64, 66, 69, 71, 74, 76, 78]):
    put(fx, 4.75 + i * 0.12, pluck(mtof(m + 12), 0.25), 0.11, np.cos(i / 8 * 2 * np.pi) * 0.8, rev=0.5)
whoosh(7.8, 0.7, False, 0.3, -0.5, 0.5)
whoosh(8.15, 0.9, True, 0.3, 0.6, 0.2)
# taps and switches
for tt in (9.95, 10.85): click(tt - 0.03, 0.45); chime(tt + 0.05, [74, 81], 0.24)
click(12.42, 0.4); thump(12.85, 0.35)
whoosh(12.85, 0.45, True, 0.16, 0.2, 0.4)
# slider ticks follow the brightness
def bright(t):
    if t < 13.0: return 80
    if t < 13.6: return 80 + (35 - 80) * (lambda p: 8*p**4 if p < .5 else 1-(-2*p+2)**4/2)((t-13.0)/0.6)
    p = min(1, (t - 13.65) / 0.45); return 35 + (70 - 35) * (8*p**4 if p < .5 else 1-(-2*p+2)**4/2)
last = 80
for tt in np.arange(13.0, 14.15, 0.005):
    v = bright(tt)
    if abs(v - last) >= 5: click(tt, 0.12, 3500, 0.3); last = v
click(14.28, 0.35); chime(14.33, [78], 0.2)
whoosh(14.75, 0.4, False, 0.12, 0.4, 0.2)
click(15.72, 0.4); thump(16.1, 0.35); whoosh(16.1, 0.45, True, 0.16, 0.2, 0.4)
click(16.83, 0.35); click(17.38, 0.35); chime(17.45, [76, 81, 85], 0.18, 0.07)
whoosh(18.0, 0.4, False, 0.12, 0.4, 0.2)
# voice
click(19.33, 0.4); chime(19.5, [69, 76], 0.22, 0.08, 0)
for i in range(23): click(19.9 + i * 0.037, 0.04, 4200, 0.1)
chime(20.9, [81, 74], 0.24)
whoosh(21.3, 0.4, False, 0.1, 0.2, 0.2)
# remote
click(22.38, 0.4); thump(22.6, 0.3); whoosh(22.6, 0.45, True, 0.16, 0.2, 0.4)
for tt in (23.3, 23.9, 24.3): click(tt - 0.02, 0.5, 1800); thump(tt, 0.25)
whoosh(24.9, 0.4, False, 0.12, 0.4, 0.2)
# nfc
whoosh(25.8, 0.7, False, 0.22, 1.0, 0.5)
whoosh(26.0, 0.6, True, 0.14, 0.2, 0.5)
for k in range(3):
    put(fx, 26.6 + k * 0.2, bell(mtof(93), 0.5), 0.16 * (0.6 ** k), 0.5, rev=0.6)
chime(27.0, [74, 81, 86], 0.24, 0.08)
pop(27.1, 0.2, 600, 400, 0.3)
whoosh(27.85, 0.6, True, 0.18, 0.5, 1.0)
# launcher
whoosh(28.85, 0.7, True, 0.22, -0.2, 0.3)
pop(29.3, 0.28, 700, 250, 0.3)
click(30.42, 0.4); chime(30.5, [81, 74], 0.2)
click(30.92, 0.35); thump(31.25, 0.3); pop(31.25, 0.25, 900, 500, 0.4)
whoosh(32.15, 0.8, False, 0.28, 0.4, -0.2)
# web
whoosh(32.45, 1.0, True, 0.26, -0.2, 0.5)
put(fx, 34.68, hp(rng.standard_normal(int(.03*SR)), 1500) * env(int(.03*SR), .0005, .004), 0.5, 0.4)
chime(34.75, [74, 81], 0.22, 0.08, 0.4)
whoosh(34.85, 0.8, True, 0.22, 1.0, 0.5)
whoosh(36.35, 0.8, False, 0.26, 0.4, -0.4)
# features
whoosh(36.95, 0.8, True, 0.16, -0.3, 0.3)
for i, m in enumerate([62, 66, 69, 71, 74, 78, 81, 83]):
    col = i % 4
    pop(37.2 + i * 0.08, 0.16, 1200, 500, (col - 1.5) / 2)
    put(fx, 37.2 + i * 0.08, pluck(mtof(m + 12), 0.2), 0.08, (col - 1.5) / 2, rev=0.5)
whoosh(38.95, 0.6, False, 0.3, -0.5, 0.5)
# end card impact
put(fx, 39.42, kick(), 0.8)
n = int(3 * SR); tt = np.arange(n) / SR
put(fx, 39.42, np.sin(2 * np.pi * 41 * tt) * np.exp(-tt / 0.8), 0.35)
put(fx, 39.42, hp(rng.standard_normal(n), 2000) * np.exp(-tt / 0.25), 0.06, rev=1.0)
chime(39.5, [86, 90, 93, 98], 0.18, 0.06, 0)
for i in range(11): put(fx, 39.75 + i * 0.035, pluck(mtof(86 + [0,2,4,7,9][i % 5] + 12 * (i // 5)), 0.15), 0.035, -0.5 + i * 0.1, rev=0.7)

# ---------------- mix ----------------
def reverb(x, secs=2.8, decay=0.9):
    n = int(secs * SR); t = np.arange(n) / SR
    ir = np.stack([rng.standard_normal(n), rng.standard_normal(n)], axis=1) * np.exp(-t / decay)[:, None]
    ir = lp(ir, 6000); ir[: int(0.015 * SR)] *= np.linspace(0, 1, int(0.015 * SR))[:, None]
    ir /= np.sqrt((ir ** 2).sum(axis=0))
    out = np.stack([fftconvolve(x[:, c], ir[:, c])[:len(x)] for c in range(2)], axis=1)
    return out

wet = reverb(send)
mix = music * 0.9 + fx * 1.0 + wet * 0.55
mix = hp(mix, 30)
# gentle fade in/out like the picture
t = np.arange(N) / SR
mix *= np.clip(t / 0.3, 0, 1)[:, None] * np.clip((DUR - t) / 1.2, 0, 1)[:, None]
mix /= np.max(np.abs(mix)) + 1e-9
mix = np.tanh(mix * 1.6) / np.tanh(1.6) * 0.89
pcm = (mix * 32767).astype(np.int16)
with wave.open(sys.argv[1] if len(sys.argv) > 1 else 'sound.wav', 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
print('ok', len(pcm) / SR)
