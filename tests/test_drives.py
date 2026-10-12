import unittest

from handheld_forge import drives as dr


def disk(name, tran="usb", size=32_000_000_000, ro=False, children=None, mounts=None):
    return {"name": name, "path": f"/dev/{name}", "size": size, "type": "disk", "rm": True,
            "hotplug": True, "tran": tran, "model": "Flash", "vendor": "Sandisk", "ro": ro,
            "mountpoints": mounts or [None], "children": children or []}


def part(name, mounts):
    return {"name": name, "path": f"/dev/{name}", "size": 1, "type": "part", "mountpoints": mounts}


class DriveFilterTests(unittest.TestCase):
    def test_plain_usb_ok(self):
        self.assertEqual(dr.evaluate_disk(disk("sdb")), (True, "ok"))

    def test_usb_with_media_mount_ok(self):
        d = disk("sdb", children=[part("sdb1", ["/run/media/deck/STICK"])])
        self.assertTrue(dr.evaluate_disk(d)[0])

    def test_nvme_blocked(self):
        self.assertFalse(dr.evaluate_disk(disk("nvme0n1", tran="nvme"))[0])

    def test_sd_card_blocked(self):
        self.assertFalse(dr.evaluate_disk(disk("mmcblk0", tran=None))[0])

    def test_running_system_on_usb_blocked(self):
        d = disk("sda", children=[part("sda1", ["/boot/efi"]), part("sda2", ["/sysroot", "/var"])])
        ok, reason = dr.evaluate_disk(d)
        self.assertFalse(ok)
        self.assertIn("running system", reason)

    def test_root_inside_luks_blocked(self):
        p = part("sda2", [None])
        p["children"] = [{"name": "luks", "type": "crypt", "mountpoints": ["/"]}]
        self.assertFalse(dr.evaluate_disk(disk("sda", children=[p]))[0])

    def test_read_only_blocked(self):
        self.assertFalse(dr.evaluate_disk(disk("sdb", ro="1"))[0])

    def test_empty_reader_blocked(self):
        self.assertFalse(dr.evaluate_disk(disk("sdc", size=0))[0])

    def test_old_lsblk_format(self):
        d = disk("sdb", ro="0")
        d.pop("mountpoints")
        d["children"] = [{"name": "sdb1", "type": "part", "mountpoint": "/"}]
        self.assertFalse(dr.evaluate_disk(d)[0])

    def test_list_disks_shape(self):
        tree = {"blockdevices": [disk("sdb", children=[part("sdb1", [None])]),
                                 disk("nvme0n1", tran="nvme"), {"name": "loop0", "type": "loop"}]}
        out = dr.list_disks(tree)
        self.assertEqual([d.path for d in out], ["/dev/sdb", "/dev/nvme0n1"])
        self.assertEqual([d.eligible for d in out], [True, False])
        self.assertEqual(out[0].model, "Sandisk Flash")
        self.assertEqual(out[0].partitions, ("sdb1",))
        self.assertEqual(out[0].size_label, "32 GB")


if __name__ == "__main__":
    unittest.main()
