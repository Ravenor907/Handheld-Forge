"""Builder end to end against a fake udisks on a private D-Bus.

Also unit tests for the boot-label rewrite and the gdbus text helpers.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from handheld_forge import builder
from handheld_forge.drives import Drive
from handheld_forge.udisks import gv, parse_int, parse_strings
from tests.isobuilder import build_iso

HERE = Path(__file__).resolve().parent
GRUB = (
    "search --no-floppy --set=root -l '{label}'\n"
    "menuentry 'Install' {{\n  linux /images/pxeboot/vmlinuz quiet root=live:CDLABEL={label} rd.live.image\n"
    "  initrd /images/pxeboot/initrd.img\n}}\n"
)


def layout(label="Bazzite-Live", efi=True):
    files = {
        "boot/grub2/grub.cfg": GRUB.format(label=label).encode(),
        "images/pxeboot/vmlinuz": b"K" * 70_000,
        "images/pxeboot/initrd.img": b"I" * 50_000,
        "LiveOS/squashfs.img": os.urandom(300_000),
    }
    if efi:
        files.update({
            "EFI/BOOT/BOOTX64.EFI": b"shim" * 1000,
            "EFI/BOOT/grubx64.efi": b"grub" * 1000,
            "EFI/BOOT/grub.cfg": GRUB.format(label=label).encode(),
        })
    return files


def gi_python():
    cands = [sys.executable, "/usr/bin/python3"] + [shutil.which(f"python3.{n}") for n in range(15, 9, -1)]
    for py in filter(lambda c: c and os.path.exists(c), cands):
        r = subprocess.run([py, "-c", "from gi.repository import Gio"], capture_output=True)
        if r.returncode == 0:
            return py
    return None


PY_GI = gi_python()
CAN_RUN = PY_GI and shutil.which("dbus-daemon") and shutil.which("gdbus")


class LabelRewriteTests(unittest.TestCase):
    def test_rewrite(self):
        out = builder.rewrite_boot_label(GRUB.format(label="Some-Very-Long-Label"),
                                         "Some-Very-Long-Label", "1234-abcd")
        self.assertIn("search --no-floppy --set=root --fs-uuid 1234-abcd", out)
        self.assertIn("root=live:UUID=1234-abcd rd.live.image", out)
        self.assertNotIn("Some-Very-Long-Label", out)

    def test_archiso_style(self):
        out = builder.rewrite_boot_label("archisolabel=X archisosearchuuid=1 LABEL=X", "X", "u")
        self.assertIn("UUID=u", out)

    def test_label_rules(self):
        self.assertTrue(builder.ext4_label_ok("Bazzite-Live"))
        self.assertFalse(builder.ext4_label_ok("Bazzite-Deck-Stable-Live"))
        self.assertFalse(builder.ext4_label_ok("has space"))


class GVariantTests(unittest.TestCase):
    def test_gv(self):
        self.assertEqual(gv({"auth.no_user_interaction": True, "label": "a'b"}),
                         "{'auth.no_user_interaction': <true>, 'label': <'a\\'b'>}")
        self.assertEqual(gv(1048576), "uint64 1048576")
        self.assertEqual(gv({}), "@a{sv} {}")

    def test_parse(self):
        self.assertEqual(parse_strings("(objectpath '/org/x/sdz1',)"), ["/org/x/sdz1"])
        self.assertEqual(parse_strings("(<[b'/run/media/me/Bazzite-Live']>,)"),
                         ["/run/media/me/Bazzite-Live"])
        self.assertEqual(parse_int("(<uint64 32000000000>,)"), 32000000000)
        self.assertEqual(parse_strings("(<@aay []>,)"), [])


@unittest.skipUnless(CAN_RUN, "needs dbus-daemon, gdbus and a Python with PyGObject")
class BuilderTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.bus = subprocess.Popen(["dbus-daemon", "--session", "--nofork", "--print-address"],
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        self.addr = self.bus.stdout.readline().strip()
        self.env = mock.patch.dict(os.environ, {"DBUS_SYSTEM_BUS_ADDRESS": self.addr})
        self.env.start()
        self.fake = None

    def tearDown(self):
        for proc in (self.fake, self.bus):
            if proc:
                proc.kill()
                proc.wait()
                proc.stdout.close()
        self.env.stop()
        self.tmp.cleanup()

    def start_fake(self, **env):
        self.fake = subprocess.Popen([PY_GI, str(HERE / "fake_udisks.py"), str(self.base)],
                                     stdout=subprocess.PIPE, text=True, env={**os.environ, **env})
        self.assertEqual(self.fake.stdout.readline().strip(), "READY")

    def run_build(self, files, label="Bazzite-Live", **env):
        self.start_fake(**env)
        iso = self.base / "test.iso"
        iso.write_bytes(build_iso(files, label=label, max_extent=100_000))
        drive = Drive("/dev/sdz", "sdz", 32_000_000_000, "Fake Stick", ("sdz1",), True, "ok")
        stages = []
        builder.build_drive(drive, iso, lambda s, d, t: stages.append(s), log=lambda m: None,
                            recheck=False)
        return stages

    def calls(self):
        return [json.loads(x) for x in (self.base / "calls.jsonl").read_text().splitlines()]

    def parts(self):
        return json.loads((self.base / "parts.json").read_text())

    def tree(self, store):
        root = Path(store)
        return {str(p.relative_to(root)): p.read_bytes() for p in root.rglob("*") if p.is_file()}

    def test_builds_bootable_layout_without_prompts(self):
        files = layout()
        stages = self.run_build(files)
        parts = self.parts()
        esp, data = parts["sdz1"], parts["sdz2"]
        self.assertEqual((esp["fstype"], esp["type"]), ("vfat", builder.ESP_TYPE))
        self.assertEqual(data["fstype"], "ext4")
        self.assertEqual(data["fsopts"]["label"], "Bazzite-Live")
        self.assertTrue(data["fsopts"]["take-ownership"])
        self.assertEqual(self.tree(data["store"]), files)
        self.assertEqual(self.tree(esp["store"]),
                         {k: v for k, v in files.items() if k.startswith("EFI/")})
        methods = [c["method"] for c in self.calls()]
        self.assertEqual(methods[0], "Format")
        self.assertEqual(methods[-1], "PowerOff")
        self.assertEqual(methods.count("Mount"), 4)  # copy + fresh read-back mount
        for s in ("prepare", "copy", "sync", "check", "eject"):
            self.assertIn(s, stages)

    def test_long_label_falls_back_to_uuid(self):
        label = "Bazzite-Deck-Live-2026"
        self.run_build(layout(label), label=label)
        parts = self.parts()
        data = parts["sdz2"]
        self.assertEqual(data["fsopts"]["label"], builder.FALLBACK_LABEL)
        for store, rel in ((data["store"], "boot/grub2/grub.cfg"), (data["store"], "EFI/BOOT/grub.cfg"),
                           (parts["sdz1"]["store"], "EFI/BOOT/grub.cfg")):
            cfg = (Path(store) / rel).read_text()
            self.assertIn(f"--fs-uuid {data['uuid']}", cfg)
            self.assertIn(f"root=live:UUID={data['uuid']}", cfg)
            self.assertNotIn(label, cfg)

    def test_desktop_automount_is_handled(self):
        files = layout()
        self.run_build(files, FAKE_AUTOMOUNT="1")
        self.assertEqual(self.tree(self.parts()["sdz2"]["store"]), files)

    def test_password_needed_means_clean_refusal(self):
        with self.assertRaises(builder.BuildError) as cm:
            self.run_build(layout(), FAKE_DENY="Format")
        self.assertIn("never asks for one", str(cm.exception))

    def test_drive_too_small(self):
        with self.assertRaises(builder.BuildError) as cm:
            self.run_build(layout(), FAKE_SIZE=str(600 * 1024 * 1024))
        self.assertIn("too small", str(cm.exception))

    def test_iso_without_uefi_loader(self):
        with self.assertRaises(builder.BuildError):
            self.run_build(layout(efi=False))


if __name__ == "__main__":
    unittest.main()
