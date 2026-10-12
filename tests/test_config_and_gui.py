import json
import os
import tempfile
import unittest
from pathlib import Path

from handheld_forge.config import CatalogError, get_distro, load_catalog


class CatalogTests(unittest.TestCase):
    def test_shipped_catalog(self):
        cat = load_catalog()
        enabled = [d.id for d in cat if d.enabled]
        self.assertEqual(enabled, ["bazzite-deck"])
        b = get_distro("bazzite-deck", cat)
        self.assertTrue(b.images[0].require_signature)
        self.assertEqual(b.images[0].filename, "bazzite-deck-stable-live-amd64.iso")
        self.assertEqual((b.name, b.edition), ("Bazzite", "Handheld KDE"))

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
            shot(w, "4-confirm")

            w.stack.setCurrentWidget(w.work_page)
            w.work_title.setText("Creating Bazzite on SanDisk Ultra Fit")
            w.bar.set_value(0)
            w.on_stage("copy", 4_100_000_000, 8_300_000_000)
            self.assertEqual(w.track.current, 3)
            self.assertTrue(w.percent.text().endswith("%"))
            shot(w, "5-working")

            class FakeWorker:
                distro = w._distro()
                drive = sandisk

            class FakeThread:
                def quit(self):
                    pass

                def wait(self):
                    pass

            w.worker, w.thread = FakeWorker(), FakeThread()
            w.on_finished("ok", "")
            self.assertEqual(w.result_title.text(), "Ready to boot")
            shot(w, "6-done")

            w.worker, w.thread = FakeWorker(), FakeThread()
            w.on_finished("error", "This drive is too small for the image.")
            self.assertEqual(w.result_btn.text(), "Try again")
            shot(w, "7-error")
        w.close()
        app.processEvents()


if __name__ == "__main__":
    unittest.main()
