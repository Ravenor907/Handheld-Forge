import json
import os
import tempfile
import threading
import unittest
from pathlib import Path

from handheld_forge.config import CatalogError, experimental_variants, get_distro, install_guide, load_catalog


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

    def test_experimental_no_password_cachyos(self):
        cat = load_catalog()
        variants = experimental_variants(cat)
        self.assertEqual([d.id for d in variants], ["cachyos-handheld-copy"])
        v = get_distro("cachyos-handheld-copy", cat)
        self.assertTrue(v.experimental)
        self.assertFalse(v.needs_password)
        self.assertEqual((v.write, v.layout), ("copy", "fat"))
        self.assertEqual(v.images, get_distro("cachyos-handheld", cat).images)  # same download
        self.assertIn("Experimental", v.warning)
        self.assertIn("touchscreen", v.warning)  # the controls warning still applies
        self.assertIn("regular CachyOS option", v.install_steps[-1])
        self.assertNotIn("—", v.warning + "".join(v.install_steps))

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

            # The no-password CachyOS sits in its own Experimental section and warns before erasing.
            exp = next(b for b in w.os_group.buttons() if b.property("distro") == "cachyos-handheld-copy")
            self.assertEqual(exp.subtitle, "No password")
            exp.setChecked(True)
            self.assertFalse(w._distro().needs_password)
            w._ask_confirm()
            self.assertIn("Experimental", w.confirm_warning.text())
            self.assertNotIn("Linux will ask for your password", w.confirm_warning.text())
            shot(w, "4c-confirm-experimental")
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


if __name__ == "__main__":
    unittest.main()
