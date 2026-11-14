(() => {
  'use strict';
  const PATH = '/api/me/player/connect', SOCKET = '/api/me/player/socket', PROTOCOL = 'spofify.library-playback.v2';
  const MAX_BUFFER = 64 * 1024, MAX_MESSAGE = 5 * 1024 * 1024, MAX_PENDING = 32;
  const PROGRESS_INTERVAL = 2000;
  const integer = (value) => Number.isSafeInteger(value) && value >= 0;
  const identifier = (value) => typeof value === 'string' && value.length > 0 && new TextEncoder().encode(value).length <= 128 && !/[\p{Cc}\p{Cs}]/u.test(value);
  const catalogId = (value) => typeof value === 'string' && value.length > 0 && new TextEncoder().encode(value).length <= 512 && !/[\p{Cc}\p{Cs}]/u.test(value);
  const finite = (value) => Number.isFinite(value) && value >= 0;
  const freeze = (value) => { if (value && typeof value === 'object' && !Object.isFrozen(value)) { Object.values(value).forEach(freeze); Object.freeze(value); } return value; };
  const validName = (value) => typeof value === 'string' && value.trim().length > 0 && Array.from(value.trim()).length <= 64 && !/[\p{Cc}\p{Cs}]/u.test(value);
  function defaultName(navigator = {}) {
    const ua = navigator.userAgent || '';
    const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox|FxiOS/.test(ua) ? 'Firefox' : /Chrome|CriOS/.test(ua) ? 'Chrome' : /Safari/.test(ua) ? 'Safari' : 'Browser';
    const system = /Android/.test(ua) ? 'Android' : /iPhone|iPod/.test(ua) ? 'iPhone' : /iPad/.test(ua) || /Macintosh/.test(ua) && navigator.maxTouchPoints > 1 ? 'iPad'
      : /Windows/.test(ua) ? 'Windows' : /Mac/.test(ua) ? 'Mac' : /Linux/.test(ua) ? 'Linux' : 'this device';
    return `${browser} on ${system}`;
  }
  // Best-effort profile identity, not authentication or physical-device attestation.
  // Exclude versions, locale, viewport and storage; normalize screen orientation.
  // Identical profiles collide, and hardware/display/privacy changes can change it.
  function browserDeviceId({ navigator = window.navigator || {}, screen = window.screen || {} } = {}) {
    const number = (value) => Number.isFinite(value) && value >= 0 ? value : 0;
    const dimensions = [number(screen.width), number(screen.height)].sort((a, b) => a - b);
    const profile = JSON.stringify(['browser-v1', defaultName(navigator), String(navigator.platform || ''),
      number(navigator.hardwareConcurrency), number(navigator.deviceMemory), number(navigator.maxTouchPoints),
      ...dimensions, number(screen.colorDepth)]);
    // FNV-1a/64 standardizes the profile without requiring a secure-context API.
    let hash = 0xcbf29ce484222325n;
    for (const byte of new TextEncoder().encode(profile)) hash = BigInt.asUintN(64, (hash ^ BigInt(byte)) * 0x100000001b3n);
    return `browser-v1-${hash.toString(16).padStart(16, '0')}`;
  }
  // Own-name preferences only: browser storage is not device identity authority.
  function createPreferences({ storage = () => window.localStorage } = {}) {
    const namePrefix = 'spofify.playback.own-name.v1:';
    const names = new Map();
    const read = (key) => { try { return storage()?.getItem(key) ?? null; } catch (_) { return null; } };
    const save = (key, value) => { try { const store = storage(); store?.setItem(key, value); return store?.getItem(key) === value; } catch (_) { return false; } };
    const accountKey = (account) => typeof account === 'string' && account.length > 0 ? namePrefix + JSON.stringify(account) : null;
    return {
      ownName(account) {
        const key = accountKey(account);
        if (!key) return null;
        if (names.has(key)) return names.get(key);
        const name = read(key);
        if (!validName(name)) return null;
        names.set(key, name.trim());
        return name.trim();
      },
      rememberOwnName(account, name) {
        const key = accountKey(account);
        if (!key || !validName(name)) return false;
        names.set(key, name.trim());
        return save(key, name.trim());
      }
    };
  }
  function allowsRoute(url, method) { return url?.pathname === PATH && !url.search && !url.hash && method === 'POST'; }
  async function safeError(response) {
    return Object.assign(new Error(response.status === 401 ? 'Sign in again to connect playback.' : 'Could not connect playback. Try again explicitly.'), { status: response.status });
  }
  const empty = () => ({ version: 1, queue: [], position: -1, context: null, shuffle: false, repeat: 'off', desiredPlaying: false,
    highKeys: [], elapsed: 0, duration: 0, isPlaying: false, isBuffering: false, isLoading: false, error: '', truncated: false, notice: '', occurrence: null });
  function validPlayback(playback) {
    return playback && Object.keys(playback).length === 11 && ['outputVersion', 'stateVersion', 'playbackSequence'].every((key) => integer(playback[key])) &&
      typeof playback.eventId === 'string' && typeof playback.hasReportedPlayback === 'boolean' &&
      ['isPlaying', 'isBuffering', 'isLoading'].every((key) => typeof playback[key] === 'boolean') &&
      finite(playback.elapsed) && finite(playback.duration) && ['', 'blocked', 'media'].includes(playback.error);
  }
  function validLeaseTiming(value) {
    return value && Object.keys(value).length === 2 && integer(value.clientSequence) && value.clientSequence > 0 && integer(value.durationMs);
  }
  function trackRef(track) {
    if (!track || typeof track.id !== 'string' || !track.id) throw new Error('Choose an available track.');
    return { id: track.id, ...Object.fromEntries(['entryId', 'targetId', 'occurrenceId', 'selectionToken'].filter((key) => track[key]).map((key) => [key, track[key]])) };
  }
  function selection(value) {
    if (value?.kind === 'occurrence' && integer(value.key) && value.key > 0) return { kind: 'occurrence', key: value.key };
    const context = value?.context;
    const canonicalContext = context && ['album', 'artist', 'playlist', 'likes', 'derived'].includes(context.kind) && typeof context.id === 'string' && context.id
      ? { kind: context.kind, id: context.id, name: context.name || '', ...(context.revision ? { revision: context.revision } : {}), ...(context.fence ? { fence: context.fence } : {}) } : null;
    if (value?.kind === 'collection' && canonicalContext) return { kind: 'collection', context: canonicalContext };
    if (value?.kind === 'track') return { kind: 'track', track: trackRef(value.track), ...(canonicalContext ? { context: canonicalContext } : {}) };
    throw new Error('Choose an available selection.');
  }
  // Translate public presentation commands once. Nothing here resolves collections,
  // places entries, allocates keys or predicts server musical transitions.
  function commandOf(value, state) {
    switch (value?.type) {
      case 'play': return { type: 'queueIntent', intent: 'now', selection: selection({ kind: 'track', track: { ...value.track,
        ...(value.entryId ? { entryId: value.entryId } : {}), ...(value.occurrenceId ? { occurrenceId: value.occurrenceId } : {}),
        ...(value.selectionToken ? { selectionToken: value.selectionToken } : {}) }, context: value.context }) };
      case 'playCollection':
        if (Object.hasOwn(value, 'shuffle') && typeof value.shuffle !== 'boolean') break;
        return { type: 'queueIntent', intent: 'now', selection: selection({ kind: 'collection', context: value.context }),
          ...(value.shuffle === true ? { shuffle: true } : {}) };
      case 'queueIntent': {
        if (!['low', 'high', 'next', 'now'].includes(value.intent)) break;
        const selected = selection(value.selection), hasShuffle = Object.hasOwn(value, 'shuffle');
        if (hasShuffle && (typeof value.shuffle !== 'boolean' || value.intent !== 'now' || selected.kind !== 'collection')) break;
        return { type: 'queueIntent', intent: value.intent, selection: selected, ...(hasShuffle ? { shuffle: value.shuffle } : {}) };
      }
      case 'toggle': return { type: state.isPlaying ? 'pause' : 'resume' };
      case 'shuffle': return { type: 'setShuffle', shuffle: !state.shuffle };
      case 'repeat': return { type: 'setRepeat', repeat: { off: 'all', all: 'one', one: 'off' }[state.repeat] };
      case 'setShuffle': if (typeof value.shuffle === 'boolean') return { type: value.type, shuffle: value.shuffle }; break;
      case 'setRepeat': if (['off', 'all', 'one'].includes(value.repeat)) return { type: value.type, repeat: value.repeat }; break;
      case 'seek': if (finite(value.value)) return { type: 'seek', value: value.value }; break;
      case 'jump': if (integer(value.key) && value.key > 0) return { type: value.type, key: value.key }; break;
      case 'remove': if (integer(value.key) && value.key > 0 && (value.undo === undefined || typeof value.undo === 'boolean')) return { type: value.type, key: value.key, ...(value.undo === undefined ? {} : {undo: value.undo}) }; break;
      case 'undoRemove': if (identifier(value.token)) return {type: value.type, token: value.token}; break;
      case 'move':
        if (integer(value.key) && value.key > 0 && Object.hasOwn(value, 'afterKey') &&
            (value.afterKey === null || integer(value.afterKey) && value.afterKey > 0 && value.afterKey !== value.key) && !Object.hasOwn(value, 'direction')) {
          return { type: 'move', key: value.key, afterKey: value.afterKey };
        }
        break;
      case 'select': if (identifier(value.deviceId)) return { type: 'select', deviceId: value.deviceId }; break;
      case 'rename':
        if (validName(value.name) && (!Object.hasOwn(value, 'deviceId') || identifier(value.deviceId))) {
          return { type: 'rename', name: value.name.trim(), ...(Object.hasOwn(value, 'deviceId') ? { deviceId: value.deviceId } : {}) };
        }
        break;
      case 'pause': case 'resume': case 'next': case 'previous': case 'retry': case 'clear': case 'close': case 'deselect': return { type: value.type };
    }
    throw new Error('Playback action unavailable.');
  }
  // The platform must supply exactly one identity input: an opaque deviceId
  // string, or deviceIdProvider({ signal }) returning that string (or a Promise).
  // Determinism across storage wipes/reinstalls is the platform's responsibility;
  // this browser adapter cannot manufacture or establish that guarantee.
  function create({ request, player, auth, deviceId: suppliedDeviceId, deviceIdProvider, notice = () => {}, WebSocket: Socket = window.WebSocket,
    clock = () => window.performance.now(), renewalMs = 60000 } = {}) {
    if (!player) return null;
    let stopped = false, authEnded = false, lifecycle = 0, socket = null, removeSocket = () => {}, admission = null;
    let phase = 'connecting', message = '', messageKind = '', feedbackSeq = 0;
    let account = '', connectionId = '', deviceId = '', sessionId = '', snapshot = null, playback = null, futurePlayback = null;
    let commandID = 0, clientSequence = 0, outputVersion = 0, execution = null, prepared = null, nextPlan = null, operation = 0, leaseDeadline = 0;
    let clientDeviceId = '';
    const canPlay = player.canPlay !== false && typeof player.prepareAudio === 'function' && typeof player.executeAudio === 'function';
    let leaseTimer = null, progressTimer = null, renewalTimer = null, connectTimer = null, renewing = false;
    let renewalAbort = null, renewalTimeout = null;
    const preferences = createPreferences();
    let displayName = defaultName(window.navigator), initial = true;
    const pending = new Map(), sendTimes = new Map(), seenInstructions = new Set(), subscribers = new Set(), cleanups = [];
    const sameAccount = () => !auth || !!account && auth.accountId?.() === account;
    const live = (version) => {
      if (stopped || authEnded || version !== lifecycle) return false;
      if (!sameAccount()) { disconnect('Playback authentication changed.'); return false; }
      return true;
    };
    const authorized = () => !!execution && !execution.revoked && live(lifecycle) && socket?.readyState === 1 && clock() < leaseDeadline;
    // Stopped observation is not permission to play. The media adapter's
    // paused desired state independently guards native play/playing events.
    const observable = () => authorized() || !!execution?.parked && !execution.revoked && live(lifecycle) && socket?.readyState === 1;
    const ownOutput = () => !!connectionId && snapshot?.selectedConnectionId === connectionId;
    const targetOutput = () => ownOutput() || !!connectionId && snapshot?.transfer?.targetConnectionId === connectionId;
    const sameSource = (value, binding = execution) => !!binding && value.outputVersion === binding.outputVersion && value.eventId === binding.eventId && value.currentInstructionId === binding.id;
    function getSnapshot() {
      const devices = snapshot?.devices || [], selected = snapshot?.selectedDeviceId || '';
      return freeze({ phase: phase === 'ready' && snapshot?.transfer ? 'switching' : phase, deviceId, connectionId, sessionId, outputVersion, stateVersion: snapshot?.stateVersion ?? 0,
        canPlay, localExecutor: ownOutput(),
        selectedConnectionId: snapshot?.selectedConnectionId || '', targetConnectionId: snapshot?.transfer?.targetConnectionId || '',
        devices: devices.map((device) => ({ id: device.deviceId, name: device.name, available: device.available, canPlay: device.canPlay,
          status: !device.available ? 'unavailable' : device.deviceId !== selected || !playback?.hasReportedPlayback ? 'available'
            : playback.error === 'blocked' ? 'needsTap' : playback.isPlaying ? 'playing' : 'paused' })),
        activeDeviceId: selected, selectedDeviceId: snapshot?.transfer?.targetDeviceId || '', selectionUnconfirmed: !!snapshot?.transfer,
        busy: pending.size > 0, needsTap: playback?.error === 'blocked', quarantineMs: snapshot?.transfer?.phase === 'draining' ? 1 : 0,
        message, messageKind, feedbackSeq, state: snapshot?.state || null, playback });
    }
    function publish() {
      player.setCoordinationState?.({ busy: pending.size > 0, switching: !!snapshot?.transfer, quarantineMs: snapshot?.transfer?.phase === 'draining' ? 1 : 0 });
      const view = getSnapshot();
      for (const subscriber of subscribers) { try { subscriber(view); } catch (_) { /* Views cannot interrupt fencing. */ } }
    }
    function feedback(text) {
      message = text; messageKind = 'error'; feedbackSeq++;
      notice(text, false); publish();
    }
    function retirePlan(id, binding = execution) {
      if (!binding || !id) return;
      (binding.retiredPlans ||= new Set()).add(id);
      if (binding.retiredPlans.size > 128) disconnect('Playback plan changed too often. Reconnect explicitly.');
    }
    function discardStage() {
      if (nextPlan) retirePlan(nextPlan.planId);
      nextPlan = null;
      // Once source-ended was sent, the reserved Next is the current execution,
      // not a future stage. Submission alone cannot revoke it: socket order may
      // commit Next before a queue/mode command that preserves that execution.
      // Authoritative cancel/replace/release and lifecycle/lease loss still fence it.
    }
    function suspend() {
      leaseDeadline = 0; operation++; prepared = null; nextPlan = null;
      window.clearTimeout(leaseTimer); leaseTimer = null;
      player.suspend();
      if (playback && execution) { playback = freeze({ ...playback, hasReportedPlayback: false, isPlaying: false, isBuffering: false, isLoading: false }); presentation(); }
    }
    function presentation(structural = false) {
      if (!snapshot) return;
      const options = { stateVersion: snapshot.stateVersion, local: ownOutput(), name: snapshot.devices.find((device) => device.deviceId === snapshot.selectedDeviceId)?.name || 'selected device', playback };
      if (structural) player.setSessionState(snapshot.state, options);
      else player.setSessionPlayback(playback || { ...snapshot.state, hasReportedPlayback: false }, options);
    }
    function finishPending(reason = 'uncertain') {
      for (const [id, entry] of pending) {
        window.clearTimeout(entry.timer); entry.resolve(freeze({ id, outcome: 'rejected', code: 'unavailable', execution: reason, final: true }));
      }
      pending.clear();
    }
    function disconnect(reason = 'Playback disconnected. Reconnect explicitly; no action will be replayed.') {
      lifecycle++; admission?.abort(); admission = null;
      window.clearTimeout(connectTimer); window.clearTimeout(progressTimer); window.clearTimeout(renewalTimer);
      connectTimer = progressTimer = renewalTimer = null; renewing = false;
      renewalAbort?.abort(); renewalAbort = null; window.clearTimeout(renewalTimeout); renewalTimeout = null;
      suspend(); execution = null;
      removeSocket(); removeSocket = () => {};
      const old = socket; socket = null;
      if (old && old.readyState < 2) { try { old.close(1000, 'client suspended'); } catch (_) { /* Already closed. */ } }
      finishPending(); sendTimes.clear(); seenInstructions.clear();
      if (playback) playback = freeze({ ...playback, hasReportedPlayback: false, isPlaying: false, isBuffering: false, isLoading: false });
      phase = stopped ? 'stopped' : 'offline'; message = reason; messageKind = 'error'; feedbackSeq++;
      presentation(); publish();
    }
    function send(value) {
      if (!live(lifecycle) || !socket || socket.readyState !== 1) return false;
      const data = JSON.stringify(value);
      if (new TextEncoder().encode(data).length > MAX_BUFFER || socket.bufferedAmount + new TextEncoder().encode(data).length > MAX_BUFFER) {
        disconnect('Playback connection is too slow. Reconnect explicitly.'); return false;
      }
      try { socket.send(data); return true; } catch (_) { disconnect(); return false; }
    }
    function numbered(value) {
      if (clientSequence >= Number.MAX_SAFE_INTEGER) { disconnect(); return 0; }
      const number = ++clientSequence, sent = clock();
      // A delayed grant whose basis was evicted is rejected, never rebased to now.
      sendTimes.set(number, sent);
      while (sendTimes.size > 256) sendTimes.delete(sendTimes.keys().next().value);
      if (!send({ ...value, clientSequence: number })) { sendTimes.delete(number); return 0; }
      return number;
    }
    function report(facts = player.getActualFacts(), final = false, endedEvent = false) {
      if (!execution || !final && !observable()) return;
      const binding = execution;
      if (!final && binding.parked && binding.lastFacts && Object.keys(facts).every(key => facts[key] === binding.lastFacts[key])) return;
      // Parked observation cannot claim live playing authority, even if the
      // platform briefly changes the element before its native-play guard.
      if (binding.parked && (facts.isPlaying || facts.ended)) return;
      const ended = facts.ended && !binding.endedReported;
      const plan = ended && endedEvent && !final && nextPlan && sameSource(nextPlan) && binding.desiredPlaying && authorized() ? nextPlan : null;
      const sent = numbered({ type: 'report', outputVersion: binding.outputVersion, eventId: binding.eventId, instructionId: binding.id,
        elapsed: facts.elapsed, duration: facts.duration, isPlaying: facts.isPlaying, isBuffering: facts.isBuffering, isLoading: facts.isLoading,
        error: facts.error, ended, ...(plan ? { planId: plan.planId } : {}) });
      if (sent) { binding.lastFacts = { ...facts }; binding.lastReportAt = clock(); }
      if (sent && ended) binding.endedReported = true;
      // Socket order is the reconciliation barrier: source end is sent before
      // the reserved next execution can emit any ACK or physical facts.
      if (sent && plan && execution === binding && authorized()) void applyNext(plan, binding).catch(() => disconnect('Playback execution failed.'));
    }
    player.setFactListener((facts, event) => {
      const previous = execution?.lastFacts;
      // Only the controller timer reports ordinary progress. Native events
      // interrupt that cadence for real state changes or a completed seek/end.
      if (previous && event !== 'seeked' && event !== 'ended' &&
          ['isPlaying', 'isBuffering', 'isLoading', 'error', 'duration', 'ended'].every(key => facts[key] === previous[key])) return;
      report(facts, false, event === 'ended');
    });
    async function applyNext(plan, source) {
      if (nextPlan !== plan || !sameSource(plan, source) || execution !== source || !authorized()) return;
      nextPlan = null; operation++; const op = operation, version = lifecycle;
      const value = { id: plan.next.instructionId, type: 'execute', outputVersion: source.outputVersion, eventId: plan.next.eventId,
        track: plan.next.track, elapsed: 0, desiredPlaying: true, planId: plan.planId };
      const binding = execution = { id: value.id, outputVersion: value.outputVersion, eventId: value.eventId, desiredPlaying: true,
        planId: plan.planId, source: plan, track: `${value.track.key}:${value.track.id}`, outcome: null };
      binding.work = player.executeAudio(value, () => authorized() && execution === binding && operation === op);
      const outcome = await binding.work;
      if (!live(version) || op !== operation || execution !== binding || binding.revoked) return;
      binding.outcome = outcome;
      // An unconfirmed reservation still needs its inherited finite deadline
      // to validate the server's eventual confirmation, including failures.
      if (binding.confirmed) park(binding, value, outcome);
      ack(value, outcome); report();
    }
    function park(binding, value, outcome) {
      if (outcome === 'rejected' || value.desiredPlaying && outcome === 'ok') return;
      // Failed playback must cancel any late native promise BEFORE its ACK
      // becomes stopped proof. A successful paused execute already did so.
      if (outcome !== 'ok') player.suspend();
      binding.parked = true; nextPlan = null; leaseDeadline = 0;
      window.clearTimeout(leaseTimer); leaseTimer = null;
      // Ensure the first stopped report survives deduplication even if the
      // adapter emitted these facts just before the completion ACK.
      binding.lastFacts = null;
    }
    function ack(instruction, result) {
      return numbered({ type: 'ack', instructionId: instruction.id, outputVersion: instruction.outputVersion, eventId: instruction.eventId, status: result });
    }
    function expireLease() {
      window.clearTimeout(leaseTimer); leaseTimer = null;
      if (!leaseDeadline) return;
      const remaining = leaseDeadline - clock();
      if (remaining > 0) {
        // Browser timers may run early. Keep an independent stop scheduled;
        // authorization checks on later media/progress events are not enough.
        leaseTimer = window.setTimeout(expireLease, remaining);
        return;
      }
      suspend(); execution = null;
      feedback('Playback permission expired. Choose the output again explicitly.');
    }
    function grant(value) {
      if (!value || !integer(value.clientSequence) || value.clientSequence < 1 || !integer(value.durationMs)) return false;
      // The echoed clientSequence identifies our earlier send time, not receipt.
      const basis = sendTimes.get(value.clientSequence);
      if (basis === undefined || value.durationMs < 1 || value.durationMs > 8000) return false;
      const deadline = basis + value.durationMs;
      if (!Number.isFinite(deadline) || deadline <= clock()) return false;
      leaseDeadline = deadline; expireLease();
      return true;
    }
    function acceptLease(value) {
      if (!value || Object.keys(value).length !== 4 || !integer(value.outputVersion) || typeof value.eventId !== 'string' ||
          !integer(value.clientSequence) || value.clientSequence < 1 || !integer(value.durationMs)) throw new Error('Invalid playback lease');
      if (value.outputVersion < outputVersion) return;
      // A source renewal in flight cannot renew or revoke a local reservation.
      // It inherits its old deadline until the matching next confirmation.
      if (execution?.source && value.outputVersion === execution.source.outputVersion && value.eventId === execution.source.eventId) return;
      // A pre-proof renewal cannot resurrect parked authority.
      if (execution?.parked && value.outputVersion === execution.outputVersion && value.eventId === execution.eventId) return;
      if (!execution || value.outputVersion !== execution.outputVersion || value.eventId !== execution.eventId || !authorized() || !grant(value)) {
        suspend(); execution = null;
      }
      // No operation receipt, execution ID or instruction delivery deduplication.
    }
    async function instruction(value, version) {
      if (!value || !identifier(value.id) || !['prepare', 'release', 'activate', 'execute', 'stageNext', 'cancelNext'].includes(value.type) ||
          !integer(value.outputVersion) || typeof value.eventId !== 'string' || !finite(value.elapsed) || typeof value.desiredPlaying !== 'boolean' ||
          ['epoch', 'replyToSeq', 'clientSequence', 'durationMs'].some(key => Object.hasOwn(value, key)) ||
          ((value.type === 'activate' || value.type === 'execute') ? !validLeaseTiming(value.lease) : Object.hasOwn(value, 'lease'))) {
        disconnect('Invalid playback instruction.'); return;
      }
      if (seenInstructions.has(value.id)) return;
      seenInstructions.add(value.id); while (seenInstructions.size > 128) seenInstructions.delete(seenInstructions.values().next().value);
      if (value.type === 'stageNext' || value.type === 'cancelNext') {
        if (!identifier(value.planId) || !identifier(value.currentInstructionId)) { disconnect('Invalid playback plan.'); return; }
        if (value.type === 'cancelNext') {
          if (sameSource(value)) {
            retirePlan(value.planId);
            if (nextPlan?.planId === value.planId) nextPlan = null;
          }
          const binding = execution;
          if (binding?.planId === value.planId && !binding.confirmed && sameSource(value, {
            id: binding.source.currentInstructionId, outputVersion: binding.source.outputVersion, eventId: binding.source.eventId
          })) {
            // Cancel only speculative media work, not the connection's lease.
            // A newer absolute execute can still reconcile within this outputVersion.
            binding.revoked = true; operation++; player.suspend();
          }
          return;
        }
        const next = value.next;
        if (!next || !identifier(next.eventId) || !identifier(next.instructionId) || !next.track ||
            !catalogId(next.track.id) || !integer(next.track.key) || next.track.key < 1 ||
            next.eventId === value.eventId || next.instructionId === value.currentInstructionId) { disconnect('Invalid playback plan.'); return; }
        if (!sameSource(value) || !authorized() || !execution.desiredPlaying || execution.endedReported || !targetOutput() ||
            execution.retiredPlans?.has(value.planId) || [...pending.values()].some((entry) => entry.command.type !== 'rename')) return;
        if (nextPlan?.planId === value.planId) return;
        if (nextPlan) retirePlan(nextPlan.planId);
        nextPlan = freeze(value);
        return;
      }
      if (value.outputVersion < outputVersion) { ack(value, 'rejected'); return; }
      if (value.type === 'release') {
        // Stop first, then preserve final actual position under the PRIOR execution
        // binding. Release's ID must never impersonate an execute report.
        player.suspend();
        if (execution) report(player.getActualFacts(), true);
        // Same-output parked resume prepares and then releases on this socket.
        // Release cancels work but does not unload that prepared track. Its ACK
        // is the fresh send-time basis used by the server's next activation.
        const ready = prepared;
        suspend(); execution = null; outputVersion = value.outputVersion;
        const basis = ack(value, 'ok');
        if (ready && ready.outputVersion === value.outputVersion && ready.eventId === value.eventId && basis) prepared = { ...ready, basis };
        return;
      }
      // Confirmation names the exact locally applied reservation. Renew/ACK it,
      // but never call the adapter again (including while metadata/play waits).
      if (value.type === 'execute' && value.planId && execution?.planId === value.planId && execution.id === value.id &&
          execution.outputVersion === value.outputVersion && execution.eventId === value.eventId && execution.track === (value.track ? `${value.track.key}:${value.track.id}` : '')) {
        const binding = execution;
        if (!authorized() || !targetOutput() || !grant(value.lease)) { suspend(); execution = null; ack(value, 'rejected'); return; }
        binding.confirmed = true;
        const op = operation, outcome = binding.outcome ?? await binding.work;
        if (!live(version) || execution !== binding || op !== operation || binding.revoked) return;
        park(binding, value, outcome); ack(value, outcome); report(); return;
      }
      nextPlan = null;
      const prepareBasis = prepared;
      if (value.outputVersion > outputVersion) { suspend(); execution = null; outputVersion = value.outputVersion; }
      if (value.type === 'prepare') {
        suspend(); execution = null;
        const op = operation;
        const outcome = await player.prepareAudio(value);
        if (!live(version) || op !== operation) return;
        const basis = ack(value, outcome);
        prepared = outcome === 'ok' && basis ? { outputVersion: value.outputVersion, eventId: value.eventId, basis, track: value.track ? `${value.track.key}:${value.track.id}` : '' } : null;
        return;
      }
      // Targeted instructions may precede the structural publication. Activate
      // advances the prepare outputVersion; the exact prepare ACK/event/track remains
      // its basis. Display snapshots never grant audio permission themselves.
      if (value.type === 'activate' && (!prepareBasis || prepareBasis.outputVersion > value.outputVersion || prepareBasis.eventId !== value.eventId ||
            prepareBasis.basis !== value.lease.clientSequence || prepareBasis.track !== (value.track ? `${value.track.key}:${value.track.id}` : '')) ||
          value.type === 'execute' && (!execution || execution.outputVersion !== value.outputVersion) || !targetOutput() || !canPlay && value.desiredPlaying || !grant(value.lease)) {
        suspend(); execution = null; ack(value, 'rejected'); return;
      }
      operation++; const op = operation; prepared = null;
      execution = { id: value.id, outputVersion: value.outputVersion, eventId: value.eventId, desiredPlaying: value.desiredPlaying };
      const binding = execution;
      const outcome = await player.executeAudio(value, () => observable() && execution === binding && operation === op);
      if (!live(version) || op !== operation || execution !== binding) return;
      park(binding, value, outcome); ack(value, outcome); report();
    }
    function acceptPlayback(value) {
      if (!validPlayback(value)) throw new Error('Invalid playback facts');
      if (!snapshot || value.outputVersion < snapshot.outputVersion || value.stateVersion < snapshot.stateVersion) return;
      if (value.outputVersion > snapshot.outputVersion || value.stateVersion > snapshot.stateVersion) {
        if (!futurePlayback || value.playbackSequence > futurePlayback.playbackSequence) futurePlayback = freeze(value);
        return;
      }
      if (value.eventId !== (snapshot.state.occurrence?.eventId || '') || playback && value.playbackSequence <= playback.playbackSequence) return;
      playback = freeze(value); presentation(); publish();
    }
    function receive(data, version) {
      if (!live(version)) { if (!stopped && version === lifecycle) disconnect('Playback authentication changed.'); return; }
      try {
        if (typeof data !== 'string' || new TextEncoder().encode(data).length > MAX_MESSAGE) throw new Error('Invalid message');
        const value = JSON.parse(data);
        if (!identifier(value.sessionId)) throw new Error('Invalid session');
        if (initial) {
          if (value.type !== 'snapshot' || !identifier(value.connectionId) || value.deviceId !== clientDeviceId) throw new Error('Snapshot required');
          sessionId = value.sessionId; connectionId = value.connectionId; deviceId = value.deviceId; initial = false;
        } else if (value.sessionId !== sessionId || value.type === 'snapshot') throw new Error('Session replaced');
        if (value.type === 'snapshot' || value.type === 'state') {
          const next = value.snapshot;
          if (!next || Object.keys(next).length !== 10 || next.sessionId !== sessionId || !integer(next.stateVersion) || !integer(next.outputVersion) || !validPlayback(next.playback) ||
              !Array.isArray(next.devices) || next.devices.some((device) => !identifier(device.deviceId) || !validName(device.name) || typeof device.available !== 'boolean' || typeof device.canPlay !== 'boolean') ||
              new Set(next.devices.map((device) => device.deviceId)).size !== next.devices.length ||
              typeof next.selectedDeviceId !== 'string' || typeof next.selectedConnectionId !== 'string' ||
              next.transfer && (!['sourceConnectionId', 'targetConnectionId', 'sourceDeviceId', 'targetDeviceId'].every((key) => typeof next.transfer[key] === 'string')) ||
              !integer(next.drainUntilMs)) throw new Error('Invalid snapshot');
          player.validateState(next.state);
          if (next.outputVersion < outputVersion || snapshot && (next.stateVersion < snapshot.stateVersion || next.outputVersion < snapshot.outputVersion)) return;
          if (snapshot && next.stateVersion === snapshot.stateVersion) { acceptPlayback(next.playback); return; }
          if (next.outputVersion > outputVersion || execution && next.selectedConnectionId !== connectionId && next.transfer?.targetConnectionId !== connectionId) { suspend(); execution = null; }
          outputVersion = Math.max(outputVersion, next.outputVersion); snapshot = freeze(next);
          const ownName = next.devices.find((device) => device.deviceId === deviceId)?.name;
          if (ownName && sameAccount()) { displayName = ownName.trim(); preferences.rememberOwnName(account, ownName); }
          const priorPlayback = playback;
          playback = null;
          const candidates = [next.playback, priorPlayback, futurePlayback].filter((entry) => entry && entry.outputVersion === next.outputVersion && entry.stateVersion === next.stateVersion && entry.eventId === (next.state.occurrence?.eventId || ''));
          playback = candidates.sort((a, b) => b.playbackSequence - a.playbackSequence)[0] || freeze({ ...next.playback, hasReportedPlayback: false });
          futurePlayback = null;
          phase = 'ready'; window.clearTimeout(connectTimer); connectTimer = null;
          presentation(true); publish();
        } else if (value.type === 'playback') acceptPlayback(value.playback);
        else if (value.type === 'lease') acceptLease(value.lease);
        else if (value.type === 'instruction') void instruction(value.instruction, version).catch(() => { if (live(version)) disconnect('Playback execution failed.'); });
        else if (value.type === 'result') {
          const result = value.result;
          if (!result || !/^[1-9][0-9]*$/.test(result.id) || !['resolving', 'accepted', 'rejected'].includes(result.outcome) || typeof result.final !== 'boolean' ||
              !['unchanged', 'pending', 'confirmed', 'blocked', 'media', 'uncertain', 'superseded'].includes(result.execution)) throw new Error('Invalid result');
          const entry = pending.get(result.id);
          if (!entry) return;
          const label = result.outcome === 'rejected' ? 'Playback action rejected. Check the current output and try again explicitly.'
            : result.execution === 'blocked' ? 'Selection accepted; playback needs a tap on the output.'
              : result.execution === 'media' ? 'Selection accepted; the output could not play this file.'
                : result.execution === 'uncertain' ? 'Action accepted; playback could not be confirmed. Check before trying again.'
                  : '';
          if (result.final) { window.clearTimeout(entry.timer); pending.delete(result.id); entry.resolve(freeze(result)); }
          if (label) feedback(label);
          else publish();
        } else if (value.type === 'error') disconnect('Playback session unavailable. Reconnect explicitly.');
        else throw new Error('Invalid message');
      } catch (_) { disconnect('Playback session changed or sent invalid data. Reconnect explicitly.'); }
    }
    function issue(command) {
      if (phase !== 'ready' || !live(lifecycle) || !socket || socket.readyState !== 1) return Promise.resolve({ outcome: 'rejected', code: 'unavailable', execution: 'unchanged', final: true });
      if (pending.size >= MAX_PENDING || commandID >= Number.MAX_SAFE_INTEGER) {
        feedback('Too many pending playback actions. Wait for confirmation.');
        return Promise.resolve({ outcome: 'rejected', code: 'capacity', execution: 'unchanged', final: true });
      }
      // Discard future stages before a command can race native ended. An already
      // admitted execution waits for authoritative controls, even for Pause/Stop.
      // Rename changes neither future musical authority nor current execution.
      if (command.type !== 'rename') discardStage();
      const id = String(++commandID), version = lifecycle;
      const guarded = ['seek', 'jump', 'remove', 'move', 'clear'].includes(command.type) || command.type === 'queueIntent' && command.selection.kind === 'occurrence';
      return new Promise((resolve) => {
        const timer = window.setTimeout(() => {
          if (!live(version) || !pending.has(id)) return;
          pending.delete(id); resolve(freeze({ id, outcome: 'rejected', code: 'unavailable', execution: 'uncertain', final: true }));
          feedback('Playback action could not be confirmed. Check the output; it will not be replayed.');
        }, 15000);
        pending.set(id, { resolve, timer, command });
        if (!send({ type: 'command', id, command, ...(guarded ? { expectedStateVersion: snapshot.stateVersion } : {}) })) {
          window.clearTimeout(timer); pending.delete(id); resolve({ id, outcome: 'rejected', execution: 'uncertain', final: true });
        }
        publish();
      });
    }
    async function control(value) {
      const version = lifecycle;
      try {
        const command = commandOf(value, snapshot?.state || empty());
        if (!live(version)) return { outcome: 'rejected', code: 'unavailable', execution: 'unchanged', final: true };
        // Every UI action is exactly one server command. In particular Shuffle
        // Play is atomic; neither its initial track nor its shuffle is local.
        const result = await issue(command);
        if (!live(version)) return { outcome: 'rejected', code: 'unavailable', execution: 'uncertain', final: true };
        return result;
      } catch (_) { feedback('Playback action unavailable. Check the selection and try again explicitly.'); return { outcome: 'rejected', final: true }; }
    }
    function scheduleProgress(version) {
      progressTimer = window.setTimeout(() => {
        if (!live(version)) return;
        if (execution && observable() && clock() - (execution.lastReportAt ?? -Infinity) >= PROGRESS_INTERVAL) report();
        scheduleProgress(version);
      }, PROGRESS_INTERVAL);
    }
    function scheduleRenewal(version) {
      if (!auth?.fetch) return;
      renewalTimer = window.setTimeout(async () => {
        if (!live(version) || renewing) return;
        renewing = true;
        const abort = renewalAbort = new AbortController();
        const timer = renewalTimeout = window.setTimeout(() => { abort.abort(); if (version === lifecycle) disconnect('Playback session renewal timed out. Reconnect explicitly.'); }, 8000);
        try {
          const response = await auth.fetch('/api/auth/session', { method: 'GET', cache: 'no-store', signal: abort.signal });
          if (!live(version)) return;
          if (!response.ok) throw new Error('Authentication unavailable');
          const identity = await response.json();
          if (!live(version)) return;
          if (identity.accountId !== account) throw new Error('Authentication changed');
          scheduleRenewal(version);
        } catch (_) { if (live(version)) disconnect('Playback session renewal failed. Reconnect explicitly.'); }
        finally { window.clearTimeout(timer); if (version === lifecycle) { renewing = false; renewalAbort = null; renewalTimeout = null; } }
      }, renewalMs);
    }
    async function connect() {
      if (stopped || authEnded) return;
      disconnect(''); phase = 'connecting'; message = ''; publish();
      const version = lifecycle;
      let identityUnavailable = false;
      try {
        const ready = auth?.ready ? await auth.ready : true;
        if (stopped || authEnded || version !== lifecycle) return;
        account = auth?.accountId?.() || '';
        if (ready === false || auth && !account) throw new Error('Authentication unavailable');
        displayName = preferences.ownName(account) || defaultName(window.navigator);
        admission = new AbortController();
        connectTimer = window.setTimeout(() => { if (version === lifecycle) disconnect('Playback connection timed out. Reconnect explicitly.'); }, 10000);
        identityUnavailable = true;
        const id = deviceIdProvider === undefined ? suppliedDeviceId
          : suppliedDeviceId === undefined && typeof deviceIdProvider === 'function' ? await deviceIdProvider({ signal: admission.signal }) : undefined;
        if (!live(version)) return;
        if (!identifier(id)) throw new Error('Client/platform device identity unavailable');
        clientDeviceId = id; identityUnavailable = false;
        const options = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}', signal: admission.signal };
        const response = request ? await request(PATH, options) : await auth.fetch(PATH, options);
        if (!live(version)) return;
        let admitted = response;
        if (typeof response?.json === 'function') { if (!response.ok) throw await safeError(response); admitted = await response.json(); }
        if (!live(version)) return;
        if (typeof admitted?.ticket !== 'string' || !admitted.ticket || admitted.ticket.length > 8192 || !Socket) throw new Error('Admission unavailable');
        commandID = clientSequence = outputVersion = 0; sessionId = connectionId = deviceId = ''; snapshot = playback = futurePlayback = null;
        execution = prepared = null; initial = true;
        const url = new URL(SOCKET, window.location.origin); url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
        const ws = socket = new Socket(url.href, PROTOCOL);
        const opened = () => { if (!live(version) || ws !== socket) return; if (ws.protocol !== PROTOCOL) { disconnect('Playback protocol unavailable.'); return; }
          send({ type: 'hello', ticket: admitted.ticket, name: displayName, deviceId: clientDeviceId, canPlay }); };
        const received = (event) => { if (ws === socket) receive(event.data, version); };
        const closed = () => { if (ws === socket && version === lifecycle) disconnect(); };
        for (const [name, handler] of [['open', opened], ['message', received], ['close', closed], ['error', closed]]) ws.addEventListener(name, handler);
        removeSocket = () => { for (const [name, handler] of [['open', opened], ['message', received], ['close', closed], ['error', closed]]) ws.removeEventListener(name, handler); };
        scheduleProgress(version); scheduleRenewal(version);
      } catch (_) { if (!stopped && version === lifecycle) disconnect(identityUnavailable
        ? 'Playback device identity unavailable. This browser requires a client/platform device identity provider.'
        : 'Could not connect playback. Reconnect explicitly.'); }
    }
    const listen = (name, handler) => { window.addEventListener(name, handler); cleanups.push(() => window.removeEventListener(name, handler)); };
    for (const event of ['spofify:logoutstarted', 'spofify:authended']) listen(event, () => { authEnded = true; disconnect('Playback authentication ended.'); });
    listen('pagehide', () => api.stop());
    const checkLease = () => { if (execution && !observable()) { suspend(); execution = null; } else if (execution?.parked) report(); if (!sameAccount() && account) disconnect('Playback authentication changed.'); };
    listen('focus', checkLease);
    if (window.document) {
      window.document.addEventListener('visibilitychange', checkLease);
      cleanups.push(() => window.document.removeEventListener('visibilitychange', checkLease));
    }
    player.setCommandHandler(control);
    async function deviceAction(promise) {
      const result = await promise;
      if (result?.outcome !== 'accepted' || ['blocked', 'media', 'uncertain', 'superseded'].includes(result.execution)) throw new Error('Playback action could not be confirmed.');
      return result;
    }
    const api = { getSnapshot, subscribe(listener) { subscribers.add(listener); return () => subscribers.delete(listener); },
      transfer: (id) => deviceAction(control({ type: 'select', deviceId: id })),
      rename(name, id = deviceId) {
        const own = id === deviceId;
        // Only authoritative snapshots update our preference. A delayed command
        // result must not overwrite a newer name published by another controller.
        return deviceAction(control({ type: 'rename', name, ...(!own ? { deviceId: id } : {}) }));
      },
      retry: connect, resumeHere: () => ownOutput() ? deviceAction(control({ type: 'retry' })) : Promise.reject(new Error('This tab is not the selected playback connection.')),
      stop() { if (stopped) return; stopped = true; disconnect(''); player.setFactListener(null); player.setCommandHandler(() => {}); cleanups.splice(0).forEach((remove) => remove()); subscribers.clear(); } };
    void connect();
    return api;
  }
  window.SpofifyPlaybackSync = { create, allowsRoute, safeError, defaultName, browserDeviceId, createPreferences };
})();
