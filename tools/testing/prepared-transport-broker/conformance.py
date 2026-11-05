#!/usr/bin/env python3
"""Bounded real-time protocol checks, not an audio/native or virtual-clock claim."""
import json
import threading
import time
from broker_fixture import Client, Fixture


def run(f):
    h = f.handshake
    a, b = Client(f.base), Client(f.base)
    sa = a.login(h['username'], h['password'])
    sb = b.login(h['username'], h['password'])
    assert sa['accountId'] == sb['accountId'] and a.cookie != b.cookie
    assert a.player()['queueIntents']['version'] == 1
    assert f.startup_view['activeDeviceId'] == ''
    assert 0 < f.startup_view['quarantineMs'] <= 8000
    va = a.player({'action': 'register', 'name': 'receiver A', 'queueIntentsVersion': 1})
    aid = va['deviceId']
    vb = b.player({'action': 'register', 'name': 'receiver B', 'queueIntentsVersion': 1})
    bid = vb['deviceId']
    # Same account, distinct session: knowing A's ID grants no A poll capability.
    try:
        b.player({'action': 'poll', 'deviceId': aid, 'epoch': vb['epoch']})
        raise AssertionError('cross-session poll accepted')
    except AssertionError as error:
        assert 'HTTP 404' in str(error)
    def poll(client, device, state=None, metadata=None, ack=None):
        view = client.player()
        body = {'action': 'poll', 'deviceId': device, 'epoch': view['epoch']}
        if state is not None:
            body.update(state=state, stateSeq=view['stateSeq'] + 1, queueIntentState=metadata)
        if ack:
            body['acks'] = [{'deliveryId': ack['deliveryId'], 'epoch': ack['epoch'], 'status': 'ok'}]
        return client.player(body)
    def select(client, device, target, command, sequence):
        return client.player({'action': 'transfer', 'deviceId': device, 'targetDeviceId': target,
                              'epoch': client.player()['epoch'], 'commandId': command, 'selectionSeq': sequence})
    state = dict(version=1, queue=[], position=-1, context=None, shuffle=False, repeat='off',
                 elapsed=0, duration=0, playing=False, waiting=False, error='', truncated=False,
                 notice='', occurrence=None)
    ma = dict(receiverId='receiver_A', revision=1, provenance='legacy-reset', origins=[], highKeys=[])
    mb = dict(ma, receiverId='receiver_B')
    select(a, aid, aid, 'first_selection', 1)
    end = time.monotonic() + 10
    while True:
        va = poll(a, aid)
        if va['directives']:
            break
        poll(b, bid)
        assert time.monotonic() < end
        time.sleep(.1)
    assert va['directives'][0]['type'] == 'prepare'
    va = poll(a, aid, ack=va['directives'][0])
    assert va['directives'][0]['type'] == 'activate'
    va = poll(a, aid, state, ma, va['directives'][0])
    assert va['activeDeviceId'] == aid and va['transfer'] is None
    assert any(o['commandId'] == 'first_selection' and o['status'] == 'ok' for o in va['commands'])
    assert 7500 < va['leaseMs'] <= 8000
    before_epoch = va['epoch']
    va = poll(a, aid, state, ma)
    assert va['queueIntents']['binding']['receiverId'] == 'receiver_A'
    binding = va['queueIntents']['binding']
    va = poll(a, aid, state, ma)
    assert va['epoch'] == before_epoch and va['queueIntents']['binding'] == binding
    select(a, aid, bid, 'handoff', 2)
    vb = poll(b, bid)
    assert vb['directives'][0]['type'] == 'prepare'
    poll(b, bid, ack=vb['directives'][0])
    va = poll(a, aid)
    assert va['directives'][0]['type'] == 'release'
    va = poll(a, aid, state, ma, va['directives'][0])
    vb = poll(b, bid)
    assert vb['directives'][0]['type'] == 'activate'
    vb = poll(b, bid, state, mb, vb['directives'][0])
    assert vb['activeDeviceId'] == bid and vb['transfer'] is None
    assert vb['queueIntents']['binding']['receiverId'] == 'receiver_B'
    # A delayed real accepted publication remains visible server-side before delivery.
    ctl = Client(f.base)
    ctl.request('/__fixture/arm', {'mode': 'hold'}, {'X-Fixture-Token': f.token})
    failures = []
    def publish():
        try:
            poll(b, bid, state, mb)
        except BaseException as error:
            failures.append(error)
    thread = threading.Thread(target=publish)
    thread.start()
    end = time.monotonic() + 2
    while True:
        witness = ctl.request('/__fixture/status', headers={'X-Fixture-Token': f.token})
        if witness and witness['accepted']:
            break
        assert time.monotonic() < end
        time.sleep(.02)
    assert thread.is_alive() and b.player()['stateSeq'] == witness['stateSeq']
    ctl.request('/__fixture/release', {}, {'X-Fixture-Token': f.token})
    thread.join(5)
    assert not thread.is_alive() and not failures
    ctl.request('/__fixture/arm', {'mode': 'drop'}, {'X-Fixture-Token': f.token})
    old_seq = b.player()['stateSeq']
    try:
        poll(b, bid, state, mb)
        raise AssertionError('drop unexpectedly delivered')
    except __import__('http.client', fromlist=['RemoteDisconnected']).RemoteDisconnected:
        pass
    assert b.player()['stateSeq'] == old_seq + 1
    # Availability is independent of retained registration. Keep B alive; stop A.
    quiet_since = time.monotonic()
    while time.monotonic() - quiet_since < 5.2:
        poll(b, bid)
        time.sleep(.25)
    view = b.player()
    assert not next(d for d in view['devices'] if d['deviceId'] == aid)['available']
    assert next(d for d in view['devices'] if d['deviceId'] == bid)['available']
    # Deliver a command but intentionally omit ACK; renew B's lease so the
    # 8-second command ACK deadline, not the lease deadline, causes fencing.
    command_epoch = view['epoch']
    view = b.player({'action': 'command', 'deviceId': bid, 'epoch': command_epoch,
                     'commandId': 'missing_ack', 'command': {'type': 'pause'}})
    command_since = time.monotonic()
    delivered = poll(b, bid)
    assert delivered['directives'][0]['type'] == 'command'
    while time.monotonic() - command_since < 8.2:
        view = poll(b, bid)
        time.sleep(.25)
    view = b.player()
    assert view['epoch'] > command_epoch and view['activeDeviceId'] == ''
    assert any(o['commandId'] == 'missing_ack' and o['status'] == 'timeout' for o in view['commands'])
    assert view['quarantineMs'] > 0
    # Stop B as well: no new selection, no autonomous owner or replay after expiry.
    time.sleep(8.2)
    view = b.player()
    assert view['activeDeviceId'] == '' and view['quarantineMs'] == 0
    # Complete the 30-second A inactivity window, while B remains registered.
    while time.monotonic() - quiet_since < 30.2:
        poll(b, bid)
        time.sleep(.25)
    assert aid not in [d['deviceId'] for d in b.player()['devices']]
    b.player({'action': 'leave', 'deviceId': bid})
    assert b.player()['devices'] == []
    print(json.dumps({'result': 'passed', 'brokerRevision': h['brokerRevision'],
                      'checks': ['independent authenticated sessions', 'extension probe',
                      'startup quarantine', 'register/session ownership', 'prepare/activate ACK',
                      'prepare/release/activate ACK', 'renew/binding stability',
                      'accepted hold/drop response', '5s availability', '8s ACK timeout/fencing',
                      'lease quarantine drain/no autoplay', '30s registration expiry', 'leave'],
                      'limits': 'protocol-only synthetic paused receiver; no player/audio/native claim'}))


if __name__ == '__main__':
    fixture = Fixture(100)
    try:
        fixture.start()
        run(fixture)
    finally:
        fixture.close()
