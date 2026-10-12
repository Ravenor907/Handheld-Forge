#!/usr/bin/env python3
"""Privileged writer for Handheld Forge.

Runs as root through pkexec, so it is deliberately standalone (stdlib only)
and trusts nothing the GUI tells it: it re-checks the target drive itself
before writing a single byte.

Protocol: one JSON object per line on stdout.
  {"stage": "unmount" | "write" | "readback", "done": int, "total": int}
  {"stage": "done"}
  {"error": "message"}
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import subprocess
import sys

CHUNK = 4 * 1024 * 1024
GB = 1000**3

# A disk holding any of these is the running system. Never touch it.
CRITICAL_MOUNTS = {
    "/", "/boot", "/boot/efi", "/efi", "/usr", "/var", "/etc", "/home",
    "/var/home", "/sysroot", "/ostree", "[SWAP]",
}


# ---------------------------------------------------------------- detection

def _bool(v) -> bool:
    return v is True or v in ("1", 1, "true")


def _mounts(node: dict) -> list[str]:
    mps = node.get("mountpoints")
    if mps is None:
        mps = [node.get("mountpoint")]
    return [m for m in mps if m]


def _run_lsblk() -> dict:
    base = ["lsblk", "--json", "--bytes", "-o"]
    cols = "NAME,PATH,SIZE,TYPE,RM,HOTPLUG,TRAN,MODEL,VENDOR,RO"
    for mcol in ("MOUNTPOINTS", "MOUNTPOINT"):
        res = subprocess.run(base + [f"{cols},{mcol}"], capture_output=True, text=True)
        if res.returncode == 0:
            return json.loads(res.stdout)
    raise RuntimeError(f"lsblk failed: {res.stderr.strip()}")


def evaluate_disk(node: dict) -> tuple[bool, str]:
    """Is this lsblk disk node a safe flash target? Returns (ok, reason)."""
    if node.get("type") != "disk":
        return False, "not a whole disk"
    if (node.get("tran") or "").lower() != "usb":
        return False, "not a USB drive"
    if _bool(node.get("ro")):
        return False, "read-only"
    if int(node.get("size") or 0) <= 0:
        return False, "no media"
    all_mounts = _mounts(node)
    for child in node.get("children") or []:
        all_mounts += _mounts(child)
        for grandchild in child.get("children") or []:  # LUKS/LVM on a partition
            all_mounts += _mounts(grandchild)
    hit = sorted(set(all_mounts) & CRITICAL_MOUNTS)
    if hit:
        return False, f"holds the running system ({', '.join(hit)})"
    return True, "ok"


def list_disks(tree: dict | None = None) -> list[dict]:
    """Every disk with an `eligible` flag and a human `reason`."""
    tree = tree if tree is not None else _run_lsblk()
    out = []
    for node in tree.get("blockdevices", []):
        if node.get("type") != "disk":
            continue
        ok, reason = evaluate_disk(node)
        mounts = _mounts(node) + [m for c in node.get("children") or [] for m in _mounts(c)]
        out.append({
            "path": node.get("path") or f"/dev/{node['name']}",
            "size": int(node.get("size") or 0),
            "model": " ".join(filter(None, [(node.get("vendor") or "").strip(),
                                            (node.get("model") or "").strip()])) or node["name"],
            "partitions": [c.get("path") or f"/dev/{c['name']}" for c in node.get("children") or []],
            "mounts": mounts,
            "eligible": ok,
            "reason": reason,
        })
    return out


# ---------------------------------------------------------------- writing

def emit(**kw) -> None:
    sys.stdout.write(json.dumps(kw) + "\n")
    sys.stdout.flush()


def check_target(device: str, iso_size: int, min_gb: int) -> dict:
    st = os.stat(device)
    if not stat.S_ISBLK(st.st_mode):
        raise RuntimeError(f"{device} is not a block device")
    real = os.path.realpath(device)
    disk = next((d for d in list_disks() if os.path.realpath(d["path"]) == real), None)
    if disk is None:
        raise RuntimeError(f"{device} is not a whole disk")
    if not disk["eligible"]:
        raise RuntimeError(f"Refusing {device}: {disk['reason']}")
    if disk["size"] < iso_size:
        raise RuntimeError("Drive is smaller than the image")
    if disk["size"] < min_gb * GB * 0.93:  # "16 GB" sticks are often ~15 GB real
        raise RuntimeError(f"Drive is under {min_gb} GB")
    return disk


def unmount_all(disk: dict) -> None:
    emit(stage="unmount", done=0, total=0)
    for part in disk["partitions"] + [disk["path"]]:
        res = subprocess.run(["findmnt", "-rn", "-o", "TARGET", "-S", part],
                             capture_output=True, text=True)
        for target in res.stdout.split():
            subprocess.run(["umount", target], check=True, capture_output=True, text=True)


def write_image(iso: str, target: str, expected_sha256: str, exclusive: bool = True) -> None:
    """Write `iso` raw to `target`, then read it back and compare hashes.

    `exclusive=False` exists only so tests can target a regular file.
    """
    size = os.path.getsize(iso)
    flags = os.O_WRONLY | os.O_CLOEXEC | (os.O_EXCL if exclusive else 0)
    src_hash = hashlib.sha256()

    fd = os.open(target, flags)
    try:
        with open(iso, "rb") as src:
            done = 0
            while chunk := src.read(CHUNK):
                src_hash.update(chunk)
                view = memoryview(chunk)
                while view:
                    n = os.write(fd, view)
                    view = view[n:]
                done += len(chunk)
                emit(stage="write", done=done, total=size)
        emit(stage="sync", done=0, total=0)
        os.fsync(fd)
    finally:
        os.close(fd)

    if src_hash.hexdigest() != expected_sha256:
        raise RuntimeError("ISO changed on disk while writing. Aborted, drive is incomplete.")

    # Read back what actually landed on the drive, bypassing the page cache.
    fd = os.open(target, os.O_RDONLY | os.O_CLOEXEC)
    try:
        os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
        h = hashlib.sha256()
        left = size
        while left:
            chunk = os.read(fd, min(CHUNK, left))
            if not chunk:
                raise RuntimeError("Drive ended early during read-back")
            h.update(chunk)
            left -= len(chunk)
            emit(stage="readback", done=size - left, total=size)
    finally:
        os.close(fd)
    if h.hexdigest() != expected_sha256:
        raise RuntimeError("Read-back mismatch: the drive did not store the image correctly. "
                           "It may be failing or counterfeit.")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list", help="print candidate disks as JSON")
    w = sub.add_parser("write", help="write an ISO to a USB drive (root)")
    w.add_argument("--iso", required=True)
    w.add_argument("--device", required=True)
    w.add_argument("--sha256", required=True)
    w.add_argument("--min-gb", type=int, default=16)
    a = p.parse_args(argv)

    try:
        if a.cmd == "list":
            print(json.dumps(list_disks(), indent=2))
            return 0
        if os.geteuid() != 0:
            raise RuntimeError("write must run as root (via pkexec)")
        if not os.path.isfile(a.iso):
            raise RuntimeError("ISO not found")
        disk = check_target(a.device, os.path.getsize(a.iso), a.min_gb)
        unmount_all(disk)
        write_image(a.iso, disk["path"], a.sha256.lower())
        subprocess.run(["udevadm", "settle"], capture_output=True)
        emit(stage="done")
        return 0
    except Exception as e:  # report everything back to the GUI as one line
        msg = e.stderr.strip() if isinstance(e, subprocess.CalledProcessError) and e.stderr else str(e)
        emit(error=msg)
        return 1


if __name__ == "__main__":
    sys.exit(main())
