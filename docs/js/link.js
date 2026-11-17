// Phone-to-phone link over WebRTC (PeerJS).
// The internet is only needed to introduce the two phones; after that the data
// channel runs directly between them (same Wi-Fi or one phone's Personal Hotspot).
//
// The Camera phone continuously measures the clock offset to the Director
// (NTP-style ping/pong, best of the lowest-latency samples) so both phones can
// start recording at the same agreed instant.

import { now } from './util.js';

const PREFIX = 'bdfutsalcampro-';
const ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; // no 0/O, 1/I/L
const WATCHDOG_MS = 12000;

export function newCode(n = 5) {
  const a = new Uint32Array(n);
  crypto.getRandomValues(a);
  return [...a].map((x) => ALPHABET[x % ALPHABET.length]).join('');
}

export const normalizeCode = (s) => (s || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8);

/** "?peer=localhost:9000/myapp" -> PeerJS server options (for self-hosting / testing). */
export function parseServer(param) {
  if (!param) return null;
  try {
    const u = new URL(/^\w+:\/\//.test(param) ? param : `${location.protocol}//${param}`);
    const local = /^(localhost|127\.|192\.168\.|10\.)/.test(u.hostname);
    return {
      host: u.hostname,
      port: Number(u.port) || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname || '/',
      secure: u.protocol === 'https:' && !local,
    };
  } catch { return null; }
}

function makePeer(id, server) {
  const P = window.peerjs?.Peer || window.Peer;
  if (!P) throw new Error('The pairing module did not load. Check the internet connection and reload.');
  const opts = { debug: 1 };
  if (server) Object.assign(opts, server);
  return id ? new P(id, opts) : new P(opts);
}

const NET_ERRORS = ['network', 'server-error', 'socket-error', 'socket-closed', 'disconnected'];

class LinkBase extends EventTarget {
  constructor({ code, server }) {
    super();
    this.code = code;
    this.server = server;
    this.peer = null;
    this.conn = null;
    this.closed = false;
    this.lastSeen = 0;
    this.timers = new Set();
  }

  get connected() { return !!(this.conn && this.conn.open); }

  emit(type, detail) { this.dispatchEvent(new CustomEvent(type, { detail })); }

  later(fn, ms) {
    const t = setTimeout(() => { this.timers.delete(t); if (!this.closed) fn(); }, ms);
    this.timers.add(t);
    return t;
  }

  send(msg) {
    if (!this.connected) return false;
    try { this.conn.send(msg); return true; } catch { return false; }
  }

  watchPeer(peer) {
    peer.on('disconnected', () => {
      if (this.closed || peer.destroyed) return;
      // Lost the signalling server only; an open phone-to-phone channel keeps working.
      this.emit('state', { state: this.connected ? 'paired' : 'connecting', text: this.connected ? null : 'Reconnecting to pairing server…' });
      this.later(() => { if (peer.disconnected && !peer.destroyed) { try { peer.reconnect(); } catch { /* retry below */ } } }, 2000);
    });
  }

  stop() {
    this.closed = true;
    this.timers.forEach(clearTimeout);
    this.timers.clear();
    clearInterval(this.pingTimer);
    try { this.conn?.close(); } catch { /* ignore */ }
    try { this.peer?.destroy(); } catch { /* ignore */ }
  }
}

/* ------------------------------------------------------------------ Director */

export class DirectorLink extends LinkBase {
  constructor(opts) {
    super(opts);
    this.claimTries = 0;
  }

  start() {
    if (this.closed) return;
    this.emit('state', { state: 'connecting', text: 'Connecting to pairing server…' });
    let peer;
    try { peer = makePeer(PREFIX + this.code, this.server); } catch (e) {
      this.emit('state', { state: 'error', text: e.message });
      return;
    }
    this.peer = peer;
    peer.on('open', () => {
      this.claimTries = 0;
      if (!this.connected) this.emit('state', { state: 'ready', text: `Code ${this.code} · waiting for camera` });
    });
    peer.on('connection', (conn) => this.accept(conn));
    peer.on('error', (err) => this.onError(err));
    this.watchPeer(peer);

    clearInterval(this.pingTimer);
    this.pingTimer = setInterval(() => {
      if (this.connected && now() - this.lastSeen > WATCHDOG_MS) {
        try { this.conn.close(); } catch { /* ignore */ }
        this.dropConn(this.conn);
      }
    }, 3000);
  }

  onError(err) {
    const type = err?.type;
    if (type === 'unavailable-id') {
      // The server still holds this code from a previous session; it frees it within seconds.
      try { this.peer.destroy(); } catch { /* ignore */ }
      if (++this.claimTries <= 8) {
        this.emit('state', { state: 'connecting', text: 'Reclaiming pairing code…' });
        this.later(() => this.start(), 2500);
      } else {
        this.emit('codeTaken');
      }
      return;
    }
    if (NET_ERRORS.includes(type)) {
      this.emit('state', { state: 'offline', text: 'No internet for pairing. Recording still works.' });
      this.later(() => {
        if (!this.peer || this.peer.destroyed) this.start();
        else if (this.peer.disconnected) { try { this.peer.reconnect(); } catch { this.start(); } }
      }, 4000);
      return;
    }
    this.emit('state', { state: 'error', text: err?.message || String(err) });
  }

  accept(conn) {
    conn.on('open', () => {
      // Newest connection wins: a camera that reloads replaces its old, stale link.
      const old = this.conn;
      this.conn = conn;
      this.lastSeen = now();
      if (old && old !== conn) { try { old.close(); } catch { /* ignore */ } }
      this.emit('camera', { connected: true });
      this.emit('state', { state: 'paired', text: null });
    });
    conn.on('data', (msg) => {
      if (conn !== this.conn) return;
      this.lastSeen = now();
      if (msg?.type === 'ping') { conn.send({ type: 'pong', t0: msg.t0, t1: now() }); return; }
      this.emit('message', msg);
    });
    conn.on('close', () => this.dropConn(conn));
    conn.on('error', () => this.dropConn(conn));
  }

  dropConn(conn) {
    if (!conn || conn !== this.conn) return;
    this.conn = null;
    this.emit('camera', { connected: false });
    this.emit('state', { state: 'ready', text: `Code ${this.code} · waiting for camera` });
  }

  disconnectCamera() {
    const c = this.conn;
    try { c?.send({ type: 'bye' }); } catch { /* ignore */ }
    setTimeout(() => { try { c?.close(); } catch { /* ignore */ } this.dropConn(c); }, 200);
  }
}

/* -------------------------------------------------------------------- Camera */

export class CameraLink extends LinkBase {
  constructor(opts) {
    super(opts);
    this.samples = [];
    this.offset = 0;   // directorClock - localClock (ms)
    this.rtt = null;
    this.synced = false;
    this.retryTimer = null;
  }

  start() {
    if (this.closed) return;
    this.emit('state', { state: 'connecting', text: 'Connecting to pairing server…' });
    let peer;
    try { peer = makePeer(null, this.server); } catch (e) {
      this.emit('state', { state: 'error', text: e.message });
      return;
    }
    this.peer = peer;
    peer.on('open', () => this.connect());
    peer.on('error', (err) => this.onError(err));
    this.watchPeer(peer);
  }

  connect() {
    if (this.closed || this.connected || !this.peer || this.peer.disconnected || this.peer.destroyed) return;
    if (this.pending && !this.pending.open && now() - this.pendingAt < 9000) return;
    this.emit('state', { state: 'searching', text: `Looking for Director ${this.code}…` });
    const conn = this.peer.connect(PREFIX + this.code, { reliable: true, serialization: 'json' });
    this.pending = conn;
    this.pendingAt = now();
    const giveUp = this.later(() => {
      if (!conn.open) { try { conn.close(); } catch { /* ignore */ } this.retry(); }
    }, 10000);

    conn.on('open', () => {
      clearTimeout(giveUp);
      this.conn = conn;
      this.pending = null;
      this.lastSeen = now();
      this.samples = [];
      this.synced = false;
      this.emit('state', { state: 'paired', text: null });
      this.emit('director', { connected: true });
      this.startPings();
    });
    conn.on('data', (msg) => {
      if (conn !== this.conn) return;
      this.lastSeen = now();
      if (msg?.type === 'pong') { this.onPong(msg); return; }
      if (msg?.type === 'bye') {
        this.emit('state', { state: 'released', text: 'The Director disconnected this camera.' });
      }
      this.emit('message', msg);
    });
    const drop = () => {
      clearTimeout(giveUp);
      if (this.pending === conn) this.pending = null;
      if (this.conn === conn) {
        this.conn = null;
        clearInterval(this.pingTimer);
        this.emit('director', { connected: false });
      }
      this.retry();
    };
    conn.on('close', drop);
    conn.on('error', drop);
  }

  retry(ms = 3000) {
    if (this.closed || this.retryTimer) return;
    this.retryTimer = this.later(() => {
      this.retryTimer = null;
      if (this.connected) return;
      if (!this.peer || this.peer.destroyed) this.start();
      else this.connect();
    }, ms);
  }

  onError(err) {
    const type = err?.type;
    if (type === 'peer-unavailable') {
      this.pending = null;
      this.emit('state', { state: 'searching', text: `Director ${this.code} not found yet. Open Director on the other phone.` });
      this.retry(3000);
      return;
    }
    if (NET_ERRORS.includes(type)) {
      this.emit('state', { state: 'offline', text: 'No internet for pairing. Recording still works on this phone.' });
      this.retry(4000);
      return;
    }
    this.emit('state', { state: 'error', text: err?.message || String(err) });
    this.retry(5000);
  }

  startPings() {
    clearInterval(this.pingTimer);
    let burst = 10;
    const ping = () => {
      if (!this.connected) return;
      if (now() - this.lastSeen > WATCHDOG_MS) {
        try { this.conn.close(); } catch { /* ignore */ }
        return;
      }
      this.send({ type: 'ping', t0: now() });
    };
    // Quick burst for a good first estimate, then keep refreshing.
    const burstTick = () => {
      if (!this.connected || burst-- <= 0) return;
      ping();
      this.later(burstTick, 120);
    };
    burstTick();
    this.pingTimer = setInterval(ping, 2000);
  }

  onPong({ t0, t1 }) {
    const t2 = now();
    const rtt = t2 - t0;
    if (rtt < 0 || rtt > 5000) return;
    this.samples.push({ rtt, offset: t1 - (t0 + t2) / 2, at: t2 });
    if (this.samples.length > 30) this.samples.shift();
    // Lowest round-trip samples are the least distorted by network jitter.
    const best = [...this.samples].sort((a, b) => a.rtt - b.rtt).slice(0, 5);
    this.offset = best.reduce((n, s) => n + s.offset, 0) / best.length;
    this.rtt = best[0].rtt;
    this.synced = this.samples.length >= 3;
    this.emit('sync', { offset: this.offset, rtt: this.rtt });
  }

  toLocal(directorTime) { return directorTime - this.offset; }
  toDirector(localTime) { return localTime + this.offset; }
}
