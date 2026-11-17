// BD Futsal Cam Pro — screens and the recording flow.

import { $, $$, now, runAt, fmtClock, fmtDuration, fmtBytes, fmtDate, escapeHtml, uid } from './util.js';
import { Settings, Takes, ROLE_DEFAULTS, storageInfo } from './store.js';
import { CameraRig, TakeRecorder, QUALITIES, LENS_NAMES, bytesPerMinute } from './camera.js';
import { DirectorLink, CameraLink, newCode, normalizeCode, parseServer } from './link.js';

const VERSION = '1.0.0';
const START_LEAD_MS = 1500;   // time between pressing Start and both phones starting
const STOP_LEAD_MS = 500;
const BEEP_DELAY_MS = 2000;   // sync beep this long after the start
const RESUME_WINDOW_MS = 4 * 3600 * 1000;

const params = new URLSearchParams(location.search);
const settings = Settings.load();
const save = () => Settings.save(settings);

const freshPartner = () => ({
  connected: false, hello: null, status: null, statusAt: 0, thumb: null, startDelta: null, awaitingStart: null,
});

const S = {
  role: null,
  rig: null,
  link: null,
  recorder: null,
  take: null,
  recording: false,
  arming: false,
  stopping: false,
  startLocal: 0,
  partner: freshPartner(),
  linkState: { state: 'idle', text: null },
  peerParam: params.get('peer'),
  server: parseServer(params.get('peer')),
  wakeLock: null,
  audioCtx: null,
  storage: null,
  loops: [],
  lastThumb: 0,
  tagSaveTimer: 0,
};

const profile = () => settings.profile[S.role] || ROLE_DEFAULTS[S.role] || ROLE_DEFAULTS.director;
const side = () => profile().side;
const camLabel = (sd) => (sd === 'left' ? 'CAM 01 · LEFT' : 'CAM 02 · RIGHT');
const camClass = (sd) => (sd === 'left' ? 'cam-left' : 'cam-right');
const partnerSide = () => S.partner.status?.side || S.partner.hello?.side || (side() === 'left' ? 'right' : 'left');
const partnerLabel = () => (S.role === 'director' ? camLabel(partnerSide()) : 'Director');

/* ------------------------------------------------------------------ toast */

let toastTimer = 0;
function toast(msg, { bad = false, ms = 3200 } = {}) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.toggle('bad', bad);
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, ms);
}

/** Two-tap confirmation (the viewer never sees confirm() dialogs in some web views). */
function armed(btn, text, fn) {
  if (btn.classList.contains('armed')) {
    btn.classList.remove('armed');
    btn.textContent = btn.dataset.label;
    fn();
    return;
  }
  btn.dataset.label = btn.textContent;
  btn.textContent = text;
  btn.classList.add('armed');
  setTimeout(() => {
    if (btn.classList.contains('armed')) { btn.classList.remove('armed'); btn.textContent = btn.dataset.label; }
  }, 3500);
}

/* ------------------------------------------------------------------ views */

function showView(name) {
  for (const v of ['home', 'rec', 'library']) $(`#view-${v}`).hidden = v !== name;
  document.body.classList.toggle('rec-active', name === 'rec');
  if (name === 'home') renderHome();
  if (name === 'library') renderLibrary();
  window.scrollTo(0, 0);
}

async function renderHome() {
  for (const r of ['director', 'camera']) {
    const p = settings.profile[r];
    const badge = $(`[data-role-cam="${r}"]`);
    badge.textContent = camLabel(p.side);
    badge.className = `cam-badge ${camClass(p.side)}`;
    $(`[data-role-device="${r}"]`).textContent = p.name || 'This iPhone';
  }
  const takes = await Takes.list().catch(() => []);
  $('#lib-count').textContent = takes.length ? `${takes.length} take${takes.length === 1 ? '' : 's'}` : 'None yet';
  renderChecklistCount();
}

function checkEnvironment() {
  const problems = [];
  if (!window.isSecureContext) problems.push('Open this app from its https:// address. Safari blocks the camera on plain http.');
  else if (!navigator.mediaDevices?.getUserMedia) problems.push('This browser cannot use the camera. Open the link in Safari, not inside another app.');
  if (typeof MediaRecorder === 'undefined') problems.push('Recording needs a newer iOS. Update in Settings → General → Software Update.');
  if (!window.indexedDB) problems.push('Storage is unavailable. Turn off Private Browsing.');
  const w = $('#env-warning');
  w.hidden = !problems.length;
  w.textContent = problems.join(' ');
  const standalone = navigator.standalone || matchMedia('(display-mode: standalone)').matches;
  $('#install-hint').hidden = standalone || !/iPhone|iPad/.test(navigator.userAgent);
}

/* ------------------------------------------------------------- enter role */

async function enterRole(role) {
  S.role = role;
  settings.role = role;
  settings.roleAt = Date.now();
  if (role === 'director' && !settings.code) settings.code = newCode();
  save();
  S.partner = freshPartner();
  S.linkState = { state: 'idle', text: null };
  showView('rec');
  buildPartner();
  renderHud();
  renderPartner();
  keepAwake();
  startLoops();
  await openCamera();
  if (role === 'director') startDirectorLink();
  else if (settings.joinCode) startCameraLink();
  else openJoin();
}

async function exitRole() {
  if (S.recording || S.arming || S.stopping) {
    toast('Stop recording before leaving this screen.');
    return;
  }
  stopLink();
  stopLoops();
  S.rig?.close();
  try { await S.wakeLock?.release(); } catch { /* ignore */ }
  S.wakeLock = null;
  S.role = null;
  settings.role = null;
  save();
  S.partner = freshPartner();
  setDim(false);
  showView('home');
}

/* ------------------------------------------------------------------ camera */

async function openCamera() {
  const msg = $('#stage-msg');
  msg.hidden = false;
  msg.textContent = 'Starting camera…';
  if (!S.rig) S.rig = new CameraRig($('#preview'));
  try {
    const info = await S.rig.open({ quality: settings.quality, lens: settings.lens });
    msg.hidden = true;
    const track = S.rig.videoTrack;
    track?.addEventListener('ended', onTrackEnded);
    track?.addEventListener('mute', onTrackMuted);
    track?.addEventListener('unmute', onTrackUnmuted);
    if (!info.hasAudio) toast('The microphone is off, so this phone records picture only.', { ms: 5000 });
    const onSize = () => { S.rig?.refreshInfo(); renderHud(); checkOrientation(); };
    $('#preview').onloadedmetadata = onSize;
    $('#preview').onresize = onSize;
  } catch (e) {
    msg.hidden = false;
    msg.innerHTML = `<strong>Camera unavailable</strong><br>${escapeHtml(cameraErrorText(e))}<br><button type="button" class="btn" id="retry-cam">Try again</button>`;
    $('#retry-cam').onclick = () => openCamera();
  }
  renderHud();
  populateLens();
  checkOrientation();
  sendStatus();
}

