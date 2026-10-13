"""Build a bootable USB from an ISO, with no root and no password.

Instead of raw-imaging the stick (which Linux only lets admins do), this lays
out the drive the way the ISO expects to find itself:

  partition 1  FAT32 EFI System Partition   EFI/ boot files (shim + GRUB)
  partition 2  ext4, labeled like the ISO   every file from the ISO

The ISO's own GRUB config and initramfs look for their files by volume label
(for Bazzite: root=live:CDLABEL=Bazzite-Live), so labeling the ext4 partition
the same makes the stick boot exactly like a flashed one, Secure Boot included.
If a label is too long for ext4 (16 bytes), the boot config is pointed at the
partition's UUID instead.

The experimental "fat" layout, for Arch-based ISOs like CachyOS, puts every file on
one FAT32 partition instead. Their boot loader and initramfs find their files by a
marker file (/boot/<ISO UUID>.uuid) on any drive, which is how archiso supports
copying an ISO onto a stick. It hasn't been tested on a handheld yet.

All disk work goes through udisks, the same service the file manager uses.
"""

from __future__ import annotations

import hashlib
import os
import re
import threading
from collections.abc import Callable
from pathlib import Path

from .drives import GB, Drive, find_drive
from .iso9660 import IsoEntry, IsoReader
from .udisks import NotAuthorized, UDisks, UDisksError, object_path_for

MiB = 1024 * 1024
ESP_SIZE = 512 * MiB
ESP_TYPE = "c12a7328-f81f-11d2-ba4b-00a0c93ec93b"
LINUX_DATA_TYPE = "0fc63daf-8483-4772-8e79-3d69d8477de4"
ESP_LABEL = "FORGE-EFI"
FALLBACK_LABEL = "FORGE-LIVE"
FAT_FILE_LIMIT = 4 * 1024**3 - 1
SYNC_EVERY = 32 * MiB
CHUNK = 4 * MiB

StageFn = Callable[[str, int, int], None]
LogFn = Callable[[str], None]


class BuildError(Exception):
    pass


class Cancelled(Exception):
    pass


def ext4_label_ok(label: str) -> bool:
    return 0 < len(label.encode()) <= 16 and re.fullmatch(r"[A-Za-z0-9._-]+", label) is not None


def rewrite_boot_label(text: str, label: str, uuid: str) -> str:
    """Point GRUB and the live initramfs at a filesystem UUID instead of a label."""
    q = re.escape(label)
    text = re.sub(rf"(search\b[^\n]*?)(?:-l|--label)\s+(['\"]?){q}\2", rf"\1--fs-uuid {uuid}", text)
    text = re.sub(rf"\b(CD)?LABEL={q}(?=[\s'\"]|$)", f"UUID={uuid}", text)
    return text


def fat_label(label: str) -> str:
    """A FAT volume label (at most 11 characters, upper case) as close to the ISO's as allowed."""
    return re.sub(r"[^A-Z0-9_-]", "_", label.upper())[:11] or "FORGE"


def rewrite_archiso_label(text: str, label: str, uuid: str) -> str:
    """Point an archiso boot entry that finds its files by label at a filesystem UUID."""
    return re.sub(rf"\barchisolabel={re.escape(label)}(?=[\s'\"]|$)",
                  f"archisodevice=/dev/disk/by-uuid/{uuid}", text)


def _is_boot_config(path: str) -> bool:
    return path.rsplit("/", 1)[-1].lower().endswith((".cfg", ".conf"))


def _is_efi(path: str) -> bool:
    return path.split("/", 1)[0].upper() == "EFI"


def _safe_join(root: Path, rel: str) -> Path:
    dest = (root / rel).resolve()
    if dest != root.resolve() and root.resolve() not in dest.parents:
        raise BuildError(f"ISO contains an unsafe path: {rel}")
    return dest


