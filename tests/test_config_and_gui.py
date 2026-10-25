import json
import os
import tempfile
import threading
import unittest
from pathlib import Path

from handheld_forge.config import CatalogError, get_distro, install_guide, load_catalog


class CatalogTests(unittest.TestCase):
    def test_shipped_catalog(self):
        cat = load_catalog()
        enabled = [d.id for d in cat if d.enabled]
        self.assertEqual(enabled, ["bazzite-deck", "steamos", "cachyos-handheld"])
        b = get_distro("bazzite-deck", cat)
        self.assertTrue(b.images[0].require_signature)
        self.assertEqual(b.images[0].filename, "bazzite-deck-stable-live-amd64.iso")
        self.assertEqual((b.name, b.edition), ("Bazzite", "Handheld KDE"))
        self.assertFalse(b.needs_password)  # Bazzite is copied file by file, no password
        # SteamOS is a raw bzip2 disk image, written byte for byte (needs the password on desktop).
        s = get_distro("steamos", cat)
        self.assertTrue(s.needs_password)
        self.assertIsNone(s.images[0].checksum_url)
        # CachyOS's ISO is written as is too.
        c = get_distro("cachyos-handheld", cat)
        self.assertTrue(c.needs_password)
        self.assertEqual(c.format, "iso")
        self.assertTrue(c.images[0].checksum_url.endswith(".iso.sha256"))
        self.assertIn("touchscreen", c.warning)

    def test_install_guide(self):
        cat = load_catalog()
        for d in cat:
            if d.images:  # every system you can pick says how to install it
                self.assertGreaterEqual(len(d.install_steps), 2, d.id)
        guide = install_guide(get_distro("bazzite-deck", cat))
        self.assertIn("Open the boot menu", guide)
        self.assertIn("ROG Ally", guide)
        self.assertIn("1. Choose the USB drive", guide)
        self.assertIn("universalblue", guide)
        self.assertEqual(install_guide(get_distro("bazzite-deck", cat), boot_menu=[]).split("\n")[0], "Then:")

    def _load(self, distros):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "c.json"
            p.write_text(json.dumps({"schema": 1, "distros": distros}))
            return load_catalog(p)

    def test_rejects_http(self):
        with self.assertRaises(CatalogError):
            self._load([{"id": "x", "name": "x", "enabled": True, "images": [
                {"label": "a", "iso_url": "http://e/x.iso", "checksum_url": "https://e/c"}]}])

    def test_rejects_enabled_without_images(self):
        with self.assertRaises(CatalogError):
            self._load([{"id": "x", "name": "x", "enabled": True, "images": []}])

    def test_rejects_signature_without_key(self):
        with self.assertRaises(CatalogError):
            self._load([{"id": "x", "name": "x", "enabled": True, "images": [
                {"label": "a", "iso_url": "https://e/x.iso", "checksum_url": "https://e/c",
                 "signature_url": "https://e/s", "require_signature": True}]}])


try:
    import PySide6  # noqa: F401
    HAVE_QT = True
except ImportError:
    HAVE_QT = False