function cameraErrorText(e) {
  switch (e?.name) {
    case 'NotAllowedError':
      return 'Camera access is blocked. In Safari tap aA in the address bar → Website Settings → Camera: Allow, then try again.';
    case 'NotFoundError': return 'No camera was found on this device.';
    case 'NotReadableError': return 'Another app is using the camera. Close it and try again.';
    case 'OverconstrainedError': return 'This lens cannot record at the chosen quality. Pick another lens or a lower quality in Settings.';
    default: return e?.message || String(e);
  }
}

function onTrackEnded() {
  if (S.recording) {
    stopRecording({ status: 'interrupted', reason: 'The camera was switched off by iOS.' });
  }
  const msg = $('#stage-msg');
  msg.hidden = false;
  msg.innerHTML = 'The camera stopped. This happens when Safari leaves the screen or another app takes the camera.<br><button type="button" class="btn" id="retry-cam">Restart camera</button>';
  $('#retry-cam').onclick = () => openCamera();
  sendStatus();
}

function onTrackMuted() {
  if (!S.recording) return;
  S.link?.send({ type: 'warn', text: 'iOS paused the camera (Safari left the screen?). Recording resumes when it comes back.' });
}

function onTrackUnmuted() {
  if (S.recording) toast('Camera is back. The video may have a short frozen gap.', { ms: 5000 });
}

async function reopenCamera() {
  if (S.recording || S.arming) return;
  await openCamera();
}

/* ----------------------------------------------------------------- linking */

function stopLink() {
  S.link?.stop();
  S.link = null;
}

function helloMsg() {
  return { type: 'hello', role: S.role, name: profile().name, side: side(), version: VERSION };
}

function sendConfig() {
  if (S.role === 'director' && S.link?.connected) {
    S.link.send({ type: 'config', quality: settings.quality, match: settings.match });
  }
}

function startDirectorLink() {
  stopLink();
  const link = new DirectorLink({ code: settings.code, server: S.server });
  S.link = link;
  link.addEventListener('state', (e) => { if (S.link !== link) return; S.linkState = e.detail; renderLink(); renderPartner(); });
  link.addEventListener('camera', (e) => {
    if (S.link !== link) return;
    const was = S.partner.connected;
    S.partner.connected = e.detail.connected;
    if (e.detail.connected) {
      link.send(helloMsg());
      sendConfig();
      if (!was) toast('Camera phone paired');
      if ($('#sheet-pair').open) setTimeout(() => $('#sheet-pair').open && $('#sheet-pair').close(), 1200);
    } else {
      if (S.partner.status?.recording) {
        toast(`Lost contact with ${partnerLabel()}. It keeps recording on its own.`, { bad: true, ms: 6000 });
      } else if (was) {
        toast('Camera phone disconnected');
      }
      S.partner.status = null;
      S.partner.thumb = null;
    }
    renderHud();
    renderPartner();
    renderPair();
  });
  link.addEventListener('message', (e) => { if (S.link === link) onDirectorMessage(e.detail); });
  link.addEventListener('codeTaken', () => {
    if (S.link !== link) return;
    settings.code = newCode();
    save();
    toast(`The old code was taken. New pairing code: ${settings.code}`, { ms: 6000 });
    startDirectorLink();
    renderPair();
  });
  link.start();
}

function onDirectorMessage(msg) {
  switch (msg?.type) {
    case 'hello':
      S.partner.hello = msg;
      if (msg.side === side()) {
        toast(`Both phones are set to ${side() === 'left' ? 'LEFT' : 'RIGHT'}. Change one of them in Settings.`, { bad: true, ms: 7000 });
      }
      break;
    case 'status':
      S.partner.status = msg;
      S.partner.statusAt = now();
      break;
    case 'thumb':
      S.partner.thumb = msg.data;
      break;
    case 'started':
      if (S.take && msg.groupId === S.take.groupId) {
        S.partner.awaitingStart = null;
        if (S.partner.status) Object.assign(S.partner.status, { recording: true, arming: false, elapsedMs: 0 });
        S.partner.statusAt = now();
        if (msg.late) {
          toast(`${partnerLabel()} joined the recording ${fmtDuration(msg.at - S.startLocal)} in.`);
        } else {
          S.partner.startDelta = msg.at - S.startLocal;
          S.take.sync.partnerStartDeltaMs = Math.round(S.partner.startDelta);
          Takes.put(S.take).catch(() => {});
          toast(`Both recording · start ${Math.round(Math.abs(S.partner.startDelta))} ms apart`);
        }
      }
      break;
    case 'stopped':
      toast(`${partnerLabel()} saved ${fmtDuration(msg.durationMs)} · ${fmtBytes(msg.bytes)}`);
      break;
    case 'warn':
    case 'error':
      toast(`${partnerLabel()}: ${msg.text}`, { bad: true, ms: 7000 });
      break;
    default:
  }
  renderPartner();
  renderHud();
}

function startCameraLink() {
  stopLink();
  const link = new CameraLink({ code: settings.joinCode, server: S.server });
  S.link = link;
  link.addEventListener('state', (e) => {
    if (S.link !== link) return;
    S.linkState = e.detail;
    if (e.detail.state === 'released') { S.partner.connected = false; stopLink(); renderHud(); }
    renderLink();
    renderPartner();
  });
  link.addEventListener('director', (e) => {
    if (S.link !== link) return;
    const was = S.partner.connected;
    S.partner.connected = e.detail.connected;
    if (e.detail.connected) {
      link.send(helloMsg());
      sendStatus();
      sendThumb();
      if (!was) toast('Paired with the Director');
      if ($('#sheet-join').open) $('#sheet-join').close();
    } else if (S.recording) {
      toast('Lost the Director. Still recording here; tap Stop on this phone if needed.', { bad: true, ms: 7000 });
    }
    renderHud();
    renderPartner();
  });
  link.addEventListener('message', (e) => { if (S.link === link) onCameraMessage(e.detail); });
  link.addEventListener('sync', () => { if (S.link === link) renderPartner(); });
  link.start();
}

async function onCameraMessage(msg) {
  switch (msg?.type) {
    case 'hello':
      S.partner.hello = msg;
      break;
    case 'config':
      if (typeof msg.match === 'string') settings.match = msg.match;
      if (msg.quality && QUALITIES[msg.quality] && msg.quality !== settings.quality) {
        settings.quality = msg.quality;
        if (!S.recording && !S.arming) {
          await reopenCamera();
          toast(`Quality set to ${QUALITIES[msg.quality].label} by the Director`);
        }
      }
      save();
      break;
    case 'cmd':
      if (msg.action === 'start') await cameraStart(msg);
      else if (msg.action === 'stop') await cameraStop(msg);
      break;
    case 'thumb-request':
      sendThumb();
      break;
    default:
  }
  renderPartner();
  renderHud();
}