def build_drive(
    drive: Drive,
    iso_path: Path,
    stage: StageFn = lambda *_: None,
    log: LogFn = print,
    cancel: threading.Event | None = None,
    udisks: UDisks | None = None,
    recheck: bool = True,
    layout: str = "split",
) -> None:
    cancel = cancel or threading.Event()
    ud = udisks or UDisks()
    if not UDisks.available():
        raise BuildError("gdbus was not found. It ships with every KDE and GNOME desktop.")

    if recheck:
        fresh = find_drive(drive.path)
        if fresh is None or not fresh.eligible:
            raise BuildError(f"{drive.path} is no longer a safe USB target"
                             + (f" ({fresh.reason})" if fresh else ""))
        drive = fresh
    block = object_path_for(drive.name)

    try:
        if ud.is_system_device(block):
            raise BuildError("Your system marks this drive as internal. Refusing to touch it.")

        with open(iso_path, "rb") as f:
            iso = IsoReader(f)
            entries = list(iso.walk())
            files = [e for e in entries if not e.is_dir and e.symlink is None]
            if not any(e.path.upper() == "EFI/BOOT/BOOTX64.EFI" for e in files):
                raise BuildError("This ISO has no UEFI boot loader, so it cannot boot from USB this way.")
            label = iso.volume_id
            fat = layout == "fat"
            if fat:
                big = next((e for e in files if e.size > FAT_FILE_LIMIT), None)
                if big:
                    raise BuildError(f"{big.path} is over 4 GB, too big for this method. "
                                     "Use the regular option, which asks for your password.")
                total = sum(e.size for e in files)
            else:
                total = sum(e.size for e in files) + sum(e.size for e in files if _is_efi(e.path))
            log(f"ISO label {label!r}, {len(files)} files, {total / GB:.1f} GB to copy")

            # ---- partition and format
            stage("prepare", 0, 0)
            for part in drive.partitions:
                ud.unmount(object_path_for(part))
            log("Creating a fresh partition table")
            ud.format_disk(block)
            if fat:
                size = (ud.size(block) - MiB - 2 * MiB) // MiB * MiB
                if size < total * 1.03 + 64 * MiB:
                    raise BuildError("This drive is too small for the image.")
                use_label = fat_label(label)
                log(f"Formatting the drive as one FAT32 partition labeled {use_label!r}")
                parts = {"data": ud.create_partition(block, MiB, size, ESP_TYPE, use_label, "vfat",
                                                     {"label": use_label})}
            else:
                esp = ud.create_partition(block, MiB, ESP_SIZE, ESP_TYPE, "EFI System",
                                          "vfat", {"label": ESP_LABEL})
                start = ud.partition_end(esp)
                size = (ud.size(block) - start - 2 * MiB) // MiB * MiB
                if size < total * 1.08 + 256 * MiB:
                    raise BuildError("This drive is too small for the image.")
                use_label = label if ext4_label_ok(label) else FALLBACK_LABEL
                log(f"Formatting data partition as ext4 labeled {use_label!r}")
                parts = {"esp": esp,
                         "data": ud.create_partition(block, start, size, LINUX_DATA_TYPE, use_label, "ext4",
                                                     {"label": use_label, "take-ownership": True,
                                                      "no-discard": True})}
            if cancel.is_set():
                raise Cancelled()

            # ---- copy
            mounts = {role: Path(ud.mount(part)) for role, part in parts.items()}
            if fat:
                skipped = [e.path for e in entries if e.symlink is not None]
                if skipped:
                    log(f"FAT32 can't hold links, skipped {len(skipped)}: {', '.join(skipped[:3])}")
            record = _copy_tree(iso, entries, mounts.get("esp"), mounts["data"], total, stage, cancel,
                                links=not fat)

            if use_label != label:
                uuid = ud.uuid(parts["data"])
                log(f"The drive can't use the label {label!r}, pointing boot config at UUID {uuid}")
                for rel, by_role in record.items():
                    if not _is_boot_config(rel):
                        continue
                    for role in by_role:
                        path = _safe_join(mounts[role], rel)
                        old = path.read_text(errors="replace")
                        new = rewrite_archiso_label(rewrite_boot_label(old, label, uuid), label, uuid)
                        if new != old:
                            path.write_bytes(new.encode())
                            by_role[role] = (hashlib.sha256(new.encode()).hexdigest(), len(new.encode()))

            stage("sync", 0, 0)
            os.sync()
            for part in parts.values():
                ud.unmount(part)

            # ---- read back from a fresh mount (no page cache)
            mounts = {role: Path(ud.mount(part)) for role, part in parts.items()}
            _verify(record, mounts, stage, cancel)
            for part in parts.values():
                ud.unmount(part)
            log("Every file on the drive matches the ISO")

        stage("eject", 0, 0)
        try:
            drive_obj = ud.drive_of(block)
            if drive_obj:
                ud.power_off(drive_obj)
                log("Drive ejected")
        except UDisksError as e:
            log(f"Could not eject automatically ({e}). Eject it from the file manager.")
    except NotAuthorized as e:
        raise BuildError(
            "Your system wouldn't allow changes to this drive without an admin password, and "
            "Handheld Forge never asks for one. Run it from your desktop session (not over SSH "
            "or from another user) and try again."
        ) from e
    except UDisksError as e:
        raise BuildError(f"The drive could not be prepared: {e}") from e


