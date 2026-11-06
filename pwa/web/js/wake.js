/**
 * The wake word ("Giacomino"), recognized in the way of "Ok Google": the user teaches it by saying
 * it a few times, each recording becomes a template of MFCC frames, and the microphone is compared
 * against them all the time with dynamic time warping. Nothing leaves the device. The same steps,
 * with the same numbers, are in data/WakeWord.kt.
 */
export const RATE = 16000;
export const SAMPLES = 4;
const FRAME = 400; // 25 ms
const HOP = 160; // 10 ms
const FFT = 512;
const MELS = 26;
const CEPS = 12;
// A frame is speech when it is this much louder than the background.
const SPEECH_DB = 10;
// How far the threshold drawn from the samples is stretched, from the strictest to the most lenient.
export const SENSITIVITY = [1.1, 1.25, 1.4];

const HAMMING = Float64Array.from({ length: FRAME }, (_, i) => 0.54 - 0.46 * Math.cos((2 * Math.PI * i) / (FRAME - 1)));
const mel = (hz) => 2595 * Math.log10(1 + hz / 700);
const hz = (m) => 700 * (10 ** (m / 2595) - 1);
// Triangles over the bins of the spectrum, evenly spaced in mels from 20 Hz to half the rate.
const BANK = (() => {
  const points = Array.from({ length: MELS + 2 }, (_, i) => mel(20) + ((mel(RATE / 2) - mel(20)) * i) / (MELS + 1))
    .map((m) => (hz(m) * FFT) / RATE);
  return Array.from({ length: MELS }, (_, f) => {
    const [a, b, c] = [points[f], points[f + 1], points[f + 2]];
    return Float64Array.from({ length: FFT / 2 + 1 }, (_, k) => (k > a && k < c ? (k <= b ? (k - a) / (b - a) : (c - k) / (c - b)) : 0));
  });
})();
const DCT = Array.from({ length: CEPS }, (_, c) => Float64Array.from({ length: MELS }, (_, m) => Math.cos((Math.PI * (c + 1) * (m + 0.5)) / MELS)));

/** In-place radix-2 FFT of [re] and [im]. */
function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      [re[i], re[j]] = [re[j], re[i]];
      [im[i], im[j]] = [im[j], im[i]];
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const angle = (-2 * Math.PI) / len;
    for (let i = 0; i < n; i += len) {
      for (let k = 0; k < len / 2; k++) {
        const wr = Math.cos(angle * k);
        const wi = Math.sin(angle * k);
        const a = i + k;
        const b = a + len / 2;
        const xr = re[b] * wr - im[b] * wi;
        const xi = re[b] * wi + im[b] * wr;
        re[b] = re[a] - xr;
        im[b] = im[a] - xi;
        re[a] += xr;
        im[a] += xi;
      }
    }
  }
}

/**
 * Turns a stream of samples at [RATE] into frames: 12 cepstral coefficients and how loud the frame
 * is over the background. No running mean is taken off them: one that has not settled yet would make
 * the first recordings unlike what is heard later, and the microphone is the same one anyway.
 */
export class Features {
  constructor() {
    this.pending = new Float64Array(0);
    this.recent = [];
  }

  /** Returns the frames completed by [samples]: { v: Float64Array, rel: dB over the background }. */
  push(samples) {
    const all = new Float64Array(this.pending.length + samples.length);
    all.set(this.pending);
    all.set(samples, this.pending.length);
    const frames = [];
    let at = 0;
    for (; at + FRAME <= all.length; at += HOP) frames.push(this.frame(all.subarray(at, at + FRAME)));
    this.pending = all.slice(at);
    return frames;
  }

  frame(x) {
    const re = new Float64Array(FFT);
    const im = new Float64Array(FFT);
    let power = 0;
    // Loudness from the plain samples, the spectrum from the pre-emphasized ones.
    for (let i = 0; i < FRAME; i++) {
      re[i] = (x[i] - 0.97 * (i > 0 ? x[i - 1] : x[0])) * HAMMING[i];
      power += x[i] * x[i];
    }
    const db = 10 * Math.log10(power / FRAME + 1e-10);
    fft(re, im);
    const spectrum = new Float64Array(FFT / 2 + 1);
    for (let k = 0; k <= FFT / 2; k++) spectrum[k] = re[k] * re[k] + im[k] * im[k];
    const logs = BANK.map((filter) => {
      let sum = 0;
      for (let k = 0; k < filter.length; k++) sum += filter[k] * spectrum[k];
      return Math.log(sum + 1e-10);
    });
    const v = Float64Array.from(DCT, (row) => row.reduce((sum, w, m) => sum + w * logs[m], 0));
    // Digital silence, as microphones give while they start, is not the background of the room.
    if (db < -90) return { v, rel: 0 };
    // The background is the quietest frame of the last two seconds: a word is shorter than that.
    this.recent.push(db);
    if (this.recent.length > 200) this.recent.shift();
    return { v, rel: db - Math.min(...this.recent) };
  }
}

const distance = (a, b) => {
  let sum = 0;
  for (let c = 0; c < a.length; c++) sum += (a[c] - b[c]) ** 2;
  return Math.sqrt(sum);
};

/**
 * How far [template] is from its best match in [frames], which may start and end anywhere in them
 * as long as it is not much shorter or longer than the template: the cost of the warping path over
 * its length. Infinity when nothing fits.
 */
