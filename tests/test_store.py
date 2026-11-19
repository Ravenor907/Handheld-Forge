"""The download folder's clean-up: the same rules and cases as the Android app's ImageStoreTest,
plus how the desktop app uses it after a drive is made and on its main screen."""

from __future__ import annotations

import os
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from handheld_forge import store
from handheld_forge.config import load_catalog, load_labs

DAY = store.DAY_MS
CATALOG = load_catalog() + load_labs()


class ImageStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.clock = 100 * DAY
        self.store = store.ImageStore(self.dir, store.owner_from(CATALOG), lambda: self.clock)

    def file(self, name: str, n: int = 1000, age: int = 0) -> Path:
        p = self.dir / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(bytes(n))
        t = (self.clock - age) / 1000
        os.utime(p, (t, t))
        return p

    def test_owners_come_from_the_catalog_whatever_the_version(self):
        owner = store.owner_from(CATALOG)
        self.assertEqual("bazzite-deck", owner("bazzite-deck-stable-live-amd64.iso"))
        self.assertEqual("bazzite-deck", owner("bazzite-deck-stable-amd64.iso"))
        self.assertEqual("steamos", owner("steamdeck-repair-latest.img.bz2"))
        self.assertEqual("cachyos-handheld", owner("cachyos-handheld-linux-251130.iso"))
        self.assertEqual("batocera", owner("batocera-zen3-x86-64-v3-42-20250101.img.gz"))
        self.assertIsNone(owner("something-else.iso"))

    def test_written_image_is_deleted_unless_kept(self):
        iso = self.file("steamdeck-repair-latest.img.bz2", 5000)
        stamp = self.file("steamdeck-repair-latest.img.bz2.verified", 10)
        self.store.used("steamos", iso)
        self.assertEqual(5000, self.store.after_write("steamos", iso))
        self.assertFalse(iso.exists())
        self.assertFalse(stamp.exists())

        iso2 = self.file("steamdeck-repair-latest.img.bz2", 5000)
        self.store.set_keep("steamos", True)
        self.assertEqual(0, self.store.after_write("steamos", iso2))
        self.assertTrue(iso2.exists())
        [e] = self.store.entries()
        self.assertTrue(e.keep)

    def test_newer_version_replaces_the_old(self):
        old = self.file("cachyos-handheld-linux-260101.iso", 3000)
        self.file("cachyos-handheld-linux-260101.iso.verified", 10)
        other = self.file("steamdeck-repair-latest.img.bz2", 2000)
        new = self.file("cachyos-handheld-linux-260628.iso", 4000)
        self.store.used("cachyos-handheld", new)
        self.assertEqual(3000, self.store.replace_older("cachyos-handheld", new))
        self.assertFalse(old.exists())
        self.assertFalse((self.dir / "cachyos-handheld-linux-260101.iso.verified").exists())
        self.assertTrue(new.exists())
        self.assertTrue(other.exists())
        self.assertEqual(1, sum(e.owner == "cachyos-handheld" for e in self.store.entries()))

    def test_images_unused_for_two_weeks_go_unless_kept(self):
        stale = self.file("steamdeck-repair-latest.img.bz2", 2000, age=20 * DAY)
        fresh = self.file("bazzite-deck-stable-live-amd64.iso", 3000, age=2 * DAY)
        kept_old = self.file("batocera-zen3-x86-64-v3-43.1-20260529.img.gz", 1000, age=40 * DAY)
        self.store.set_keep("batocera", True)
        self.assertEqual(2000, self.store.remove_unused())
        self.assertFalse(stale.exists())
        self.assertTrue(fresh.exists())
        self.assertTrue(kept_old.exists())

        # Using an image resets its clock.
        self.store.used("bazzite-deck", fresh)
        self.clock += 13 * DAY
        self.assertEqual(0, self.store.remove_unused())
        self.clock += 2 * DAY
        self.assertEqual(3000, self.store.remove_unused())

    def test_leftovers_go_on_start_but_a_recent_partial_download_stays(self):
        old_part = self.file("bazzite-deck-stable-live-amd64.iso.part", 700, age=3 * DAY)
        old_expected = self.file("bazzite-deck-stable-live-amd64.iso.part.expected", 10, age=3 * DAY)
        new_part = self.file("cachyos-handheld-linux-260628.iso.part", 500, age=60_000)
        new_expected = self.file("cachyos-handheld-linux-260628.iso.part.expected", 10)
        orphan_stamp = self.file("steamdeck-repair-latest.img.bz2.verified", 10)
        orphan_expected = self.file("gone.iso.part.expected", 10)
        tmp = self.file("images.txt.tmp", 10, age=2 * DAY)
        image = self.file("bazzite-deck-stable-amd64.iso", 900)
        good_stamp = self.file("bazzite-deck-stable-amd64.iso.verified", 10)
        v_part = self.file("ventoy/ventoy-1.0-linux.tar.gz.part", 50, age=5 * DAY)

        self.store.clean_leftovers()
        for f in (old_part, old_expected, orphan_stamp, orphan_expected, tmp, v_part):
            self.assertFalse(f.exists(), f.name)
        self.assertFalse((self.dir / "ventoy").exists())
        for f in (new_part, new_expected, image, good_stamp):
            self.assertTrue(f.exists(), f.name)
        # Partial downloads and stamps aren't images.
        self.assertEqual([image.name], [e.path.name for e in self.store.entries()])

    def test_remove_all_empties_the_folder_but_keeps_choices(self):
        self.file("bazzite-deck-stable-live-amd64.iso", 3000)
        self.file("bazzite-deck-stable-live-amd64.iso.verified", 10)
        self.file("cachyos-handheld-linux-260628.iso.part", 500)
        self.file("ventoy/ventoy-1.0-linux.tar.gz", 200)
        self.store.set_keep("bazzite-deck", True)
        self.assertEqual(3200, self.store.total_bytes())
        self.assertGreaterEqual(self.store.remove_all(), 3200)
        self.assertEqual([], self.store.entries())
        self.assertEqual([store.INDEX], [p.name for p in self.dir.iterdir()])
        self.assertTrue(self.store.is_kept("bazzite-deck"))

    def test_ventoy_folder_counts_as_one_image(self):
        self.file("ventoy/ventoy-1.1-linux.tar.gz", 300)
        [e] = self.store.entries()
        self.assertEqual((store.VENTOY, 300), (e.owner, e.bytes))
        self.assertEqual(300, self.store.after_write(store.VENTOY, self.dir / "ventoy"))
        self.assertFalse((self.dir / "ventoy").exists())

    def test_index_matches_the_android_apps(self):
        iso = self.file("bazzite-deck-stable-live-amd64.iso")
        self.store.used("bazzite-deck", iso)
        self.store.set_keep("steamos", True)
        self.assertEqual(f"use\t{iso.name}\tbazzite-deck\t{self.clock}\nkeep\tsteamos\n",
                         (self.dir / store.INDEX).read_text())

    def test_note_says_what_was_freed_and_that_it_comes_back(self):
        self.assertEqual("Freed 6.2 GB by deleting the downloaded image. It downloads again when needed.",
                         store.freed_note(6_200_000_000, "the downloaded image"))
        self.assertEqual("12 GB", store.size(12_400_000_000))
        self.assertEqual("1 MB", store.size(10))

    def test_clean_on_start_says_what_it_freed(self):
        self.file("steamdeck-repair-latest.img.bz2", 2_000_000_000, age=20 * DAY)
        self.file("bazzite-deck-stable-live-amd64.iso.part", 10, age=3 * DAY)
        self.assertEqual("Freed 2.0 GB by deleting images not used for 14 days. It downloads again when "
                         "needed.",
                         store.clean_on_start(self.store))
        self.assertEqual([], list(self.dir.glob("*.iso*")) + list(self.dir.glob("*.bz2")))
        self.assertEqual("", store.clean_on_start(self.store))


