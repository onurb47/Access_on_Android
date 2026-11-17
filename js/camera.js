// Camera capture (getUserMedia) and chunked recording (MediaRecorder -> IndexedDB).

import { Takes } from './store.js';
import { now } from './util.js';

export const QUALITIES = {
  '4k':    { label: '4K',    width: 3840, height: 2160, bps: 24_000_000 },
  '1080p': { label: '1080p', width: 1920, height: 1080, bps: 10_000_000 },
  '720p':  { label: '720p',  width: 1280, height: 720,  bps: 5_000_000 },
};

/** Bytes written per minute at a quality preset (video + 128 kbit/s audio). */
export const bytesPerMinute = (q) => ((QUALITIES[q] || QUALITIES['4k']).bps + 128_000) / 8 * 60;

// Ambient court sound: no voice-call processing.
const AUDIO = { echoCancellation: false, noiseSuppression: false, autoGainControl: false };

/** iOS Safari labels: "Back Ultra Wide Camera", "Back Camera", "Back Telephoto Camera", "Back Triple Camera"... */
function classify(label = '') {
  const l = label.toLowerCase();
  if (/front|user|facetime/.test(l)) return 'front';
  if (/ultra/.test(l)) return 'ultrawide';
  if (/tele/.test(l)) return 'tele';
  if (/triple|dual/.test(l)) return 'multi';
  if (/back|rear|environment|camera/.test(l)) return 'wide';
  return 'other';
}

export const LENS_NAMES = {
  ultrawide: 'Ultra Wide 0.5×', wide: 'Wide 1×', tele: 'Telephoto', multi: 'Auto-switching', front: 'Front', other: 'Camera',
};

export class CameraRig {
  constructor(video) {
    this.video = video;
    this.stream = null;
    this.lenses = [];
    this.info = null;
    this.canvas = document.createElement('canvas');
  }

  async refreshLenses() {
    const devs = await navigator.mediaDevices.enumerateDevices();
    this.lenses = devs
      .filter((d) => d.kind === 'videoinput')
      .map((d) => ({ deviceId: d.deviceId, label: d.label || 'Camera', kind: classify(d.label) }));
    return this.lenses;
  }

  pick(pref) {
    const byId = this.lenses.find((l) => l.deviceId === pref);
    if (byId) return byId;
    const want = pref === 'wide' ? 'wide' : 'ultrawide';
    return this.lenses.find((l) => l.kind === want)
      || this.lenses.find((l) => l.kind === 'wide')
      || this.lenses.find((l) => l.kind !== 'front')
      || this.lenses[0]
      || null;
  }

  async open({ quality = '4k', lens = 'ultrawide' } = {}) {
    if (!navigator.mediaDevices?.getUserMedia) {
      throw Object.assign(new Error('This browser cannot use the camera. Open the app in Safari.'), { name: 'NoCamera' });
    }
    this.close();
    const q = QUALITIES[quality] || QUALITIES['4k'];

    // Labels (and so the ultra-wide lens) are only visible after permission is granted.
    if (!this.lenses.length || !this.lenses.some((l) => l.label && l.label !== 'Camera')) {
      let probe;
      try {
        probe = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: AUDIO });
      } catch (e) {
        if (e.name !== 'NotAllowedError' && e.name !== 'NotFoundError') throw e;
        probe = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } } });
      }
      probe.getTracks().forEach((t) => t.stop());
      await this.refreshLenses();
    }

    const chosen = this.pick(lens);
    const video = {
      width: { ideal: q.width },
      height: { ideal: q.height },
      frameRate: { ideal: 30, max: 30 },
    };
    if (chosen) video.deviceId = { exact: chosen.deviceId };
    else video.facingMode = { ideal: 'environment' };

    let stream;
    let hasAudio = true;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video, audio: AUDIO });
    } catch (e) {
      if (e.name === 'OverconstrainedError') {
        delete video.width; delete video.height;
        stream = await navigator.mediaDevices.getUserMedia({ video, audio: AUDIO });
      } else if (e.name === 'NotAllowedError' || e.name === 'NotFoundError' || e.name === 'NotReadableError') {
        // Microphone refused or busy: record picture only.
        stream = await navigator.mediaDevices.getUserMedia({ video });
        hasAudio = false;
      } else {
        throw e;
      }
    }
    hasAudio = hasAudio && stream.getAudioTracks().length > 0;

    this.stream = stream;
    this.video.srcObject = stream;
    this.video.muted = true;
    try { await this.video.play(); } catch { /* autoplay is allowed for muted inline video */ }

    const track = stream.getVideoTracks()[0];
    const s = track.getSettings ? track.getSettings() : {};
    const lensInfo = this.lenses.find((l) => l.deviceId === s.deviceId) || chosen || { kind: 'other', label: 'Camera' };
    this.info = {
      width: s.width || this.video.videoWidth || 0,
      height: s.height || this.video.videoHeight || 0,
      fps: Math.round(s.frameRate || 30),
      lensKind: lensInfo.kind,
      lensLabel: lensInfo.label,
      deviceId: s.deviceId || lensInfo.deviceId,
      hasAudio,
      requested: q,
    };
    return this.info;
  }

  /** Re-read the live track size (it changes when the phone rotates). */
  refreshInfo() {
    const track = this.stream?.getVideoTracks()[0];
    if (!track || !this.info) return this.info;
    const s = track.getSettings ? track.getSettings() : {};
    this.info.width = s.width || this.video.videoWidth || this.info.width;
    this.info.height = s.height || this.video.videoHeight || this.info.height;
    return this.info;
  }

  get videoTrack() { return this.stream?.getVideoTracks()[0] || null; }

  /** Small JPEG of the current frame, for the Director's coverage view. */
  snapshot(maxW = 288, quality = 0.55) {
    const v = this.video;
    if (!v.videoWidth) return null;
    const w = Math.min(maxW, v.videoWidth);
    const h = Math.round((w * v.videoHeight) / v.videoWidth);
    this.canvas.width = w;
    this.canvas.height = h;
    const ctx = this.canvas.getContext('2d');
    ctx.drawImage(v, 0, 0, w, h);
    try { return this.canvas.toDataURL('image/jpeg', quality); } catch { return null; }
  }

  close() {
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    if (this.video) this.video.srcObject = null;
  }
}

