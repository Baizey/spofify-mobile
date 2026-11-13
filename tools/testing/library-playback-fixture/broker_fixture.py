#!/usr/bin/env python3
"""Private, provider-free current-library broker. stdout is ONE secret handshake.

Dedicated Linux subreaper, not importable as a shared test-host process. No relay,
checkout execution, download, legacy protocol, or pin override. Keep stdin open;
write 'stop\n' (or close stdin), then await exit AND the ECHILD cleanup receipt.
"""
import argparse
from contextlib import closing, contextmanager
import ctypes
import hashlib
import http.client
import json
import os
from pathlib import Path
import select
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import uuid

SHA256 = '492a8dd2d834a3e48d10246b0459d12e9bba18322b6c3aa99693760e123a0de4'
BINARY_VCS = '92f3e12a1a1f7b02fdd6586786646cf8c14dbca0'
SOURCE_REVISION = 'c23a9db747b20b492697f141369ad9388e729a53'
SOURCE_TREE = '842a060f65396e3fd1ad5d5c314874ecfaa10ee7'
RUNTIME_BYTES = 2 << 30
LOG_BYTES = 16 << 20


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


@contextmanager
def owned_database(path):
    # sqlite3.Connection.__exit__ commits/rolls back but does NOT close. Always
    # close the native connection (including WAL/mmap handles), even on failure,
    # before its caller may remove the private database/runtime directory.
    with closing(sqlite3.connect(path, timeout=5)) as connection, connection:
        yield connection


class Client:
    def __init__(self, port):
        self.port = port
        self.base = f'http://127.0.0.1:{port}'
        self.cookie = ''
        self.csrf = ''

    def request(self, path, body=None, login=False):
        assert path.startswith('/') and not path.startswith('//')
        headers = {'Origin': self.base}
        if self.cookie:
            headers['Cookie'] = self.cookie
        if self.csrf:
            headers['X-CSRF-Token'] = self.csrf
        if login:
            headers['X-Spofify-Login'] = '1'
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            headers['Content-Type'] = 'application/json'
        connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=3)
        try:
            connection.request('GET' if data is None else 'POST', path, data, headers)
            response = connection.getresponse()
            raw = response.read(2 << 20)
            assert response.status in (200, 201, 204), f'synthetic setup HTTP {response.status}'
            assert len(raw) < 2 << 20
            for name, value in response.getheaders():
                if name.lower() == 'set-cookie':
                    self.cookie = value.split(';', 1)[0]
            return json.loads(raw) if raw else None
        finally:
            connection.close()

    def login(self, name, password):
        session = self.request('/api/auth/login', {'username': name, 'password': password}, login=True)
        self.csrf = session['csrfToken']
        assert self.request('/api/auth/session')['accountId'] == session['accountId']


