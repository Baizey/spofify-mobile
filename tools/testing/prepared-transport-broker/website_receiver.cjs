#!/usr/bin/env node
'use strict';
// TEST ONLY. Private JSONL stdin/stdout; never log the start message.
// Required env: SPOFIFY_TEST_PLAYWRIGHT_MODULE, SPOFIFY_TEST_CHROMIUM_PATH.
// Optional env: SPOFIFY_TEST_WEBSITE_RUNTIME_ROOT (default /dev/shm).
// start: {op:'start', fixture:<broker handshake>, seconds?:15..600}
// observe: {op:'observe'}; progress: {op:'progress', milliseconds?:200..2000}
// stop: {op:'stop'}. EOF also stops. Broker is independently owned and stays alive.
// No synthetic media/ACKs, autoplay bypass, downloads, or native/acoustic claim.
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const REVISION = 'b6eb54ffdc91e07e2ce7824b8be5f90afa8b05eb';

// Python is the dedicated subreaper, not the test/JVM host. Scratch removal is
// conditional on ORIGINAL Node/Chromium launcher shutdown and waitpid ECHILD.
const supervisor = String.raw`
import ctypes, json, os, pathlib, shutil, signal, stat, subprocess, sys, tempfile, time

def scratch_bytes(root, stat_entry=None):
    # Descriptor-relative, no-follow traversal never follows a disappearing or
    # replaced Chrome directory into a symlink/foreign tree. Only ENOENT for a
    # child is benign. Missing/replaced root and every other I/O error propagate.
    root = pathlib.Path(root)
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    root_fd = os.open(root, flags)
    identity = os.fstat(root_fd)
    def check_root():
        current = root.stat(follow_symlinks=False)
        if (current.st_dev, current.st_ino) != (identity.st_dev, identity.st_ino):
            raise RuntimeError('owned runtime root replaced')
    def walk(fd):
        total = 0
        with os.scandir(fd) as entries:
            for entry in entries:
                try:
                    info = stat_entry(entry) if stat_entry else entry.stat(follow_symlinks=False)
                except FileNotFoundError:
                    check_root()
                    continue
                if stat.S_ISREG(info.st_mode):
                    total += info.st_size
                elif stat.S_ISDIR(info.st_mode):
                    try:
                        child_fd = os.open(entry.name, flags, dir_fd=fd)
                    except FileNotFoundError:
                        check_root()
                        continue
                    try: total += walk(child_fd)
                    finally: os.close(child_fd)
        return total
    try:
        size = walk(root_fd)
        check_root()
        return size
    finally: os.close(root_fd)

def await_descendants(proc):
    # ALWAYS await the original launcher before reaping adopted descendants.
    if proc is not None:
        if proc.poll() is None:
            try: proc.terminate()
            except ProcessLookupError: pass
        try: proc.wait(timeout=12)
        except subprocess.TimeoutExpired:
            try: proc.kill()
            except ProcessLookupError: pass
            proc.wait(timeout=3)
    end = time.monotonic()+10
    while True:
        try: pid, status = os.waitpid(-1, os.WNOHANG)
        except ChildProcessError: return # ECHILD, including no-launch failures.
        if time.monotonic() > end:
            raise RuntimeError('descendants remain; preserving scratch')
        if pid == 0:
            # Only this dedicated subreaper's direct/adopted children. No process
            # name searches, shared groups, or sibling/foreign process cleanup.
            children = pathlib.Path('/proc/self/task/'+str(os.getpid())+'/children').read_text().split()
            for child in children:
                try: os.kill(int(child), signal.SIGKILL)
                except ProcessLookupError: pass
            time.sleep(.05)

def supervise(command=None, runtime=None, scan=None):
    # The optional arguments permit deterministic Python-only unit tests of this
    # EXACT embedded supervisor, without Node/Chrome, network or a second runner.
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
        raise RuntimeError('subreaper unavailable')
    runtime = pathlib.Path(runtime or os.environ.get('SPOFIFY_TEST_WEBSITE_RUNTIME_ROOT','/dev/shm')).resolve()
    root = pathlib.Path(tempfile.mkdtemp(prefix='spofify-transport-website-', dir=runtime))
    root_identity = None
    proc, primary, cleanup_error = None, None, None
    peak, code, stopping = 0, 1, False
    def stop(*args):
        nonlocal stopping
        stopping = True
        if proc is not None and proc.poll() is None:
            try: proc.send_signal(signal.SIGTERM)
            except ProcessLookupError: pass
    previous_handlers = {}
    try:
        for sig in (signal.SIGTERM, signal.SIGINT): previous_handlers[sig] = signal.signal(sig, stop)
        root_identity = root.stat(follow_symlinks=False)
        root.chmod(0o700)
        print(json.dumps({'receiverAllocated':str(root),'quotaHeadroom':'unknown'}),file=sys.stderr,flush=True)
        if shutil.disk_usage(root).free < 2 << 30: raise RuntimeError('scratch headroom unavailable')
        env = {'PATH':os.environ.get('PATH','/usr/bin:/bin'),'LANG':'C.UTF-8',
               'HOME':str(root),'TMPDIR':str(root),'WEBSITE_RECEIVER_ROOT':str(root),
               'SPOFIFY_TEST_PLAYWRIGHT_MODULE':os.environ.get('SPOFIFY_TEST_PLAYWRIGHT_MODULE',''),
               'SPOFIFY_TEST_CHROMIUM_PATH':os.environ.get('SPOFIFY_TEST_CHROMIUM_PATH','')}
        proc = subprocess.Popen(command or [sys.argv[1],sys.argv[2],'--owned-worker'],cwd=root,env=env,start_new_session=True)
        deadline = time.monotonic()+620
        while proc.poll() is None:
            size = (scan or scratch_bytes)(root)
            peak = max(peak,size)
            if stopping or time.monotonic()>deadline or size>2<<30:
                # Resource/deadline disposal remains a hard failure, even when
                # child signal handling would otherwise return zero.
                break
            time.sleep(.25)
        else:
            code = proc.returncode if not stopping else 1
    except BaseException as failure:
        primary = failure
    finally:
        try:
            await_descendants(proc)
            current = root.stat(follow_symlinks=False)
            if (root_identity is None or root.parent != runtime or not root.name.startswith('spofify-transport-website-') or
                (current.st_dev, current.st_ino) != (root_identity.st_dev, root_identity.st_ino) or not stat.S_ISDIR(current.st_mode)):
                raise RuntimeError('owned runtime root changed; preserving scratch')
            shutil.rmtree(root)
            if root.exists(): raise RuntimeError('owned runtime cleanup incomplete')
            print(json.dumps({'receiverCleanup':'complete','descendants':'ECHILD','observedPeakBytes':peak}),file=sys.stderr,flush=True)
        except BaseException as failure:
            cleanup_error = failure
            print(json.dumps({'receiverCleanup':'unverified','scratchPreserved':str(root)}),file=sys.stderr,flush=True)
        for sig, handler in previous_handlers.items(): signal.signal(sig, handler)
    if primary is not None:
        print(json.dumps({'receiverFailure':'supervisor','exceptionType':type(primary).__name__}),file=sys.stderr,flush=True)
        if cleanup_error is not None: primary.add_note('Descendant/scratch cleanup unverified; owned runtime preserved')
        raise primary
    if cleanup_error is not None: raise cleanup_error
    return code

if __name__ == '__main__':
    sys.exit(supervise())
`;
if (process.argv[2] !== '--owned-worker') {
  const child = spawnSync('/usr/bin/python3', ['-B', '-c', supervisor, process.execPath, __filename], { stdio: 'inherit' });
  process.exit(child.status ?? 1);
}

