"""Loads the distro catalog (distros.json).

Adding a new OS later means adding an entry to distros.json, not new code.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

PACKAGE_DIR = Path(__file__).resolve().parent
CATALOG_PATH = PACKAGE_DIR / "distros.json"


@dataclass(frozen=True)
class ImageSource:
    label: str
    iso_url: str
    checksum_url: str | None  # None when the publisher has no checksum (SteamOS)
    signature_url: str | None
    require_signature: bool

    @property
    def filename(self) -> str:
        return self.iso_url.rstrip("/").rsplit("/", 1)[-1]


@dataclass(frozen=True)
class Distro:
    id: str
    name: str
    edition: str
    enabled: bool
    min_drive_gb: int
    public_key: Path | None
    images: list[ImageSource] = field(default_factory=list)
    format: str = "iso"
    # How the desktop app makes the drive: "copy" lays out partitions and copies the ISO's
    # files (no password); "raw" writes the image byte for byte, which Linux only allows
    # after the admin password (SteamOS's disk image, CachyOS's ISO).
    write: str = "copy"
    # What to do after booting the drive, and anything to know before erasing it.
    install_steps: list[str] = field(default_factory=list)
    warning: str | None = None

    @property
    def needs_password(self) -> bool:
        return self.write == "raw"


class CatalogError(ValueError):
    pass


def load_catalog(path: Path = CATALOG_PATH) -> list[Distro]:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if data.get("schema") != 1:
        raise CatalogError(f"Unsupported catalog schema: {data.get('schema')!r}")

    distros: list[Distro] = []
    seen: set[str] = set()
    for raw in data["distros"]:
        if raw["id"] in seen:
            raise CatalogError(f"Duplicate distro id: {raw['id']}")
        seen.add(raw["id"])

        images = [
            ImageSource(
                label=img["label"],
                iso_url=img["iso_url"],
                checksum_url=img.get("checksum_url"),
                signature_url=img.get("signature_url"),
                require_signature=bool(img.get("require_signature", False)),
            )
            for img in raw.get("images", [])
        ]
        for img in images:
            for url in (img.iso_url, img.checksum_url, img.signature_url):
                if url is not None and not url.startswith("https://"):
                    raise CatalogError(f"{raw['id']}: only https URLs are allowed ({url})")
            if img.require_signature and not img.signature_url:
                raise CatalogError(f"{raw['id']}: require_signature set but no signature_url")

        key = raw.get("public_key")
        key_path = (PACKAGE_DIR / key) if key else None
        if any(i.require_signature for i in images) and (key_path is None or not key_path.is_file()):
            raise CatalogError(f"{raw['id']}: signature required but public key missing")
        if raw.get("enabled") and not images:
            raise CatalogError(f"{raw['id']}: enabled but has no images")

        distros.append(
            Distro(
                id=raw["id"],
                name=raw["name"],
                edition=raw.get("edition", ""),
                enabled=bool(raw.get("enabled", False)),
                format=raw.get("format", "iso"),
                write="raw" if raw.get("format", "iso") != "iso" or raw.get("write") == "raw" else "copy",
                install_steps=list(raw.get("install_steps", [])),
                warning=raw.get("warning"),
                min_drive_gb=int(raw.get("min_drive_gb", 16)),
                public_key=key_path,
                images=images,
            )
        )
    return distros


def load_boot_menu(path: Path = CATALOG_PATH) -> list[str]:
    """How to open the boot menu on common handhelds; the same for every system."""
    return list(json.loads(Path(path).read_text(encoding="utf-8")).get("boot_menu", []))


def install_guide(distro: Distro, boot_menu: list[str] | None = None) -> str:
    """Plain-text "what now" for a finished drive: opening the boot menu, then the installer."""
    menu = load_boot_menu() if boot_menu is None else boot_menu
    parts = []
    if menu:
        parts.append("Open the boot menu:\n" + "\n".join(f"- {line}" for line in menu))
    if distro.install_steps:
        parts.append("Then:\n" + "\n".join(f"{i}. {s}" for i, s in enumerate(distro.install_steps, 1)))
    return "\n\n".join(parts)


def get_distro(distro_id: str, catalog: list[Distro] | None = None) -> Distro:
    for d in catalog if catalog is not None else load_catalog():
        if d.id == distro_id:
            return d
    raise KeyError(distro_id)