class Fixture:
    def __init__(self, seconds):
        assert 30 <= seconds <= 120, 'lifetime must be 30..120 seconds'
        self.deadline = time.monotonic() + seconds
        self.stop = threading.Event()
        self.children = []
        self.roles = {}
        self.root = None
        self.log = None
        self.monitor = None
        self.failure = False
        self.peak = 0

    def launch(self, args, role='helper'):
        assert not self.stop.is_set() and time.monotonic() < self.deadline
        child = subprocess.Popen(args, cwd=self.root, env=self.env, stdin=subprocess.DEVNULL,
                                 stdout=self.log, stderr=self.log, start_new_session=True)
        self.children.append(child)
        self.roles[child.pid] = role
        return child

    def start(self):
        configured = os.environ.get('SPOFIFY_TEST_GO_BROKER_BINARY', '')
        assert configured, 'SPOFIFY_TEST_GO_BROKER_BINARY is required (current library pin)'
        binary = Path(configured)
        assert binary.is_absolute() and binary.is_file() and os.access(binary, os.X_OK)
        assert not binary.is_symlink() and digest(binary) == SHA256, 'current library binary SHA256 mismatch'
        parent_value = os.environ.get('SPOFIFY_TEST_LIBRARY_PLAYBACK_RUNTIME_ROOT', '')
        assert parent_value, 'SPOFIFY_TEST_LIBRARY_PLAYBACK_RUNTIME_ROOT is required'
        parent = Path(parent_value)
        assert parent.is_absolute() and parent.resolve() == parent and parent.is_dir()
        assert parent.stat().st_uid == os.getuid() and not parent.stat().st_mode & 0o077, 'private owned runtime parent required'
        assert not any(parent == p or p in parent.parents for p in (Path('/tmp'), Path('/dev/shm')))
        assert shutil.disk_usage(parent).free >= RUNTIME_BYTES + (128 << 20), 'runtime/evidence headroom unavailable; quota unknown'
        stat = os.statvfs(parent)
        assert not stat.f_files or stat.f_favail >= 1024, 'runtime inode headroom unavailable'
        memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
        assert int(memory['MemAvailable'].split()[0]) >= 512 * 1024, 'need 512MiB available memory'
        assert ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) == 0, 'subreaper required'
        self.parent = parent
        self.root = Path(tempfile.mkdtemp(prefix='spofify-library-', dir=parent))
        self.root.chmod(0o700)
        self.token = uuid.uuid4().hex
        (self.root / '.owner').write_text(self.token)
        print(json.dumps({'fixtureAllocated': str(self.root), 'quota': 'unknown',
                          'inodeVisibility': 'available' if stat.f_files else 'unknown'}), file=sys.stderr, flush=True)
        for name in ('home', 'data', 'library', 'tmp', 'xdg-config', 'xdg-cache', 'xdg-data', 'xdg-state', 'go'):
            (self.root / name).mkdir(mode=0o700)
        # Verify actual SQLite WAL/mmap before server launch. No alternate root.
        probe = self.root / 'sqlite-probe.db'
        with owned_database(probe) as db:
            assert db.execute('PRAGMA journal_mode=WAL').fetchone()[0] == 'wal'
            assert db.execute('PRAGMA mmap_size=1048576').fetchone()[0] >= 1048576
            db.execute('CREATE TABLE probe(value TEXT)')
            db.execute("INSERT INTO probe VALUES ('synthetic')")
            assert db.execute('SELECT value FROM probe').fetchone()[0] == 'synthetic'
        probe.unlink()
        with socket.socket() as bound:
            bound.bind(('127.0.0.1', 0))
            port = bound.getsockname()[1]
        self.env = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8',
                    'HTTP_PROXY': 'http://127.0.0.1:1', 'HTTPS_PROXY': 'http://127.0.0.1:1',
                    'ALL_PROXY': 'http://127.0.0.1:1', 'NO_PROXY': '127.0.0.1,localhost',
                    'HOME': str(self.root / 'home'), 'GOMEMLIMIT': '256MiB', 'GOMAXPROCS': '2',
                    'SPOFIFY_LISTEN_ADDR': f'127.0.0.1:{port}',
                    'SPOFIFY_BASE_URL': f'http://127.0.0.1:{port}',
                    'SPOFIFY_DATA_DIR': str(self.root / 'data'), 'SPOFIFY_LIBRARY_DIR': str(self.root / 'library')}
        for key in ('TMPDIR', 'TMP', 'TEMP', 'GOTMPDIR'):
            self.env[key] = str(self.root / 'tmp')
        for key, directory in (('XDG_CONFIG_HOME', 'xdg-config'), ('XDG_CACHE_HOME', 'xdg-cache'),
                               ('XDG_DATA_HOME', 'xdg-data'), ('XDG_STATE_HOME', 'xdg-state'),
                               ('GOPATH', 'go'), ('GOCACHE', 'go'), ('GOMODCACHE', 'go')):
            self.env[key] = str(self.root / directory)
        password = 'Synthetic-Library-' + uuid.uuid4().hex
        self.env['SPOFIFY_ADMIN_PASSWORD'] = password
        self.log = (self.root / 'broker.log').open('wb')
        assert digest(binary) == SHA256, 'pin changed before launch'
        app = self.launch([str(binary)], role='broker')
        self.monitor = threading.Thread(target=self.watch, name='library-fixture-budget')
        self.monitor.start()
        admin = Client(port)
        until = min(self.deadline, time.monotonic() + 15)
        while True:
            try:
                admin.login('admin', password)
                break
            except (OSError, http.client.HTTPException):
                assert app.poll() is None and time.monotonic() < until and not self.stop.is_set(), 'broker startup unavailable'
                time.sleep(.1)
        status = admin.request('/api/status?jobsLimit=100')
        assert not status['jobs']
        assert not any(v for k, v in status.get('integrations', {}).items() if k.endswith(('Configured', 'Connected')))
        manifest = self.seed(admin)
        accounts = []
        for name in ('library-listener', 'library-other'):
            user = admin.request('/api/users', {'username': name})['user']
            credential = admin.request('/api/users/' + user['id'] + '/password', {})
            listener = Client(port)
            listener.login(name, credential['password'])
            accounts.append({'username': name, 'password': credential['password']})
        assert not admin.request('/api/status?jobsLimit=100')['jobs'], 'seeding must not enqueue provider/import work'
        return {'version': 2, 'baseUrl': admin.base, 'accounts': accounts, **manifest,
                'brokerSha256': SHA256, 'binaryVcsRevision': BINARY_VCS, 'binaryVcsModified': False,
                'sourceRevision': SOURCE_REVISION, 'sourceTree': SOURCE_TREE,
                'deadlineRemainingMs': int((self.deadline - time.monotonic()) * 1000)}

    def seed(self, admin):
        media = self.root / 'synthetic.mp3'
        child = self.launch(['/usr/bin/ffmpeg', '-nostdin', '-v', 'error', '-threads', '1', '-f', 'lavfi',
                             '-i', 'sine=frequency=440:duration=24', '-filter_threads', '1', '-c:a', 'libmp3lame', '-threads', '1', str(media)], role='synthetic-ffmpeg')
        assert child.wait(timeout=15) == 0, 'synthetic encoder failed'
        artist = admin.request('/api/sources/manual/artists', {'name': 'Synthetic Library'})
        album = admin.request('/api/sources/manual/albums', {'artistId': artist['id'], 'name': 'Canonical Library'})
        tracks = []
        # Disposable DB seed follows server synthetic-library-seed.cjs, not acquisition APIs.
        with owned_database(self.root / 'data' / 'spofify.db') as db:
            db.execute('PRAGMA foreign_keys=ON')
            for index, name in enumerate(('Canonical A', 'Canonical B', 'Canonical C')):
                identifier = 'manual-' + uuid.uuid4().hex
                directory = self.root / 'library' / artist['id'] / album['id']
                directory.mkdir(parents=True, exist_ok=True, mode=0o700)
                published = directory / (identifier + '.mp3')
                shutil.copyfile(media, published)
                credits = [{'id': artist.get('catalogArtistId', ''), 'name': artist['name']}]
                now = '2026-01-01T00:00:00Z'
                db.execute('INSERT INTO tracks(id,name,artists,album_id,album_name,album_artists,release_date,duration_ms,track_number,disc_number,library_path,metadata_source,source_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                           (identifier, name, json.dumps(credits), '', album['name'], json.dumps(credits), '', 24000, index + 1, 1, str(published), 'manual', identifier))
                db.execute('INSERT INTO manual_sources(track_id,created_at,artist_id,album_id) VALUES(?,?,?,?)', (identifier, now, artist['id'], album['id']))
                db.execute('INSERT INTO manual_track_artist_name_keys(track_id,name_key) VALUES(?,?)', (identifier, artist['name'].lower()))
                if artist.get('catalogArtistId'):
                    db.execute('INSERT INTO track_artists(track_id,artist_id,position) VALUES(?,?,0)', (identifier, artist['catalogArtistId']))
                tracks.append({'id': identifier, 'name': name})
        return {'tracks': tracks, 'albumId': album['id'], 'artistId': artist['id']}

    def watch(self):
        while not self.stop.wait(.1):
            total = logs = 0
            for directory, _, files in os.walk(self.root, followlinks=False):
                for name in files:
                    try:
                        size = (Path(directory) / name).lstat().st_size
                    except FileNotFoundError:
                        continue
                    total += size
                    if name.endswith('.log'):
                        logs += size
            self.peak = max(total, self.peak)
            if total > RUNTIME_BYTES or logs > LOG_BYTES or time.monotonic() >= self.deadline:
                self.failure = True
                self.stop.set()
                self.terminate()

    def terminate(self):
        for child in self.children:
            if child.poll() is None:
                try:
                    os.killpg(child.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass

    def close(self):
        self.stop.set()
        if self.monitor:
            self.monitor.join(2)
            assert not self.monitor.is_alive(), 'monitor remains; preserve scratch'
        self.terminate()
        for child in self.children:
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait(timeout=3)
        # Dedicated subreaper: these are our adopted descendants only. ECHILD,
        # not launcher exit or a PID lookup, is the scratch-deletion prerequisite.
        end = time.monotonic() + 5
        while True:
            try:
                pid, _ = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                break
            if pid == 0:
                assert time.monotonic() < end, 'descendants remain; preserve scratch'
                children = Path(f'/proc/self/task/{os.getpid()}/children').read_text().split()
                for value in children:
                    try:
                        os.kill(int(value), signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                time.sleep(.05)
        if self.log:
            self.log.close()
        if self.root:
            assert self.root.parent == self.parent and self.root.resolve() == self.root, 'runtime ownership path mismatch'
            assert (self.root / '.owner').read_text() == self.token, 'runtime owner token mismatch'
            stopped = {'fixtureStopped': 'complete', 'descendants': 'ECHILD', 'runtime': str(self.root),
                       'originalLauncherReturnCodes': [child.returncode for child in self.children],
                       'originalLaunchers': [{'role': self.roles[child.pid], 'pid': child.pid,
                                             'returnCode': child.returncode, 'awaited': True}
                                            for child in self.children]}
            assert all(code is not None for code in stopped['originalLauncherReturnCodes'])
            print(json.dumps(stopped), file=sys.stderr, flush=True)
            # Preserve ownership and stop proof if any CONTENT removal fails. Do not
            # rmtree the parent/marker first and lose recovery accounting on EBUSY.
            proof = self.root / '.stopped.json'
            with proof.open('x') as stream:
                json.dump(stopped, stream)
            for child in self.root.iterdir():
                if child.name in ('.owner', '.stopped.json'):
                    continue
                try:
                    if child.is_dir() and not child.is_symlink():
                        shutil.rmtree(child)
                    else:
                        child.unlink()
                except OSError as error:
                    category = child.name if child.name in ('home', 'data', 'library', 'tmp') else 'other-owned-content'
                    print(json.dumps({'fixtureRemovalFailed': 'content', 'category': category, 'errno': error.errno}),
                          file=sys.stderr, flush=True)
                    raise
            proof.unlink()
            (self.root / '.owner').unlink()
            self.root.rmdir()
            assert not self.root.exists(), 'runtime removal not observed'
            print(json.dumps({'fixtureCleanup': 'complete', 'descendants': 'ECHILD',
                              'removedScratch': str(self.root), 'observedPeakBytes': self.peak}), file=sys.stderr, flush=True)
        assert not self.failure, 'fixture deadline or budget exceeded'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds', type=int, default=120)
    fixture = Fixture(parser.parse_args().seconds)
    signal.signal(signal.SIGTERM, lambda *_: fixture.stop.set())
    signal.signal(signal.SIGINT, lambda *_: fixture.stop.set())
    try:
        handshake = json.dumps(fixture.start())
        assert len(handshake.encode()) <= 16384
        print(handshake, flush=True)
        while not fixture.stop.is_set():
            if select.select([sys.stdin], [], [], .1)[0]:
                command = sys.stdin.buffer.readline(65)
                assert command in (b'', b'stop\n'), 'invalid fixture control'
                break
    finally:
        fixture.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never print exception messages/tracebacks that could include auth bodies.
        diagnostic = {'fixtureFailure': type(error).__name__}
        # Only our fixed assertion messages, never arbitrary backend/OS exceptions.
        if isinstance(error, AssertionError):
            diagnostic['assertion'] = str(error)[:256]
        if isinstance(error, OSError):
            diagnostic['errno'] = error.errno
        print(json.dumps(diagnostic), file=sys.stderr, flush=True)
        sys.exit(1)
