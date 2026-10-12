import io
import os
import unittest

from handheld_forge.iso9660 import IsoError, IsoReader
from tests.isobuilder import build_iso

LAYOUT = {
    "EFI/BOOT/BOOTX64.EFI": b"shim" * 300,
    "EFI/BOOT/grub.cfg": b"search --no-floppy --set=root -l 'Bazzite-Live'\n",
    "boot/grub2/grub.cfg": b"cfg",
    "images/pxeboot/vmlinuz": b"kernel" * 1000,
    "images/pxeboot/initrd.img": b"initrd" * 1000,
    "LiveOS/squashfs.img": os.urandom(50_000),
}


def files_of(r: IsoReader) -> dict[str, bytes]:
    return {e.path: b"".join(r.read_chunks(e)) for e in r.walk() if not e.is_dir}


class IsoReaderTests(unittest.TestCase):
    def test_rock_ridge_names_and_contents(self):
        r = IsoReader(io.BytesIO(build_iso(LAYOUT)))
        self.assertEqual(r.volume_id, "Bazzite-Live")
        self.assertEqual(files_of(r), LAYOUT)

    def test_dirs_listed_before_their_files(self):
        r = IsoReader(io.BytesIO(build_iso(LAYOUT)))
        seen = set()
        for e in r.walk():
            parent = e.path.rsplit("/", 1)[0] if "/" in e.path else ""
            if parent:
                self.assertIn(parent, seen)
            seen.add(e.path)

    def test_multi_extent_file(self):
        r = IsoReader(io.BytesIO(build_iso(LAYOUT, max_extent=4096)))
        sq = next(e for e in r.walk() if e.path == "LiveOS/squashfs.img")
        self.assertGreater(len(sq.extents), 10)
        self.assertEqual(sq.size, len(LAYOUT["LiveOS/squashfs.img"]))
        self.assertEqual(files_of(r), LAYOUT)

    def test_names_in_continuation_area(self):
        r = IsoReader(io.BytesIO(build_iso(LAYOUT, ce_names=True)))
        self.assertEqual(files_of(r), LAYOUT)

    def test_plain_iso9660_fallback(self):
        r = IsoReader(io.BytesIO(build_iso({"README.TXT": b"hi"}, rock_ridge=False)))
        self.assertEqual(files_of(r), {"README.TXT": b"hi"})

    def test_read_file(self):
        r = IsoReader(io.BytesIO(build_iso(LAYOUT)))
        self.assertEqual(r.read_file("EFI/BOOT/grub.cfg"), LAYOUT["EFI/BOOT/grub.cfg"])

    def test_not_an_iso(self):
        with self.assertRaises(IsoError):
            IsoReader(io.BytesIO(b"\0" * 40 * 2048))

    def test_truncated(self):
        data = build_iso(LAYOUT)
        r = IsoReader(io.BytesIO(data[: len(data) - 30_000]))
        with self.assertRaises(IsoError):
            files_of(r)


if __name__ == "__main__":
    unittest.main()