const readline = require('node:readline');
const { setTimeout: delay } = require('node:timers/promises');
let browser, page, timer, closing = false, started = false;
const root = process.env.WEBSITE_RECEIVER_ROOT;
const output = value => process.stdout.write(JSON.stringify(value) + '\n');
function requireCondition(value, code) { if (!value) throw Object.assign(new Error(code), { receiverCode: code }); }
async function close() {
  if (closing) return;
  closing = true;
  clearTimeout(timer);
  if (browser) await browser.close();
}
async function sample() {
  requireCondition(page && started, 'NOT_STARTED');
  return page.evaluate(() => {
    const p = window.__preparedTransportPlayer.getSnapshot();
    const s = window.__preparedTransportSync.getSnapshot();
    const a = document.querySelector('#player-audio');
    return { deviceId: s.deviceId, activeDeviceId: s.activeDeviceId,
      phase: s.phase, busy: s.busy, needsTap: s.needsTap,
      queue: p.queue.map(({ id, key, entryId, occurrenceId, targetId }) => ({ id, key, entryId, occurrenceId, targetId })),
      entryKey: p.entryKey, position: p.position, shuffle: p.shuffle, repeat: p.repeat,
      status: p.status, playing: p.playing,
      audio: { currentTime: a.currentTime, paused: a.paused, ended: a.ended,
        readyState: a.readyState, duration: Number.isFinite(a.duration) ? a.duration : null,
        error: a.error?.code || null, muted: a.muted, volume: a.volume },
      monotonicMs: performance.now() };
  });
}
async function progress(milliseconds = 600) {
  requireCondition(Number.isInteger(milliseconds) && milliseconds >= 200 && milliseconds <= 2000, 'INVALID_SAMPLE_INTERVAL');
  const before = await sample();
  await delay(milliseconds);
  const after = await sample();
  return { before, after, progressed: before.entryKey === after.entryKey &&
    !before.audio.paused && !after.audio.paused && !after.audio.error &&
    after.audio.readyState >= 2 && after.audio.currentTime > before.audio.currentTime + milliseconds / 2000 };
}
async function start(message) {
  requireCondition(!browser, 'ALREADY_STARTED');
  const f = message.fixture, seconds = message.seconds ?? 180;
  requireCondition(f?.brokerRevision === REVISION && f.brokerSha256 === '425e4bb46efcf1c25e195d92d4d1845ed2389cd35e6ebf9ad8981cd80c2c70f4', 'BROKER_PIN_MISMATCH');
  const base = new URL(f.baseUrl);
  requireCondition(base.protocol === 'http:' && base.hostname === '127.0.0.1' && base.port && base.pathname === '/' && !base.username && !base.password && !base.search && !base.hash, 'LOOPBACK_FIXTURE_REQUIRED');
  requireCondition(typeof f.username === 'string' && typeof f.password === 'string' && typeof f.albumId === 'string', 'INVALID_HANDSHAKE');
  requireCondition(Number.isInteger(seconds) && seconds >= 15 && seconds <= 600, 'INVALID_LIFETIME');
  const playwrightModule = process.env.SPOFIFY_TEST_PLAYWRIGHT_MODULE || '';
  const chromiumPath = process.env.SPOFIFY_TEST_CHROMIUM_PATH || '';
  requireCondition(path.isAbsolute(playwrightModule) && path.isAbsolute(chromiumPath), 'REQUIRED_ENV_SPOFIFY_TEST_PLAYWRIGHT_MODULE_AND_SPOFIFY_TEST_CHROMIUM_PATH');
  fs.accessSync(chromiumPath, fs.constants.X_OK);
  const { chromium } = require(playwrightModule);
  fs.writeFileSync(path.join(root, 'fonts.conf'), `<?xml version="1.0"?><fontconfig><dir>/usr/share/fonts</dir><cachedir>${root}/font-cache</cachedir></fontconfig>`);
  timer = setTimeout(() => { close().finally(() => process.exit(1)); }, seconds * 1000);
  browser = await chromium.launch({ headless: true, timeout: 30000, executablePath: chromiumPath,
    env: { PATH: process.env.PATH, LANG: 'C.UTF-8', HOME: root, TMPDIR: root,
      XDG_CONFIG_HOME: path.join(root, 'config'), XDG_CACHE_HOME: path.join(root, 'cache'),
      FONTCONFIG_FILE: path.join(root, 'fonts.conf'), GSETTINGS_BACKEND: 'memory' },
    args: ['--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1', '--no-pings', '--disable-domain-reliability'] });
  const context = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1440, height: 1000 } });
  await context.route('**/*', route => new URL(route.request().url()).origin === base.origin ? route.continue() : route.abort('blockedbyclient'));
  // Observe public create()/getSnapshot() only. No media methods, player state,
  // authority, ACKs, clocks or permission/autoplay behavior are replaced.
  await context.addInitScript(() => {
    for (const [name, captured] of [['SpofifyPlayer', '__preparedTransportPlayer'], ['SpofifyPlaybackSync', '__preparedTransportSync']]) {
      Object.defineProperty(window, name, { configurable: true, set(api) {
        const create = api.create;
        api.create = function (...args) { const instance = Reflect.apply(create, this, args); window[captured] = instance; return instance; };
        Object.defineProperty(window, name, { configurable: true, writable: true, value: api });
      } });
    }
  });
  page = await context.newPage();
  page.setDefaultTimeout(20000);
  await page.goto(base.origin + '/login', { waitUntil: 'domcontentloaded' });
  await page.locator('#login-username').fill(f.username);
  await page.locator('#login-password').fill(f.password);
  await page.locator('#login-submit').click();
  f.password = ''; // Never return credentials, cookies, origins or raw errors.
  await page.waitForFunction(() => !!window.__preparedTransportSync?.getSnapshot().deviceId);
  // Do not guess rounded lease/quarantine admission. Only one deliberate Play
  // occurs after the website reports settled registration and no quarantine.
  await page.waitForFunction(() => { const s = window.__preparedTransportSync.getSnapshot(); return !s.busy && !s.quarantineMs; });
  await page.evaluate(id => { location.hash = `#/library?album=${encodeURIComponent(id)}&scope=all`; }, f.albumId);
  await page.locator('#library-heading [data-player-context][data-player-shuffle="false"]').click();
  started = true;
  await page.waitForFunction(() => { const a = document.querySelector('#player-audio'); return a && !a.paused && a.readyState >= 2 && !a.error && a.currentTime > .2; });
  const measurement = await progress();
  requireCondition(measurement.progressed, 'INITIAL_PLAY_DID_NOT_PROGRESS');
  return { browserVersion: browser.version(), layer: 'headless-desktop-synthetic-media-not-native-or-acoustic', measurement };
}
process.on('SIGTERM', () => close().finally(() => process.exit(1)));
process.on('SIGINT', () => close().finally(() => process.exit(1)));
(async () => {
  let count = 0;
  try {
    const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
    for await (const line of lines) {
      requireCondition(++count <= 1000 && Buffer.byteLength(line) <= 32768, 'CONTROL_BOUND_EXCEEDED');
      const message = JSON.parse(line);
      if (message.op === 'stop') { await close(); output({ op: 'stop', ok: true }); break; }
      let result;
      if (message.op === 'start') result = await start(message);
      else if (message.op === 'observe') result = await sample();
      else if (message.op === 'progress') result = await progress(message.milliseconds);
      else throw Object.assign(new Error('INVALID_OPERATION'), { receiverCode: 'INVALID_OPERATION' });
      output({ op: message.op, ok: true, result });
    }
  } catch (error) {
    // Playwright error messages can contain URLs, form values and raw browser
    // output. Retain only stable classifications; fail closed and await cleanup.
    output({ ok: false, error: error.receiverCode || (error.name === 'TimeoutError' ? 'BROWSER_TIMEOUT' : 'BROWSER_OR_CONTROL_FAILURE') });
    process.exitCode = 1;
  } finally { process.stdin.destroy(); await close(); }
})().catch(() => { process.exitCode = 1; });
