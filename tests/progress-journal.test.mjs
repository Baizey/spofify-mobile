import test from 'node:test';
import assert from 'node:assert/strict';
import { createProgressTracker } from '../progress-journal.js';

function fixture(append) {
  let clock = 0;
  const tracker = createProgressTracker({ trackId: 'track', eventId: 'event', now: () => 100000 + clock,
    monotonic: () => clock, append });
  return { tracker, async at(time, position, flags = {}) {
    clock = time;
    return tracker.observe({ currentTime: position, playing: true, seeking: false, buffering: false, ...flags });
  } };
}

test('qualifies actual progress once with exactly six frozen fields', async () => {
  const payloads = [];
  const f = fixture(async p => payloads.push(p));
  await f.at(0, 20);
  await f.at(500, 20.5);
  assert.equal(payloads.length, 0);
  await f.at(1000, 21);
  await f.at(2000, 22);
  assert.deepEqual(payloads, [{ eventId: 'event', trackId: 'track', origin: 'offline', occurredAtMs: 101000, elapsedMs: 1000, progressedMs: 1000 }]);
  assert.ok(Object.isFrozen(payloads[0]));
});

test('seeks, backward jumps, pauses, buffering and unobserved gaps add no progress', async () => {
  const payloads = [];
  const f = fixture(async p => payloads.push(p));
  await f.at(0, 0);
  await f.at(1000, 80); // unflagged discontinuity
  await f.at(2000, 81, { seeking: true });
  await f.at(3000, 82);
  await f.at(4000, 83, { playing: false });
  await f.at(5000, 84);
  await f.at(6000, 85, { buffering: true });
  await f.at(7000, 86);
  await f.at(8000, 2); // negative jump
  await f.at(12000, 6); // observation gap
  assert.equal(payloads.length, 0);
  await f.at(12500, 6.5);
  await f.at(13000, 7);
  assert.equal(payloads.length, 1);
  assert.equal(payloads[0].progressedMs, 1000);
});

test('no timer-only evidence; reset drops the observation baseline but not accumulated progress', async () => {
  const payloads = [];
  const f = fixture(async p => payloads.push(p));
  await f.at(0, 0);
  await f.at(1000, 0);
  await f.at(1500, 0.5);
  f.tracker.resetObservation();
  await f.at(2000, 90);
  assert.equal(payloads.length, 0);
  await f.at(2500, 90.5);
  assert.equal(payloads[0].progressedMs, 1000);
});

test('failed durable append retries the identical object without new ID or clock', async () => {
  const payloads = [];
  let fail = true;
  const f = fixture(async p => { payloads.push(p); if (fail) throw new Error('disk full'); });
  await f.at(0, 0);
  await assert.rejects(f.at(1000, 1), /disk full/);
  fail = false;
  f.tracker.resetObservation();
  await f.at(900000, 100);
  assert.equal(payloads.length, 2);
  assert.equal(payloads[0], payloads[1]);
  assert.equal(payloads[1].occurredAtMs, 101000);
});

test('concurrent observations share one in-flight durable append', async () => {
  let release, writes = 0;
  const f = fixture(async () => { writes++; await new Promise(resolve => { release = resolve; }); });
  await f.at(0, 0);
  const first = f.at(1000, 1);
  const second = f.at(1100, 1.1);
  await Promise.resolve();
  assert.equal(writes, 1);
  release();
  await Promise.all([first, second]);
  await f.at(3000, 3);
  assert.equal(writes, 1);
});

test('positive delta is capped at observed monotonic time; invalid observations reset baseline', async () => {
  const payloads = [];
  const f = fixture(async p => payloads.push(p));
  await f.at(0, 0);
  await f.at(500, 0.55);
  await f.at(600, NaN);
  await f.at(700, 70);
  await f.at(1200, 70.55);
  assert.equal(payloads[0].progressedMs, 1000);
  assert.equal(payloads[0].elapsedMs, 1200);
});
