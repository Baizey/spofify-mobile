#!/usr/bin/env python3
"""Python-only checks of the EXACT embedded website supervisor. No Node/browser/network.
Run: python3 -B tools/testing/prepared-transport-broker/website_supervisor_test.py
Dedicated process, <=60s / 8MiB owned scratch. No caches or Go writes.
"""
import ctypes
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest


def load_supervisor():
    source = Path(__file__).with_name('website_receiver.cjs').read_text()
    embedded = source.split('const supervisor = String.raw`\n', 1)[1].split('\n`;', 1)[0]
    namespace = {'__name__': 'website_supervisor_unit'}
    exec(compile(embedded, 'website_receiver.cjs:supervisor', 'exec'), namespace)
    return namespace


class SupervisorTest(unittest.TestCase):
    def setUp(self):
        self.supervisor = load_supervisor()
        self.root = Path(tempfile.mkdtemp(prefix='website-supervisor-unit-', dir='/dev/shm'))

    def tearDown(self):
        # This whole unittest invocation is itself a dedicated subreaper. Even a
        # failed driver must reach ECHILD here before deleting our parent scratch.
        self.supervisor['await_descendants'](None)
        self.assertLess(self.supervisor['scratch_bytes'](self.root), 8 << 20)
        shutil.rmtree(self.root)
        self.assertFalse(self.root.exists())

    def test_disappearing_child_is_ignored_and_symlink_is_not_followed(self):
        (self.root / 'kept').write_bytes(b'123')
        (self.root / 'vanished').write_bytes(b'12345')
        # External sibling is our own sentinel; scan must not count/follow it.
        sibling = self.root / 'sibling'
        sibling.mkdir()
        (sibling / 'sentinel').write_bytes(b'1234567')
        (self.root / 'link').symlink_to(sibling, target_is_directory=True)
        removed = []
        def stat_entry(entry):
            if entry.name == 'vanished':
                (self.root / entry.name).unlink()
                removed.append(entry.name)
            return entry.stat(follow_symlinks=False)
        self.assertEqual(10, self.supervisor['scratch_bytes'](self.root, stat_entry))
        self.assertEqual(['vanished'], removed)
        self.assertEqual(b'1234567', (sibling / 'sentinel').read_bytes())

    def test_missing_root_and_unexpected_io_are_not_ignored(self):
        (self.root / 'file').write_bytes(b'1')
        def denied(_):
            raise PermissionError('deterministic unexpected I/O')
        with self.assertRaises(PermissionError):
            self.supervisor['scratch_bytes'](self.root, denied)
        with self.assertRaises(FileNotFoundError):
            self.supervisor['scratch_bytes'](self.root / 'missing-root')

    def test_normal_exit_reaps_original_and_adopted_descendant(self):
        self.run_tiny_supervisor(forced_failure=False)

    def test_exception_keeps_primary_failure_and_reaps_all_before_removal(self):
        self.run_tiny_supervisor(forced_failure=True)

    def test_unverified_cleanup_keeps_runtime_without_false_complete_receipt(self):
        self.run_tiny_supervisor(forced_failure=True, fail_cleanup=True)

    def run_tiny_supervisor(self, forced_failure, fail_cleanup=False):
        parent_sentinel = self.root / 'parent-sentinel'
        parent_sentinel.write_text('untouched')
        pid_record = self.root / 'owned-pids.json'
        descendant = (
            "import os,pathlib,time; os.setsid(); "
            "pathlib.Path('descendant-ready').write_text('ready'); time.sleep(45)"
        )
        launcher = (
            "import json,os,pathlib,subprocess,sys,time; "
            f"child=subprocess.Popen([sys.executable,'-B','-c',{descendant!r}]); "
            f"pathlib.Path({str(pid_record)!r}).write_text(json.dumps([os.getpid(),child.pid])); "
            "time.sleep(45)"
        )
        # This sibling process belongs to the TEST parent, not the supervisor.
        # It must remain alive throughout supervisor cleanup, then be awaited by us.
        foreign = subprocess.Popen([sys.executable, '-B', '-c', 'import time; time.sleep(45)'])
        driver_source = f'''
import os, pathlib, runpy, sys, time
ns = runpy.run_path({str(Path(__file__).resolve())!r})['load_supervisor']()
if {fail_cleanup!r}:
    original_await = ns['await_descendants']
    def unverified(proc):
        original_await(proc)
        raise RuntimeError('forced cleanup proof failure')
    ns['await_descendants'] = unverified
def scan(root):
    end = time.monotonic()+3
    while not (root/'descendant-ready').exists():
        if time.monotonic()>end: raise RuntimeError('tiny descendant startup timeout')
        time.sleep(.01)
    if {forced_failure!r}: raise ValueError('forced supervisor scan failure')
    # Normal path: let the original launcher exit zero, leaving its child for
    # the dedicated subreaper (not a process group) to reap.
    pids = __import__('json').loads(pathlib.Path({str(pid_record)!r}).read_text())
    os.kill(pids[0], __import__('signal').SIGUSR1)
    return ns['scratch_bytes'](root)
# Default SIGUSR1 would be nonzero. Normal case installs an actual clean-exit
# handler in the original launcher instead of faking the supervisor return code.
command = {([sys.executable, '-B', '-c', 'import signal,sys; signal.signal(signal.SIGUSR1,lambda *_:sys.exit(0)); '+launcher])!r}
sys.exit(ns['supervise'](command=command,runtime={str(self.root)!r},scan=scan))
'''
        driver = subprocess.Popen([sys.executable, '-B', '-c', driver_source], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            try:
                stdout, stderr = driver.communicate(timeout=15)
            except subprocess.TimeoutExpired:
                driver.terminate()
                stdout, stderr = driver.communicate(timeout=15)
                self.fail('Tiny supervisor exceeded its bounded test deadline')
            self.assertLess(len(stdout) + len(stderr), 32 << 10)
            records = [json.loads(line) for line in stderr.splitlines() if line.startswith('{')]
            receipt = [item for item in records if item.get('receiverCleanup') == 'complete']
            if fail_cleanup:
                self.assertEqual([], receipt, stderr)
                self.assertTrue(any(item.get('receiverCleanup') == 'unverified' for item in records))
                self.assertIn('cleanup unverified; owned runtime preserved', stderr)
            else:
                self.assertEqual(1, len(receipt), stderr)
                self.assertEqual('ECHILD', receipt[0]['descendants'])
            self.assertEqual(1 if forced_failure else 0, driver.returncode, stderr)
            if forced_failure:
                self.assertIn('ValueError: forced supervisor scan failure', stderr)
                self.assertTrue(any(item.get('exceptionType') == 'ValueError' for item in records))
            allocated = Path(next(item['receiverAllocated'] for item in records if 'receiverAllocated' in item))
            self.assertEqual(fail_cleanup, allocated.exists(), 'Only proven cleanup may remove runtime')
            for pid in json.loads(pid_record.read_text()):
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
            self.assertIsNone(foreign.poll(), 'Supervisor must not kill a sibling process')
            self.assertTrue(self.root.is_dir(), 'Runtime parent must never be deleted')
            self.assertEqual('untouched', parent_sentinel.read_text())
        finally:
            if driver.poll() is None:
                driver.terminate()
                driver.wait(timeout=15)
            foreign.terminate()
            foreign.wait(timeout=3)


if __name__ == '__main__':
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
        raise RuntimeError('test subreaper unavailable')
    unittest.main(verbosity=2)
