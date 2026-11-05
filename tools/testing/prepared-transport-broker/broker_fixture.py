#!/usr/bin/env python3
"""Offline, Linux-only real pinned broker fixture. No Go checkout writes/imports.
Stdout: one private JSON handshake; stdin: `stop` (or EOF) requests awaited cleanup.
"""
import argparse
from collections import OrderedDict, deque
import ctypes
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REVISION = 'b6eb54ffdc91e07e2ce7824b8be5f90afa8b05eb'
SHA256 = '425e4bb46efcf1c25e195d92d4d1845ed2389cd35e6ebf9ad8981cd80c2c70f4'
EXT = {'X-Spofify-Playback-Extensions': 'queue-intents-v1'}
MAX_BODY = 2 << 20


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class Client:
    """Separate authenticated synthetic session, no ambient proxy/cookie environment."""
    def __init__(self, base):
        assert base.startswith('http://127.0.0.1:')
        self.base, self.cookie, self.csrf = base, '', ''

    def request(self, path, body=None, headers=None, method=None):
        assert path.startswith('/') and not path.startswith('//')
        conn = http.client.HTTPConnection('127.0.0.1', int(self.base.rsplit(':', 1)[1]), timeout=4)
        hdr = {'Origin': self.base, **(headers or {})}
        if self.cookie:
            hdr['Cookie'] = self.cookie
        if self.csrf:
            hdr['X-CSRF-Token'] = self.csrf
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            hdr['Content-Type'] = 'application/json'
        try:
            conn.request(method or ('POST' if body is not None else 'GET'), path, body, hdr)
            response = conn.getresponse()
            data = response.read(8 << 20)
            assert len(data) < 8 << 20, 'response bound'
            for line in response.headers.get_all('Set-Cookie', []):
                if line.startswith('spofify_session='):
                    self.cookie = line.split(';', 1)[0]
            value = json.loads(data) if data else None
            if not 200 <= response.status < 300:
                raise AssertionError(f'{path}: HTTP {response.status}: {value}')
            return value
        finally:
            conn.close()

    def login(self, username, password):
        session = self.request('/api/auth/login', {'username': username, 'password': password}, {'X-Spofify-Login': '1'})
        self.csrf = session['csrfToken']
        assert self.request('/api/auth/session') == session
        return session

    def player(self, body=None):
        return self.request('/api/me/player', body, EXT)


