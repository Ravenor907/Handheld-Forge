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
        self.refreshes = []
        self.refresh_patch = mock.patch.object(installer, "refresh_menus", side_effect=self.refreshes.append)
        self.refresh_patch.start()

    def tearDown(self):
        self.home_patch.stop()
        self.refresh_patch.stop()
        self.tmp.cleanup()

    def test_menu_entry_has_update_and_uninstall(self):
        installer.write_menu_entry(self.p)
        text = self.p.desktop.read_text()
        self.assertIn("Actions=update;uninstall;", text)
        self.assertIn(f"Exec={self.p.venv}/bin/handheld-forge uninstall --ask", text)
        self.assertEqual(os.readlink(self.p.bin_link), str(self.p.venv / "bin/handheld-forge"))

    def test_menu_entry_rewritten_only_when_changed(self):
        installer.write_menu_entry(self.p)
        self.assertEqual(len(self.refreshes), 1)
        inode = self.p.desktop.stat().st_ino
        installer.write_menu_entry(self.p)  # same content: the file isn't touched
        self.assertEqual(self.p.desktop.stat().st_ino, inode)
        self.assertEqual(len(self.refreshes), 1)
        self.p.desktop.write_text("[Desktop Entry]\nName=old\n")
        installer.write_menu_entry(self.p)  # changed: replaced in one step, menus refreshed
        self.assertIn("StartupWMClass=handheld-forge", self.p.desktop.read_text())
        self.assertEqual(len(self.refreshes), 2)
        self.assertEqual([f.name for f in self.p.desktop.parent.iterdir()], ["handheld-forge.desktop"])

    def test_refresh_uses_kde_cache_builder(self):
        self.refresh_patch.stop()
        calls = []
        with mock.patch.object(installer.shutil, "which", side_effect=lambda c: f"/usr/bin/{c}"):
            installer.refresh_menus(self.home, run=lambda cmd, **kw: calls.append(cmd))
        self.refresh_patch.start()
        self.assertEqual(calls, [["kbuildsycoca6"]])

    def make_source(self, where="Handheld-Forge"):
        src = self.home / where
        (src / "handheld_forge").mkdir(parents=True)
        (src / "install.sh").write_text("#!/bin/sh\n")
        (src / "handheld_forge/__init__.py").write_text('__version__ = "0.4.2"\n')
        self.p.source_note.write_text(f"{src}\n")
        return src

    def test_uninstall_removes_everything(self):
        installer.write_menu_entry(self.p)
        src = self.make_source()
        plan = installer.removal_plan(self.p)
        self.assertEqual([i.path for i in plan],
                         [self.p.desktop, self.p.bin_link, self.p.app_dir, self.p.cache, src.resolve()])
        installer.uninstall(self.p)
        for path in (self.p.desktop, self.p.bin_link, self.p.app_dir, self.p.cache, src):
            self.assertFalse(path.exists() or path.is_symlink(), path)
        self.assertFalse(self.p.bin_link.parent.exists())  # ~/.local/bin was left empty, so it goes too
        self.assertEqual(installer.removal_plan(self.p), [])  # nothing left to remove

    def test_keeps_a_non_empty_local_bin(self):
        installer.write_menu_entry(self.p)
        (self.p.bin_link.parent / "other-tool").write_text("")
        installer.uninstall(self.p)
        self.assertTrue((self.p.bin_link.parent / "other-tool").exists())

    def test_can_keep_source(self):
        src = self.make_source()
        installer.uninstall(self.p, keep_source=True)
        self.assertTrue(src.exists())

    def test_never_deletes_a_source_that_isnt_ours(self):
        self.p.source_note.write_text(f"{self.home}\n")  # the home folder itself
        self.assertNotIn(self.home.resolve(), [i.path for i in installer.removal_plan(self.p)])
        other = self.home / "Projects"
        other.mkdir()
        self.p.source_note.write_text(f"{other}\n")  # not a Handheld Forge copy
        installer.uninstall(self.p)
        self.assertTrue(other.exists())

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
