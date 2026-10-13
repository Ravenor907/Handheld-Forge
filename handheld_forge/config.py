"""Loads the distro catalog (distros.json).

Adding a new OS later means adding an entry to distros.json, not new code.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field, replace
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
    # "split": an EFI partition plus an ext4 copy of the ISO (Bazzite). "fat": everything on
    # one FAT32 partition, which Arch-based ISOs like CachyOS can find by their marker file.
    layout: str = "split"
    # A password-free way to make this system's drive that hasn't been proven to boot yet.
    experimental_copy: str | None = None
    experimental: bool = False

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
                experimental_copy=raw.get("experimental_copy"),
                min_drive_gb=int(raw.get("min_drive_gb", 16)),
                public_key=key_path,
                images=images,
            )
        )
    return distros


def experimental_variants(catalog: list[Distro]) -> list[Distro]:
    """Password-free versions of byte-for-byte systems, built by copying files instead.

    They aren't proven to boot, so they're listed apart from the regular choices.
    """
    out = []
    for d in catalog:
        if not (d.enabled and d.needs_password and d.experimental_copy and d.format == "iso"):
            continue
        steps = [*d.install_steps, f"If the drive doesn't start, make it again with the regular "
                                   f"{d.name} option. That one asks for your password but is known to work."]
        out.append(replace(
            d, id=f"{d.id}-copy", edition="No password", write="copy", layout="fat",
            experimental=True, install_steps=steps,
            warning="\n\n".join(filter(None, [d.experimental_copy, d.warning])),
        ))
    return out


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
    catalog = catalog if catalog is not None else load_catalog()
    for d in catalog + experimental_variants(catalog):
        if d.id == distro_id:
            return d
    raise KeyError(distro_id)