class Server(ThreadingHTTPServer):
    daemon_threads = False
    block_on_close = True
    request_queue_size = 32

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()

    def handle_error(self, request, address):
        self.fixture.proxy_diagnostic('OTHER', 'other', 'handler', sys.exc_info()[1])

    def __init__(self, fixture):
        self.fixture = fixture
        self.slots = threading.BoundedSemaphore(32)
        super().__init__(('127.0.0.1', 0), Handler)


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'

    def log_message(self, *args):
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(4)

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        self.phase = 'request-body'
        try:
            self.forward_request()
        except Exception as error:
            # Only allowlisted exception type and fixed route/method classification.
            self.server.fixture.proxy_diagnostic(self.command, self.path, self.phase, error)

    def forward_request(self):
        f = self.server.fixture
        if self.headers.get('Transfer-Encoding'):
            self.send_error(400)
            return
        size = int(self.headers.get('Content-Length', '0'))
        if size < 0 or size > MAX_BODY:
            self.send_error(413)
            return
        body = self.rfile.read(size)
        if len(body) != size:
            raise EOFError('incomplete framed request')
        if self.path.startswith('/__fixture/'):
            # Synthetic control data only; never sent to Go or treated as authority.
            if self.headers.get('X-Fixture-Token') != f.token:
                self.send_error(403)
                return
            command = self.path.rsplit('/', 1)[-1]
            with f.lock:
                if command == 'arm' and self.command == 'POST':
                    options = json.loads(body)
                    assert options.get('mode') in ('hold', 'drop')
                    target = options.get('target', 'paused-poll')
                    assert target in ('paused-poll', 'claim', 'queue-start')
                    assert f.fault is None
                    f.fault = dict(mode=options['mode'], target=target, accepted=False, released=False,
                                   stateSeq=None, status=None)
                elif command == 'release' and self.command == 'POST':
                    if f.fault:
                        f.fault['released'] = True
                    f.release.set()
                elif command == 'arm-handoff' and self.command == 'POST':
                    assert json.loads(body) == {}, 'handoff arm requires an empty object'
                    assert not f.handoff_status['armed'], 'handoff choreography is one-shot'
                    f.handoff_status['armed'] = True
                elif command == 'release-handoff' and self.command == 'POST':
                    assert json.loads(body) == {}, 'handoff release requires an empty object'
                    assert f.handoff_status['freshPollHeld'], 'no fresh handoff poll is held'
                    f.handoff_continue.set()
                elif command in ('status', 'metrics', 'trace', 'handoff-status') and self.command == 'GET':
                    pass
                else:
                    self.send_error(400)
                    return
                value = (dict(f.handoff_status) if command in ('arm-handoff', 'handoff-status', 'release-handoff') else
                         ({'events': list(f.trace_events)} if command == 'trace' else
                          (dict(f.metrics) if command == 'metrics' else
                           (dict(f.fault or f.last_fault) if (f.fault or f.last_fault) else None))))
            data = json.dumps(value).encode()
            self.send_response(200)
            self.send_header('Connection', 'close')
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if not self.path.startswith('/') or self.path.startswith('//'):
            self.send_error(400)
            return
        headers = {k: v for k, v in self.headers.items()
                   if k.lower() not in ('connection', 'transfer-encoding', 'host')}
        headers['Host'] = f.base.removeprefix('http://')
        conn = http.client.HTTPConnection('127.0.0.1', f.upstream, timeout=4)
        try:
            self.phase = 'upstream-request'
            f.observe_request(self.command, self.path, body)
            handoff_role = f.handoff_before_request(self.command, self.path, body)
            conn.request(self.command, self.path, body, headers)
            self.phase = 'upstream-headers'
            response = conn.getresponse()
            self.phase = 'upstream-body'
            data = response.read(8 << 20)
            assert len(data) < 8 << 20
            f.observe_player_response(self.command, self.path, body, response.status, data)
            f.handoff_after_response(self.command, self.path, body, response.status, data, handoff_role)
            fault = None
            if self.path == '/api/me/player' and self.command == 'POST' and response.status == 200:
                request = json.loads(body)
                # Fault only a fully received real Go success, never fabricate an ACK.
                with f.lock:
                    target = (f.fault or {}).get('target')
                    matches = (target == 'claim' and request.get('action') == 'transfer' and
                               request.get('requireUnselected') is True or
                               target == 'paused-poll' and request.get('action') == 'poll' and
                               request.get('state', {}).get('playing') is False or
                               target == 'queue-start' and request.get('action') == 'poll' and
                               request.get('state', {}).get('playing') is True)
                    if matches and f.fault and not f.fault['accepted']:
                        fault = f.fault
                        accepted = json.loads(data)
                        fault.update(accepted=True, status=response.status, stateSeq=accepted['stateSeq'],
                                     requestEpoch=request.get('epoch'), responseEpoch=accepted.get('epoch'),
                                     requestPlaying=request.get('state', {}).get('playing'),
                                     requestAction=request['action'], requestHasCommand='command' in request,
                                     requestAckCount=len(request.get('acks', [])),
                                     responseDirectiveCount=len(accepted.get('directives', [])))
            if fault:
                if fault['mode'] == 'drop':
                    return  # Close actual accepted response, do not invent HTTP success.
                f.release.wait(min(12, max(0, f.deadline - time.monotonic())))
                if not f.release.is_set():
                    return  # Finite hold expires as a lost response.
            self.phase = 'downstream-response'
            self.send_response(response.status)
            self.send_header('Connection', 'close')
            for key, value in response.getheaders():
                if key.lower() not in ('connection', 'transfer-encoding', 'content-length'):
                    self.send_header(key, value)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            if self.path == '/api/me/player' and self.command == 'GET':
                f.proxy_diagnostic(self.command, self.path, 'complete', status=response.status)
        finally:
            conn.close()
            if 'fault' in locals() and fault:
                with f.lock:
                    # Retain the small acceptance witness until the next arm.
                    f.last_fault = dict(fault)
                    f.fault = None
                    f.release.clear()


