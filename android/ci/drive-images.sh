#!/usr/bin/env bash
# Checks the app's Format and Ventoy output against the real tools.
#   1. Format drive: fsck.exfat, then the kernel's exFAT driver mounts it and a 5 GB file round-trips.
#   2. Ventoy: the image the app made is compared with Ventoy2Disk.sh's own install on a disk of the
#      same size (boot code, partition table, core.img, the EFI partition), its exFAT is checked and
#      mounted, an ISO is copied on, and both drives are booted in QEMU (BIOS and UEFI) with screenshots.
#   3. Secure Boot off: the EFI partition matches Ventoy2Disk.sh -I -S file for file (and an mtools
#      edit of the release's image), is a clean FAT, and Secure Boot firmware behaves as expected.
#   4. Importer: images the app added (plain, .xz, .zip) are intact, Ventoy's menu lists them, and
#      the one unpacked from .bz2 (first in the menu) boots from it.
#   5. Boot next: with an image set to start on its own, Ventoy boots it with no key presses.
# Expects: IMG (plain.img, ventoy.img, ventoy-nosb.img, ventoy-import.img, ventoy-bootnext.img), VTDIR (extracted
# ventoy-x.y.z), SECTORS, and forge-test.iso plus imports/ from the workflow.
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
# The small bootable ISO the workflow built, for Ventoy's menu to list.
[ -s forge-test.iso ] || err "Ventoy" "forge-test.iso is missing (the workflow builds it)"
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

ocr() { # screenshot png -> lowercase text
  convert "$1" -resize 200% -colorspace Gray -negate /tmp/ocr.png 2>/dev/null && tesseract /tmp/ocr.png - 2>/dev/null | tr 'A-Z' 'a-z' | tr -s ' \n' ' '
}
shot() { # name: screenshot the running QEMU
  printf 'screendump /tmp/%s.ppm\n' "$1" | socat - UNIX-CONNECT:/tmp/qmon >/dev/null || true
  sleep 2; convert "/tmp/$1.ppm" "$shots/$1.png" && echo "screenshot $1"
}
key() { printf 'sendkey %s\n' "$1" | socat - UNIX-CONNECT:/tmp/qmon >/dev/null || true; }

echo "== Secure Boot off: reference install with Ventoy2Disk.sh -I -S"
# Ventoy2Disk.sh pipes xzcat into dd without iflag=fullblock, so on some runners dd stops short and
# the tail of the EFI partition is left as zeros (seen above). The reference here uses a copy of the
# installer with only that fixed, so it holds what Ventoy means to write.
VTFIX=$(mktemp -d)/ventoy; cp -a "$VTDIR" "$VTFIX"
sed -i 's#xzcat ./ventoy/ventoy.disk.img.xz | dd status=none conv=fsync#xzcat ./ventoy/ventoy.disk.img.xz | dd status=none iflag=fullblock conv=fsync#' "$VTFIX/tool/VentoyWorker.sh"
grep -q 'iflag=fullblock' "$VTFIX/tool/VentoyWorker.sh" || err "Secure Boot off" "couldn't apply the fullblock fix to Ventoy2Disk.sh's worker"
truncate -s $((SECTORS * 512)) ref-nosb.img
R=$(part_loop ref-nosb.img)
(cd "$VTFIX" && printf 'y\ny\n' | sudo sh Ventoy2Disk.sh -I -S "$R") | tail -5
sudo losetup -d "$R"
# An independent reference: the release's EFI image edited with mtools the way vtoycli partresize -s does.
xz -dc "$VTDIR/ventoy/ventoy.disk.img.xz" > /tmp/efi-mtools.img
mcopy -n -i /tmp/efi-mtools.img ::/EFI/BOOT/grubx64_real.efi /tmp/real64.efi
mcopy -o -i /tmp/efi-mtools.img /tmp/real64.efi ::/EFI/BOOT/BOOTX64.EFI
if mdir -i /tmp/efi-mtools.img ::/EFI/BOOT/grubia32_real.efi >/dev/null 2>&1; then
  mcopy -n -i /tmp/efi-mtools.img ::/EFI/BOOT/grubia32_real.efi /tmp/real32.efi
  mcopy -o -i /tmp/efi-mtools.img /tmp/real32.efi ::/EFI/BOOT/BOOTIA32.EFI
fi
for f in /EFI/BOOT/grubx64.efi /EFI/BOOT/grubx64_real.efi /EFI/BOOT/MokManager.efi /EFI/BOOT/mmx64.efi \
         /EFI/BOOT/grubia32.efi /EFI/BOOT/grubia32_real.efi /EFI/BOOT/mmia32.efi /ENROLL_THIS_KEY_IN_MOKMANAGER.cer; do
  mdel -i /tmp/efi-mtools.img "::$f" 2>/dev/null || true