async function cameraStart(msg) {
  if (S.recording || S.arming) return;
  const link = S.link;
  const at = link?.synced ? Math.max(now() + 30, link.toLocal(msg.at)) : now() + 150;
  const ok = await startRecording({
    at, groupId: msg.groupId, groupStamp: msg.groupStamp, match: msg.match, directorAt: msg.at, refStart: msg.refStart,
  });
  if (ok && link?.connected) {
    link.send({
      type: 'started', groupId: msg.groupId, at: link.toDirector(S.startLocal), synced: link.synced, late: !!msg.late,
    });
  } else if (!ok) {
    link?.send({ type: 'error', text: 'Could not start recording. Check this phone.' });
  }
}

async function cameraStop(msg) {
  if (!S.recording) return;
  const at = S.link?.synced ? S.link.toLocal(msg.at) : now();
  const take = await stopRecording({ at });
  if (take && S.link?.connected) {
    S.link.send({ type: 'stopped', groupId: take.groupId, durationMs: take.durationMs, bytes: take.bytes });
  }
}

/* --------------------------------------------------------------- recording */

async function onRecButton() {
  if (S.arming || S.stopping) return;
  if (!S.wakeLock) keepAwake();
  if (S.role === 'camera' && S.partner.connected) {
    toast('The Director starts and stops this camera.');
    return;
  }
  unlockAudio();
  const withCam = S.role === 'director' && S.link?.connected;

  if (!S.recording) {
    // Director not recording but the camera is (e.g. after a reload): offer to stop it.
    if (withCam && S.partner.status?.recording) {
      S.link.send({ type: 'cmd', action: 'stop', at: now() + 200 });
      toast(`Stopping ${partnerLabel()}`);
      return;
    }
    if (!S.rig?.stream) { toast('The camera is not running yet.', { bad: true }); return; }
    const groupId = uid('g');
    const groupStamp = Date.now();
    S.partner.startDelta = null;
    const at = now() + (withCam ? START_LEAD_MS : 250);
    if (withCam) {
      S.link.send({ type: 'cmd', action: 'start', at, groupId, groupStamp, match: settings.match, refStart: at });
      S.partner.awaitingStart = groupId;
      setTimeout(() => {
        if (S.partner.awaitingStart === groupId) {
          S.partner.awaitingStart = null;
          toast(`${partnerLabel()} did not confirm it started. Check that phone.`, { bad: true, ms: 7000 });
        }
      }, START_LEAD_MS + 4000);
    }
    const ok = await startRecording({
      at, groupId, groupStamp, match: settings.match, directorAt: S.role === 'director' ? at : null,
    });
    if (ok && S.role === 'director' && settings.syncBeep) scheduleBeep(S.startLocal + BEEP_DELAY_MS);
  } else {
    const at = now() + (withCam ? STOP_LEAD_MS : 0);
    if (withCam) S.link.send({ type: 'cmd', action: 'stop', at });
    await stopRecording({ at });
  }
}

/** Ask a camera that dropped out mid-recording to join the running recording. */
function lateStartPartner() {
  if (!S.recording || !S.take || !S.link?.connected) return;
  const at = now() + 1200;
  S.link.send({
    type: 'cmd', action: 'start', at, groupId: S.take.groupId, groupStamp: S.take.groupStamp,
    match: S.take.match, refStart: S.startLocal, late: true,
  });
  S.partner.awaitingStart = S.take.groupId;
  S.partner.startDelta = null;
  toast(`Starting ${partnerLabel()}…`);
}

async function startRecording({ at, groupId, groupStamp, match, directorAt, refStart }) {
  S.arming = true;
  renderHud();
  const q = QUALITIES[settings.quality] || QUALITIES['4k'];
  const info = S.rig.refreshInfo() || {};
  const take = {
    id: uid('t'),
    groupId,
    groupStamp: groupStamp || Date.now(),
    role: S.role,
    side: side(),
    cam: camLabel(side()),
    deviceName: profile().name,
    match: match || '',
    createdAt: Date.now(),
    quality: settings.quality,
    width: info.width,
    height: info.height,
    fps: info.fps,
    lens: LENS_NAMES[info.lensKind] || info.lensLabel || '',
    hasAudio: !!info.hasAudio,
    tags: [],
    sync: {
      directorAt: directorAt ?? null,
      clockOffsetMs: S.role === 'camera' && S.link?.synced ? Math.round(S.link.offset) : null,
      clockErrorMs: S.role === 'camera' && S.link?.rtt != null ? Math.max(1, Math.round(S.link.rtt / 2)) : null,
      beepAtMs: S.role === 'director' && settings.syncBeep ? BEEP_DELAY_MS : null,
    },
    status: 'recording',
    bytes: 0,
    durationMs: 0,
  };
  const rec = new TakeRecorder(S.rig.stream, take, { bps: q.bps });
  try {
    await rec.prepare();
  } catch (e) {
    S.arming = false;
    renderHud();
    toast(`Could not start recording: ${e.message}`, { bad: true, ms: 7000 });
    return false;
  }
  rec.addEventListener('error', (e) => {
    toast(e.detail.message, { bad: true, ms: 8000 });
    S.link?.send({ type: 'warn', text: e.detail.message });
    if (S.recording && S.recorder === rec) stopRecording({ status: 'interrupted', reason: e.detail.message });
  });
  showCountdown(at);
  try {
    await runAt(at, () => rec.start());
  } catch (e) {
    S.arming = false;
    renderHud();
    Takes.remove(take.id).catch(() => {});
    toast(`Could not start recording: ${e.message}`, { bad: true, ms: 7000 });
    return false;
  }
  S.recorder = rec;
  S.take = take;
  S.startLocal = rec.startedAt;
  if (S.role === 'camera' && refStart != null && S.link?.synced) {
    take.sync.startsIntoDirectorMs = Math.round(S.link.toDirector(S.startLocal) - refStart);
  }
  S.recording = true;
  S.arming = false;
  flash('rec-on');
  renderHud();
  sendStatus();
  return true;
}

async function stopRecording({ at = now(), status = 'complete', reason = '' } = {}) {
  if (!S.recording || S.stopping) return null;
  S.stopping = true;
  renderHud();
  if (at > now()) await runAt(at, () => {});
  const rec = S.recorder;
  const take = await rec.stop(status);
  S.recording = false;
  S.stopping = false;
  S.recorder = null;
  S.take = null;
  renderHud();
  sendStatus();
  updateStorage();
  if (take.status === 'complete') {
    toast(`Saved ${take.cam} · ${fmtDuration(take.durationMs)} · ${fmtBytes(take.bytes)}`, { ms: 4500 });
  } else {
    toast(`Recording stopped early. ${reason} Everything up to that point is saved.`, { bad: true, ms: 7000 });
  }
  return take;
}

