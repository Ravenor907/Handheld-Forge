import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from handheld_forge import installer


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name)
        data, cache = self.home / ".local/share", self.home / ".cache"
        app = data / "handheld-forge"
        self.p = installer.Paths(app, app / "venv", self.home / ".local/bin/handheld-forge",
                                 data / "applications/handheld-forge.desktop", cache / "handheld-forge")
        (self.p.venv / "bin").mkdir(parents=True)
        (self.p.venv / "bin/handheld-forge").write_text("#!/bin/sh\n")
        (self.p.venv / "bin/pip").write_text("")
        self.p.cache.mkdir(parents=True)
        (self.p.cache / "bazzite.iso").write_bytes(b"x" * 1000)
        self.home_patch = mock.patch.object(Path, "home", return_value=self.home)
        self.home_patch.start()

    def tearDown(self):
        self.home_patch.stop()
        self.tmp.cleanup()

    def test_menu_entry_has_update_and_uninstall(self):
        installer.write_menu_entry(self.p)
        text = self.p.desktop.read_text()
        self.assertIn("Actions=update;uninstall;", text)
        self.assertIn(f"Exec={self.p.venv}/bin/handheld-forge uninstall --ask", text)
        self.assertEqual(os.readlink(self.p.bin_link), str(self.p.venv / "bin/handheld-forge"))

    def test_uninstall_removes_everything(self):
        installer.write_menu_entry(self.p)
        installer.uninstall(self.p)
        for path in (self.p.desktop, self.p.bin_link, self.p.app_dir, self.p.cache):
            self.assertFalse(path.exists() or path.is_symlink(), path)

    def test_uninstall_can_keep_downloads(self):
        installer.write_menu_entry(self.p)
        installer.uninstall(self.p, keep_downloads=True)
        self.assertFalse(self.p.app_dir.exists())
        self.assertTrue((self.p.cache / "bazzite.iso").exists())

    def test_uninstall_leaves_someone_elses_command_alone(self):
        self.p.bin_link.parent.mkdir(parents=True)
        self.p.bin_link.write_text("not ours")
        installer.uninstall(self.p)
        self.assertEqual(self.p.bin_link.read_text(), "not ours")

    def test_versions(self):
        self.assertEqual(installer.parse_version('__version__ = "0.10.2"\n'), "0.10.2")
        self.assertTrue(installer.is_newer("0.10.0", "0.9.9"))
        self.assertFalse(installer.is_newer("0.4.0", "0.4.0"))
        self.assertFalse(installer.is_newer(None, "0.4.0"))

    def test_update_reinstalls_and_refreshes_menu(self):
        calls = []

        def run(cmd, **kw):
            calls.append(cmd)
            return SimpleNamespace(returncode=0, stdout="0.5.0\n", stderr="")

        self.assertEqual(installer.update(self.p, source="SRC", run=run), "0.5.0")
        self.assertIn("--force-reinstall", calls[1])  # same version number still reinstalls
        self.assertTrue(self.p.desktop.exists())

    def test_update_reports_pip_failure(self):
        def run(cmd, **kw):
            return SimpleNamespace(returncode=1, stdout="", stderr="network unreachable")

        with self.assertRaises(RuntimeError) as e:
            installer.update(self.p, source="SRC", run=run)
        self.assertIn("network unreachable", str(e.exception))

    def test_update_needs_install_sh_layout(self):
        (self.p.venv / "bin/pip").unlink()
        with self.assertRaises(RuntimeError):
            installer.update(self.p, source="SRC", run=lambda *a, **k: None)


if __name__ == "__main__":
    unittest.main()
