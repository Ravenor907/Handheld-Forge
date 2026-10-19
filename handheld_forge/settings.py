"""Labs settings, kept in ~/.config/handheld-forge/settings.json (the Android app keeps the same
three in its preferences). Everything is off until Labs is turned on by tapping the version."""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass, fields
from pathlib import Path


def path() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / "handheld-forge" / "settings.json"


@dataclass
class Settings:
    labs: bool = False
    # Write the image while it downloads (raw systems only); its checksum is checked at the end.
    stream: bool = False
    # Don't read the drive back after writing. Faster, and you find out about a bad drive later.
    skip_check: bool = False

    @property
    def verify(self) -> bool:
        return not (self.labs and self.skip_check)

    @property
    def streaming(self) -> bool:
        return self.labs and self.stream


def load(p: Path | None = None) -> Settings:
    p = p or path()
    try:
        data = json.loads(p.read_text("utf-8"))
    except (OSError, ValueError):
        return Settings()
    names = {f.name for f in fields(Settings)}
    return (
        Settings(**{k: bool(v) for k, v in data.items() if k in names})
        if isinstance(data, dict)
        else Settings()
    )


def save(s: Settings, p: Path | None = None) -> None:
    p = p or path()
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_name(p.name + ".new")
        tmp.write_text(json.dumps(asdict(s), indent=2) + "\n", "utf-8")
        tmp.replace(p)
    except OSError:
        pass  # a setting that doesn't stick isn't worth stopping for
