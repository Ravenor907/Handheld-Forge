import tempfile
import unittest
from pathlib import Path

from handheld_forge.preflight import MachineState, can_start, checks, read_battery

GOOD = MachineState(battery_percent=80, charging=False, online=True, unmetered=True)


class PreflightTests(unittest.TestCase):
    def test_all_good_needs_only_the_tick(self):
        items = checks(GOOD)
        self.assertTrue(all(c.ok for c in items))
        self.assertFalse(can_start(items, acknowledged=False))
        self.assertTrue(can_start(items, acknowledged=True))

    def test_low_battery_blocks_unless_charging(self):
        low = MachineState(12, False, True, True)
        self.assertFalse(can_start(checks(low), True))
        self.assertTrue(can_start(checks(MachineState(12, True, True, True)), True))
        self.assertTrue(can_start(checks(MachineState(30, False, True, True)), True))

    def test_desktop_without_battery_is_fine(self):
        items = checks(MachineState(None, False, True, True))
        self.assertTrue(items[0].ok)
        self.assertEqual(items[0].detail, "Running on mains power")

    def test_offline_blocks_but_unknown_does_not(self):
        self.assertFalse(can_start(checks(MachineState(80, False, False, True)), True))
        # Without NetworkManager we can't tell, so don't lock people out.
        self.assertTrue(can_start(checks(MachineState(80, False, None, None)), True))

    def test_metered_only_warns(self):
        items = checks(MachineState(80, False, True, False))
        self.assertFalse(items[2].ok)
        self.assertFalse(items[2].required)
        self.assertTrue(can_start(items, True))

    def test_reads_sysfs_battery(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)

            def dev(name, **files):
                (root / name).mkdir()
                for k, v in files.items():
                    (root / name / k).write_text(v + "\n")

            dev("BAT0", type="Battery", capacity="64", status="Discharging")
            dev("hidpp_battery_0", type="Battery", capacity="5", status="Discharging", scope="Device")
            dev("ACAD", type="Mains", online="0")
            self.assertEqual(read_battery(root), (64, False))  # the mouse's 5% is ignored
            (root / "ACAD" / "online").write_text("1\n")
            self.assertEqual(read_battery(root), (64, True))
        self.assertEqual(read_battery(Path("/nonexistent")), (None, False))


if __name__ == "__main__":
    unittest.main()
