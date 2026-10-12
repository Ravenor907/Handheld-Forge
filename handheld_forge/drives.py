"""Find USB drives that are safe to turn into boot drives. Read-only, no privileges."""

from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass

GB = 1000**3

# A disk holding any of these is the running system. Never touch it.
CRITICAL_MOUNTS = {
    "/", "/boot", "/boot/efi", "/efi", "/usr", "/var", "/etc", "/home",
    "/var/home", "/sysroot", "/ostree", "[SWAP]",
}


@dataclass(frozen=True)
class Drive:
    path: str
    name: str  # kernel name, e.g. "sdb"
    size: int
    model: str
    partitions: tuple[str, ...]
    eligible: bool
    reason: str

    @property
    def size_label(self) -> str:
        return f"{self.size / GB:.0f} GB" if self.size >= 10 * GB else f"{self.size / GB:.1f} GB"

    @property
    def label(self) -> str:
        return f"{self.model}  ({self.size_label}, {self.path})"


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
    err = ""
    for mcol in ("MOUNTPOINTS", "MOUNTPOINT"):
        res = subprocess.run(base + [f"{cols},{mcol}"], capture_output=True, text=True)
        if res.returncode == 0:
            return json.loads(res.stdout)
        err = res.stderr.strip()
    raise RuntimeError(f"lsblk failed: {err}")


def evaluate_disk(node: dict) -> tuple[bool, str]:
    """Is this lsblk disk node a safe target? Returns (ok, reason)."""
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


def list_disks(tree: dict | None = None) -> list[Drive]:
    """Every whole disk, each flagged eligible or not with a reason."""
    tree = tree if tree is not None else _run_lsblk()
    out = []
    for node in tree.get("blockdevices", []):
        if node.get("type") != "disk":
            continue
        ok, reason = evaluate_disk(node)
        model = " ".join(filter(None, [(node.get("vendor") or "").strip(),
                                       (node.get("model") or "").strip()])) or node["name"]
        out.append(Drive(
            path=node.get("path") or f"/dev/{node['name']}",
            name=node["name"],
            size=int(node.get("size") or 0),
            model=model,
            partitions=tuple(c["name"] for c in node.get("children") or []),
            eligible=ok,
            reason=reason,
        ))
    return out


def list_drives(include_ineligible: bool = False) -> list[Drive]:
    drives = list_disks()
    return drives if include_ineligible else [d for d in drives if d.eligible]


def find_drive(path: str) -> Drive | None:
    """Fresh, re-checked view of one drive (never trust a stale GUI list)."""
    return next((d for d in list_disks() if d.path == path), None)
