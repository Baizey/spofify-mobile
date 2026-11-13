#!/usr/bin/env python3
"""Offline supervisor/pin oracles. Never launches a Go broker."""
import contextlib
import ctypes
import errno
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import shutil
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from broker_fixture import Fixture, owned_database


class SupervisorTest(unittest.TestCase):
    def setUp(self):
        value = os.environ.get('SPOFIFY_TEST_LIBRARY_PLAYBACK_RUNTIME_ROOT')
        self.assertTrue(value, 'explicit private test runtime parent required')
        self.parent = Path(value)
        self.root = Path(tempfile.mkdtemp(prefix='supervisor-oracle-', dir=self.parent))

    def tearDown(self):
        # Tests must account their own children before unlinking any files.
        self.assertFalse(list(self.root.iterdir()), 'oracle retained state needs investigation')
        self.root.rmdir()

    def test_cleanup_waits_original_launcher_and_adopted_children(self):
        self.assertEqual(ctypes.CDLL(None).prctl(36, 1, 0, 0, 0), 0)
        fixture = Fixture(30)
        fixture.parent = self.root
        fixture.root = self.root / 'spofify-library-oracle'
        fixture.root.mkdir(mode=0o700)
        for name in ('home', 'data', 'library', 'tmp'):
            (fixture.root / name).mkdir(mode=0o700)
            (fixture.root / name / 'synthetic').write_text('synthetic')
        fixture.token = 'synthetic-oracle-owner'
        (fixture.root / '.owner').write_text(fixture.token)
        fixture.env = {'PATH': '/usr/bin:/bin', 'HOME': str(fixture.root), 'TMPDIR': str(fixture.root)}
        fixture.log = (fixture.root / 'broker.log').open('wb')
        child = fixture.launch(['/bin/sh', '-c', 'sleep 30 & echo ready > ready; wait'])
        import time
        deadline = time.monotonic() + 3
        while not (fixture.root / 'ready').exists():
            self.assertLess(time.monotonic(), deadline)
            time.sleep(.01)
        captured = io.StringIO()
        with contextlib.redirect_stderr(captured):
            fixture.close()
        self.assertIsNotNone(child.returncode)
        receipts = [json.loads(line) for line in captured.getvalue().splitlines()]
        stopped = next(item for item in receipts if item.get('fixtureStopped') == 'complete')
        self.assertEqual(stopped['descendants'], 'ECHILD')
        self.assertEqual(stopped['originalLauncherReturnCodes'], [child.returncode])
        self.assertEqual(stopped['originalLaunchers'], [{'role': 'helper', 'pid': child.pid,
                                                       'returnCode': child.returncode, 'awaited': True}])
        receipt = next(item for item in receipts if item.get('fixtureCleanup') == 'complete')
        self.assertLess(receipts.index(stopped), receipts.index(receipt))
        self.assertEqual(receipt['descendants'], 'ECHILD')
        self.assertEqual(receipt['fixtureCleanup'], 'complete')
        self.assertFalse(fixture.root.exists())
        with self.assertRaises(ChildProcessError):
            os.waitpid(-1, os.WNOHANG)

    def test_content_removal_failure_preserves_owner_and_echild_proof(self):
        fixture = Fixture(30)
        fixture.parent = self.root
        fixture.root = self.root / 'spofify-library-removal-failure'
        fixture.root.mkdir(mode=0o700)
        fixture.token = 'synthetic-removal-owner'
        (fixture.root / '.owner').write_text(fixture.token)
        (fixture.root / 'data').mkdir(mode=0o700)
        captured = io.StringIO()
        with contextlib.redirect_stderr(captured):
            with patch('broker_fixture.shutil.rmtree', side_effect=OSError(errno.EBUSY, 'synthetic busy directory')):
                with self.assertRaises(OSError):
                    fixture.close()
        receipts = [json.loads(line) for line in captured.getvalue().splitlines()]
        stopped = receipts[0]
        self.assertEqual(stopped['fixtureStopped'], 'complete')
        self.assertEqual(stopped['descendants'], 'ECHILD')
        self.assertEqual(receipts[1], {'fixtureRemovalFailed': 'content', 'category': 'data', 'errno': errno.EBUSY})
        self.assertFalse(any(item.get('fixtureCleanup') == 'complete' for item in receipts))
        self.assertEqual((fixture.root / '.owner').read_text(), fixture.token)
        self.assertEqual(json.loads((fixture.root / '.stopped.json').read_text()), stopped)
        # This is this oracle's proven-stopped synthetic root, NOT a prior unknown run.
        shutil.rmtree(fixture.root)

    def test_sqlite_wal_mmap_handles_close_on_success_and_transaction_failure(self):
        path = self.root / 'owned.sqlite'
        with owned_database(path) as successful:
            self.assertEqual(successful.execute('PRAGMA journal_mode=WAL').fetchone()[0], 'wal')
            self.assertGreaterEqual(successful.execute('PRAGMA mmap_size=1048576').fetchone()[0], 1048576)
            successful.execute('CREATE TABLE values_for_test(value TEXT)')
            successful.execute("INSERT INTO values_for_test VALUES ('committed')")
        with self.assertRaises(sqlite3.ProgrammingError):
            successful.execute('SELECT 1')
        with self.assertRaisesRegex(RuntimeError, 'synthetic transaction failure'):
            with owned_database(path) as failed:
                failed.execute("INSERT INTO values_for_test VALUES ('rolled back')")
                raise RuntimeError('synthetic transaction failure')
        with self.assertRaises(sqlite3.ProgrammingError):
            failed.execute('SELECT 1')
        with owned_database(path) as verified:
            self.assertEqual(verified.execute('SELECT value FROM values_for_test').fetchall(), [('committed',)])
        self.assertFalse(path.with_name(path.name + '-wal').exists())
        self.assertFalse(path.with_name(path.name + '-shm').exists())
        path.unlink()

    def test_missing_and_wrong_binary_fail_before_allocation(self):
        script = Path(__file__).with_name('broker_fixture.py').resolve()
        invalid = self.root / 'not-a-broker'
        invalid.write_text('#!/bin/sh\nexit 99\n')
        invalid.chmod(0o700)
        try:
            for binary in ('', str(invalid)):
                env = {'PATH': '/usr/bin:/bin', 'HOME': str(self.root), 'TMPDIR': str(self.root),
                       'SPOFIFY_TEST_LIBRARY_PLAYBACK_RUNTIME_ROOT': str(self.root),
                       'SPOFIFY_TEST_GO_BROKER_BINARY': binary}
                result = subprocess.run(['/usr/bin/python3', '-B', str(script), '--seconds', '30'],
                                        env=env, input=b'stop\n', capture_output=True, timeout=5)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, b'')
                self.assertNotIn(b'fixtureAllocated', result.stderr)
                self.assertEqual(list(self.root.iterdir()), [invalid])
        finally:
            invalid.unlink()

    def test_lifetime_rejects_unbounded_and_over_approved(self):
        for value in (-1, 0, 29, 121, 600):
            with self.assertRaises(AssertionError):
                Fixture(value)


if __name__ == '__main__':
    unittest.main()