@unittest.skipUnless(HAVE_QT, "PySide6 not installed")
class GuiSmokeTests(unittest.TestCase):
    """Builds every view headless. Set HF_SCREENSHOT_DIR to save PNGs of each."""

    def test_every_view(self):
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        from unittest import mock

        from PySide6.QtWidgets import QApplication

        from handheld_forge import gui
        from handheld_forge.drives import Drive

        app = QApplication.instance() or QApplication([])
        app.setStyleSheet(gui.STYLE)
        shots = os.environ.get("HF_SCREENSHOT_DIR")

        def shot(w, name):
            app.processEvents()
            if shots:
                Path(shots).mkdir(parents=True, exist_ok=True)
                w.grab().save(str(Path(shots) / f"{name}.png"))

        sandisk = Drive("/dev/sdb", "sdb", 32_000_000_000, "SanDisk Ultra Fit", ("sdb1",), True, "ok")
        kingston = Drive("/dev/sdc", "sdc", 64_000_000_000, "Kingston DataTraveler", (), True, "ok")

        with mock.patch.object(gui, "list_drives", return_value=[]):
            w = gui.MainWindow()
            w.show()
            self.assertEqual(w._distro().id, "bazzite-deck")
            self.assertFalse(w.create_btn.isEnabled())
            app.processEvents()
            self.assertTrue(w.empty_slot.isVisible())
            shot(w, "1-no-drive")

        with mock.patch.object(gui, "list_drives", return_value=[sandisk]):
            w.refresh_drives()
            self.assertEqual(w._drive(), sandisk)  # a single drive is picked for you
            self.assertTrue(w.create_btn.isEnabled())
            shot(w, "2-one-drive")

        from handheld_forge.preflight import MachineState

        state = mock.patch.object(gui, "read_state", return_value=MachineState(80, True, True, True))
        state.start()
        self.addCleanup(state.stop)

        with mock.patch.object(gui, "list_drives", return_value=[sandisk, kingston]):
            w.refresh_drives()
            self.assertEqual(w._drive(), sandisk)  # selection survives a refresh
            app.processEvents()
            tiles = [b for b in w.drive_group.buttons() if b.isVisible()]
            self.assertEqual([t.title for t in tiles], ["SanDisk Ultra Fit", "Kingston DataTraveler"])
            self.assertFalse(w.empty_slot.isVisible())
            shot(w, "3-two-drives")

            w._ask_confirm()
            self.assertIs(w.stack.currentWidget(), w.confirm_page)
            self.assertEqual(w.confirm_tile.title, "SanDisk Ultra Fit")
            # Erase stays off until the person agrees to leave it alone.
            self.assertFalse(w.erase_btn.isEnabled())
            self.assertFalse(w.confirm_warning.isVisibleTo(w))  # Bazzite has no warning
            w.ack.setChecked(True)
            self.assertTrue(w.erase_btn.isEnabled())
            shot(w, "4-confirm")
            # A low battery blocks it again, even with the tick.
            gui.read_state.return_value = MachineState(12, False, True, True)
            w._refresh_checks()
            self.assertFalse(w.erase_btn.isEnabled())
            self.assertIn("Plug in the charger", w.check_labels[0].text())
            gui.read_state.return_value = MachineState(80, True, True, True)
            w._refresh_checks()

            # SteamOS and CachyOS can be picked; the erase screen says the password comes once.
            self.assertTrue(all(b.isEnabled() for b in w.os_group.buttons()))
            steam = next(b for b in w.os_group.buttons() if b.property("distro") == "steamos")
            steam.setChecked(True)
            w._ask_confirm()
            self.assertTrue(w.confirm_warning.isVisibleTo(w))
            self.assertIn("password", w.confirm_warning.text())
            shot(w, "4b-confirm-steamos")
            w.stack.setCurrentWidget(w.choose_page)

            next(b for b in w.os_group.buttons() if b.property("distro") == "bazzite-deck").setChecked(True)
            w._ask_confirm()

            w.stack.setCurrentWidget(w.work_page)
            w.work_title.setText("Creating Bazzite on SanDisk Ultra Fit")
            w.bar.set_value(0)
            w.on_stage("copy", 4_100_000_000, 8_300_000_000)
            self.assertEqual(w.work_status.text(), "Step 4 of 5: Copying files to the drive")
            self.assertAlmostEqual(w.bar.target, w.progress.fraction)  # the bar glides there, left to right
            w.bar.value = w.bar.target
            self.assertTrue(w.work_detail.text().startswith("4.1 of 8.3 GB"))
            w._append("Signature verified against pinned Bazzite key")
            self.assertEqual(w.work_last.text(), "Signature verified against pinned Bazzite key")
            self.assertEqual(w.track.current, 3)
            self.assertTrue(w.percent.text().endswith("%"))
            shot(w, "5-working")

            class FakeWorker:
                distro = w._distro()
                drive = sandisk
                cancel = threading.Event()

            # Stop asks first: one click arms it, the second actually stops.
            w.worker = FakeWorker()
            w._stop_armed = False
            w.cancel()
            self.assertFalse(FakeWorker.cancel.is_set())
            self.assertEqual(w.cancel_btn.text(), "Click again to stop")
            shot(w, "5b-stop-confirm")
            w.cancel()
            self.assertTrue(FakeWorker.cancel.is_set())
            self.assertEqual(w.cancel_btn.text(), "Stopping")
            FakeWorker.cancel = threading.Event()

            class FakeThread:
                def quit(self):
                    pass

                def wait(self):
                    pass

            w.worker, w.thread = FakeWorker(), FakeThread()
            w.on_finished("ok", "")
            self.assertEqual(w.result_title.text(), "Ready to boot")
            self.assertIn("Open the boot menu", w.result_guide.text())
            self.assertIn("universalblue", w.result_guide.text())
            shot(w, "6-done")

            w.worker, w.thread = FakeWorker(), FakeThread()
            w.on_finished("error", "This drive is too small for the image.")
            self.assertEqual(w.result_btn.text(), "Try again")
            shot(w, "7-error")
        w.close()
        app.processEvents()


