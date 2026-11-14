// Call observe on regular media observations (not just a wall-clock timer).
// Longer gaps are deliberately not evidence. resetObservation preserves this
// occurrence's accumulated evidence and immutable retry payload, not its baseline.
export const MAX_OBSERVATION_GAP_MS = 2000;

export function createProgressTracker({
  trackId, eventId = globalThis.crypto.randomUUID(), now = Date.now,
  monotonic = () => performance.now(), append,
}) {
  if (typeof append !== 'function' || typeof trackId !== 'string' || !trackId ||
      typeof eventId !== 'string' || !eventId) throw new TypeError('Invalid progress tracker');
  let previous, started, progressed = 0, pending, appended = false, writing;

  async function persist() {
    if (!pending || appended) return;
    if (!writing) {
      writing = Promise.resolve().then(() => append(pending)).then(() => { appended = true; });
    }
    const attempt = writing;
    try { await attempt; } finally { if (writing === attempt) writing = undefined; }
  }

  return {
    async observe({ currentTime, playing, seeking, buffering }) {
      if (pending) { await persist(); return; }
      const time = monotonic();
      if (!Number.isFinite(time) || !Number.isFinite(currentTime) || currentTime < 0) {
        previous = undefined;
        return;
      }
      if (started === undefined) started = time;
      const active = playing === true && !seeking && !buffering;
      if (previous && active && previous.active) {
        const wall = time - previous.time;
        const delta = (currentTime - previous.currentTime) * 1000;
        // Reject discontinuities instead of counting a seek as a wall interval.
        if (wall > 0 && wall <= MAX_OBSERVATION_GAP_MS && delta > 0 && delta <= wall + 100) {
          progressed += Math.min(delta, wall);
        }
      }
      previous = { time, currentTime, active };
      const elapsedMs = Math.floor(time - started);
      const progressedMs = Math.floor(Math.min(progressed, elapsedMs));
      if (elapsedMs >= 1000 && progressedMs >= 1000) {
        const occurredAtMs = now();
        if (!Number.isSafeInteger(occurredAtMs) || occurredAtMs < elapsedMs) {
          throw new Error('Invalid qualification clock');
        }
        pending = Object.freeze({ eventId, trackId, origin: 'offline', occurredAtMs, elapsedMs, progressedMs });
        await persist();
      }
    },
    resetObservation() { previous = undefined; },
  };
}