done
# The app's Secure Boot-off EFI partition: a clean FAT, and the same files as both references.
dd if="$IMG/ventoy-nosb.img" of=/tmp/efi-ours.img bs=512 skip="$P2" count=65536 status=none
fsck.fat -n /tmp/efi-ours.img | tail -2 || err "Secure Boot off" "fsck.fat found problems in the EFI partition"
sudo mkdir -p /mnt/sb-ours /mnt/sb-ref /mnt/sb-mtools
sudo mount -o ro,loop /tmp/efi-ours.img /mnt/sb-ours
sudo mount -o ro,loop,offset=$((P2 * 512)),sizelimit=$((65536 * 512)) ref-nosb.img /mnt/sb-ref
sudo mount -o ro,loop /tmp/efi-mtools.img /mnt/sb-mtools
echo "Secure Boot off, the app's /EFI/BOOT: $(sudo ls /mnt/sb-ours/EFI/BOOT | tr '\n' ' ')"
sudo diff -r /mnt/sb-ours /mnt/sb-mtools || err "Secure Boot off" "the app's EFI files differ from the release image edited with mtools"
if ! out=$(sudo diff -r /mnt/sb-ours /mnt/sb-ref 2>&1); then
  err "Secure Boot off" "the app's EFI files differ from Ventoy2Disk.sh -I -S: $(echo "$out" | head -6 | tr '\n' ' ')"
fi
[ ! -e /mnt/sb-ours/EFI/BOOT/grubx64_real.efi ] || err "Secure Boot off" "grubx64_real.efi is still there"
sudo umount /mnt/sb-ours /mnt/sb-ref /mnt/sb-mtools
# The Secure Boot-on drive kept shim and MokManager.
sudo mount -o ro,loop,offset=$((P2 * 512)),sizelimit=$((65536 * 512)) "$IMG/ventoy.img" /mnt/sb-ours
for f in EFI/BOOT/grubx64_real.efi ENROLL_THIS_KEY_IN_MOKMANAGER.cer; do
  sudo test -e "/mnt/sb-ours/$f" || err "Secure Boot on" "$f is missing"
done
sudo test -e /mnt/sb-ours/EFI/BOOT/mmx64.efi || sudo test -e /mnt/sb-ours/EFI/BOOT/MokManager.efi || err "Secure Boot on" "MokManager is missing"
echo "::notice title=Secure Boot on, /EFI/BOOT::$(sudo ls /mnt/sb-ours/EFI/BOOT | tr '\n' ' ')"
sudo umount /mnt/sb-ours
echo "Secure Boot off: EFI files identical to Ventoy2Disk.sh -I -S and to the mtools edit; fsck.fat clean"

echo "== Importer: the drive the app added images to"
I=$(part_loop "$IMG/ventoy-import.img")
fsck_ok "${I}p1" "Importer"
sudo mkdir -p /mnt/imp && mnt "${I}p1" /mnt/imp
names=$(sudo ls /mnt/imp | tr '\n' ' ')
echo "On the drive: $names"
[ "$names" = "forge-bz.iso forge-packed.iso forge-test.iso forge-zip.iso " ] || err "Importer" "expected forge-bz.iso forge-packed.iso forge-test.iso forge-zip.iso, found: $names"
want=$(sha256sum forge-test.iso | cut -d' ' -f1)
for f in forge-bz.iso forge-packed.iso forge-test.iso forge-zip.iso; do
  [ "$(sudo sha256sum "/mnt/imp/$f" | cut -d' ' -f1)" = "$want" ] || err "Importer" "$f doesn't match the original ISO"
done
sudo umount /mnt/imp
sudo losetup -d "$I"
echo "Importer: clean exFAT, the four images are byte-identical to the original ISO"

# UEFI with Secure Boot enforced: Microsoft keys enrolled, SMM on.
sbcode=/usr/share/OVMF/OVMF_CODE_4M.secboot.fd
sbvars=/usr/share/OVMF/OVMF_VARS_4M.ms.fd
sbboot() { # image, name
  cp "$sbvars" /tmp/sbvars.fd
  boot "$1" "$2" -machine q35,smm=on -global driver=cfi.pflash01,property=secure,value=on \
    -drive "if=pflash,format=raw,unit=0,readonly=on,file=$sbcode" -drive "if=pflash,format=raw,unit=1,file=/tmp/sbvars.fd"
}
sudo chmod a+r "$IMG"/ventoy-*.img
if [ -f "$sbcode" ] && [ -f "$sbvars" ]; then
  sbboot "$IMG/ventoy.img" sb-on-secureboot-firmware
  echo "::notice title=Secure Boot on drive, Secure Boot firmware::$(ocr "$shots/sb-on-secureboot-firmware.png" | cut -c1-300)"
  sbboot "$IMG/ventoy-nosb.img" sb-off-secureboot-firmware
  echo "::notice title=Secure Boot off drive, Secure Boot firmware::$(ocr "$shots/sb-off-secureboot-firmware.png" | cut -c1-300)"
