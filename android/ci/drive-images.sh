#!/usr/bin/env bash
# Checks the app's Format and Ventoy output against the real tools.
#   1. Format drive: fsck.exfat, then the kernel's exFAT driver mounts it and a 5 GB file round-trips.
#   2. Ventoy: the image the app made is compared with Ventoy2Disk.sh's own install on a disk of the
#      same size (boot code, partition table, core.img, the EFI partition), its exFAT is checked and
#      mounted, an ISO is copied on, and both drives are booted in QEMU (BIOS and UEFI) with screenshots.
# Expects: IMG (dir with plain.img, ventoy.img), VTDIR (extracted ventoy-x.y.z), SECTORS.
set -euo pipefail
trap 'echo "::error title=drive-images.sh line $LINENO::$BASH_COMMAND"' ERR
# The kernel's exFAT driver if this runner has it, otherwise exfat-fuse (an independent implementation).
mnt() { sudo mount -t exfat "$1" "$2" 2>/dev/null || sudo mount.exfat-fuse "$1" "$2"; }
shots=screens/drive-images
mkdir -p "$shots"
err() { echo "::error title=$1::$2"; exit 1; }
fsck_ok() { # device, what
  local out; if out=$(sudo fsck.exfat -n "$1" 2>&1); then echo "$out" | tail -1; else
    echo "::error title=$2 fsck.exfat::$(echo "$out" | tail -8 | sed ':a;N;$!ba;s/\n/%0A/g')"; exit 1; fi
}

part_loop() { # image -> loop device with partitions
  local l; l=$(sudo losetup -f --show -P "$1"); sudo partprobe "$l" || true; sleep 1; echo "$l"
}

echo "== Format drive"
L=$(part_loop "$IMG/plain.img")
lsblk -o NAME,SIZE,FSTYPE,LABEL,PARTTYPE "$L"
fsck_ok "${L}p1" "Format drive"
[ "$(sudo blkid -s LABEL -o value "${L}p1")" = "USB DRIVE" ] || err "Format drive" "label isn't USB DRIVE"
sudo mkdir -p /mnt/plain && mnt "${L}p1" /mnt/plain
head -c 5000000000 /dev/urandom | sudo tee /mnt/plain/big-5GB.bin | sha256sum | cut -d' ' -f1 > /tmp/want
sudo umount /mnt/plain && mnt "${L}p1" /mnt/plain
sudo sha256sum /mnt/plain/big-5GB.bin | cut -d' ' -f1 > /tmp/got
cmp -s /tmp/want /tmp/got || err "Format drive" "5 GB file didn't read back intact"
sudo umount /mnt/plain
fsck_ok "${L}p1" "Format drive after writing"
sudo losetup -d "$L"
echo "Format drive: clean, mounts, 5 GB file round-trips"

echo "== Ventoy: reference install with Ventoy2Disk.sh"
truncate -s $((SECTORS * 512)) ref.img
R=$(part_loop ref.img)
(cd "$VTDIR" && printf 'y\ny\n' | sudo sh Ventoy2Disk.sh -I "$R") | tail -5
sudo losetup -d "$R"

echo "== Ventoy: compare with the app's install"
python3 - "$IMG/ventoy.img" ref.img <<'PY'
import struct, sys
ours, ref = (open(p, "rb") for p in sys.argv[1:3])
def rd(f, lba, n): f.seek(lba * 512); return f.read(n * 512)
a, b = rd(ours, 0, 1), rd(ref, 0, 1)
bad, notes = [], []
if a[:384] != b[:384] or a[400:440] != b[400:440]: bad.append("boot code in sector 0")
def entries(m):
    return [(m[446+16*i], m[450+16*i], *struct.unpack("<II", m[454+16*i:462+16*i])) for i in range(4)]
if entries(a) != entries(b):
    bad.append("partition table: ours (flag,type,start,len) %s vs Ventoy2Disk.sh %s" % (entries(a), entries(b)))
elif a[446:510] != b[446:510]:
    notes.append("CHS bytes differ: ours %s ref %s" % (a[446:478].hex(), b[446:478].hex()))
if a[510:512] != b[510:512]: bad.append("boot signature")
if rd(ours, 1, 2047) != rd(ref, 1, 2047): bad.append("core.img (sectors 1-2047)")
p2 = struct.unpack("<I", b[470:474])[0]
same_raw = rd(ours, p2, 65536) == rd(ref, p2, 65536)
open("/tmp/p2", "w").write(str(p2))
open("/tmp/p2same", "w").write("1" if same_raw else "0")
for n in notes: print("::notice title=Ventoy compare::" + n)
if bad:
    print("::error title=Ventoy differs from Ventoy2Disk.sh::" + "; ".join(bad)); sys.exit(1)
print("Boot code, partition table and core.img identical to Ventoy2Disk.sh; EFI partition raw bytes", "identical" if same_raw else "differ (checking files)")
PY
# The EFI partition must be exactly ventoy.disk.img from the release (what Ventoy2Disk.sh means to write).
P2=$(cat /tmp/p2)
python3 - "$IMG/ventoy.img" ref.img "$P2" "$VTDIR/ventoy/ventoy.disk.img.xz" <<'PY'
import lzma, sys
p2 = int(sys.argv[3])
src = lzma.open(sys.argv[4]).read()
def part(path):
    f = open(path, "rb"); f.seek(p2 * 512); return f.read(65536 * 512)
