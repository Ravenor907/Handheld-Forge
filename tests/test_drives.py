import hashlib
import os
import tempfile
import unittest

from handheld_forge import flash_helper as fh


def disk(name, tran="usb", size=32_000_000_000, ro=False, children=None, mounts=None):
    return {"name": name, "path": f"/dev/{name}", "size": size, "type": "disk", "rm": True,
            "hotplug": True, "tran": tran, "model": "Flash", "vendor": "Sandisk", "ro": ro,
            "mountpoints": mounts or [None], "children": children or []}


def part(name, mounts):
    return {"name": name, "path": f"/dev/{name}", "size": 1, "type": "part", "mountpoints": mounts}


class DriveFilterTests(unittest.TestCase):
    def test_plain_usb_ok(self):
        self.assertEqual(fh.evaluate_disk(disk("sdb")), (True, "ok"))

    def test_usb_with_media_mount_ok(self):
        d = disk("sdb", children=[part("sdb1", ["/run/media/deck/STICK"])])
        self.assertTrue(fh.evaluate_disk(d)[0])

    def test_nvme_blocked(self):
        self.assertFalse(fh.evaluate_disk(disk("nvme0n1", tran="nvme"))[0])

    def test_sd_card_blocked(self):
        self.assertFalse(fh.evaluate_disk(disk("mmcblk0", tran=None))[0])

    def test_running_system_on_usb_blocked(self):
        d = disk("sda", children=[part("sda1", ["/boot/efi"]), part("sda2", ["/sysroot", "/var"])])
        ok, reason = fh.evaluate_disk(d)
        self.assertFalse(ok)
        self.assertIn("running system", reason)

    def test_root_inside_luks_blocked(self):
        luks = {"name": "luks", "type": "crypt", "mountpoints": ["/"]}
        p = part("sda2", [None])
        p["children"] = [luks]
        self.assertFalse(fh.evaluate_disk(disk("sda", children=[p]))[0])

    def test_read_only_blocked(self):
        self.assertFalse(fh.evaluate_disk(disk("sdb", ro="1"))[0])

    def test_empty_reader_blocked(self):
        self.assertFalse(fh.evaluate_disk(disk("sdc", size=0))[0])

    def test_old_lsblk_format(self):
        d = disk("sdb", ro="0")
        d.pop("mountpoints")
        d["children"] = [{"name": "sdb1", "type": "part", "mountpoint": "/"}]
        self.assertFalse(fh.evaluate_disk(d)[0])

    def test_list_disks_shape(self):
        tree = {"blockdevices": [disk("sdb"), disk("nvme0n1", tran="nvme"),
                                 {"name": "loop0", "type": "loop"}]}
        out = fh.list_disks(tree)
        self.assertEqual([d["path"] for d in out], ["/dev/sdb", "/dev/nvme0n1"])
        self.assertEqual([d["eligible"] for d in out], [True, False])
        self.assertEqual(out[0]["model"], "Sandisk Flash")


class WriteTests(unittest.TestCase):
    def _iso(self, d, data):
        p = os.path.join(d, "img.iso")
        with open(p, "wb") as f:
            f.write(data)
        return p

    def test_write_and_readback(self):
        data = os.urandom(fh.CHUNK * 2 + 12345)
        with tempfile.TemporaryDirectory() as d:
            iso = self._iso(d, data)
            target = os.path.join(d, "usb")
            with open(target, "wb") as f:
                f.write(b"\0" * (len(data) + 4096))
            fh.write_image(iso, target, hashlib.sha256(data).hexdigest(), exclusive=False)
            with open(target, "rb") as f:
                self.assertEqual(f.read(len(data)), data)

    def test_wrong_expected_hash_fails(self):
        with tempfile.TemporaryDirectory() as d:
            iso = self._iso(d, b"abc" * 1000)
            target = os.path.join(d, "usb")
            open(target, "wb").close()
            with self.assertRaises(RuntimeError):
                fh.write_image(iso, target, "0" * 64, exclusive=False)

    def test_write_refuses_non_root(self):
        if os.geteuid() == 0:
            self.skipTest("running as root")
        rc = fh.main(["write", "--iso", "/nope", "--device", "/dev/null", "--sha256", "0" * 64])
        self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main()
