// Extracted unchanged from spofify-server38d524e player.js; see README.
export function createAudioAdapter({ audio, onFacts = () => {} } = {}) {
    let disposed = false, generation = 0, active = false, desired = false, permit = () => false;
    let loaded = '', waiting = false, loading = false, failure = '', ended = false;
    let cancelWork = null, removeEvents = () => {};
    const facts = () => ({ elapsed: Number.isFinite(audio.currentTime) ? Math.max(0, audio.currentTime) : 0,
      duration: Number.isFinite(audio.duration) ? Math.max(0, audio.duration) : 0,
      isPlaying: !audio.paused && !audio.ended && !audio.error, isBuffering: waiting, isLoading: loading,
      error: audio.error ? 'media' : failure, ended: ended && audio.ended === true });
    const emit = (event = '') => { if (!disposed && active && permit()) onFacts(facts(), event); };
    function cancel() {
      generation++; cancelWork?.(); cancelWork = null; removeEvents(); removeEvents = () => {};
      active = desired = false; waiting = loading = false; audio.pause();
    }
    function suspend({ unload = false } = {}) {
      cancel(); permit = () => false;
      if (unload) { loaded = ''; audio.removeAttribute('src'); audio.load(); }
    }
    function bounded(promise, version, milliseconds = 4000) {
      return new Promise((resolve) => {
        let done = false;
        const finish = (value) => { if (done) return; done = true; window.clearTimeout(timer); if (cancelWork === abort) cancelWork = null; resolve(value); };
        const abort = () => finish('rejected');
        const timer = window.setTimeout(() => finish('media'), milliseconds);
        cancelWork = abort;
        Promise.resolve(promise).then(() => {
          if (version !== generation || disposed) { if (!active || !desired || !permit()) audio.pause(); finish('rejected'); }
          else finish('ok');
        }, (error) => finish(version !== generation || disposed ? 'rejected' : error?.name === 'NotAllowedError' ? 'blocked' : 'media'));
      });
    }
    async function load(track, version) {
      if (!track) { suspend({ unload: true }); return 'ok'; }
      const identity = `${track.key}:${track.id}`;
      if (loaded === identity && audio.readyState >= 1 && !audio.error) return 'ok';
      let detach = () => {};
      const metadata = new Promise((resolve, reject) => {
        const ready = () => resolve(), failed = () => reject(new Error('Media unavailable'));
        audio.addEventListener('loadedmetadata', ready); audio.addEventListener('error', failed);
        detach = () => { audio.removeEventListener('loadedmetadata', ready); audio.removeEventListener('error', failed); };
        loaded = identity; audio.preload = 'auto'; audio.src = `/api/library/tracks/${encodeURIComponent(track.id)}/audio`; audio.load();
        if (audio.readyState >= 1 && !audio.error) ready();
      });
      loading = true;
      const result = await bounded(metadata, version);
      detach(); if (version === generation) loading = false;
      return result;
    }
    async function prepare(instruction) {
      if (disposed) return 'rejected';
      cancel(); failure = ''; ended = false;
      // No occurrence on an unselected receiver is ACK-only, not an implicit load/play.
      if (!instruction.track) return 'ok';
      const version = generation;
      const result = await load(instruction.track, version);
      if (version !== generation || disposed) return 'rejected';
      audio.pause();
      if (result === 'ok') { try { audio.currentTime = Math.min(audio.duration || Infinity, instruction.elapsed); } catch (_) { return 'media'; } }
      return result;
    }
    function attach(version) {
      const listeners = {};
      let observedPlaying = false;
      for (const name of ['play', 'playing', 'pause', 'waiting', 'stalled', 'canplay', 'error', 'seeking', 'seeked', 'ended', 'timeupdate', 'durationchange']) {
        const handler = () => {
          if (disposed || version !== generation || !active) return;
          if (!permit()) { suspend(); return; }
          // Keep the local expiry check even when background timers lag;
          // ordinary progress is sampled for the socket by the controller.
          if (name === 'timeupdate') return;
          if (name === 'ended' && (!audio.ended || ended || !desired || !observedPlaying)) return;
          if (name === 'error' && !audio.error) return;
          if (name === 'playing' && (audio.paused || audio.ended)) return;
          if (name === 'playing') observedPlaying = true;
          if (name === 'waiting' || name === 'stalled') waiting = !audio.paused;
          if (['playing', 'pause', 'canplay', 'seeked', 'ended', 'error'].includes(name)) waiting = false;
          if (name === 'error') failure = 'media';
          if (name === 'ended') ended = true;
          emit(name);
        };
        audio.addEventListener(name, handler); listeners[name] = handler;
      }
      removeEvents = () => Object.entries(listeners).forEach(([name, handler]) => audio.removeEventListener(name, handler));
    }
    async function execute(instruction, authorized) {
      if (disposed) return 'rejected';
      cancel(); permit = authorized; failure = ''; ended = false;
      const version = generation;
      if (!permit()) return 'rejected';
      if (!instruction.track) { suspend({ unload: true }); return 'ok'; }
      const result = await load(instruction.track, version);
      if (version !== generation || disposed || !permit()) { if (version === generation) suspend(); return 'rejected'; }
      if (result !== 'ok') { failure = result; active = true; emit(); return result; }
      try { audio.currentTime = Math.max(0, Math.min(Number.isFinite(audio.duration) ? audio.duration : Infinity, instruction.elapsed)); }
      catch (_) { failure = 'media'; active = true; emit(); return 'media'; }
      desired = instruction.desiredPlaying; active = true; attach(version);
      if (!instruction.desiredPlaying) { audio.pause(); emit(); return 'ok'; }
      let play;
      try { play = audio.play(); } catch (error) { play = Promise.reject(error); }
      const outcome = await bounded(play, version);
      if (version !== generation || disposed || !permit()) { if (version === generation) suspend(); return 'rejected'; }
      // A resolved play promise is not a playing fact. Read the element itself.
      if (outcome !== 'ok') {
        failure = outcome; audio.pause(); emit();
        // A timed-out native promise can still settle later. Retire its event
        // generation and permission, so that late play cannot escape the pause.
        cancel(); permit = () => false;
        return outcome;
      }
      emit(); return outcome;
    }
    const guard = () => { if (!active || !desired || !permit()) audio.pause(); };
    audio.addEventListener('play', guard); audio.addEventListener('playing', guard);
    return { prepare, execute, facts, suspend, stop() {
      if (disposed) return;
      suspend({ unload: true }); disposed = true;
      audio.removeEventListener('play', guard); audio.removeEventListener('playing', guard);
    } };
  }