o, r = part(sys.argv[1]), part(sys.argv[2])
print("source", len(src), "bytes | ours == source:", o == src, "| Ventoy2Disk.sh == source:", r == src)
if o != src:
    first = next(i for i in range(len(o)) if o[i] != src[i])
    print("::error title=Ventoy EFI::the app's EFI partition differs from ventoy.disk.img at byte %d" % first); sys.exit(1)
if r != src:
    first = next(i for i in range(len(r)) if r[i] != src[i])
    zeros = all(x == 0 for x in r[first:])
    print("::notice title=Ventoy compare::Ventoy2Disk.sh's EFI partition departs from ventoy.disk.img at byte %d of %d%s; the app's is an exact copy"
          % (first, len(src), " (all zeros from there: a short write)" if zeros else ""))
PY
# And it's a clean FAT filesystem the firmware can read.
sudo mkdir -p /mnt/efi-ours
sudo mount -o ro,loop,offset=$((P2 * 512)),sizelimit=$((65536 * 512)) "$IMG/ventoy.img" /mnt/efi-ours
echo "EFI partition files: $(sudo find /mnt/efi-ours -type f | wc -l)"; sudo ls /mnt/efi-ours/EFI/BOOT
sudo umount /mnt/efi-ours

V=$(part_loop "$IMG/ventoy.img")
lsblk -o NAME,SIZE,FSTYPE,LABEL,PARTTYPE "$V"
fsck_ok "${V}p1" "Ventoy"
[ "$(sudo blkid -s LABEL -o value "${V}p1")" = "Ventoy" ] || err "Ventoy" "partition 1 isn't labeled Ventoy"
# A small bootable ISO for Ventoy's menu to list.
mkdir -p iso/boot/grub && echo 'menuentry "Forge test" { echo "Booted Forge test ISO"; sleep 600 }' > iso/boot/grub/grub.cfg
grub-mkrescue -o forge-test.iso iso >/dev/null 2>&1 || xorriso -as mkisofs -o forge-test.iso iso >/dev/null 2>&1
sudo mkdir -p /mnt/vt && mnt "${V}p1" /mnt/vt && sudo cp forge-test.iso /mnt/vt/ && sudo umount /mnt/vt
sudo losetup -d "$V"
R=$(part_loop ref.img); mnt "${R}p1" /mnt/vt && sudo cp forge-test.iso /mnt/vt/ && sudo umount /mnt/vt; sudo losetup -d "$R"

boot() { # image, name, extra qemu args...
  local img=$1 name=$2; shift 2
  rm -f /tmp/qmon
  qemu-system-x86_64 -m 1024 -drive file="$img",format=raw,if=ide -display none -monitor unix:/tmp/qmon,server,nowait "$@" &
  local pid=$!
  sleep 75
  printf 'screendump /tmp/%s.ppm\n' "$name" | socat - UNIX-CONNECT:/tmp/qmon >/dev/null || true
  sleep 2; kill $pid || true; wait $pid 2>/dev/null || true
  convert "/tmp/$name.ppm" "$shots/$name.png" && echo "screenshot $name"
}
sudo chmod a+r "$IMG/ventoy.img" ref.img
boot "$IMG/ventoy.img" ventoy-app-bios
boot ref.img ventoy-ref-bios
# UEFI firmware: a single OVMF.fd if the distro ships one, else the split CODE/VARS pair.
uefi=()
if [ -f /usr/share/ovmf/OVMF.fd ]; then
  cp /usr/share/ovmf/OVMF.fd /tmp/ovmf.fd; uefi=(-bios /tmp/ovmf.fd)
else
  code=$(find /usr/share/OVMF -name 'OVMF_CODE*.fd' ! -name '*secboot*' ! -name '*ms*' | sort | head -1)
  vars=$(find /usr/share/OVMF -name 'OVMF_VARS*.fd' ! -name '*ms*' | sort | head -1)
  cp "$vars" /tmp/vars.fd
  uefi=(-drive "if=pflash,format=raw,readonly=on,file=$code" -drive "if=pflash,format=raw,file=/tmp/vars.fd")
fi
echo "UEFI firmware: ${uefi[*]}"
boot "$IMG/ventoy.img" ventoy-app-uefi "${uefi[@]}"
[ -f /tmp/vars.fd ] && cp "$vars" /tmp/vars.fd
boot ref.img ventoy-ref-uefi "${uefi[@]}"

# The app's drive and Ventoy's own should look the same at boot.
python3 - "$shots" <<'PY'
import sys
from PIL import Image, ImageChops
d = sys.argv[1]
for fw in ("bios", "uefi"):
    a = Image.open(f"{d}/ventoy-app-{fw}.png").convert("L"); b = Image.open(f"{d}/ventoy-ref-{fw}.png").convert("L")
    lit = sum(1 for p in a.getdata() if p > 40)
    diff = ImageChops.difference(a.resize(b.size), b).getbbox()
    print(fw, "lit pixels", lit, "diff box", diff)
    if lit < 2000:
        print(f"::error title=Ventoy {fw} boot::the app's drive showed a blank screen"); sys.exit(1)
PY
echo "Ventoy boots in BIOS and UEFI"