Record = dict[str, dict[str, tuple[str, int]]]  # relpath -> {role: (sha256, size)}


def _copy_tree(iso: IsoReader, entries: list[IsoEntry], esp: Path | None, data: Path, total: int,
               stage: StageFn, cancel: threading.Event, links: bool = True) -> Record:
    """Copy every entry to the data partition (and EFI/ also to the ESP, if there is one)."""
    done = 0
    record: Record = {}
    for e in entries:
        targets = {"data": data, **({"esp": esp} if esp and _is_efi(e.path) else {})}
        if e.is_dir:
            for root in targets.values():
                _safe_join(root, e.path).mkdir(parents=True, exist_ok=True)
            continue
        if e.symlink is not None:
            if not links:
                continue
            dest = _safe_join(data, e.path)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if not dest.is_symlink():
                os.symlink(e.symlink, dest)
            continue

        outs = []
        for root in targets.values():
            dest = _safe_join(root, e.path)
            dest.parent.mkdir(parents=True, exist_ok=True)
            outs.append(open(dest, "wb"))
        h = hashlib.sha256()
        unsynced = 0
        try:
            for chunk in iso.read_chunks(e, CHUNK):
                if cancel.is_set():
                    raise Cancelled()
                h.update(chunk)
                for o in outs:
                    o.write(chunk)
                done += len(chunk) * len(outs)
                unsynced += len(chunk)
                if unsynced >= SYNC_EVERY:  # keeps progress honest instead of racing the cache
                    for o in outs:
                        o.flush()
                        os.fdatasync(o.fileno())
                    unsynced = 0
                stage("copy", done, total)
        finally:
            for o in outs:
                o.close()
        record[e.path] = {role: (h.hexdigest(), e.size) for role in targets}
    stage("copy", total, total)
    return record


def _verify(record: Record, mounts: dict[str, Path], stage: StageFn, cancel: threading.Event) -> None:
    """Re-read every copied file from a fresh mount and compare hashes."""
    total = sum(size for by_role in record.values() for _, size in by_role.values())
    done = 0
    for rel, by_role in record.items():
        for role, (digest, _size) in by_role.items():
            h = hashlib.sha256()
            with open(_safe_join(mounts[role], rel), "rb") as f:
                while chunk := f.read(CHUNK):
                    if cancel.is_set():
                        raise Cancelled()
                    h.update(chunk)
                    done += len(chunk)
                    stage("check", done, total)
            if h.hexdigest() != digest:
                raise BuildError(f"{rel} did not read back correctly. The drive may be failing "
                                 "or counterfeit. Try a different one.")
    stage("check", total, total)