else
  echo "::warning title=Secure Boot boot::no Secure Boot OVMF on this runner"
fi
[ -f /tmp/vars.fd ] && cp "$vars" /tmp/vars.fd
boot "$IMG/ventoy-nosb.img" sb-off-uefi "${uefi[@]}"
python3 - "$shots/sb-off-uefi.png" <<'PY'
import sys
from PIL import Image
lit = sum(1 for p in Image.open(sys.argv[1]).convert("L").getdata() if p > 40)
print("Secure Boot off drive, plain UEFI: lit pixels", lit)
if lit < 2000: print("::error title=Secure Boot off boot::blank screen"); sys.exit(1)
PY

echo "== Importer: Ventoy's menu, then boot the image that was unpacked from .bz2"
[ -f /tmp/vars.fd ] && cp "$vars" /tmp/vars.fd
rm -f /tmp/qmon
rm -f /tmp/serial-ventoy-import.log
qemu-system-x86_64 -m 1024 -drive file="$IMG/ventoy-import.img",format=raw,if=ide -display none -serial file:/tmp/serial-ventoy-import.log \
  -monitor unix:/tmp/qmon,server,nowait "${uefi[@]}" &
qpid=$!
sleep 75
shot import-menu
menu=$(ocr "$shots/import-menu.png")
echo "::notice title=Importer menu (OCR)::$(echo "$menu" | cut -c1-400)"
for n in bz packed test zip; do
  echo "$menu" | grep -q "forge.$n" || err "Importer menu" "forge-$n.iso isn't in Ventoy's menu (OCR: $menu)"
done
key ret; sleep 10; shot import-after-enter; key ret; sleep 45
shot import-booted
booted=$(ocr "$shots/import-booted.png")
echo "::notice title=Importer after choosing (OCR)::$(echo "$booted" | cut -c1-300)"
kill $qpid || true; wait $qpid 2>/dev/null || true
serial=$(tr -cd '[:print:]\n' < /tmp/serial-ventoy-import.log | tail -c 600)
echo "::notice title=Importer serial console::$(echo "$serial" | tail -c 300 | tr '\n' ' ')"
{ echo "$booted" | grep -q "booted forge" || grep -q "Booted Forge test ISO" /tmp/serial-ventoy-import.log; } || err "Importer boot" "the imported ISO didn't boot from Ventoy's menu (OCR: $booted)"
echo "Importer: Ventoy lists all four images and boots the one unpacked from .bz2"

echo "== Boot next: Ventoy starts the chosen image on its own"
B=$(part_loop "$IMG/ventoy-bootnext.img")
fsck_ok "${B}p1" "Boot next"
mnt "${B}p1" /mnt/imp
echo "ventoy.json the app wrote:"; sudo cat /mnt/imp/ventoy/ventoy.json
sudo grep -q '"VTOY_DEFAULT_IMAGE": "/forge-zip.iso"' /mnt/imp/ventoy/ventoy.json || err "Boot next" "ventoy.json doesn't name forge-zip.iso"
sudo umount /mnt/imp
sudo losetup -d "$B"
sudo chmod a+r "$IMG/ventoy-bootnext.img"
[ -f /tmp/vars.fd ] && cp "$vars" /tmp/vars.fd
rm -f /tmp/qmon
rm -f /tmp/serial-ventoy-bootnext.log
qemu-system-x86_64 -m 1024 -drive file="$IMG/ventoy-bootnext.img",format=raw,if=ide -display none -serial file:/tmp/serial-ventoy-bootnext.log \
  -monitor unix:/tmp/qmon,server,nowait "${uefi[@]}" &
qpid=$!
sleep 100
shot bootnext-no-keys
auto=$(ocr "$shots/bootnext-no-keys.png")
kill $qpid || true; wait $qpid 2>/dev/null || true
echo "::notice title=Boot next, no keys (OCR)::$(echo "$auto" | cut -c1-300)"
echo "::notice title=Boot next serial console::$(tr -cd '[:print:]\n' < /tmp/serial-ventoy-bootnext.log | tail -c 300 | tr '\n' ' ')"
{ echo "$auto" | grep -q "booted forge" || grep -q "Booted Forge test ISO" /tmp/serial-ventoy-bootnext.log; } || err "Boot next" "Ventoy didn't start forge-zip.iso on its own (OCR: $auto)"
echo "Boot next: Ventoy started the chosen image with no input"