function onTag(label) {
  if (!S.recording || !S.take) return;
  const t = Math.round(now() - S.startLocal);
  S.take.tags.push({ t, label });
  flash('on');
  toast(`${label} · ${fmtDuration(t)}`, { ms: 1600 });
  clearTimeout(S.tagSaveTimer);
  const take = S.take;
  S.tagSaveTimer = setTimeout(() => Takes.put(take).catch(() => {}), 400);
}

function showCountdown(at) {
  const el = $('#countdown');
  const step = () => {
    const left = at - now();
    if (left <= 0 || !S.arming) { el.hidden = true; return; }
    el.hidden = false;
    el.textContent = (left / 1000).toFixed(1);
    requestAnimationFrame(step);
  };
  step();
}

function flash(cls) {
  const f = $('#flash');
  f.classList.remove('on', 'rec-on');
  void f.offsetWidth;
  f.classList.add(cls);
  setTimeout(() => f.classList.remove(cls), 140);
}

/* ------------------------------------------------------------- sync beep */

function unlockAudio() {
  try {
    if (!S.audioCtx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (AC) S.audioCtx = new AC();
    }
    S.audioCtx?.resume?.();
  } catch { /* no audio output: the beep is optional */ }
}

function scheduleBeep(atLocal) {
  const ctx = S.audioCtx;
  if (!ctx) return;
  const base = ctx.currentTime + Math.max(0.05, (atLocal - now()) / 1000);
  for (const [freq, delay] of [[1000, 0], [2000, 0.25]]) {
    const o = ctx.createOscillator();
    const g = ctx.createGain();
    o.type = 'sine';
    o.frequency.value = freq;
    const t = base + delay;
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(1, t + 0.01);
    g.gain.setValueAtTime(1, t + 0.13);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.15);
    o.connect(g).connect(ctx.destination);
    o.start(t);
    o.stop(t + 0.17);
  }
}

/* --------------------------------------------------- status to the Director */

function statusMsg() {
  const info = S.rig?.info;
  return {
    type: 'status',
    recording: S.recording,
    arming: S.arming,
    elapsedMs: S.recording ? Math.round(now() - S.startLocal) : 0,
    bytes: S.recorder?.bytes || 0,
    width: info?.width || null,
    height: info?.height || null,
    fps: info?.fps || null,
    lens: info ? (LENS_NAMES[info.lensKind] || info.lensLabel) : null,
    camReady: !!S.rig?.stream?.active,
    hasAudio: !!info?.hasAudio,
    free: S.storage?.free ?? null,
    quality: settings.quality,
    synced: !!S.link?.synced,
    rtt: S.link?.rtt ?? null,
    visible: document.visibilityState === 'visible',
    side: side(),
    name: profile().name,
  };
}

function sendStatus() {
  if (S.role === 'camera' && S.link?.connected) S.link.send(statusMsg());
}

function sendThumb() {
  if (S.role !== 'camera' || !S.link?.connected) return;
  const data = S.rig?.snapshot();
  if (data) { S.link.send({ type: 'thumb', data }); S.lastThumb = now(); }
}

/* ---------------------------------------------------------------- loops */

function startLoops() {
  stopLoops();
  S.loops.push(setInterval(tickTimer, 250));
  S.loops.push(setInterval(sendStatus, 2000));
  S.loops.push(setInterval(() => {
    // Coverage thumbnails: every 4 s while framing, every 10 s while recording.
    if (now() - S.lastThumb > (S.recording ? 10000 : 4000)) sendThumb();
  }, 1000));
  S.loops.push(setInterval(() => { checkOrientation(); renderPartner(); }, 1000));
  S.loops.push(setInterval(updateStorage, 20000));
  updateStorage();
}

function stopLoops() {
  S.loops.forEach(clearInterval);
  S.loops = [];
}

function tickTimer() {
  const s = fmtClock(S.recording ? now() - S.startLocal : 0);
  $('#timer').textContent = s;
  $('#dim-time').textContent = s;
}

function checkOrientation() {
  if (!S.rig?.stream) { $('#rotate-hint').hidden = true; return; }
  const before = S.rig.info ? `${S.rig.info.width}x${S.rig.info.height}` : '';
  const info = S.rig.refreshInfo();
  if (info && `${info.width}x${info.height}` !== before) renderHud();
  const portraitVideo = info && info.height > info.width;
  const portraitScreen = innerHeight > innerWidth;
  const hint = $('#rotate-hint');
  hint.hidden = !(portraitVideo || portraitScreen);
  // Phone held upright: a short nudge. Phone sideways but picture upright: rotation lock is on.
  const short = portraitScreen;
  hint.classList.toggle('short', short);
  $('#rotate-detail').hidden = short;
}

async function updateStorage() {
  S.storage = await storageInfo();
  renderStorage();
}

/* ----------------------------------------------------------------- render */

function renderStorage() {
  const chip = $('#chip-storage');
  const st = S.storage;
  if (!st || st.free == null) { chip.textContent = 'Storage —'; return; }
  const mins = st.free / bytesPerMinute(settings.quality);
  chip.textContent = mins >= 90 ? `${(mins / 60).toFixed(1)} h free` : `${Math.floor(mins)} min free`;
  chip.classList.toggle('low', mins < 50);
}

const LINK_CHIP = {
  idle: 'Not paired', connecting: 'Connecting…', ready: 'Waiting for camera', searching: 'Looking for Director',
  offline: 'No internet', error: 'Pairing problem', released: 'Disconnected',
};

function renderLink() {
  const chip = $('#chip-link');
  const st = S.linkState;
  let cls = st.state;
  let text = LINK_CHIP[st.state] || 'Not paired';
  if (S.partner.connected) {
    cls = 'paired';
    text = S.role === 'director' ? `${camLabel(partnerSide()).split(' · ')[0]} linked` : 'Director linked';
  } else if (S.role === 'director' && st.state === 'ready') {
    text = `Code ${settings.code}`;
  }
  chip.className = `chip chip-link ${cls}`;
  chip.querySelector('span').textContent = text;
  if ($('#sheet-pair').open) renderPair();
}

