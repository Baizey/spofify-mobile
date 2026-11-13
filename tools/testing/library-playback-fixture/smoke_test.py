#!/usr/bin/env python3
"""Explicit real-binary fixture smoke. No credentials/raw frames are emitted.

Uses stdlib RFC6455 framing to prove direct authenticated upgrade and provider-free
canonical resolution; the application integration separately uses production Kotlin.
"""
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import selectors
import socket
import struct
import subprocess
import sys
import threading
import time

from broker_fixture import Client, SHA256, SOURCE_TREE


class Socket:
    def __init__(self, port, client):
        ticket = client.request('/api/me/player/connect', {})['ticket']
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=3)
        self.socket.settimeout(12)
        key = base64.b64encode(os.urandom(16)).decode()
        request = (f'GET /api/me/player/socket HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n'
                   f'Origin: {client.base}\r\nCookie: {client.cookie}\r\nUpgrade: websocket\r\n'
                   f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n'
                   'Sec-WebSocket-Protocol: spofify.library-playback.v2\r\n\r\n')
        self.socket.sendall(request.encode())
        self.input = self.socket.makefile('rb')
        assert self.input.readline(4096).startswith(b'HTTP/1.1 101 '), 'real WebSocket upgrade required'
        headers = {}
        for _ in range(32):
            line = self.input.readline(4096)
            if line == b'\r\n':
                break
            name, value = line.decode().split(':', 1)
            headers[name.lower()] = value.strip()
        else:
            raise AssertionError('bounded upgrade headers')
        accept = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        assert headers['sec-websocket-accept'] == accept
        assert headers['sec-websocket-protocol'] == 'spofify.library-playback.v2'
        self.send({'type': 'hello', 'ticket': ticket, 'deviceId': 'fixture-smoke', 'name': 'Synthetic smoke', 'canPlay': True})
        self.sequence = 0

    def exact(self, count):
        data = self.input.read(count)
        assert len(data) == count, 'socket closed'
        return data

    def send(self, value, opcode=1):
        raw = json.dumps(value).encode() if opcode == 1 else value
        assert len(raw) <= 65535
        mask = os.urandom(4)
        header = bytes([0x80 | opcode])
        header += bytes([0x80 | len(raw)]) if len(raw) < 126 else bytes([0x80 | 126]) + struct.pack('!H', len(raw))
        self.socket.sendall(header + mask + bytes(value ^ mask[i % 4] for i, value in enumerate(raw)))

    def read(self):
        first, length = self.exact(2)
        assert not length & 0x80 and first & 0x80
        count = length & 127
        if count == 126:
            count = struct.unpack('!H', self.exact(2))[0]
        elif count == 127:
            count = struct.unpack('!Q', self.exact(8))[0]
        assert count <= 2 << 20
        raw = self.exact(count)
        if first & 15 == 9:
            self.send(raw, 10)
            return self.read()
        assert first & 15 == 1, 'text application message required'
        return json.loads(raw)

    def until(self, predicate, deadline):
        while time.monotonic() < deadline:
            message = self.read()
            if message['type'] == 'instruction':
                instruction = message['instruction']
                if instruction['type'] not in ('stageNext', 'cancelNext'):
                    self.sequence += 1
                    self.send({'type': 'ack', 'instructionId': instruction['id'], 'outputVersion': instruction['outputVersion'],
                               'eventId': instruction['eventId'], 'clientSequence': self.sequence, 'status': 'ok'})
            if predicate(message):
                return message
        raise AssertionError('bounded socket observation expired')

    def close(self):
        self.input.close()
        self.socket.close()


def main():
    script = Path(__file__).with_name('broker_fixture.py').resolve()
    process = subprocess.Popen(['/usr/bin/python3', '-B', str(script), '--seconds', '120'], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=dict(os.environ))
    diagnostics = bytearray()
    def drain():
        while chunk := process.stderr.read(4096):
            diagnostics.extend(chunk)
            assert len(diagnostics) <= 1 << 20
    reader = threading.Thread(target=drain)
    reader.start()
    ws = None
    try:
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        try:
            assert selector.select(45), 'bounded fixture handshake timeout'
            raw = process.stdout.readline(16385)
            assert raw.endswith(b'\n') and len(raw) <= 16384
        finally:
            selector.close()
        handshake = json.loads(raw)
        assert handshake['brokerSha256'] == SHA256 and handshake['sourceTree'] == SOURCE_TREE
        port = int(handshake['baseUrl'].rsplit(':', 1)[1])
        account = handshake['accounts'][0]
        client = Client(port)
        client.login(account['username'], account['password'])
        # Admission trust negatives use the real HTTP adapter, not a proxy.
        for origin, csrf in ((client.base, ''), ('http://127.0.0.1:1', client.csrf)):
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=3)
            try:
                connection.request('POST', '/api/me/player/connect', '{}', {'Content-Type': 'application/json',
                                   'Cookie': client.cookie, 'Origin': origin, 'X-CSRF-Token': csrf})
                response = connection.getresponse()
                assert response.status == 403
                response.read(65536)
            finally:
                connection.close()
        ws = Socket(port, client)
        deadline = time.monotonic() + 30
        initial = ws.read()
        assert initial['type'] == 'snapshot'
        selected = ws.until(lambda m: m['type'] == 'state' and m['snapshot']['transfer'] is None and
                            m['snapshot']['selectedConnectionId'] == initial['connectionId'], deadline)
        assert not selected['snapshot']['state']['desiredPlaying']
        ws.send({'type': 'command', 'id': '1', 'command': {'type': 'queueIntent', 'intent': 'now',
                 'selection': {'kind': 'collection', 'context': {'kind': 'album', 'id': handshake['albumId'], 'name': 'forged'}}}})
        state = ws.until(lambda m: m['type'] == 'state' and len(m['snapshot']['state']['queue']) == 3, deadline)['snapshot']['state']
        assert [track['id'] for track in state['queue']] == [track['id'] for track in handshake['tracks']]
        assert [track['name'] for track in state['queue']] == [track['name'] for track in handshake['tracks']]
        assert state['context']['name'] == 'Canonical Library'
        result = ws.until(lambda m: m['type'] == 'result' and m['result']['final'], deadline)['result']
        assert result['outcome'] == 'accepted' and result['acceptedCount'] == 3
        print(json.dumps({'protocolAssertions': 'passed', 'upgrade': 'real-v2', 'canonicalTracks': 3,
                          'originCsrfNegatives': '403', 'audioEvidence': 'none'}), flush=True)
    finally:
        if ws:
            ws.close()
        if process.poll() is None:
            process.stdin.write(b'stop\n')
            process.stdin.flush()
        process.stdin.close()
        code = process.wait(timeout=20)
        reader.join(3)
        assert not reader.is_alive()
        receipts = [json.loads(line) for line in diagnostics.splitlines()]
        # Diagnostics are fixed classifications/allocation/cleanup metadata only.
        # Preserve them even on failed shutdown, without raw broker logs/auth.
        for item in receipts:
            print(json.dumps(item), flush=True)
        print(json.dumps({'fixtureSupervisor': 'stopped', 'pid': process.pid,
                          'returnCode': code, 'awaited': True}), flush=True)
        assert code == 0, 'supervisor failed; preserve private state'
        receipt = next(item for item in receipts if item.get('fixtureCleanup') == 'complete')
        assert receipt['descendants'] == 'ECHILD'
        assert not Path(receipt['removedScratch']).exists()
    print(json.dumps({'fixtureSmoke': 'passed', 'cleanup': 'ECHILD'}), flush=True)


if __name__ == '__main__':
    main()
