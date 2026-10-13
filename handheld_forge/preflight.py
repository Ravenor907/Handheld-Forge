"""Checks before erasing a drive, and keeping the computer awake while it works.

The same rules as the Android app's Preflight: a job is a multi-GB download
plus a full write and read-back, so it should only start when it's likely to
finish. Required checks block the start; the rest only warn.
"""

from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

MIN_BATTERY = 30
POWER_SUPPLY = Path("/sys/class/power_supply")


@dataclass(frozen=True)
class MachineState:
    battery_percent: int | None  # None: no battery (a desktop) or unknown
    charging: bool
    online: bool | None  # None: NetworkManager didn't say
    unmetered: bool | None


@dataclass(frozen=True)
class Check:
    ok: bool
    required: bool
    title: str
    detail: str


def checks(s: MachineState) -> list[Check]:
    on_power = s.charging or s.battery_percent is None
    battery_ok = on_power or s.battery_percent >= MIN_BATTERY
    if s.battery_percent is None:
        battery_detail = "Running on mains power"
    elif s.charging:
        battery_detail = "Charging"
    elif battery_ok:
        battery_detail = f"At {s.battery_percent}%. Plugging in the charger is still a good idea."
    else:
        battery_detail = f"At {s.battery_percent}%. Plug in the charger first."
    return [
        Check(battery_ok, True, f"Battery {MIN_BATTERY}% or more, or charging", battery_detail),
        Check(s.online is not False, True, "Connected to the internet",
              "Needed to download the latest release" if s.online is not False
              else "Connect to a network first"),
        Check(s.unmetered is not False, False, "On an unmetered connection",
              "Good for a download of several GB" if s.unmetered is not False
              else "This connection is metered (a hotspot or mobile data). The download is several GB."),
    ]


def can_start(items: list[Check], acknowledged: bool) -> bool:
    return acknowledged and all(c.ok or not c.required for c in items)


def explain_network(message: str) -> str:
    return ("The connection dropped. Check your network and try again. "
            f"The download picks up where it left off.\n\n({message})")


# ---------------------------------------------------------------- reading the machine

def _read(p: Path) -> str:
    try:
        return p.read_text().strip()
    except OSError:
        return ""


def read_battery(root: Path = POWER_SUPPLY) -> tuple[int | None, bool]:
    """(percent, on external power). Percent is None when there's no battery."""
    percent, external = None, False
    if not root.is_dir():
        return None, False
    for dev in sorted(root.iterdir()):
        kind = _read(dev / "type")
        if kind == "Battery" and _read(dev / "scope") != "Device":  # skip mice and controllers
            cap = _read(dev / "capacity")
            if cap.isdigit():
                percent = int(cap) if percent is None else min(percent, int(cap))
            if _read(dev / "status") in ("Charging", "Full"):
                external = True
        elif kind in ("Mains", "USB", "USB_PD") and _read(dev / "online") == "1":
            external = True
    return percent, external


def _nm_property(name: str) -> int | None:
    try:
        out = subprocess.run(
            ["gdbus", "call", "--system", "--dest", "org.freedesktop.NetworkManager",
             "--object-path", "/org/freedesktop/NetworkManager",
             "--method", "org.freedesktop.DBus.Properties.Get", "org.freedesktop.NetworkManager", name],
            capture_output=True, text=True, timeout=3,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    digits = "".join(ch for ch in out.stdout.rsplit("uint32", 1)[-1] if ch.isdigit())
    return int(digits) if out.returncode == 0 and digits else None


def read_state() -> MachineState:
    percent, charging = read_battery()
    connectivity = _nm_property("Connectivity")  # 4 full, 3 limited, 2 portal, 1 none, 0 unknown
    metered = _nm_property("Metered")  # 1 yes, 3 guess-yes, 2 no, 4 guess-no, 0 unknown
    return MachineState(
        battery_percent=percent,
        charging=charging,
        online=None if connectivity in (None, 0) else connectivity >= 3,
        unmetered=None if metered in (None, 0) else metered not in (1, 3),
    )


class SleepBlocker:
    """Holds a logind inhibitor so the computer doesn't sleep mid-job (the desktop's wake lock).

    Uses systemd-inhibit, which any active user may run; quietly does nothing
    where it isn't available.
    """

    def __init__(self, why: str = "Writing a USB drive") -> None:
        self.why = why
        self.proc: subprocess.Popen | None = None

    def __enter__(self) -> SleepBlocker:
        exe = shutil.which("systemd-inhibit")
        if exe:
            try:
                self.proc = subprocess.Popen(
                    [exe, "--what=sleep:idle", "--who=Handheld Forge", f"--why={self.why}",
                     "--mode=block", "sleep", "infinity"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
            except OSError:
                self.proc = None
        return self

    def __exit__(self, *_) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.proc.kill()