class Fixture:
    def __init__(self, seconds=120):
        assert 15 <= seconds <= 600
        self.deadline = time.monotonic() + seconds
        self.stop = threading.Event()
        self.release = threading.Event()
        self.lock = threading.Lock()
        self.fault, self.last_fault = None, None
        self.token = os.urandom(24).hex()
        self.processes, self.threads = [], []
        self.root, self.server = None, None
        self.log = None
        self.peak_bytes = 0
        self.diagnostic_lock = threading.Lock()
        self.diagnostic_count = 0
        self.metrics = dict(registrations=0, claims=0, transfers=0,
                            collectionPlaybackReads=0, historyWrites=0, polls=0)
        self.trace_events = deque(maxlen=64)
        self.trace_delivery_types = OrderedDict()  # <=64 private ID->fixed-type entries, RAM only.
        self.handoff_status = dict(armed=False, capturedTransfer=False, sourceReleaseHeld=False,
                                  oldPollHeld=False, releaseAccepted=False, oldPollForwarded=False,
                                  oldPollStatus=None, oldPollStaleEpoch=False, epochAdvanced=False,
                                  freshPollHeld=False, freshPollForwarded=False)
        self.handoff_private = None  # Source/target IDs and old epoch: RAM only, never control output.
        self.handoff_phase = None
        self.handoff_old_poll = threading.Event()
        self.handoff_epoch_advanced = threading.Event()
        self.handoff_continue = threading.Event()
        self.handoff_failed = False

    def handoff_wait(self, event):
        # No mutex is held across a barrier. Cancellation/timeout never forwards
        # a held request or manufactures a response; normal cleanup still reaps.
        if not event.wait(min(2, max(0, self.deadline - time.monotonic()))) or self.stop.is_set():
            with self.lock:
                self.handoff_failed = True
            self.stop.set()
            self.handoff_old_poll.set()
            self.handoff_epoch_advanced.set()
            self.handoff_continue.set()
            raise TimeoutError('fixture handoff barrier timed out or was cancelled')

    def handoff_before_request(self, method, path, body):
        if method != 'POST' or path != '/api/me/player':
            return None
        try:
            request = json.loads(body)
        except (ValueError, UnicodeError):
            return None
        if not isinstance(request, dict):
            return None
        role = None
        with self.lock:
            private = self.handoff_private
            if not private or self.handoff_phase != 'releasing' or request.get('action') != 'poll':
                return None
            acks = request.get('acks', [])
            empty_target_poll = (request.get('deviceId') == private['target'] and 'state' not in request
                                 and 'stateSeq' not in request and 'queueIntentState' not in request and not acks)
            if self.handoff_status['epochAdvanced']:
                if (empty_target_poll and request.get('epoch') == private['oldEpoch'] + 1
                        and self.handoff_status['oldPollStatus'] == 409 and self.handoff_status['oldPollStaleEpoch']
                        and not self.handoff_status['freshPollHeld']):
                    self.handoff_status['freshPollHeld'] = True
                    role = 'fresh-poll'
                else:
                    return None
            elif request.get('epoch') != private['oldEpoch']:
                return None
            elif empty_target_poll and not self.handoff_status['oldPollHeld']:
                self.handoff_status['oldPollHeld'] = True
                self.handoff_old_poll.set()
                role = 'old-poll'
            elif (request.get('deviceId') == private['source'] and isinstance(acks, list)
                  and not self.handoff_status['sourceReleaseHeld']
                  and any(isinstance(ack, dict) and ack.get('status') == 'ok'
                          and isinstance(ack.get('deliveryId'), str)
                          and self.trace_delivery_types.get(ack['deliveryId']) == 'release' for ack in acks)):
                self.handoff_status['sourceReleaseHeld'] = True
                role = 'source-release'
        if role == 'source-release':
            self.handoff_wait(self.handoff_old_poll)
        elif role == 'old-poll':
            self.handoff_wait(self.handoff_epoch_advanced)
            with self.lock:
                if not self.handoff_status['epochAdvanced'] or self.handoff_failed:
                    raise TimeoutError('fixture handoff epoch was not established')
                self.handoff_status['oldPollForwarded'] = True
        elif role == 'fresh-poll':
            self.handoff_wait(self.handoff_continue)
            with self.lock:
                if self.handoff_failed:
                    raise TimeoutError('fixture handoff continuation was cancelled')
                self.handoff_status['freshPollForwarded'] = True
        return role

    def handoff_after_response(self, method, path, body, status, data, role=None):
        if method != 'POST' or path != '/api/me/player':
            return
        try:
            request, response = json.loads(body), json.loads(data)
        except (ValueError, UnicodeError):
            return
        if not isinstance(request, dict) or not isinstance(response, dict):
            return
        transfer = response.get('transfer')
        transfer = transfer if isinstance(transfer, dict) else {}
        with self.lock:
            if not self.handoff_status['armed']:
                return
            if not self.handoff_status['capturedTransfer']:
                source, target = transfer.get('sourceDeviceId'), transfer.get('targetDeviceId')
                command = transfer.get('commandId')
                epoch = response.get('epoch')
                if (request.get('action') == 'transfer' and status == 200
                        and isinstance(source, str) and source and len(source) <= 128
                        and isinstance(target, str) and target == request.get('deviceId') and 0 < len(target) <= 128
                        and isinstance(command, str) and command == request.get('commandId') and 0 < len(command) <= 128
                        and type(epoch) is int and 0 < epoch <= 9007199254740991):
                    self.handoff_private = dict(source=source, target=target, command=command, oldEpoch=epoch)
                    self.handoff_status['capturedTransfer'] = True
                return
            private = self.handoff_private
            matching = (transfer.get('commandId') == private['command']
                        and transfer.get('sourceDeviceId') == private['source']
                        and transfer.get('targetDeviceId') == private['target'])
            # A source release-directive response may beat the target's prepare-ACK
            # response through the proxy. Either REAL matching releasing view proves
            # Go accepted prepare; gate the subsequent source ACK in both orderings.
            if (status == 200 and matching and transfer.get('phase') == 'releasing'
                    and response.get('epoch') == private['oldEpoch']):
                self.handoff_phase = 'releasing'
            if (role == 'source-release' and status == 200 and matching
                    and response.get('epoch') == private['oldEpoch'] + 1
                    and transfer.get('phase') == 'activating'
                    and response.get('activeDeviceId') == private['target']):
                self.handoff_status['releaseAccepted'] = True
                self.handoff_status['epochAdvanced'] = True
                self.handoff_epoch_advanced.set()
            if role == 'old-poll':
                self.handoff_status['oldPollStatus'] = int(status)
                self.handoff_status['oldPollStaleEpoch'] = response.get('error') == 'stale epoch'

    def observe_player_response(self, method, path, body, status, data):
        # Observation of real upstream bytes only, before any forwarding/hold/drop.
        # No protocol material is retained except allowlisted scalar facts below.
        if method != 'POST' or path != '/api/me/player':
            return
        try:
            request, response = json.loads(body), json.loads(data)
        except (ValueError, UnicodeError):
            return
        if not isinstance(request, dict) or not isinstance(response, dict):
            return
        action = request.get('action')
        if action not in ('transfer', 'poll'):
            return
        directives = response.get('directives')
        directives = directives if isinstance(directives, list) else []
        acks = request.get('acks')
        acks = acks if isinstance(acks, list) else []
        if action == 'poll' and status == 200 and not directives and not acks:
            return  # Keep conflict/reconciliation facts, not ordinary successful heartbeats.

        def integer(value):
            return value if type(value) is int and 0 < value <= 9007199254740991 else None

        def object_value(value):
            return value if isinstance(value, dict) else {}

        def playing(value):
            value = object_value(value).get('playing')
            return value if type(value) is bool else None

        def metadata(value):
            return {'present': isinstance(value, dict),
                    'revision': integer(object_value(value).get('revision'))}

        transfer = object_value(response.get('transfer'))
        queue = object_value(response.get('queueIntents'))
        phase = transfer.get('phase')
        error = object_value(response.get('state')).get('error')
        issuer, target = request.get('deviceId'), transfer.get('targetDeviceId')
        event = {'action': action, 'statusHTTP': int(status),
                 'staleEpoch': response.get('error') == 'stale epoch',
                 'requestEpoch': integer(request.get('epoch')),
                 'epoch': integer(response.get('epoch')),
                 'transferPhase': phase if phase in ('waiting', 'preparing', 'releasing', 'activating') else None,
                 'sourcePresent': isinstance(transfer.get('sourceDeviceId'), str) and bool(transfer.get('sourceDeviceId')),
                 'targetSelf': isinstance(issuer, str) and isinstance(target, str) and issuer == target,
                 'requestPlaying': playing(request.get('state')),
                 'responsePlaying': playing(response.get('state')),
                 'responseError': error if error in ('', 'blocked', 'media', 'queue') else None,
                 'requestMetadata': metadata(request.get('queueIntentState')),
                 'responseMetadata': metadata(queue.get('state')),
                 'acks': [], 'directives': []}
        types = ('prepare', 'release', 'activate', 'command')
        sidecars = queue.get('directives')
        sidecars = sidecars[:8] if isinstance(sidecars, list) else []
        with self.lock:
            for directive in directives[:8]:
                if not isinstance(directive, dict) or directive.get('type') not in types:
                    continue
                delivery = directive.get('deliveryId')
                if isinstance(delivery, str) and 0 < len(delivery) <= 128:
                    self.trace_delivery_types[delivery] = directive['type']
                    self.trace_delivery_types.move_to_end(delivery)
                    while len(self.trace_delivery_types) > 64:
                        self.trace_delivery_types.popitem(last=False)
                sidecar = next((item.get('state') for item in sidecars
                                if isinstance(item, dict) and item.get('deliveryId') == delivery), None)
                event['directives'].append({'type': directive['type'],
                    'playing': playing(directive.get('state')),
                    'epoch': integer(directive.get('epoch')), 'metadata': metadata(sidecar)})
            for ack in acks[:8]:
                if not isinstance(ack, dict):
                    continue
                ack_status = ack.get('status')
                delivery = ack.get('deliveryId')
                event['acks'].append({
                    'status': ack_status if ack_status in ('ok', 'blocked', 'media', 'rejected') else None,
                    'matchedType': self.trace_delivery_types.get(delivery) if isinstance(delivery, str) else None})
            self.trace_events.append(event)

    def observe_request(self, method, path, body):
        # Count attempts at forwarding, not accepted execution. Never retain the
        # request/body/path or infer authoritative success from these counters.
        route = path.split('?', 1)[0]
        keys = []
        if method == 'POST' and route == '/api/me/player':
            try:
                request = json.loads(body)
                if isinstance(request, dict):
                    action = request.get('action')
                    if action == 'register':
                        keys.append('registrations')
                    elif action == 'poll':
                        keys.append('polls')
                    elif action == 'transfer':
                        keys.append('transfers')
                        if request.get('requireUnselected') is True:
                            keys.append('claims')
            except (ValueError, UnicodeError):
                pass  # Go remains the only validator/admission authority.
        if method == 'POST' and route == '/api/me/history':
            keys.append('historyWrites')
        parts = route.split('/')
        collection = (len(parts) == 6 and parts[-1] == 'playback' and
                      (parts[1:4] in (['api', 'library', 'albums'], ['api', 'library', 'artists'],
                                      ['api', 'me', 'playlists'])))
        if method in ('GET', 'POST') and (collection or route == '/api/me/likes/playback'):
            keys.append('collectionPlaybackReads')
        with self.lock:
            for key in keys:
                self.metrics[key] += 1

    def proxy_diagnostic(self, method, path, phase, error=None, status=None):
        # Total stderr diagnostics <= 64 small JSON lines; no arbitrary exception
        # strings, URLs/queries, headers, body, IDs, credentials or control tokens.
        with self.diagnostic_lock:
            if self.diagnostic_count >= 64:
                return
            self.diagnostic_count += 1
            route = {'/api/me/player': 'player', '/api/auth/login': 'auth-login',
                     '/api/auth/session': 'auth-session'}.get(path, 'other')
            if path.startswith('/__fixture/'):
                route = 'fixture-control'
            allowed = ('TimeoutError', 'ConnectionRefusedError', 'ConnectionResetError',
                       'BrokenPipeError', 'RemoteDisconnected', 'IncompleteRead',
                       'BadStatusLine', 'LineTooLong', 'EOFError', 'ValueError',
                       'UnicodeDecodeError', 'JSONDecodeError', 'AssertionError', 'OSError')
            name = type(error).__name__ if error is not None else None
            value = {'proxyDiagnostic': self.diagnostic_count,
                     'method': method if method in ('GET', 'POST') else 'OTHER',
                     'endpoint': route, 'phase': phase,
                     'exceptionType': name if name in allowed else ('Other' if error else None)}
            if status is not None:
                value['status'] = int(status)
            print(json.dumps(value), file=sys.stderr, flush=True)

    def launch(self, args, **kwargs):
        if self.stop.is_set() or time.monotonic() >= self.deadline:
            raise TimeoutError('fixture internal deadline reached')
        p = subprocess.Popen(args, start_new_session=True, **kwargs)
        self.processes.append(p)
        return p

    def start(self):
        configured = os.environ.get('SPOFIFY_TEST_GO_BROKER_BINARY', '').strip()
        if not configured:
            raise RuntimeError('SPOFIFY_TEST_GO_BROKER_BINARY is required for selected real-broker integration')
        try:
            source = Path(configured).expanduser().resolve(strict=True)
            if not source.is_file() or digest(source) != SHA256:
                raise ValueError('invalid pin')
        except (OSError, ValueError):
            raise RuntimeError('SPOFIFY_TEST_GO_BROKER_BINARY must name the required SHA256-pinned executable') from None
        # Validate required provisioning before any mkdir/copy/allocation or process side effect.
        # This runner must be dedicated, not embedded in a JVM/Python test host.
        if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
            raise OSError('PR_SET_CHILD_SUBREAPER failed')
        default_root = Path(__file__).resolve().parents[3] / '.tooling' / 'prepared-transport-broker'
        retained = Path(os.environ.get('SPOFIFY_TEST_FIXTURE_ROOT') or default_root).expanduser().absolute()
        if retained.is_symlink():
            raise AssertionError('owned fixture root must not be symlink')
        retained.mkdir(parents=True, exist_ok=True, mode=0o700)
        retained = retained.resolve()
        usage = shutil.disk_usage(retained)
        assert usage.free > 2 << 30, 'need 2 GiB apparent space; quota may still be unknown'
        binary = retained / 'spofify-b6eb54f'
        if not binary.exists():
            staging = retained / ('binary-' + self.token)
            try:
                shutil.copyfile(source, staging)
                assert digest(staging) == SHA256
                staging.chmod(0o700)
                # Do not replace any peer-owned file.
                os.link(staging, binary)
            except FileExistsError:
                pass
            finally:
                staging.unlink(missing_ok=True)
        assert not binary.is_symlink() and digest(binary) == SHA256
        configured_runtime = os.environ.get('SPOFIFY_TEST_GO_BROKER_RUNTIME_ROOT', '').strip()
        if configured_runtime:
            self.runtime_parent = Path(configured_runtime).expanduser().resolve()
        else:
            self.runtime_parent = Path(tempfile.gettempdir()).resolve()
            shared_memory = Path('/dev/shm')
            try:
                if (shared_memory.is_dir() and os.access(shared_memory, os.W_OK | os.X_OK)
                        and shutil.disk_usage(shared_memory).free > 2 << 30):
                    self.runtime_parent = shared_memory.resolve()
            except OSError:
                pass  # Ordinary Linux temporary filesystem is the documented fallback.
        if not self.runtime_parent.is_dir():
            raise RuntimeError('SPOFIFY_TEST_GO_BROKER_RUNTIME_ROOT must name an existing writable runtime directory')
        assert shutil.disk_usage(self.runtime_parent).free > 2 << 30, 'need 2 GiB runtime scratch headroom'
        self.root = Path(tempfile.mkdtemp(prefix='spofify-transport-broker-', dir=self.runtime_parent))
        print(json.dumps({'fixtureAllocated': str(self.root)}), file=sys.stderr, flush=True)
        self.root.chmod(0o700)
        for name in ('home', 'data', 'library', 'tmp'):
            (self.root / name).mkdir(mode=0o700)
        self.upstream = port()
        self.server = Server(self)
        self.base = f'http://127.0.0.1:{self.server.server_port}'
        thread = threading.Thread(target=self.server.serve_forever, name='broker-proxy')
        thread.start()
        self.threads.append(thread)
        self.log = (self.root / 'broker.log').open('wb')
        self.password = 'Synthetic-Transport-Only-' + os.urandom(12).hex()
        env = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'HOME': str(self.root / 'home'),
               'TMPDIR': str(self.root / 'tmp'), 'GOTMPDIR': str(self.root / 'tmp'),
               'HTTP_PROXY': 'http://127.0.0.1:1', 'HTTPS_PROXY': 'http://127.0.0.1:1',
               'NO_PROXY': '127.0.0.1,localhost', 'SPOFIFY_LISTEN_ADDR': f'127.0.0.1:{self.upstream}',
               'SPOFIFY_BASE_URL': self.base, 'SPOFIFY_DATA_DIR': str(self.root / 'data'),
               'SPOFIFY_LIBRARY_DIR': str(self.root / 'library'), 'SPOFIFY_ADMIN_PASSWORD': self.password}
        self.app = self.launch([str(binary)], cwd=self.root, env=env, stdout=self.log, stderr=self.log)
        watcher = threading.Thread(target=self.watch, name='fixture-deadline')
        watcher.start()
        self.threads.append(watcher)
        admin = Client(self.base)
        ready = time.monotonic() + 15
        while True:
            try:
                admin.login('admin', self.password)
                break
            except (OSError, http.client.HTTPException):
                if self.app.poll() is not None or time.monotonic() >= ready:
                    self.log.flush()
                    tail = (self.root / 'broker.log').read_bytes()[-4096:].decode(errors='replace')
                    raise RuntimeError('broker startup failed: ' + tail)
                time.sleep(.1)
        self.admin = admin
        self.startup_view = admin.player()
        status = admin.request('/api/status?jobsLimit=100')
        assert not status['jobs']
        assert not any(value for key, value in status.get('integrations', {}).items()
                       if key.endswith(('Configured', 'Connected'))), 'no provider settings allowed'
        self.seed(admin, env)
        return self

    def watch(self):
        while not self.stop.wait(.25):
            total = logs = 0
            for directory, dirs, files in os.walk(self.root, followlinks=False):
                for name in files:
                    path = Path(directory) / name
                    try:
                        size = path.lstat().st_size
                    except FileNotFoundError:
                        continue  # Import workers may atomically rename owned staging files.
                    total += size
                    if '.log' in name:
                        logs += size
            self.peak_bytes = max(self.peak_bytes, total)
            if time.monotonic() >= self.deadline or total > 2 << 30 or logs > 16 << 20:
                self.stop.set()
                self.release.set()
                # Enforce deadline during setup/blocked requests, not only CLI event loop.
                for p in self.processes:
                    if p.poll() is None:
                        try:
                            os.killpg(p.pid, signal.SIGTERM)
                        except ProcessLookupError:
                            pass
                return

    def seed(self, admin, env):
        media = self.root / 'tone.mp3'
        p = self.launch(['/usr/bin/ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i',
                         'sine=frequency=440:duration=120', '-c:a', 'libmp3lame', str(media)],
                        cwd=self.root, env=env, stdout=self.log, stderr=self.log)
        try:
            assert p.wait(timeout=15) == 0, 'synthetic FFmpeg failed'
        except subprocess.TimeoutExpired:
            os.killpg(p.pid, signal.SIGKILL)
            p.wait(timeout=3)
            raise
        artist = admin.request('/api/sources/manual/artists', {'name': 'Synthetic Transport'})
        album = admin.request('/api/sources/manual/albums', {'artistId': artist['id'], 'name': 'Transport fixtures'})
        tracks = []
        for index, name in enumerate(('A', 'H1', 'C1', 'C2', 'B1', 'B2')):
            boundary = 'fixture-' + os.urandom(12).hex()
            body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="tone.mp3"\r\nContent-Type: audio/mpeg\r\n\r\n'.encode()
                    + media.read_bytes() + f'\r\n--{boundary}--\r\n'.encode())
            upload = admin.request('/api/sources/manual/upload', body, {'Content-Type': f'multipart/form-data; boundary={boundary}'})
            saved = admin.request('/api/sources/manual', {'uploadToken': upload['uploadToken'], 'name': name,
                'artistId': artist['id'], 'albumId': album['id'], 'artists': [artist['name']],
                'durationMs': 120000, 'trackNumber': index + 1, 'discNumber': 1})
            tracks.append({'id': saved['source']['track']['id'], 'name': name})
        until = min(self.deadline, time.monotonic() + 30)
        while True:
            jobs = admin.request('/api/status?jobsLimit=100')['jobs']
            assert not any(j['status'] == 'failed' for j in jobs), 'synthetic import failed'
            if len(jobs) == 6 and all(j['status'] == 'completed' for j in jobs):
                break
            assert time.monotonic() < until, 'catalog publication timeout'
            time.sleep(.1)
        created = admin.request('/api/users', {'username': 'transport-listener'})
        credential = admin.request('/api/users/' + created['user']['id'] + '/password', {})
        self.handshake = {'version': 1, 'baseUrl': self.base, 'username': 'transport-listener',
                          'password': credential['password'], 'brokerRevision': REVISION,
                          'brokerSha256': SHA256, 'tracks': tracks, 'albumId': album['id'],
                          'artistId': artist['id'], 'controlToken': self.token,
                          'deadlineRemainingMs': int((self.deadline - time.monotonic()) * 1000)}
        listener = Client(self.base)
        listener.login(self.handshake['username'], self.handshake['password'])
        assert listener.request('/api/library?kind=tracks&scope=all&limit=100&offset=0')['total'] == 6

    def close(self):
        self.stop.set()
        self.release.set()
        self.handoff_old_poll.set()
        self.handoff_epoch_advanced.set()
        self.handoff_continue.set()
        if self.server:
            self.server.shutdown()
            self.server.server_close()  # Await every non-daemon request handler.
        for p in self.processes:
            if p.poll() is None:
                try:
                    os.killpg(p.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
        for p in self.processes:
            try:
                p.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(p.pid, signal.SIGKILL)
                p.wait(timeout=3)
        # Reap adopted descendants (including FFmpeg subprocesses spawned by Go).
        # Never remove scratch before ECHILD proves no owned descendants remain.
        end = time.monotonic() + 5
        while True:
            try:
                pid, _ = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                break
            if pid == 0:
                if time.monotonic() >= end:
                    raise RuntimeError('descendants remain: preserving owned scratch')
                for p in self.processes:
                    try:
                        os.killpg(p.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                time.sleep(.05)
        for thread in self.threads:
            thread.join(timeout=2)
            assert not thread.is_alive(), 'fixture thread remains; preserving scratch'
        if self.log:
            self.log.close()
        if self.root:
            assert self.root.parent == self.runtime_parent and self.root.name.startswith('spofify-transport-broker-') and not self.root.is_symlink()
            shutil.rmtree(self.root)
            assert not self.root.exists()
            print(json.dumps({'fixtureCleanup': 'complete', 'descendants': 'ECHILD',
                              'removedScratch': str(self.root), 'observedPeakBytes': self.peak_bytes}),
                  file=sys.stderr, flush=True)
        if self.handoff_failed:
            raise TimeoutError('fixture handoff barrier failed or timed out; owned processes were reaped')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds', type=int, default=120)
    args = parser.parse_args()
    fixture = Fixture(args.seconds)
    def stop(*_):
        fixture.stop.set()
        fixture.release.set()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        fixture.start()
        print(json.dumps(fixture.handshake), flush=True)
        # Daemon reads no files; EOF is intentional owner-death disposal.
        def input_loop():
            for line in sys.stdin:
                if line.strip() == 'stop':
                    break
            stop()
        threading.Thread(target=input_loop, daemon=True).start()
        fixture.stop.wait(max(0, fixture.deadline - time.monotonic()))
    finally:
        fixture.close()


if __name__ == '__main__':
    main()