function renderHud() {
  if (!S.role) return;
  const sd = side();
  const cc = $('#chip-cam');
  cc.textContent = `${camLabel(sd)} · ${S.role === 'director' ? 'Director' : 'Camera'}`;
  cc.className = `chip chip-cam ${sd}`;

  const info = S.rig?.info;
  const f = $('#chip-format');
  if (info?.width && S.rig?.stream) {
    const q = QUALITIES[settings.quality] || QUALITIES['4k'];
    const long = Math.max(info.width, info.height);
    const below = long < q.width * 0.95;
    f.textContent = `${info.width}×${info.height} · ${info.fps} fps · ${LENS_NAMES[info.lensKind] || 'Camera'}`;
    f.classList.toggle('low', below);
  } else {
    f.textContent = 'Camera off';
    f.classList.remove('low');
  }
  renderLink();
  renderStorage();

  const btn = $('#rec-btn');
  const label = $('#rec-btn-label');
  const state = $('#rec-state');
  btn.classList.toggle('recording', S.recording);
  btn.classList.toggle('arming', S.arming);
  const camLocked = S.role === 'camera' && S.partner.connected && !S.arming;
  const partnerRec = S.role === 'director' && S.partner.connected && S.partner.status?.recording;
  btn.disabled = S.stopping || (!S.recording && !S.rig?.stream?.active && !partnerRec) || camLocked;

  state.classList.toggle('live', S.recording && !S.stopping);
  if (S.arming) { state.textContent = 'Starting…'; label.textContent = 'Get ready'; }
  else if (S.stopping) { state.textContent = 'Saving…'; label.textContent = 'Saving'; }
  else if (S.recording) {
    state.textContent = 'Rec';
    label.textContent = S.role === 'camera' ? (S.partner.connected ? 'Director controls' : 'Stop') : (S.partner.connected ? 'Stop both' : 'Stop');
  } else if (S.role === 'director') {
    state.textContent = S.partner.connected ? 'Both phones ready' : 'Ready · no camera paired';
    label.textContent = partnerRec ? `Stop ${camLabel(partnerSide()).split(' · ')[0]}` : (S.partner.connected ? 'Start both' : 'Start this phone');
  } else {
    state.textContent = S.partner.connected ? 'Waiting for Director' : 'Not paired';
    label.textContent = S.partner.connected ? 'Director controls' : 'Record here';
  }
  btn.setAttribute('aria-label', label.textContent);

  $('#tags').hidden = !(S.role === 'director' && S.recording);
  $('#tool-pair').textContent = S.role === 'director' ? 'Pair' : (S.partner.connected ? 'Paired' : 'Join');
  $('#chip-awake').hidden = !!S.wakeLock;
  $('#dim-rec').classList.toggle('live', S.recording);
  $('#dim-sub').textContent = S.recording
    ? 'Recording. Screen dimmed to save battery. Tap to wake.'
    : 'Not recording. Tap to wake.';
}

function buildPartner() {
  $('#partner').innerHTML = `
    <div class="partner-thumb" data-p="thumbbox"><img alt="" data-p="img" hidden><i class="rec-dot" data-p="dot" hidden></i></div>
    <div class="partner-body">
      <div class="partner-title"><span class="cam-badge" data-p="badge"></span><strong data-p="name"></strong></div>
      <div class="partner-line" data-p="l1"></div>
      <div class="partner-line" data-p="l2"></div>
      <div class="partner-line" data-p="l3"></div>
      <button type="button" class="btn" data-p="act"></button>
    </div>`;
}

function renderPartner() {
  if (!S.role) return;
  const box = $('#partner');
  const q = (k) => box.querySelector(`[data-p="${k}"]`);
  if (!q('l1')) buildPartner();
  const set = (k, text, cls = '') => {
    const el = q(k);
    el.textContent = text || '';
    el.hidden = !text;
    el.className = `partner-line ${cls}`;
  };
  const act = q('act');
  const img = q('img');
  const badge = q('badge');

  if (S.role === 'director') {
    const st = S.partner.status;
    const psd = partnerSide();
    badge.textContent = camLabel(psd);
    badge.className = `cam-badge ${camClass(psd)}`;
    q('thumbbox').hidden = false;
    if (!S.partner.connected) {
      q('name').textContent = 'Not paired';
      img.hidden = true;
      q('dot').hidden = true;
      set('l1', S.linkState.text || 'Starting pairing…', S.linkState.state === 'offline' || S.linkState.state === 'error' ? 'bad' : '');
      set('l2', `Pairing code ${settings.code}`);
      set('l3', '');
      act.hidden = false;
      act.textContent = 'Show QR code';
      act.dataset.act = 'pair';
      return;
    }
    q('name').textContent = st?.name || S.partner.hello?.name || 'Camera phone';
    img.hidden = !S.partner.thumb;
    if (S.partner.thumb && img.getAttribute('src') !== S.partner.thumb) img.src = S.partner.thumb;
    q('dot').hidden = !st?.recording;
    const age = now() - S.partner.statusAt;
    if (!st) set('l1', 'Connecting…');
    else if (st.recording) set('l1', `REC ${fmtClock(st.elapsedMs + Math.min(age, 4000))} · ${fmtBytes(st.bytes)}`, 'live');
    else if (st.arming) set('l1', 'Starting…', 'live');
    else if (st.camReady) set('l1', `Ready · ${st.width}×${st.height} · ${st.fps} fps`, 'good');
    else set('l1', 'Camera not running on that phone', 'bad');
    const sync = st?.synced ? `Clock sync ±${Math.max(1, Math.round((st.rtt || 0) / 2))} ms` : 'Measuring clock sync…';
    const free = st?.free != null ? ` · ${Math.floor(st.free / bytesPerMinute(st.quality || settings.quality))} min free` : '';
    set('l2', st ? sync + free : '');
    if (st && age > 8000) set('l3', `No update for ${Math.round(age / 1000)} s`, 'bad');
    else if (st && !st.visible) set('l3', 'Safari is not on screen on that phone', 'bad');
    else if (st && st.hasAudio === false) set('l3', 'Its microphone is off', 'bad');
    else if (S.partner.startDelta != null) set('l3', `Last start ${Math.round(Math.abs(S.partner.startDelta))} ms apart`);
    else set('l3', '');
    if (S.recording && st && !st.recording && !st.arming && !S.partner.awaitingStart) {
      act.hidden = false;
      act.textContent = 'Start it now';
      act.dataset.act = 'late';
    } else if (!S.recording) {
      act.hidden = false;
      act.textContent = 'Disconnect';
      act.dataset.act = 'disconnect';
      act.className = 'btn btn-quiet';
      return;
    } else {
      act.hidden = true;
    }
    act.className = 'btn';
    return;
  }

  // Camera role: show the Director link.
  q('thumbbox').hidden = true;
  badge.textContent = 'Director';
  badge.className = `cam-badge ${camClass(side() === 'left' ? 'right' : 'left')}`;
  act.className = 'btn';
  if (S.partner.connected) {
    q('name').textContent = S.partner.hello?.name || '';
    set('l1', `Linked with code ${settings.joinCode}. The Director starts and stops recording.`, 'good');
    const l = S.link;
    set('l2', l?.synced ? `Clock sync ±${Math.max(1, Math.round(l.rtt / 2))} ms` : 'Measuring clock sync…');
    set('l3', '');
    act.hidden = true;
  } else {
    q('name').textContent = settings.joinCode ? `Code ${settings.joinCode}` : 'Not joined';
    const bad = ['offline', 'error'].includes(S.linkState.state);
    set('l1', S.linkState.text || (settings.joinCode ? 'Connecting…' : 'Enter the code shown on the Director phone.'), bad ? 'bad' : '');
    set('l2', S.recording ? 'Recording on this phone only.' : '');
    set('l3', '');
    act.hidden = false;
    act.textContent = settings.joinCode ? 'Change code' : 'Enter code';
    act.dataset.act = 'join';
  }
}