@unittest.skipUnless(HAVE_QT, "PySide6 not installed")
class GuiParityTests(unittest.TestCase):
    """The screens that match the Android app: your own file, Ventoy (Secure Boot choice, adding
    images, starts on its own), Format drive, Labs (unlock, settings, tools) and every result."""

    def setUp(self):
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        from unittest import mock

        from PySide6.QtWidgets import QApplication

        from handheld_forge import gui
        from handheld_forge.drives import Drive
        from handheld_forge.preflight import MachineState

        self.app = QApplication.instance() or QApplication([])
        self.app.setStyleSheet(gui.STYLE)
        self.gui, self.mock = gui, mock
        self.tmp = tempfile.TemporaryDirectory()
        self.drive = Drive("/dev/sdb", "sdb", 64_000_000_000, "SanDisk Extreme", ("sdb1", "sdb2"), True, "ok")
        self.patches = [
            mock.patch.dict(os.environ, {"XDG_DATA_HOME": self.tmp.name, "XDG_CONFIG_HOME": self.tmp.name,
                                         "XDG_CACHE_HOME": self.tmp.name}),
            mock.patch.object(gui, "list_drives", return_value=[self.drive]),
            mock.patch.object(gui, "read_state", return_value=MachineState(80, True, True, True)),
            mock.patch.object(gui.jobs, "ventoy_parts", return_value=("sdb1", "sdb2")),
        ]
        for p in self.patches:
            p.start()
        self.shots = os.environ.get("HF_SCREENSHOT_DIR")

    def tearDown(self):
        for p in reversed(self.patches):
            p.stop()
        self.tmp.cleanup()

    def shot(self, w, name):
        self.app.processEvents()
        if self.shots:
            Path(self.shots).mkdir(parents=True, exist_ok=True)
            w.grab().save(str(Path(self.shots) / f"{name}.png"))

    def wait(self, cond, secs=5.0):
        import time as t
        end = t.monotonic() + secs
        while not cond() and t.monotonic() < end:
            self.app.processEvents()
            t.sleep(0.01)
        self.app.processEvents()
        self.assertTrue(cond())

    def finish(self, w, job, status="ok", message="", result=None, **args):
        class FakeWorker:
            pass

        fw = FakeWorker()
        fw.distro, fw.drive, fw.job, fw.result, fw.args = None, self.drive, job, result, args
        fw.cancel = threading.Event()

        class FakeThread:
            def quit(self):
                pass

            def wait(self):
                pass

        w.worker, w.thread = fw, FakeThread()
        w.on_finished(status, message)

    def test_screens(self):
        from handheld_forge import drivetest, jobs
        gui = self.gui
        w = gui.MainWindow()
        w.resize(620, 760)
        w.show()
        self.assertTrue(w.ventoy_bar.isVisibleTo(w))  # the drive has Ventoy: add images without erasing
        self.assertIn("has Ventoy", w.drive_group.buttons()[0].subtitle)
        self.assertFalse(w.labs_btn.isVisibleTo(w))
        self.shot(w, "p1-choose")

        # Your own file.
        iso = Path(self.tmp.name) / "my-distro.iso"
        data = bytearray(4 * 1024 * 1024)
        data[32769:32774] = b"CD001"
        data[510:512] = b"\x55\xAA"
        iso.write_bytes(bytes(data))
        w.set_own_file(iso)
        self.assertEqual("file", w._choice())
        self.assertEqual("Write to drive", w.create_btn.text())
        self.assertEqual("my-distro.iso", w.file_tile.title)
        self.assertEqual("Change", w.file_tile.tag)
        self.shot(w, "p2-own-file")
        w._ask_confirm()
        self.assertEqual("Erase and write", w.erase_btn.text())
        self.assertIn("my-distro.iso", w.confirm_text.text())
        self.assertIn("password", w.confirm_warning.text())
        # No download, so no network check.
        self.assertEqual(1, sum(lab.isVisibleTo(w) for lab in w.check_labels))
        w._leave_confirm()

        # Ventoy, with the Secure Boot choice.
        w.ventoy_tile.setChecked(True)
        self.assertEqual("Install Ventoy", w.create_btn.text())
        w._ask_confirm()
        self.assertTrue(w.sb_box.isVisibleTo(w))
        self.assertTrue(w.sb_on.isChecked())
        self.assertEqual("Erase and install", w.erase_btn.text())
        w.ack.setChecked(True)
        self.shot(w, "p3-confirm-ventoy")
        w.sb_off.click()
        self.assertFalse(w.secure_boot)
        self.assertIn("Secure Boot is turned off in the BIOS", w.sb_text.text())
        self.shot(w, "p3b-confirm-ventoy-sb-off")
        w._leave_confirm()

        # Format drive.
        w.format_tile.setChecked(True)
        self.assertEqual("Format drive", w.create_btn.text())
        w._ask_confirm()
        self.assertFalse(w.sb_box.isVisibleTo(w))
        self.assertIn("USB DRIVE", w.confirm_text.text())
        self.assertEqual("Erase and format", w.erase_btn.text())
        self.shot(w, "p4-confirm-format")
        w._leave_confirm()

        # Ventoy's files: picked images, what's on the drive, starts on its own, remove.
        files = [jobs.VentoyFile("bazzite-deck-stable.iso", 8_300_000_000),
                 jobs.VentoyFile("SteamOS-recovery.img", 2_800_000_000)]
        info = jobs.VentoyInfo("/b", "1.1.18", True, files, 21_400_000_000, 64_000_000_000,
                               "bazzite-deck-stable.iso")
        calls = []
        with self.mock.patch.object(gui.jobs, "ventoy_info", return_value=info), \
                self.mock.patch.object(gui.jobs, "set_boot_next",
                                       side_effect=lambda d, n, **k: calls.append(("next", n))), \
                self.mock.patch.object(gui.jobs, "remove_image",
                                       side_effect=lambda d, n, **k: calls.append(("rm", n))):
            w._open_ventoy()
            self.wait(lambda: w.ventoy_info is not None)
            self.assertIn("Ventoy 1.1.18, Secure Boot support on. 21.4 GB free.", w.v_info.text())
            self.assertEqual("Pick images", w.v_go.text())
            packed = Path(self.tmp.name) / "steamos.img.bz2"
            import bz2
            disk = bytearray(2_000_000)
            disk[510:512] = b"\x55\xAA"
            packed.write_bytes(bz2.compress(bytes(disk)))
            w.add_picked([iso, packed])
            self.assertEqual("Copy 2 to drive", w.v_go.text())
            self.assertTrue(w.v_go.isEnabled())
            self.shot(w, "p5-ventoy-files")
            cards = [c for c in w.ventoy_page.findChildren(gui.Card) if c.isVisibleTo(w)]
            on_drive = [c for c in cards if c.title.text() == "SteamOS-recovery.img"][0]
            on_drive.clicked.emit()
            self.wait(lambda: ("next", "SteamOS-recovery.img") in calls and not w._v_busy)
            w._remove("SteamOS-recovery.img")
            self.assertEqual("SteamOS-recovery.img", w._confirm_remove)  # asks first
            self.shot(w, "p5b-ventoy-remove-ask")
            w._remove("SteamOS-recovery.img")
            self.wait(lambda: ("rm", "SteamOS-recovery.img") in calls and not w._v_busy)
        w._back_to_start()

        # Labs: ten taps on the version.
        for _ in range(9):
            w.version_btn.click()
        self.assertFalse(w.labs_btn.isVisibleTo(w))
        self.assertIn("1 more tap to turn on Labs", w.empty_hint.text())
        w.version_btn.click()
        self.assertTrue(w.labs_btn.isVisibleTo(w))
        self.assertTrue(w.labs_box.isVisibleTo(w))  # Batocera
        self.assertTrue(gui.settings.load().labs)
        self.shot(w, "p6-labs-on")
        w.labs_btn.click()
        self.assertIs(w.stack.currentWidget(), w.labs_page)
        w.stream_switch.click()
        self.assertTrue(gui.settings.load().stream)
        self.assertTrue(w.test_btn.isEnabled())
        self.assertFalse(w.check_btn.isEnabled())  # Bazzite isn't downloaded here
        w.test_btn.click()
        self.assertEqual("Erase and test", w.test_btn.text())
        self.shot(w, "p7-labs")
        w._back_to_start()

        # Results, as on Android.
        report = drivetest.Report(64_000_000_000, 4_000_000_000, [95.0, 94.0, 31.0, 30.0], 52.0, 160.0,
                                  0, 128, 0)
        self.finish(w, "test", result=report)
        self.assertEqual("Drive test results", w.result_title.text())
        self.assertIn("drops to 30 MB/s", w.result_lines.text())
        self.assertEqual("Done", w.result_btn.text())
        self.shot(w, "p8-done-test")
        fake = drivetest.Report(64_000_000_000, 4_000_000_000, [90.0], 80.0, 150.0, 0, 128, 100,
                                8_000_000_000)
        self.finish(w, "test", result=fake)
        self.assertEqual("This drive failed the test", w.result_title.text())
        self.finish(w, "check", result=None, image=iso)
        self.assertEqual("The drive matches", w.result_title.text())
        self.finish(w, "check", result=4096, image=iso)
        self.assertEqual("The drive doesn't match", w.result_title.text())
        self.assertIn("byte 4,096", w.result_text.text())
        self.finish(w, "ventoy", result="1.1.18", secure_boot=False)
        self.assertEqual("Ventoy is installed", w.result_title.text())
        self.assertIn("without Secure Boot support", w.result_text.text())
        self.assertIn("How to use it", w.result_guide.text())
        self.assertEqual("Add images", w.result_btn.text())
        self.assertTrue(w.result_done.isVisibleTo(w))
        self.shot(w, "p9-done-ventoy")
        self.finish(w, "import", result=["bazzite-deck-stable.iso", "steamos.img"])
        self.assertEqual("Added to Ventoy", w.result_title.text())
        self.assertEqual("Add more", w.result_btn.text())
        self.shot(w, "p10-done-import")
        self.finish(w, "import", status="error", message="The drive filled up while copying steamos.img.")
        self.assertEqual("The images weren't all added", w.result_title.text())
        self.assertEqual("Back to the drive", w.result_btn.text())
        self.finish(w, "format")
        self.assertEqual("Drive formatted", w.result_title.text())
        self.shot(w, "p11-done-format")

        # Turning Labs off from the menu hides it again.
        w._set_labs(False)
        self.assertFalse(w.labs_btn.isVisibleTo(w))
        self.assertFalse(gui.settings.load().labs)
        w.close()
        self.app.processEvents()


if __name__ == "__main__":
    unittest.main()
