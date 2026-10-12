"""Talk to udisks2 (the desktop's disk service) over D-Bus using `gdbus`.

udisks is what the file manager uses to format and mount USB sticks. For the
logged-in desktop user its default rules allow formatting, partitioning,
mounting and ejecting removable drives with no password. Every call here sets
`auth.no_user_interaction`, so if a system's rules would ever want a password,
the call fails cleanly instead of popping up a prompt.
"""

from __future__ import annotations

import re
import shutil
import subprocess
from collections.abc import Callable

BUS = "org.freedesktop.UDisks2"
ROOT = "/org/freedesktop/UDisks2"
BLOCK = "org.freedesktop.UDisks2.Block"
FILESYSTEM = "org.freedesktop.UDisks2.Filesystem"
PARTITION_TABLE = "org.freedesktop.UDisks2.PartitionTable"
PARTITION = "org.freedesktop.UDisks2.Partition"
DRIVE = "org.freedesktop.UDisks2.Drive"

Runner = Callable[..., subprocess.CompletedProcess]


class UDisksError(Exception):
    def __init__(self, message: str, name: str = "") -> None:
        super().__init__(message)
        self.name = name  # D-Bus error name, e.g. org.freedesktop.UDisks2.Error.NotAuthorized


class NotAuthorized(UDisksError):
    pass


# ------------------------------------------------------------ GVariant text

def gv(value) -> str:
    """Python value -> GVariant text for gdbus arguments."""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return f"uint64 {value}"
    if isinstance(value, str):
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    if isinstance(value, dict):
        if not value:
            return "@a{sv} {}"
        return "{" + ", ".join(f"{gv(k)}: <{gv(v)}>" for k, v in value.items()) + "}"
    raise TypeError(type(value))


_STR = re.compile(r"b?'((?:[^'\\]|\\.)*)'")


def parse_strings(text: str) -> list[str]:
    """All quoted strings (and b'' bytestrings) in gdbus output, unescaped."""
    out = []
    for m in _STR.finditer(text):
        s = m.group(1)
        out.append(re.sub(r"\\(.)", r"\1", s))
    return out


def parse_int(text: str) -> int:
    m = re.search(r"(?:uint64|int64|uint32|int32)?\s*(-?\d+)", text.split("<", 1)[-1])
    if not m:
        raise UDisksError(f"Expected a number in: {text.strip()}")
    return int(m.group(1))


def parse_bool(text: str) -> bool:
    return "true" in text


def object_path_for(kernel_name: str) -> str:
    """/dev/sdb -> /org/freedesktop/UDisks2/block_devices/sdb (udisks escaping)."""
    safe = "".join(c if c.isalnum() else f"_{ord(c):02x}" for c in kernel_name)
    return f"{ROOT}/block_devices/{safe}"


# ------------------------------------------------------------ client

class UDisks:
    NO_PROMPT = {"auth.no_user_interaction": True}

    def __init__(self, run: Runner = subprocess.run) -> None:
        self._run = run

    @staticmethod
    def available() -> bool:
        return shutil.which("gdbus") is not None

    def _gdbus(self, obj: str, method: str, args: list[str], timeout: int = 120) -> str:
        cmd = ["gdbus", "call", "--system", "--dest", BUS, "--object-path", obj,
               "--method", method, "--timeout", str(timeout), *args]
        try:
            res = self._run(cmd, capture_output=True, text=True, timeout=timeout + 15)
        except FileNotFoundError as e:
            raise UDisksError("gdbus is missing (part of glib2), cannot reach udisks") from e
        except subprocess.TimeoutExpired as e:
            raise UDisksError(f"udisks timed out on {method.rsplit('.', 1)[-1]}") from e
        if res.returncode != 0:
            err = (res.stderr or res.stdout).strip()
            m = re.search(r"GDBus\.Error:([\w.]+):\s*(.*)", err, re.S)
            name, msg = (m.group(1), m.group(2).strip()) if m else ("", err)
            if "NotAuthorized" in name or "AccessDenied" in name:
                raise NotAuthorized(msg, name)
            raise UDisksError(msg or f"{method} failed", name)
        return res.stdout

    def call(self, obj: str, iface: str, method: str, *args, timeout: int = 120) -> str:
        return self._gdbus(obj, f"{iface}.{method}", [gv(a) for a in args], timeout)

    def get(self, obj: str, iface: str, prop: str) -> str:
        return self._gdbus(obj, "org.freedesktop.DBus.Properties.Get", [gv(iface), gv(prop)], 30)

    # -------------------------------------------------------- operations

    def is_system_device(self, block: str) -> bool:
        return parse_bool(self.get(block, BLOCK, "HintSystem"))

    def size(self, block: str) -> int:
        return parse_int(self.get(block, BLOCK, "Size"))

    def drive_of(self, block: str) -> str | None:
        paths = parse_strings(self.get(block, BLOCK, "Drive"))
        return paths[0] if paths and paths[0] != "/" else None

    def uuid(self, block: str) -> str:
        vals = parse_strings(self.get(block, BLOCK, "IdUUID"))
        return vals[0] if vals else ""

    def mount_points(self, block: str) -> list[str]:
        try:
            return [p.rstrip("\x00") for p in parse_strings(self.get(block, FILESYSTEM, "MountPoints"))]
        except UDisksError:
            return []  # no filesystem interface

    def unmount(self, block: str) -> None:
        if self.mount_points(block):
            self.call(block, FILESYSTEM, "Unmount", self.NO_PROMPT)

    def mount(self, block: str) -> str:
        try:
            out = self.call(block, FILESYSTEM, "Mount", self.NO_PROMPT)
            return parse_strings(out)[0]
        except UDisksError as e:
            if "AlreadyMounted" in e.name:  # the desktop may automount new partitions
                mps = self.mount_points(block)
                if mps:
                    return mps[0]
            raise

    def format_disk(self, block: str, table: str = "gpt") -> None:
        self.call(block, BLOCK, "Format", table, self.NO_PROMPT, timeout=300)

    def create_partition(self, disk: str, offset: int, size: int, type_guid: str, name: str,
                         fstype: str, fs_options: dict) -> str:
        out = self.call(disk, PARTITION_TABLE, "CreatePartitionAndFormat",
                        offset, size, type_guid, name, self.NO_PROMPT,
                        fstype, {**self.NO_PROMPT, **fs_options}, timeout=900)
        paths = parse_strings(out)
        if not paths:
            raise UDisksError(f"udisks did not return the new partition: {out.strip()}")
        return paths[0]

    def partition_end(self, part: str) -> int:
        return parse_int(self.get(part, PARTITION, "Offset")) + parse_int(self.get(part, PARTITION, "Size"))

    def power_off(self, drive: str) -> None:
        self.call(drive, DRIVE, "PowerOff", self.NO_PROMPT)