/* ------------------------------------------------------------ pair / join */

function joinUrl() {
  const u = new URL(location.href);
  u.search = '';
  u.hash = '';
  u.searchParams.set('join', settings.code);
  if (S.peerParam) u.searchParams.set('peer', S.peerParam);
  return u.toString();
}

let qrFor = '';
function renderPair() {
  if (S.role !== 'director') return;
  $('#pair-code').textContent = settings.code;
  $('#pair-other').textContent = settings.profile.camera?.name || 'other iPhone';
  const url = joinUrl();
  if (qrFor !== url) {
    const box = $('#pair-qr');
    try {
      const qr = window.qrcode(0, 'M');
      qr.addData(url);
      qr.make();
      box.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 0, scalable: true, alt: `QR code for ${url}` });
      qrFor = url;
    } catch {
      box.innerHTML = '<span class="qr-empty">QR code unavailable. Type the code instead.</span>';
    }
  }
  const status = $('#pair-status');
  if (S.partner.connected) {
    status.textContent = `Paired with ${S.partner.status?.name || S.partner.hello?.name || 'the camera phone'} (${camLabel(partnerSide())})`;
    status.className = 'pair-status paired';
  } else {
    status.textContent = S.linkState.text || 'Connecting…';
    status.className = `pair-status ${S.linkState.state}`;
  }
  $('#pair-new').disabled = S.recording || S.arming;
}

function openPair() {
  if (S.role !== 'director') { openJoin(); return; }
  renderPair();
  $('#sheet-pair').showModal();
}

async function shareJoinLink() {
  const url = joinUrl();
  if (navigator.share) {
    try { await navigator.share({ title: 'Join BD Futsal Cam Pro', url }); return; } catch (e) {
      if (e?.name === 'AbortError') return;
    }
  }
  try {
    await navigator.clipboard.writeText(url);
    toast('Link copied');
  } catch {
    toast(url, { ms: 8000 });
  }
}

function changeCode(btn) {
  const go = () => {
    settings.code = newCode();
    save();
    qrFor = '';
    S.partner = freshPartner();
    startDirectorLink();
    renderPair();
    renderHud();
    renderPartner();
    $('#set-code').textContent = settings.code;
    toast(`New pairing code: ${settings.code}`);
  };
  if (S.partner.connected) armed(btn, 'Tap again: unpairs the camera', go);
  else go();
}

function openJoin() {
  $('#join-code').value = settings.joinCode || '';
  $('#sheet-join').showModal();
  setTimeout(() => $('#join-code').focus(), 60);
}

/* ---------------------------------------------------------------- settings */

function populateLens() {
  const sel = $('#set-lens');
  const lenses = S.rig?.lenses || [];
  const opts = [];
  const hasUW = lenses.some((l) => l.kind === 'ultrawide');
  opts.push(['ultrawide', hasUW ? 'Ultra Wide 0.5× (recommended)' : 'Ultra Wide 0.5× (not found on this phone)']);
  opts.push(['wide', 'Wide 1×']);
  for (const l of lenses) {
    if (['tele', 'multi'].includes(l.kind)) opts.push([l.deviceId, l.label]);
  }
  sel.innerHTML = opts.map(([v, t]) => `<option value="${escapeHtml(v)}">${escapeHtml(t)}</option>`).join('');
  sel.value = opts.some(([v]) => v === settings.lens) ? settings.lens : 'ultrawide';
}

async function renderSettingsStorage() {
  const st = await storageInfo();
  const takes = await Takes.list().catch(() => []);
  const used = takes.reduce((n, t) => n + (t.bytes || 0), 0);
  let persisted = false;
  try { persisted = await navigator.storage?.persisted?.(); } catch { /* ignore */ }
  $('#set-storage').textContent = `${takes.length} recording${takes.length === 1 ? '' : 's'} · ${fmtBytes(used)} on this phone`
    + (st?.free != null ? ` · ${fmtBytes(st.free)} available to this app` : '')
    + (persisted ? '' : ' · iOS may clear browser storage if the phone runs very low on space, so save important matches to Photos.');
}

function qualityHint() {
  const q = settings.quality;
  const gb = (bytesPerMinute(q) * 60) / 1e9;
  $('#hint-quality').textContent = `${QUALITIES[q].label} uses about ${gb.toFixed(gb < 10 ? 1 : 0)} GB per hour on each phone.`;
}

function openSettings() {
  const locked = S.recording || S.arming;
  $('#set-match').value = settings.match || '';
  $('#set-name').value = profile().name || '';
  $(`#set-side-${side()}`).checked = true;
  const qid = { '4k': '4k', '1080p': '1080', '720p': '720' }[settings.quality] || '4k';
  $(`#set-q-${qid}`).checked = true;
  $('#set-beep').checked = !!settings.syncBeep;
  $('#row-beep').hidden = S.role !== 'director';
  $('#row-code').hidden = S.role !== 'director';
  $('#set-code').textContent = settings.code;
  $('#hint-match').textContent = S.role === 'director'
    ? 'Used in file names. The Camera phone gets it automatically.'
    : 'Used in file names. The Director overrides it when paired.';
  $$('input[name="side"], input[name="quality"]').forEach((i) => { i.disabled = locked; });
  $('#set-lens').disabled = locked;
  $('#set-locked').hidden = !locked;
  populateLens();
  qualityHint();
  renderSettingsStorage();
  $('#sheet-settings').showModal();
}

/* ------------------------------------------------------------------- dim */

function setDim(on) {
  $('#dim').hidden = !on;
}

async function keepAwake() {
  if (!('wakeLock' in navigator)) { S.wakeLock = null; renderHud(); return; }
  if (S.wakeLock) return;
  try {
    const lock = await navigator.wakeLock.request('screen');
    S.wakeLock = lock;
    lock.addEventListener('release', () => { if (S.wakeLock === lock) S.wakeLock = null; renderHud(); });
  } catch { S.wakeLock = null; }
  renderHud();
}

/* ---------------------------------------------------------------- library */

function statusPill(t) {
  switch (t.status) {
    case 'complete': return '<span class="pill pill-ok">Saved</span>';
    case 'recording': return '<span class="pill pill-live">Recording</span>';
    case 'recovered': return '<span class="pill pill-warn">Recovered</span>';
    case 'interrupted': return '<span class="pill pill-warn">Cut short</span>';
    default: return '<span class="pill pill-warn">Empty</span>';
  }
}

