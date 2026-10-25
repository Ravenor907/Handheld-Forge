"""Loads the distro catalog (distros.json).

Adding a new OS later means adding an entry to distros.json, not new code.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field, replace
from pathlib import Path

PACKAGE_DIR = Path(__file__).resolve().parent
CATALOG_PATH = PACKAGE_DIR / "distros.json"
LABS_PATH = PACKAGE_DIR / "labs.json"  # shared with the Android app; shown once Labs is on


@dataclass(frozen=True)
class ImageSource:
    label: str
    iso_url: str
    checksum_url: str | None  # None when the publisher has no checksum (SteamOS)
    signature_url: str | None
    require_signature: bool
    # Publishers with no "latest" link (CachyOS names each build by date): a page to read and a
    # pattern whose first group sorts newest-last. The newest match replaces iso_url when it exists.
    latest_page: str | None = None
    latest_pattern: str | None = None

    @property
    def filename(self) -> str:
        return self.iso_url.rstrip("/").rsplit("/", 1)[-1]

    def at(self, iso_url: str) -> ImageSource:
        """The same source pointed at another build; the checksum keeps its suffix."""
        keyed = bool(self.checksum_url) and self.checksum_url.startswith(self.iso_url)
        checksum = iso_url + self.checksum_url[len(self.iso_url):] if keyed else self.checksum_url
        return replace(self, iso_url=iso_url, checksum_url=checksum)


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
    labs: bool = False

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
                latest_page=img.get("latest_page"),
                latest_pattern=img.get("latest_pattern"),
            )
            for img in raw.get("images", [])
        ]
        for img in images:
            if bool(img.latest_page) != bool(img.latest_pattern):
                raise CatalogError(f"{raw['id']}: latest_page and latest_pattern go together")
            for url in (img.iso_url, img.checksum_url, img.signature_url, img.latest_page):
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


def load_labs(path: Path = LABS_PATH) -> list[Distro]:
    """Labs systems (Batocera): hidden until Labs is turned on. Empty if the file is missing."""
    if not Path(path).is_file():
        return []
    return [replace(d, labs=True) for d in load_catalog(path) if d.enabled]


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
    for d in catalog + load_labs():
        if d.id == distro_id:
            return d
    raise KeyError(distro_id)
