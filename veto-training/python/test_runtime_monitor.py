"""Injected runtime evidence; no torch, GPU or training required."""
import unittest
from unittest.mock import patch

from runtime_monitor import RuntimeMonitor


class RuntimeMonitorTest(unittest.TestCase):
    def setUp(self):
        self.records = []
        self.monitor = RuntimeMonitor("injected-run", self.records.append, 10, 20)

    def test_exception_chain_and_unknown_code(self):
        try:
            try:
                raise ValueError("injected cause")
            except ValueError as cause:
                raise RuntimeError("CUDA error: injected failure") from cause
        except RuntimeError as error:
            self.monitor.exception(error)
        record = self.records[-1]
        self.assertIn("injected cause", record["traceback"])
        self.assertIn("CUDA error: injected failure", record["traceback"])
        self.assertIsNone(record["cuda_error_code"])
        self.assertFalse(record["failed_state_recoverable"])

    def test_progress_and_stall_are_distinct_and_deduplicated(self):
        self.monitor.check_progress()
        self.assertEqual([], self.records)
        self.monitor.last_progress -= 11
        self.monitor.check_progress()
        self.assertEqual("slow_step", self.records[-1]["kind"])
        self.monitor.last_progress -= 11
        self.monitor.check_progress()
        self.monitor.check_progress()
        self.assertEqual(2, len(self.records))
        self.assertEqual("no_progress", self.records[-1]["kind"])
        self.monitor.progress(phase="optimizer_complete")
        self.assertEqual("progress_resumed", self.records[-1]["kind"])

    def test_permission_failure_is_unavailable(self):
        with patch("runtime_monitor.os.name", "nt"), patch.object(
                self.monitor, "query_windows", side_effect=PermissionError("injected denied")):
            self.monitor.watch_windows()
        self.assertEqual("monitor_unavailable", self.records[-1]["kind"])
        self.assertIn("denied", self.records[-1]["message"])

    def test_event_153_preserves_evidence_without_cuda_interpretation(self):
        event = dict(event_id=153, record_id=101, provider="nvlddmkm",
                     event_time="2026-11-12T01:00:00Z", message="injected driver event",
                     xml="<Event>injected</Event>")
        with patch("runtime_monitor.os.name", "nt"), patch.object(
                self.monitor, "query_windows", side_effect=[
                    dict(cursor=100, oldest=1, events=[]),
                    dict(cursor=101, oldest=1, events=[event])]), patch.object(
                        self.monitor.stopped, "wait", side_effect=[False, True]):
            self.monitor.watch_windows()
        record = self.records[-1]
        self.assertEqual("windows_driver_event", record["kind"])
        self.assertEqual(event["xml"], record["xml"])
        self.assertNotIn("cuda_error_code", record)
        self.assertIn("unknown", record["attribution"])


if __name__ == "__main__":
    unittest.main()