async function renderLibrary() {
  const list = $('#take-list');
  const takes = await Takes.list().catch(() => []);
  const used = takes.reduce((n, t) => n + (t.bytes || 0), 0);
  $('#lib-storage').textContent = takes.length ? fmtBytes(used) : '';
  if (!takes.length) {
    list.innerHTML = '<li class="empty"><strong>No recordings yet</strong>Start one from the Director or Camera screen. Each phone keeps its recordings here until you save them to Photos or AirDrop them.</li>';
    return;
  }
  list.innerHTML = takes.map((t) => {
    const bits = [
      fmtDate(t.startedAt || t.createdAt),
      fmtDuration(t.durationMs || 0),
      fmtBytes(t.bytes || 0),
    ];
    if (t.width) bits.push(`${t.width}×${t.height}`);
    if (t.tags?.length) bits.push(`${t.tags.length} tag${t.tags.length === 1 ? '' : 's'}`);
    return `<li><button type="button" class="take" data-id="${escapeHtml(t.id)}">
      <span class="take-cam ${camClass(t.side)}">CAM<br>${t.side === 'left' ? '01 L' : '02 R'}</span>
      <span class="take-main"><span class="take-title">${escapeHtml(t.match || 'Untitled match')}</span>
      <span class="take-sub">${escapeHtml(bits.join(' · '))}</span></span>
      ${statusPill(t)}</button></li>`;
  }).join('');
}

const T = { take: null, file: null, url: null };

async function openTake(id) {
  const t = await Takes.get(id);
  if (!t) return;
  T.take = t;
  $('#take-title').textContent = t.match || 'Untitled match';
  const rows = [
    ['Camera', `${t.cam} · ${t.deviceName || ''}`],
    ['Recorded', fmtDate(t.startedAt || t.createdAt)],
    ['Length', fmtDuration(t.durationMs || 0)],
    ['Size', fmtBytes(t.bytes || 0)],
    ['Picture', t.width ? `${t.width}×${t.height} · ${t.fps} fps · ${t.lens || ''}` : '—'],
    ['Sound', t.hasAudio ? 'Yes' : 'No microphone'],
  ];
  if (t.sync?.beepAtMs) rows.push(['Sync beep', `${(t.sync.beepAtMs / 1000).toFixed(1)} s in`]);
  if (t.sync?.partnerStartDeltaMs != null) rows.push(['Other camera started', `${Math.abs(t.sync.partnerStartDeltaMs)} ms ${t.sync.partnerStartDeltaMs >= 0 ? 'later' : 'earlier'}`]);
  if (t.sync?.clockErrorMs != null) rows.push(['Clock sync', `±${t.sync.clockErrorMs} ms`]);
  if (t.sync?.startsIntoDirectorMs > 1000) rows.push(['Starts into Director video', fmtDuration(t.sync.startsIntoDirectorMs)]);
  rows.push(['File', Takes.filename(t)]);
  $('#take-meta').innerHTML = rows.map(([k, v]) => `<div><dt>${escapeHtml(k)}</dt><dd>${escapeHtml(v)}</dd></div>`).join('');

  const tags = t.tags || [];
  $('#take-taglist').innerHTML = tags.map((g) => `<li><time>${fmtDuration(g.t)}</time><span>${escapeHtml(g.label)}</span></li>`).join('');
  $('#take-tags').hidden = !tags.length;

  const live = t.status === 'recording';
  for (const b of ['#take-share', '#take-download', '#take-delete']) $(b).disabled = live;
  $('#take-note').textContent = live
    ? 'This take is still recording.'
    : t.status === 'recovered'
      ? 'The app closed during this recording. Everything written before that point was recovered.'
      : 'Save to Photos opens the share sheet: choose Save Video, or AirDrop it to the other iPhone or a Mac.';

  const video = $('#take-video');
  revokeTake();
  if (!live) {
    try {
      T.file = await Takes.file(t);
      T.url = URL.createObjectURL(T.file);
      video.src = T.url;
    } catch (e) {
      $('#take-note').textContent = `This recording could not be read: ${e.message}`;
    }
  }
  $('#sheet-take').showModal();
}

function revokeTake() {
  const video = $('#take-video');
  video.pause();
  video.removeAttribute('src');
  video.load();
  if (T.url) URL.revokeObjectURL(T.url);
  T.url = null;
  T.file = null;
}

function downloadBlob(url, name) {
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
}

async function shareFile(file) {
  if (navigator.canShare?.({ files: [file] })) {
    try { await navigator.share({ files: [file], title: file.name }); } catch (e) {
      if (e?.name !== 'AbortError') toast(`Sharing failed: ${e.message}`, { bad: true });
    }
    return;
  }
  const url = URL.createObjectURL(file);
  downloadBlob(url, file.name);
  setTimeout(() => URL.revokeObjectURL(url), 60000);
  toast('Downloading. Find it in the Files app → Downloads.');
}

function tagsCsv(t) {
  const esc = (s) => `"${String(s).replace(/"/g, '""')}"`;
  const lines = ['Time,Seconds,Tag,Camera,Match'];
  for (const g of t.tags || []) {
    lines.push([fmtDuration(g.t), (g.t / 1000).toFixed(1), esc(g.label), esc(t.cam), esc(t.match || '')].join(','));
  }
  return `${lines.join('\n')}\n`;
}

/* --------------------------------------------------------------- checklist */

const CHECKLIST = [
  ['Auto-Lock set to Never', 'Settings → Display & Brightness → Auto-Lock, on both phones. If the screen locks, iOS stops the camera.'],
  ['Portrait Orientation Lock off', 'Open Control Centre. Mount both phones in landscape.'],
  ['Do Not Disturb on', 'A phone call takes over the camera and microphone.'],
  ['Power bank connected, Low Power Mode off', 'Recording a full match in 4K with the screen on is hard on the battery. Use Dim between halves.'],
  ['Enough free space', '4K needs about 11 GB per hour on each phone; 1080p about 4.5 GB.'],
  ['Both phones online to pair', 'Same Wi-Fi, or the Camera phone on the Director\'s Personal Hotspot. After pairing they talk directly.'],
  ['Lenses clean, both on Ultra Wide', 'On the Director, check the coverage view: the two pictures should overlap across the centre circle.'],
  ['Clap once after starting', 'A sharp clap in front of the mount (as well as the sync beep) gives an exact point to line the two videos up.'],
];

function todayKey() { return new Date().toDateString(); }

function ensureChecklistDay() {
  if (settings.checklistDay !== todayKey()) {
    settings.checklistDay = todayKey();
    settings.checklist = {};
    save();
  }
}

function renderChecklistCount() {
  ensureChecklistDay();
  const done = CHECKLIST.filter((_, i) => settings.checklist[i]).length;
  $('#check-count').textContent = `${done} / ${CHECKLIST.length}`;
}