class DesktopJobTests(unittest.TestCase):
    """The desktop app's jobs: a drive made from a download deletes it (unless kept), a new version
    replaces the old one, and Ventoy's download goes once it's installed."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        p = mock.patch.dict(os.environ, {"XDG_CACHE_HOME": self.tmp.name, "XDG_CONFIG_HOME": self.tmp.name})
        p.start()
        self.addCleanup(p.stop)
        from handheld_forge import download
        self.dir = download.cache_dir()
        self.distro = next(d for d in CATALOG if d.id == "cachyos-handheld")

    def run_system(self, filename: str, live: bool = False):
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        from handheld_forge import gui
        from handheld_forge.download import PreparedImage
        from handheld_forge.drives import Drive

        img = self.dir / filename
        img.write_bytes(bytes(4000))
        (self.dir / (filename + ".verified")).write_text("x")
        source = mock.Mock(filename=filename)
        path = img.with_name(img.name + ".part") if live else img
        prepared = PreparedImage(path, "x", source, False, False,
                                 live=mock.Mock() if live else None)
        drive = Drive("/dev/sdb", "sdb", 64_000_000_000, "SanDisk", (), True, "ok")
        w = gui.Worker(self.distro, drive, gui.SYSTEM, catalog=CATALOG)
        logs = []
        w.log.connect(logs.append)
        with mock.patch.object(gui, "prepare_iso", return_value=prepared), \
                mock.patch.object(gui, "make_drive"), mock.patch.object(gui.jobs, "log_drive"):
            w._run_job()
        return w, logs

    def test_a_made_drive_deletes_its_download_and_older_versions(self):
        old = self.dir / "cachyos-handheld-linux-260101.iso"
        old.write_bytes(bytes(3000))
        w, logs = self.run_system("cachyos-handheld-linux-260628.iso")
        self.assertEqual([], [p.name for p in self.dir.iterdir() if p.name != store.INDEX])
        self.assertEqual([f"Freed 1 MB by deleting the older {self.distro.name} image. It downloads again "
                          "when needed.",
                          "Freed 1 MB by deleting the downloaded image now that the drive is made. It "
                          "downloads again when needed."], w.notes)
        self.assertEqual(w.notes, logs)

    def test_a_kept_download_stays_for_next_time(self):
        store.for_catalog(CATALOG).set_keep("cachyos-handheld", True)
        w, _ = self.run_system("cachyos-handheld-linux-260628.iso", live=True)
        self.assertTrue((self.dir / "cachyos-handheld-linux-260628.iso").exists())
        self.assertEqual([], w.notes)
        [e] = store.for_catalog(CATALOG).entries()
        self.assertTrue(e.keep)

    def test_ventoys_download_goes_once_installed(self):
        from handheld_forge import gui
        from handheld_forge.drives import Drive

        (self.dir / "ventoy").mkdir()
        (self.dir / "ventoy" / "ventoy-1.1-linux.tar.gz").write_bytes(bytes(300))
        drive = Drive("/dev/sdb", "sdb", 64_000_000_000, "SanDisk", (), True, "ok")
        w = gui.Worker(None, drive, gui.VENTOY, secure_boot=True, catalog=CATALOG)
        with mock.patch.object(gui.jobs, "install_ventoy", return_value="1.1"):
            self.assertEqual("1.1", w._run_job())
        self.assertFalse((self.dir / "ventoy").exists())
        self.assertIn("the Ventoy download now that it's installed", w.notes[0])


class StorageLineTests(unittest.TestCase):
    """The main screen's storage line: total, a Keep switch for each image, Remove all, and the
    Keep the download switch before erasing."""

    def test_storage_line(self):
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        from PySide6.QtWidgets import QApplication

        from handheld_forge import gui
        from handheld_forge.drives import Drive
        from handheld_forge.preflight import MachineState

        app = QApplication.instance() or QApplication([])
        app.setStyleSheet(gui.STYLE)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        drive = Drive("/dev/sdb", "sdb", 64_000_000_000, "SanDisk Extreme", (), True, "ok")
        for p in (mock.patch.dict(os.environ, {"XDG_CACHE_HOME": tmp.name, "XDG_CONFIG_HOME": tmp.name,
                                               "XDG_DATA_HOME": tmp.name}),
                  mock.patch.object(gui, "list_drives", return_value=[drive]),
                  mock.patch.object(gui, "read_state", return_value=MachineState(80, True, True, True))):
            p.start()
            self.addCleanup(p.stop)
        shots = os.environ.get("HF_SCREENSHOT_DIR")

        def shot(w, name):
            app.processEvents()
            if shots:
                Path(shots).mkdir(parents=True, exist_ok=True)
                w.grab().save(str(Path(shots) / f"{name}.png"))

        def settle(cond, secs=3.0):
            import time
            end = time.monotonic() + secs
            while not cond() and time.monotonic() < end:
                app.processEvents()
                time.sleep(0.01)
            self.assertTrue(cond())

        w = gui.MainWindow()
        w.resize(620, 760)
        w.show()
        app.processEvents()
        self.assertFalse(w.storage_line.isVisible())  # nothing downloaded, nothing to say

        cache = Path(tmp.name) / "handheld-forge"
        old = cache / "steamdeck-repair-latest.img.bz2"
        old.write_bytes(bytes(2000))
        t = (store.time.time() - 20 * 86400)
        os.utime(old, (t, t))
        (cache / "bazzite-deck-stable-live-amd64.iso").write_bytes(bytes(3000))
        (cache / "bazzite-deck-stable-live-amd64.iso.part").write_bytes(bytes(10))
        os.utime(cache / "bazzite-deck-stable-live-amd64.iso.part", (t, t))

        w.clean_on_start()
        settle(lambda: not w._tasks)
        self.assertFalse(old.exists())
        self.assertFalse((cache / "bazzite-deck-stable-live-amd64.iso.part").exists())
        self.assertTrue(w.storage_line.isVisible())
        self.assertEqual("Downloaded images: 1 MB", w.storage_line.total.text())
        self.assertIn("images not used for 14 days", w.storage_line.note.text())
        shot(w, "s1-storage")

        w.storage_line.toggle()
        settle(lambda: w.storage_line.body.height() > 40)
        [(owner, sw, detail, _, _)] = w.storage_line._rows
        self.assertEqual("bazzite-deck", owner)
        self.assertEqual("1 MB, deleted after the next drive", detail.text())
        sw.toggle()
        self.assertTrue(store.for_catalog(CATALOG).is_kept("bazzite-deck"))
        self.assertEqual("1 MB, kept", detail.text())
        settle(lambda: sw._t == 1.0)  # the knob glides over
        shot(w, "s2-storage-open")

        # Keep the download, before erasing: shows the same choice.
        w._ask_confirm()
        self.assertTrue(w.keep_card.isVisibleTo(w))
        self.assertTrue(w.keep_switch.isChecked())
        self.assertIn("Kept on this computer", w.keep_card.detail.text())
        w.keep_card.clicked.emit()
        self.assertFalse(store.for_catalog(CATALOG).is_kept("bazzite-deck"))
        self.assertIn("Deleted once the drive is made", w.keep_card.detail.text())
        settle(lambda: w.keep_switch._t == 0.0)
        shot(w, "s3-keep-download")
        w._leave_confirm()

        w.storage_line.remove_btn.click()
        app.processEvents()
        self.assertEqual([store.INDEX], [p.name for p in cache.iterdir()])
        self.assertEqual("Downloaded images: none", w.storage_line.total.text())
        self.assertIn("Freed 1 MB by deleting the downloaded images", w.storage_line.note.text())
        shot(w, "s4-removed")

        # A running job is never cleaned under.
        w.thread = threading.Thread()
        (cache / "x.iso").write_bytes(b"1")
        w._remove_all_images()
        self.assertTrue((cache / "x.iso").exists())
        w.thread = None
        w.close()


if __name__ == "__main__":
    unittest.main()
