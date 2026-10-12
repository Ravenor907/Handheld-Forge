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
    def test_window_builds(self):
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        from unittest import mock

        from PySide6.QtWidgets import QApplication

        from handheld_forge import gui
        from handheld_forge.flasher import Drive

        app = QApplication.instance() or QApplication([])
        usb = Drive("/dev/sdb", 32_000_000_000, "Sandisk Flash", (), True, "ok")
        with mock.patch.object(gui, "list_drives", return_value=[usb]):
            w = gui.MainWindow()
            self.assertEqual(w.distro_box.currentData(), "bazzite-deck")
            self.assertTrue(w.create_btn.isEnabled())
            w.on_stage("download", 500, 1000)
            self.assertEqual(w.progress.value(), 500)
        with mock.patch.object(gui, "list_drives", return_value=[]):
            w.refresh_drives()
            self.assertFalse(w.create_btn.isEnabled())
        w.close()
        app.processEvents()


if __name__ == "__main__":
    unittest.main()