function openGuide() {
  ensureChecklistDay();
  $('#checklist').innerHTML = CHECKLIST.map(([title, detail], i) => `
    <li><label><input type="checkbox" id="chk-${i}" data-i="${i}" ${settings.checklist[i] ? 'checked' : ''}>
    <span><strong>${escapeHtml(title)}</strong><span>${escapeHtml(detail)}</span></span></label></li>`).join('');
  $('#sheet-guide').showModal();
}

/* ------------------------------------------------------------------ events */

function bindEvents() {
  $('#pick-director').addEventListener('click', () => enterRole('director'));
  $('#pick-camera').addEventListener('click', () => enterRole('camera'));
  $('#open-library').addEventListener('click', () => showView('library'));
  $('#open-guide').addEventListener('click', openGuide);
  $('#lib-back').addEventListener('click', () => showView('home'));

  $$('dialog').forEach((d) => {
    d.addEventListener('click', (e) => { if (e.target === d) d.close(); });
  });
  $$('[data-close]').forEach((b) => b.addEventListener('click', () => b.closest('dialog').close()));

  $('#rec-btn').addEventListener('click', onRecButton);
  $('#tags').addEventListener('click', (e) => {
    const b = e.target.closest('[data-tag]');
    if (b) onTag(b.dataset.tag);
  });
  $('#tool-pair').addEventListener('click', () => (S.role === 'director' ? openPair() : openJoin()));
  $('#tool-settings').addEventListener('click', openSettings);
  $('#tool-dim').addEventListener('click', () => setDim(true));
  $('#tool-exit').addEventListener('click', exitRole);
  $('#dim').addEventListener('click', () => setDim(false));

  $('#partner').addEventListener('click', (e) => {
    const b = e.target.closest('[data-act]');
    if (!b) { $('#partner').classList.toggle('compact'); return; }
    if (b.dataset.act === 'pair') openPair();
    else if (b.dataset.act === 'join') openJoin();
    else if (b.dataset.act === 'late') lateStartPartner();
    else if (b.dataset.act === 'disconnect') armed(b, 'Tap again to unpair', () => S.link?.disconnectCamera());
  });

  $('#pair-copy').addEventListener('click', shareJoinLink);
  $('#pair-new').addEventListener('click', (e) => changeCode(e.currentTarget));
  $('#set-new-code').addEventListener('click', (e) => changeCode(e.currentTarget));

  $('#join-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const code = normalizeCode($('#join-code').value);
    if (code.length < 4) { toast('Type the code shown on the Director phone.'); return; }
    settings.joinCode = code;
    save();
    $('#sheet-join').close();
    S.partner = freshPartner();
    startCameraLink();
    renderPartner();
  });

  let matchTimer = 0;
  $('#set-match').addEventListener('input', (e) => {
    settings.match = e.target.value.trim();
    save();
    clearTimeout(matchTimer);
    matchTimer = setTimeout(sendConfig, 500);
  });
  $('#set-name').addEventListener('input', (e) => {
    profile().name = e.target.value.trim();
    save();
    S.link?.send(helloMsg());
  });
  $$('input[name="side"]').forEach((i) => i.addEventListener('change', () => {
    profile().side = i.value;
    save();
    renderHud();
    S.link?.send(helloMsg());
    sendStatus();
  }));
  $$('input[name="quality"]').forEach((i) => i.addEventListener('change', async () => {
    settings.quality = i.value;
    save();
    qualityHint();
    sendConfig();
    await reopenCamera();
  }));
  $('#set-lens').addEventListener('change', async (e) => {
    settings.lens = e.target.value;
    save();
    await reopenCamera();
  });
  $('#set-beep').addEventListener('change', (e) => { settings.syncBeep = e.target.checked; save(); });

  $('#take-list').addEventListener('click', (e) => {
    const b = e.target.closest('[data-id]');
    if (b) openTake(b.dataset.id);
  });
  $('#sheet-take').addEventListener('close', revokeTake);
  $('#take-share').addEventListener('click', () => { if (T.file) shareFile(T.file); });
  $('#take-download').addEventListener('click', () => {
    if (!T.file) return;
    downloadBlob(T.url, T.file.name);
    toast('Downloading. Find it in the Files app → Downloads.');
  });
  $('#take-tags').addEventListener('click', () => {
    if (!T.take) return;
    const name = Takes.filename(T.take, 'csv').replace('.csv', '_tags.csv');
    shareFile(new File([tagsCsv(T.take)], name, { type: 'text/csv' }));
  });
  $('#take-delete').addEventListener('click', (e) => armed(e.currentTarget, 'Tap again to delete', async () => {
    if (!T.take) return;
    const name = Takes.filename(T.take);
    await Takes.remove(T.take.id);
    $('#sheet-take').close();
    renderLibrary();
    updateStorage();
    toast(`Deleted ${name}`);
  }));

  $('#checklist').addEventListener('change', (e) => {
    const i = e.target.dataset.i;
    if (i == null) return;
    settings.checklist[i] = e.target.checked;
    save();
    renderChecklistCount();
  });

  document.addEventListener('visibilitychange', () => {
    if (!S.role) return;
    if (document.visibilityState === 'visible') {
      keepAwake();
      if (S.rig && !S.rig.stream?.active && !S.recording) openCamera();
      sendStatus();
    } else if (S.recording) {
      S.link?.send({ type: 'warn', text: 'Safari left the screen. iOS pauses the camera until it comes back.' });
    }
  });
  window.addEventListener('pagehide', () => {
    try { S.recorder?.mr?.requestData(); } catch { /* best effort */ }
  });
  window.addEventListener('resize', () => { if (S.role) checkOrientation(); });
}

/* -------------------------------------------------------------------- boot */

function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  if (location.protocol !== 'https:' && location.hostname !== 'localhost') return;
  navigator.serviceWorker.register('sw.js').catch(() => { /* offline cache is optional */ });
}

async function boot() {
  $('#app-version').textContent = VERSION;
  checkEnvironment();
  bindEvents();
  try {
    const n = await Takes.recoverInterrupted();
    if (n) toast(`Recovered ${n} recording${n > 1 ? 's' : ''} cut off when the app closed. See Recordings.`, { ms: 7000 });
  } catch { /* storage unavailable: reported by checkEnvironment */ }
  try { await navigator.storage?.persist?.(); } catch { /* optional */ }
  registerServiceWorker();

  const join = normalizeCode(params.get('join'));
  if (join) {
    settings.joinCode = join;
    save();
    params.delete('join');
    const qs = params.toString();
    history.replaceState(null, '', location.pathname + (qs ? `?${qs}` : ''));
    await enterRole('camera');
    return;
  }
  // Safari reloaded mid-session (or the phone was swapped back): go straight back in.
  if (settings.role && Date.now() - (settings.roleAt || 0) < RESUME_WINDOW_MS) {
    await enterRole(settings.role);
    return;
  }
  showView('home');
}

boot();
