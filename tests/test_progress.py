import unittest

from handheld_forge.progress import Progress, describe

GB = 1_000_000_000


class Clock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


class ProgressTests(unittest.TestCase):
    def run_job(self, events):
        p = Progress()
        seen = []
        for e in events:
            p.update(*e)
            seen.append(p.percent)
        return p, seen

    def test_a_whole_job_only_counts_up(self):
        # Copy builder: the stages that measure nothing (0 of 0) used to reset the bar
        # to the start of their step, e.g. 100% while checking, then 88% while ejecting.
        events = [("lookup", 0, 0)] + [("download", d, 8 * GB) for d in range(0, 8 * GB + 1, GB)]
        events += [("verify", 0, 0)] + [("verify", d, 8 * GB) for d in range(0, 8 * GB + 1, 2 * GB)]
        events += [("prepare", 0, 0)] + [("copy", d, 8 * GB) for d in range(0, 8 * GB + 1, GB)]
        events += [("sync", 0, 0)] + [("check", d, 8 * GB) for d in range(0, 8 * GB + 1, 2 * GB)]
        events += [("eject", 0, 0)]
        p, seen = self.run_job(events)
        self.assertEqual(seen, sorted(seen))  # never goes down
        self.assertEqual(seen[0], 0)
        self.assertEqual(seen[-1], 100)
        self.assertEqual(p.step, 4)

    def test_stays_between_0_and_100_whatever_it_is_told(self):
        p, seen = self.run_job([("download", -5, GB), ("download", 3 * GB, GB), ("copy", 9, 3),
                                ("check", 10, 1), ("eject", 0, 0), ("check", -1, -1)])
        self.assertTrue(all(0 <= s <= 100 for s in seen), seen)
        self.assertEqual(seen, sorted(seen))

    def test_a_late_report_from_an_earlier_step_changes_nothing(self):
        p, seen = self.run_job([("copy", GB, 2 * GB), ("download", 0, GB)])
        self.assertEqual(p.step, 3)
        self.assertEqual(seen[0], seen[1])

    def test_a_restarted_download_doesnt_go_backwards(self):
        p, seen = self.run_job([("download", 6 * GB, 8 * GB), ("download", 1 * GB, 8 * GB),
                                ("download", 7 * GB, 8 * GB)])
        self.assertEqual(seen, sorted(seen))

    def test_time_left_uses_the_steps_average_speed(self):
        clock = Clock()
        p = Progress(clock)
        p.update("download", 0, 10 * GB)
        clock.t = 1
        p.update("download", GB // 10, 10 * GB)
        self.assertEqual(p.time_left(GB // 10, 10 * GB), "")  # too early to say
        clock.t = 100
        p.update("download", 2 * GB, 10 * GB)  # 20 MB/s, 8 GB to go: 400 s
        self.assertEqual(p.time_left(2 * GB, 10 * GB), "about 7 min left")
        clock.t = 480
        p.update("download", 9_800_000_000, 10 * GB)
        self.assertEqual(p.time_left(9_800_000_000, 10 * GB), "less than a minute left")
        self.assertEqual(p.time_left(11 * GB, 10 * GB), "")  # nonsense in, nothing out
        p.update("sync", 0, 0)
        self.assertEqual(p.time_left(0, 0), "")

    def test_raw_write_steps(self):
        p, seen = self.run_job([("download", GB, GB), ("verify", 0, 0), ("prepare", 0, 0),
                                ("write", GB // 2, GB), ("write", GB, GB), ("sync", 0, 0),
                                ("check", GB, GB), ("eject", 0, 0)])
        self.assertEqual(seen, sorted(seen))
        self.assertEqual(seen[-1], 100)


class DescribeTests(unittest.TestCase):
    def test_measured_steps_show_amount_and_time(self):
        got = describe("download", 2 * GB, 8 * GB, 30, name="Bazzite", time_left="about 6 min left")
        self.assertEqual(got, ("Step 1 of 5: Downloading Bazzite", "2.0 of 8.0 GB, about 6 min left"))
        self.assertEqual(describe("write", 9 * GB, 8 * GB, 10)[1], "8.0 of 8.0 GB")  # never more than total

    def test_waiting_steps_explain_and_show_a_running_timer(self):
        head, detail = describe("sync", 0, 0, 3)
        self.assertEqual(head, "Step 4 of 5: Finishing writes")
        self.assertNotIn("(", detail)  # no timer for the first few seconds
        self.assertTrue(describe("sync", 0, 0, 83)[1].endswith("(1:23)"))
        self.assertEqual(describe("eject", 0, 0, 1)[0], "Step 5 of 5: Ejecting the drive")

    def test_password_wait_says_what_to_do(self):
        head, detail = describe("prepare", 0, 0, 12, needs_password=True)
        self.assertEqual(head, "Step 3 of 5: Waiting for your password")
        self.assertIn("Type your password", detail)
        self.assertTrue(detail.endswith("(0:12)"))
        self.assertEqual(describe("prepare", 0, 0, 1)[0], "Step 3 of 5: Preparing the drive")


if __name__ == "__main__":
    unittest.main()
