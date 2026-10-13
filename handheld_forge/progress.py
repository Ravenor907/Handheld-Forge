"""Overall progress for a whole job (download, verify, prepare, copy or write, check).

The engine reports stages as (name, done, total). Some stages can't measure
anything (formatting, finishing writes, ejecting) and report 0 of 0, and a few
numbers can briefly be odd (a download that restarts, a size that changes). This
turns all of that into one percentage that only ever moves forward, stays between
0 and 100, and a time estimate based on the step's average speed so it settles
instead of jumping around.
"""

from __future__ import annotations

import time

STEP_OF = {"lookup": 0, "download": 0, "verify": 1, "prepare": 2, "copy": 3, "write": 3, "sync": 3,
           "check": 4, "eject": 4}
WEIGHTS = (40, 8, 4, 36, 12)  # roughly how long each step takes
MEASURED = ("download", "verify", "copy", "write", "check")


class Progress:
    def __init__(self, clock=time.monotonic) -> None:
        self.clock = clock
        self.step = 0
        self.fraction = 0.0  # of the whole job, 0..1, never decreasing
        self._stage = ""
        self._start: tuple[float, int] | None = None  # (time, done) when the stage started

    @property
    def stage(self) -> str:
        return self._stage

    @property
    def elapsed(self) -> float:
        """Seconds since the current stage began."""
        return self.clock() - self._start[0] if self._start else 0.0

    @property
    def percent(self) -> int:
        return min(100, max(0, int(self.fraction * 100)))

    def update(self, stage: str, done: int, total: int) -> None:
        # Never go back a step: a late report from an earlier stage changes nothing.
        self.step = max(self.step, STEP_OF.get(stage, self.step))
        start = sum(WEIGHTS[: self.step])
        if total > 0:
            part = min(1.0, max(0.0, done / total))
            value = (start + WEIGHTS[self.step] * part) / sum(WEIGHTS)
        else:
            value = start / sum(WEIGHTS)  # nothing to measure: the step has at least begun
        self.fraction = min(1.0, max(self.fraction, value))
        if stage != self._stage or (self._start and done < self._start[1]):
            self._stage, self._start = stage, (self.clock(), max(0, done))

    def finish(self) -> None:
        self.step, self.fraction = len(WEIGHTS) - 1, 1.0

    def time_left(self, done: int, total: int) -> str:
        """'about 4 min left' from the stage's average speed, or '' when there's no fair estimate."""
        if self._stage not in MEASURED or not self._start or total <= 0 or not 0 <= done <= total:
            return ""
        t0, d0 = self._start
        elapsed = self.clock() - t0
        if elapsed < 3 or done <= d0:
            return ""
        seconds = (total - done) / ((done - d0) / elapsed)
        if seconds < 60:
            return "less than a minute left"
        return f"about {round(seconds / 60)} min left"


STEP_NAMES = ("Download", "Verify", "Prepare", "Copy", "Check")

# What each stage is called, and what to say while it can't show a measurement.
_HEADLINE = {
    "lookup": "Checking for the latest release",
    "download": "Downloading {name}",
    "verify": "Checking that the download is genuine",
    "prepare": "Preparing the drive",
    "copy": "Copying files to the drive",
    "write": "Writing the image to the drive",
    "sync": "Finishing writes",
    "check": "Reading the drive back to confirm",
    "eject": "Ejecting the drive",
}
_WAITING = {
    "lookup": "Asking the download server which version is newest.",
    "verify": "Comparing it with the publisher's checksum.",
    "prepare": "Erasing it and creating fresh partitions. This takes a few seconds.",
    "sync": "Making sure everything has reached the drive. Slow drives can take a few minutes.",
    "check": "Comparing every byte on the drive with the image.",
    "eject": "Almost done.",
}


def clock_text(seconds: float) -> str:
    s = max(0, int(seconds))
    return f"{s // 60}:{s % 60:02d}"


def describe(stage: str, done: int, total: int, elapsed: float, needs_password: bool = False,
             name: str = "", time_left: str = "") -> tuple[str, str]:
    """(headline, detail) for the work screen.

    The headline says which of the five steps this is and what it does. The detail
    has the amount and time left when the step can measure itself; otherwise it
    explains what's going on, with a running timer after a few seconds so the
    screen never looks frozen.
    """
    step = STEP_OF.get(stage, 0)
    label = _HEADLINE.get(stage, stage).format(name=name or "the system")
    if needs_password and stage == "prepare":
        label = "Waiting for your password"
    headline = f"Step {step + 1} of {len(STEP_NAMES)}: {label}"
    if total > 0 and stage in ("download", "copy", "write", "verify", "check"):
        gb = 1_000_000_000
        detail = f"{min(done, total) / gb:.1f} of {total / gb:.1f} GB"
        return headline, detail + (f", {time_left}" if time_left else "")
    if needs_password and stage == "prepare":
        detail = "Type your password in the window Linux opened. It allows writing this drive, nothing else."
    else:
        detail = _WAITING.get(stage, "Working on it.")
    if elapsed >= 5:
        detail += f" ({clock_text(elapsed)})"
    return headline, detail
