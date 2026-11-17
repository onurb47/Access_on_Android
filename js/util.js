// Small helpers shared by every module.

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

/** High-resolution wall-clock time in ms. Used for cross-phone scheduling. */
export function now() {
  return performance.timeOrigin + performance.now();
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Run `fn` as close as possible to local time `t` (ms, same clock as now()).
 * Sleeps with setTimeout, then spins for the last few milliseconds.
 */
export function runAt(t, fn) {
  return new Promise((resolve, reject) => {
    const tick = () => {
      const left = t - now();
      if (left > 25) {
        setTimeout(tick, Math.max(0, left - 20));
        return;
      }
      while (now() < t) { /* spin for the final few ms */ }
      try { resolve(fn()); } catch (e) { reject(e); }
    };
    tick();
  });
}

const pad = (n) => String(n).padStart(2, '0');

/** 00:12:34 */
export function fmtClock(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
}

/** 12:34 or 1:02:03 */
export function fmtDuration(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h ? `${h}:${pad(m)}:${pad(s % 60)}` : `${m}:${pad(s % 60)}`;
}

export function fmtBytes(b) {
  if (b == null || !isFinite(b)) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (b >= 1000 && i < units.length - 1) { b /= 1000; i++; }
  return `${b.toFixed(b < 10 && i > 0 ? 1 : 0)} ${units[i]}`;
}

export function fmtDate(ts) {
  try {
    return new Date(ts).toLocaleString(undefined, {
      weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
    });
  } catch { return new Date(ts).toISOString(); }
}

/** 2026-11-17_1930 (local time) */
export function fileStamp(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}`;
}

/** "Uni vs City (H1)" -> "Uni-vs-City-H1" */
export function slug(s) {
  return (s || '')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^A-Za-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48);
}

export function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

export function uid(prefix = '') {
  const a = new Uint32Array(2);
  crypto.getRandomValues(a);
  return prefix + Date.now().toString(36) + a[0].toString(36) + a[1].toString(36);
}
