// Persistent storage.
//  - Settings: localStorage (small, per phone).
//  - Recordings: IndexedDB. Video arrives from MediaRecorder in ~1 s chunks and each
//    chunk is written to disk straight away, so a 40-minute 4K half never has to sit
//    in memory, and a crash or reload loses at most the last second.

import { fileStamp, slug } from './util.js';

/* ---------------------------------------------------------------- settings */

const SETTINGS_KEY = 'bdfcp.settings.v1';

export const ROLE_DEFAULTS = {
  director: { side: 'right', name: 'iPhone 17 Pro Max' },
  camera: { side: 'left', name: 'iPhone 15 Pro Max' },
};

const DEFAULT_SETTINGS = {
  role: null,
  roleAt: 0,         // when the role was chosen; a reload within a few hours resumes it
  code: '',          // this phone's pairing code when it is the Director
  joinCode: '',      // the Director code this phone joins when it is the Camera
  match: '',
  quality: '4k',
  lens: 'ultrawide', // 'ultrawide' | 'wide' | a deviceId
  syncBeep: true,
  profile: structuredClone(ROLE_DEFAULTS),
  checklist: {},
  checklistDay: '',
};

export const Settings = {
  load() {
    try {
      const raw = localStorage.getItem(SETTINGS_KEY);
      if (raw) {
        const s = JSON.parse(raw);
        return {
          ...structuredClone(DEFAULT_SETTINGS),
          ...s,
          profile: { ...structuredClone(ROLE_DEFAULTS), ...(s.profile || {}) },
        };
      }
    } catch { /* storage blocked or corrupt: fall through to defaults */ }
    return structuredClone(DEFAULT_SETTINGS);
  },
  save(s) {
    try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(s)); } catch { /* ignore */ }
  },
};

/* ---------------------------------------------------------------- IndexedDB */

const DB_NAME = 'bd-futsal-cam-pro';
const DB_VERSION = 1;
let dbPromise = null;

function openDb() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains('takes')) db.createObjectStore('takes', { keyPath: 'id' });
        if (!db.objectStoreNames.contains('chunks')) db.createObjectStore('chunks', { keyPath: ['takeId', 'seq'] });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
      req.onblocked = () => reject(new Error('Storage is busy in another tab. Close other tabs of this app.'));
    });
    dbPromise.catch(() => { dbPromise = null; });
  }
  return dbPromise;
}

/**
 * Run `fn(tx)` inside one transaction. `fn` must only issue IDB requests
 * synchronously; whatever it returns (or sets on the returned box) resolves after commit.
 */
async function transact(stores, mode, fn) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(stores, mode);
    const box = { value: undefined };
    tx.oncomplete = () => resolve(box.value);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error || new Error('Storage write was cancelled'));
    try { fn(tx, box); } catch (e) { try { tx.abort(); } catch { /* already done */ } reject(e); }
  });
}

const chunkRange = (takeId) => IDBKeyRange.bound([takeId, 0], [takeId, Infinity]);

export const Takes = {
  put(take) {
    return transact(['takes'], 'readwrite', (tx) => { tx.objectStore('takes').put(take); });
  },

  get(id) {
    return transact(['takes'], 'readonly', (tx, box) => {
      const r = tx.objectStore('takes').get(id);
      r.onsuccess = () => { box.value = r.result; };
    });
  },

  async list() {
    const all = await transact(['takes'], 'readonly', (tx, box) => {
      const r = tx.objectStore('takes').getAll();
      r.onsuccess = () => { box.value = r.result || []; };
    });
    return all.sort((a, b) => b.createdAt - a.createdAt);
  },

  async patch(id, fields) {
    const t = await Takes.get(id);
    if (!t) return null;
    Object.assign(t, fields);
    await Takes.put(t);
    return t;
  },

  putChunk(takeId, seq, blob) {
    return transact(['chunks'], 'readwrite', (tx) => {
      tx.objectStore('chunks').put({ takeId, seq, blob });
    });
  },

  async chunks(takeId) {
    const rows = await transact(['chunks'], 'readonly', (tx, box) => {
      const r = tx.objectStore('chunks').getAll(chunkRange(takeId));
      r.onsuccess = () => { box.value = r.result || []; };
    });
    return rows.sort((a, b) => a.seq - b.seq).map((r) => r.blob);
  },

  remove(id) {
    return transact(['takes', 'chunks'], 'readwrite', (tx) => {
      tx.objectStore('takes').delete(id);
      tx.objectStore('chunks').delete(chunkRange(id));
    });
  },

  /** Takes left in "recording" state by a crash or reload become "recovered". */
  async recoverInterrupted() {
    const all = await Takes.list();
    const stuck = all.filter((t) => t.status === 'recording');
    for (const t of stuck) {
      const blobs = await Takes.chunks(t.id);
      if (!blobs.length) { await Takes.remove(t.id); continue; } // nothing was written
      t.status = 'recovered';
      t.durationMs = Math.max(t.durationMs || 0, blobs.length * 1000); // one chunk per second
      t.bytes = blobs.reduce((n, b) => n + b.size, 0);
      t.chunks = blobs.length;
      t.endedAt = t.endedAt || t.startedAt || t.createdAt;
      await Takes.put(t);
    }
    return stuck.length;
  },

  async file(take) {
    const blobs = await Takes.chunks(take.id);
    const type = (take.mimeType || 'video/mp4').split(';')[0].trim();
    return new File(blobs, Takes.filename(take), { type });
  },

  filename(take, ext) {
    const e = ext || ((take.mimeType || '').includes('webm') ? 'webm' : 'mp4');
    const match = slug(take.match) || 'Match';
    const cam = take.side === 'left' ? 'CAM01-LEFT' : 'CAM02-RIGHT';
    return `BDFutsal_${match}_${fileStamp(take.groupStamp || take.createdAt)}_${cam}.${e}`;
  },
};

export async function storageInfo() {
  try {
    const est = await navigator.storage?.estimate?.();
    if (!est) return null;
    const quota = est.quota ?? null;
    const usage = est.usage ?? null;
    return { quota, usage, free: quota != null && usage != null ? Math.max(0, quota - usage) : null };
  } catch { return null; }
}