export function match(template, frames) {
  const n = template.length;
  const m = frames.length;
  let cost = new Float64Array(m);
  let start = new Int32Array(m);
  for (let j = 0; j < m; j++) {
    cost[j] = distance(template[0], frames[j].v);
    start[j] = j;
  }
  for (let i = 1; i < n; i++) {
    const next = new Float64Array(m);
    const from = new Int32Array(m);
    for (let j = 0; j < m; j++) {
      let best = cost[j];
      let origin = start[j];
      if (j > 0 && cost[j - 1] < best) [best, origin] = [cost[j - 1], start[j - 1]];
      if (j > 0 && next[j - 1] < best) [best, origin] = [next[j - 1], from[j - 1]];
      next[j] = best + distance(template[i], frames[j].v);
      from[j] = origin;
    }
    cost = next;
    start = from;
  }
  let best = Infinity;
  for (let j = 0; j < m; j++) {
    const length = j - start[j] + 1;
    if (length < 0.6 * n || length > 1.7 * n) continue;
    let speech = 0;
    for (let k = start[j]; k <= j; k++) if (frames[k].rel > SPEECH_DB) speech++;
    // Silence or a hum is never the word, however its shape happens to fit.
    if (speech < 0.3 * length) continue;
    best = Math.min(best, cost[j] / (n + length));
  }
  return best;
}

/** The two closest templates together, so that one lucky match is not enough. */
function score(templates, frames) {
  const found = templates.map((t) => match(t, frames)).sort((a, b) => a - b);
  return found.length > 1 ? (found[0] + found[1]) / 2 : found[0];
}

/** The speech in a recording, with a little silence around it; null when there is none or too much. */
export function trim(frames) {
  const first = frames.findIndex((f) => f.rel > SPEECH_DB);
  if (first < 0) return null;
  let last = frames.length - 1;
  while (frames[last].rel <= SPEECH_DB) last--;
  if (last - first < 25 || last - first > 200) return null;
  return frames.slice(Math.max(0, first - 5), last + 6);
}

/**
 * Builds the model from the recordings, each the frames of a [trim]: the threshold is the score of
 * the sample least like the others. Returns { bad } with the index of a sample to say again when it
 * is far from all the rest.
 */
export function train(samples) {
  const templates = samples.map((frames) => frames.map((f) => Array.from(f.v, (x) => Math.round(x * 1000) / 1000)));
  const scores = samples.map((frames, i) => score(templates.filter((_, k) => k !== i), frames));
  const sorted = [...scores].sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)];
  const worst = scores.indexOf(sorted[sorted.length - 1]);
  if (!Number.isFinite(sorted[sorted.length - 1]) || sorted[sorted.length - 1] > 1.6 * median) return { bad: worst };
  return { templates, threshold: sorted[sorted.length - 1] };
}

/** Listens to the frames as they come and tells when the last ones were the word. */
export class Detector {
  constructor(model, sensitivity = 1) {
    this.templates = model.templates;
    this.threshold = model.threshold * SENSITIVITY[sensitivity];
    this.keep = 2 * Math.max(...this.templates.map((t) => t.length));
    this.frames = [];
    this.count = 0;
    this.last = Infinity;
  }

  /** Whether the word has just been said; [last] holds the score for a meter. */
  feed(frames) {
    let heard = false;
    for (const frame of frames) {
      this.frames.push(frame);
      if (this.frames.length > this.keep) this.frames.shift();
      // A look every 40 ms is plenty: the path is free to end on any frame of the window.
      if (++this.count % 4 !== 0 || !this.frames.some((f) => f.rel > SPEECH_DB)) continue;
      this.last = score(this.templates, this.frames) / this.threshold;
      if (this.last < 1) {
        heard = true;
        this.frames = [];
      }
    }
    return heard;
  }
}

/**
 * Opens the microphone and hands [onFrames] the frames of what it hears, at [RATE] whatever the
 * rate of the device. Resolves to a function that closes it again; rejects without permission.
 */
export async function openMicrophone(onFrames) {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 } });
  const context = new AudioContext();
  const source = context.createMediaStreamSource(stream);
  // shortcut: ScriptProcessorNode is deprecated but needs no worklet file; move to AudioWorklet if browsers drop it.
  const node = context.createScriptProcessor(4096, 1, 1);
  const features = new Features();
  const step = context.sampleRate / RATE;
  // Where the next sample falls in the next buffer; -1 is the last sample of the buffer before.
  let at = 0;
  let previous = 0;
  node.onaudioprocess = (e) => {
    const input = e.inputBuffer.getChannelData(0);
    const out = [];
    for (; at < input.length - 1; at += step) {
      const i = Math.floor(at);
      const a = i < 0 ? previous : input[i];
      out.push(a + (input[i + 1] - a) * (at - i));
    }
    previous = input[input.length - 1];
    at -= input.length;
    onFrames(features.push(out));
  };
  source.connect(node);
  node.connect(context.destination);
  if (context.state === 'suspended') await context.resume().catch(() => {});
  return () => {
    node.onaudioprocess = null;
    source.disconnect();
    node.disconnect();
    stream.getTracks().forEach((t) => t.stop());
    context.close().catch(() => {});
  };
}

/** One recording while teaching: from a little before the voice starts to half a second of quiet after it. */
export class Recorder {
  constructor() {
    this.before = [];
    this.taken = [];
    this.quiet = 0;
  }

  /** Returns the whole recording once it ends, null until then. */
  feed(frame) {
    const loud = frame.rel > SPEECH_DB;
    if (!this.taken.length) {
      this.before.push(frame);
      if (this.before.length > 30) this.before.shift();
      if (loud) this.taken = [...this.before];
      return null;
    }
    this.taken.push(frame);
    this.quiet = loud ? 0 : this.quiet + 1;
    if (this.quiet < 50 && this.taken.length < 400) return null;
    const done = this.taken;
    this.taken = [];
    this.before = [];
    this.quiet = 0;
    return done;
  }
}
