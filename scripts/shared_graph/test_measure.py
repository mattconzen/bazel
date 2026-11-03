import tempfile
import unittest
from pathlib import Path

from measure import rollup, sample, start_ticks

ROLLUP = """0000-ffff r--p 00000000 00:00 0 [rollup]
Rss: 40 kB
Pss: 25 kB
Private_Clean: 1 kB
Private_Dirty: 9 kB
Shared_Clean: 20 kB
Shared_Dirty: 10 kB
SwapPss: 0 kB
"""


def stat(start):
    return "123 (name with ) spaces) " + " ".join(["S"] + ["0"] * 18 + [str(start)])


class MeasurementTest(unittest.TestCase):
    def test_process_identity_handles_parentheses_and_spaces(self):
        self.assertEqual(start_ticks(stat(998)), 998)

    def test_rollup_converts_kib_and_preserves_pss_rss_distinction(self):
        values = rollup(ROLLUP)
        self.assertEqual(values["pss_bytes"], 25 * 1024)
        self.assertEqual(values["rss_bytes"], 40 * 1024)
        with self.assertRaises(ValueError):
            rollup(ROLLUP.replace("Pss: 25 kB", "Pss: 25 MB"))
        with self.assertRaises(ValueError):
            rollup(ROLLUP.replace("Pss: 25 kB", ""))

    def test_missing_or_reused_process_invalidates_total(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            process = proc / "123"
            process.mkdir()
            (process / "stat").write_text(stat(998))
            (process / "smaps_rollup").write_text(ROLLUP)
            reading = sample({123: 998}, proc)
            self.assertTrue(reading["complete"])
            self.assertEqual(reading["aggregate"]["pss_bytes"], 25 * 1024)
            reading = sample({123: 997}, proc)
            self.assertFalse(reading["complete"])
            self.assertIsNone(reading["aggregate"])
            self.assertIn("reused", reading["processes"][0]["error"])
            reading = sample({123: 998, 124: 998}, proc)
            self.assertFalse(reading["complete"])
            self.assertIsNone(reading["aggregate"])
            self.assertEqual(reading["observed_partial_totals"]["pss_bytes"], 25 * 1024)


if __name__ == "__main__":
    unittest.main()
