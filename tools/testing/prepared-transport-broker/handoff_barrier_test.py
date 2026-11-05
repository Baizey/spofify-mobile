#!/usr/bin/env python3
"""Synthetic choreography unit checks ONLY: NOT real Go protocol acceptance.

Imports Fixture but never calls start()/close(), allocates scratch, opens sockets,
launches processes, or contacts a broker. Synthetic responses exercise the proxy's
barriers, not production authority, playback, ACK conformance or real HTTP 409s.
"""
import io
import json
import threading
import time
import unittest
from types import SimpleNamespace

from broker_fixture import Fixture, Handler


class HandoffBarrierTest(unittest.TestCase):
    SOURCE = 'private_source_marker'
    TARGET = 'private_target_marker'
    COMMAND = 'private_command_marker'
    PREPARE = 'private_prepare_marker'
    RELEASE = 'private_release_marker'
    OLD = 2
    SAFE_FIELDS = {
        'armed', 'capturedTransfer', 'sourceReleaseHeld', 'oldPollHeld',
        'releaseAccepted', 'oldPollForwarded', 'oldPollStatus', 'oldPollStaleEpoch',
        'epochAdvanced', 'freshPollHeld', 'freshPollForwarded',
    }

    def setUp(self):
        self.fixtures = []
        self.workers = []

    def tearDown(self):
        # Release only this test's in-memory workers; do not invoke process cleanup.
        for fixture in self.fixtures:
            fixture.stop.set()
            fixture.handoff_old_poll.set()
            fixture.handoff_epoch_advanced.set()
            fixture.handoff_continue.set()
        for worker in self.workers:
            worker.thread.join(2.5)
            self.assertFalse(worker.thread.is_alive(), 'owned unit worker did not stop')
        for fixture in self.fixtures:
            self.assertIsNone(fixture.root)
            self.assertIsNone(fixture.server)
            self.assertIsNone(fixture.log)
            self.assertEqual([], fixture.processes)
            self.assertEqual([], fixture.threads)

    @staticmethod
    def wire(value):
        # Deliberate formatting lets checks assert byte-for-byte input preservation.
        return json.dumps(value, indent=2).encode('utf-8')

    def control(self, fixture, action, method='GET'):
        """Exercise actual token control dispatch entirely through BytesIO, no socket."""
        body = self.wire({}) if method == 'POST' else b''
        original = bytes(body)
        statuses = []
        handler = Handler.__new__(Handler)
        handler.server = SimpleNamespace(fixture=fixture)
        handler.command = method
        handler.path = '/__fixture/' + action
        handler.headers = {'Content-Length': str(len(body)), 'X-Fixture-Token': fixture.token}
        handler.rfile = io.BytesIO(body)
        handler.wfile = io.BytesIO()
        handler.send_response = statuses.append
        handler.send_header = lambda *_: None
        handler.end_headers = lambda: None
        handler.send_error = lambda *_: self.fail('in-memory fixture control refused')
        handler.forward_request()
        self.assertEqual([200], statuses)
        self.assertEqual(original, body)
        return json.loads(handler.wfile.getvalue())

    def fixture(self):
        fixture = Fixture(15)
        self.fixtures.append(fixture)
        self.control(fixture, 'arm-handoff', 'POST')
        return fixture

    def poll(self, device=None, epoch=None, **fields):
        return self.wire(dict(action='poll', deviceId=device or self.TARGET,
                              epoch=self.OLD if epoch is None else epoch, **fields))

    def transfer_view(self, phase='preparing', epoch=None, **changes):
        transfer = dict(commandId=self.COMMAND, sourceDeviceId=self.SOURCE,
                        targetDeviceId=self.TARGET, phase=phase)
        transfer.update(changes.pop('transfer_changes', {}))
        result = dict(epoch=self.OLD if epoch is None else epoch, transfer=transfer,
                      directives=[], state={'playing': True, 'error': ''})
        result.update(changes)
        return result

    def after(self, fixture, request, response, status=200, role=None):
        data = self.wire(response)
        original_request, original_response = bytes(request), bytes(data)
        # Same observation order as actual proxy; maps types from synthetic directives.
        fixture.observe_player_response('POST', '/api/me/player', request, status, data)
        fixture.handoff_after_response('POST', '/api/me/player', request, status, data, role)
        self.assertEqual(original_request, request)
        self.assertEqual(original_response, data)

    def capture(self, fixture):
        request = self.wire(dict(action='transfer', deviceId=self.TARGET,
                                 targetDeviceId=self.TARGET, commandId=self.COMMAND, epoch=self.OLD))
        self.after(fixture, request, self.transfer_view())
        self.assertTrue(fixture.handoff_status['capturedTransfer'])
        return request

    def ready(self):
        fixture = self.fixture()
        self.capture(fixture)
        self.after(fixture, self.poll(), self.transfer_view(directives=[
            dict(type='prepare', deliveryId=self.PREPARE, epoch=self.OLD)]))
        # Deliberately observe source release directive BEFORE target prepare-ACK
        # response. That matching releasing view alone must establish the phase.
        self.after(fixture, self.poll(self.SOURCE), self.transfer_view('releasing', directives=[
            dict(type='release', deliveryId=self.RELEASE, epoch=self.OLD)]))
        self.assertEqual('releasing', fixture.handoff_phase)
        self.assertFalse(fixture.handoff_status['sourceReleaseHeld'])
        return fixture

    def source_request(self):
        return self.poll(self.SOURCE, state={'playing': True}, stateSeq=7,
                         acks=[dict(deliveryId=self.RELEASE, epoch=self.OLD, status='ok')])

    def before(self, fixture, body):
        original = bytes(body)
        try:
            return fixture.handoff_before_request('POST', '/api/me/player', body)
        finally:
            self.assertEqual(original, body)

    def worker(self, fixture, body):
        worker = SimpleNamespace(done=threading.Event(), result=None, error=None)
        def run():
            try:
                worker.result = self.before(fixture, body)
            except BaseException as error:
                worker.error = error
            finally:
                worker.done.set()
        worker.thread = threading.Thread(target=run, name='handoff-unit-worker')
        self.workers.append(worker)
        worker.thread.start()
        return worker

    def wait_for(self, predicate):
        end = time.monotonic() + .75
        tick = threading.Event()
        while not predicate():
            self.assertLess(time.monotonic(), end, 'bounded unit barrier observation expired')
            tick.wait(.002)

    def finish_worker(self, worker, role):
        self.assertTrue(worker.done.wait(.75), 'bounded unit worker completion expired')
        worker.thread.join(.25)
        self.assertFalse(worker.thread.is_alive())
        if worker.error:
            raise worker.error
        self.assertEqual(role, worker.result)

    def advance(self, fixture, source):
        self.after(fixture, source, self.transfer_view('activating', epoch=self.OLD + 1,
                   activeDeviceId=self.TARGET), role='source-release')

    def complete_old_poll(self, order='source-first'):
        fixture = self.ready()
        source, target = self.source_request(), self.poll(acks=[])
        if order == 'source-first':
            source_worker = self.worker(fixture, source)
            self.wait_for(lambda: fixture.handoff_status['sourceReleaseHeld'])
            self.assertFalse(source_worker.done.is_set())
            target_worker = self.worker(fixture, target)
        else:
            target_worker = self.worker(fixture, target)
            self.assertTrue(fixture.handoff_old_poll.wait(.75))
            self.assertFalse(target_worker.done.is_set())
            source_worker = self.worker(fixture, source)
        self.finish_worker(source_worker, 'source-release')
        self.assertTrue(fixture.handoff_status['oldPollHeld'])
        self.assertFalse(target_worker.done.is_set())
        self.assertFalse(fixture.handoff_status['oldPollForwarded'])
        # Target prepare-ACK response can arrive late without losing the source view.
        self.after(fixture, self.poll(acks=[dict(deliveryId=self.PREPARE, epoch=self.OLD, status='ok')]),
                   self.transfer_view('releasing'))
        self.advance(fixture, source)
        self.finish_worker(target_worker, 'old-poll')
        self.assertTrue(fixture.handoff_status['releaseAccepted'])
        self.assertTrue(fixture.handoff_status['epochAdvanced'])
        # SYNTHETIC unit input, not an assertion of actual Go protocol acceptance.
        self.after(fixture, target, {'epoch': self.OLD + 1, 'error': 'stale epoch', 'directives': []},
                   status=409, role='old-poll')
        self.assertEqual(409, fixture.handoff_status['oldPollStatus'])
        self.assertTrue(fixture.handoff_status['oldPollStaleEpoch'])
        return fixture

    def test_both_arrival_orders_and_source_directive_before_prepare_ack(self):
        for order in ('source-first', 'target-first'):
            with self.subTest(order=order):
                self.complete_old_poll(order)

    def test_capture_and_phase_require_exact_private_command_source_target_epoch(self):
        fixture = self.fixture()
        request = self.wire(dict(action='transfer', deviceId=self.TARGET,
                                 commandId=self.COMMAND, epoch=self.OLD))
        self.after(fixture, request, self.transfer_view(transfer_changes={'commandId': 'other_command'}))
        self.assertFalse(fixture.handoff_status['capturedTransfer'])
        self.after(fixture, request, self.transfer_view(), status=409)
        self.assertFalse(fixture.handoff_status['capturedTransfer'])
        self.capture(fixture)
        original_private = dict(fixture.handoff_private)
        for change in ({'commandId': 'other_command'}, {'sourceDeviceId': 'other_source'},
                       {'targetDeviceId': 'other_target'}):
            self.after(fixture, self.poll(self.SOURCE), self.transfer_view('releasing', transfer_changes=change))
            self.assertIsNone(fixture.handoff_phase)
        self.after(fixture, self.poll(self.SOURCE), self.transfer_view('releasing', epoch=self.OLD + 1))
        self.assertIsNone(fixture.handoff_phase)
        self.after(fixture, self.poll(self.SOURCE), self.transfer_view('releasing'))
        self.assertEqual('releasing', fixture.handoff_phase)
        self.assertEqual(original_private, fixture.handoff_private)
        for change in ({'commandId': 'other_command'}, {'sourceDeviceId': 'other_source'},
                       {'targetDeviceId': 'other_target'}):
            self.after(fixture, self.source_request(), self.transfer_view('activating', epoch=self.OLD + 1,
                       activeDeviceId=self.TARGET, transfer_changes=change), role='source-release')
            self.assertFalse(fixture.handoff_status['epochAdvanced'])
        for epoch, status in ((self.OLD, 200), (self.OLD + 2, 200), (self.OLD + 1, 409)):
            self.after(fixture, self.source_request(), self.transfer_view('activating', epoch=epoch,
                       activeDeviceId=self.TARGET), status=status, role='source-release')
            self.assertFalse(fixture.handoff_epoch_advanced.is_set())
        self.advance(fixture, self.source_request())
        self.assertTrue(fixture.handoff_epoch_advanced.is_set())

    def test_unrelated_requests_do_not_hold(self):
        fixture = self.ready()
        bodies = [self.poll(epoch=self.OLD - 1), self.poll(epoch=self.OLD + 1), self.poll('other_device'),
                  self.poll(state={'playing': False}), self.poll(stateSeq=1),
                  self.poll(queueIntentState={'revision': 1}),
                  self.poll(acks=[dict(deliveryId='unknown', status='ok')]),
                  self.poll(self.SOURCE, acks=[dict(deliveryId='unknown', status='ok')]),
                  self.poll(self.SOURCE, acks=[dict(deliveryId=self.RELEASE, status='rejected')]),
                  self.poll(self.SOURCE, epoch=self.OLD + 1,
                            acks=[dict(deliveryId=self.RELEASE, status='ok')]),
                  self.wire({'action': 'command'}), b'not-json']
        for body in bodies:
            with self.subTest(bodyKind=bodies.index(body)):
                self.assertIsNone(self.before(fixture, body))
        self.assertIsNone(fixture.handoff_before_request('GET', '/api/me/player', self.poll()))
        self.assertIsNone(fixture.handoff_before_request('POST', '/other', self.poll()))
        self.assertFalse(fixture.handoff_status['oldPollHeld'])
        self.assertFalse(fixture.handoff_status['sourceReleaseHeld'])

    def test_fresh_poll_requires_stale_reason_and_explicit_control_continue(self):
        fixture = self.complete_old_poll()
        fresh = self.poll(epoch=self.OLD + 1, acks=[])
        self.after(fixture, self.poll(), {'epoch': self.OLD + 1, 'error': 'other conflict'},
                   status=409, role='old-poll')
        self.assertIsNone(self.before(fixture, fresh))
        self.after(fixture, self.poll(), {'epoch': self.OLD + 1, 'error': 'stale epoch'},
                   status=409, role='old-poll')
        self.assertIsNone(self.before(fixture, self.poll(epoch=self.OLD + 2)))
        self.assertIsNone(self.before(fixture, self.poll(epoch=self.OLD + 1, stateSeq=1)))
        worker = self.worker(fixture, fresh)
        self.wait_for(lambda: fixture.handoff_status['freshPollHeld'])
        self.assertFalse(worker.done.is_set())
        self.assertFalse(fixture.handoff_status['freshPollForwarded'])
        snapshot = self.control(fixture, 'handoff-status')
        snapshot['freshPollHeld'] = False
        self.assertTrue(fixture.handoff_status['freshPollHeld'], 'status must not alias mutable state')
        self.control(fixture, 'release-handoff', 'POST')
        self.finish_worker(worker, 'fresh-poll')
        self.assertTrue(fixture.handoff_status['freshPollForwarded'])
        self.assertIsNone(self.before(fixture, fresh), 'fresh barrier is one-shot')

    def test_timeout_and_cancellation_fail_closed_without_claimed_forward(self):
        for stage in ('source', 'old', 'fresh'):
            for cancelled in (False, True):
                with self.subTest(stage=stage, cancelled=cancelled):
                    fixture = self.complete_old_poll() if stage == 'fresh' else self.ready()
                    body = (self.source_request() if stage == 'source' else
                            self.poll(epoch=self.OLD + 1 if stage == 'fresh' else self.OLD))
                    if cancelled:
                        fixture.stop.set()
                        fixture.handoff_old_poll.set()
                        fixture.handoff_epoch_advanced.set()
                        fixture.handoff_continue.set()
                    else:
                        fixture.deadline = time.monotonic() - 1  # Deterministic zero-wait deadline.
                    with self.assertRaises(TimeoutError):
                        self.before(fixture, body)
                    self.assertTrue(fixture.handoff_failed)
                    self.assertTrue(fixture.stop.is_set())
                    self.assertTrue(fixture.handoff_old_poll.is_set())
                    self.assertTrue(fixture.handoff_epoch_advanced.is_set())
                    self.assertTrue(fixture.handoff_continue.is_set())
                    if stage == 'fresh':
                        self.assertFalse(fixture.handoff_status['freshPollForwarded'])
                    else:
                        self.assertFalse(fixture.handoff_status['oldPollForwarded'])
                        self.assertFalse(fixture.handoff_status['releaseAccepted'])
                        self.assertFalse(fixture.handoff_status['epochAdvanced'])

    def test_status_is_fixed_safe_fields_without_private_ids(self):
        fixture = self.complete_old_poll('target-first')
        snapshot = self.control(fixture, 'handoff-status')
        self.assertEqual(self.SAFE_FIELDS, set(snapshot))
        for key, value in snapshot.items():
            if key == 'oldPollStatus':
                self.assertEqual(409, value)
            else:
                self.assertIs(type(value), bool)
        rendered = json.dumps(snapshot)
        for private in (self.SOURCE, self.TARGET, self.COMMAND, self.PREPARE, self.RELEASE, fixture.token):
            self.assertNotIn(private, rendered)


if __name__ == '__main__':
    unittest.main(verbosity=2)