const MIME_AV = ['video/mp4;codecs=avc1,mp4a.40.2', 'video/mp4', 'video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'];
const MIME_V = ['video/mp4;codecs=avc1', 'video/mp4', 'video/webm;codecs=vp9', 'video/webm'];

export function pickMime(hasAudio) {
  if (typeof MediaRecorder === 'undefined') return null;
  for (const m of hasAudio ? MIME_AV : MIME_V) {
    try { if (MediaRecorder.isTypeSupported(m)) return m; } catch { /* keep looking */ }
  }
  return '';
}

/**
 * One recording ("take") on this phone. Chunks go to IndexedDB as they arrive.
 * Events: 'error' (detail: Error).
 */
export class TakeRecorder extends EventTarget {
  constructor(stream, take, { bps }) {
    super();
    this.stream = stream;
    this.take = take;
    this.bps = bps;
    this.seq = 0;
    this.bytes = 0;
    this.chain = Promise.resolve();
    this.startedAt = 0;
    this.stoppedAt = 0;
    this.failed = null;
    this.mr = null;
  }

  async prepare() {
    const hasAudio = this.stream.getAudioTracks().length > 0;
    const mime = pickMime(hasAudio);
    if (mime === null) throw new Error('Recording is not supported in this browser. Update iOS and use Safari.');
    const opts = { videoBitsPerSecond: this.bps };
    if (hasAudio) opts.audioBitsPerSecond = 128_000;
    if (mime) opts.mimeType = mime;
    this.mr = new MediaRecorder(this.stream, opts);
    this.mr.ondataavailable = (e) => this.onData(e);
    this.mr.onerror = (e) => this.fail(e.error || new Error('The recorder stopped unexpectedly'));
    this.take.mimeType = mime || 'video/mp4';
    this.take.status = 'recording';
    await Takes.put(this.take);
  }

  /** Call at the scheduled instant. Returns the local start time. */
  start() {
    this.mr.start(1000);
    this.startedAt = now();
    this.take.startedAt = Date.now();
    if (this.mr.mimeType) this.take.mimeType = this.mr.mimeType;
    return this.startedAt;
  }

  get elapsed() {
    if (!this.startedAt) return 0;
    return (this.stoppedAt || now()) - this.startedAt;
  }

  onData(e) {
    if (!e.data || !e.data.size) return;
    const seq = this.seq++;
    const blob = e.data;
    this.bytes += blob.size;
    this.chain = this.chain
      .then(() => Takes.putChunk(this.take.id, seq, blob))
      .then(() => (seq % 10 === 0 ? this.checkpoint() : null))
      .catch((err) => this.fail(err));
  }

  checkpoint() {
    this.take.bytes = this.bytes;
    this.take.chunks = this.seq;
    this.take.durationMs = Math.round(this.elapsed);
    return Takes.put(this.take);
  }

  fail(err) {
    if (this.failed) return;
    this.failed = err;
    if (err?.name === 'QuotaExceededError') {
      this.failed = new Error('This phone is out of storage space. Recording stopped and was saved.');
    }
    this.dispatchEvent(new CustomEvent('error', { detail: this.failed }));
  }

  /** Stop and finalise. Resolves with the saved take. */
  stop(status = 'complete') {
    return new Promise((resolve) => {
      const finish = async () => {
        if (!this.stoppedAt) this.stoppedAt = now();
        try { await this.chain; } catch { /* reported via fail() */ }
        this.take.status = this.failed && status === 'complete' ? 'interrupted' : status;
        this.take.endedAt = Date.now();
        this.take.durationMs = Math.round(this.elapsed);
        this.take.bytes = this.bytes;
        this.take.chunks = this.seq;
        try { await Takes.put(this.take); } catch { /* best effort */ }
        resolve(this.take);
      };
      if (!this.mr || this.mr.state === 'inactive') { finish(); return; }
      this.mr.onstop = () => setTimeout(finish, 0); // let the final dataavailable queue first
      this.stoppedAt = now();
      try { this.mr.stop(); } catch { finish(); }
    });
  }
}
