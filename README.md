# Handheld Forge

Pick a system, pick a USB drive, press **Create boot drive**. No terminal, no password.

| System | Status |
| --- | --- |
| Bazzite (Handheld KDE) | Ready |
| SteamOS | Planned |
| CachyOS Handheld | Planned |

## What it does

1. **Gets the latest release** from the official Bazzite server. It's cached in `~/.cache/handheld-forge`, so if you already have the newest one it isn't downloaded again, and an interrupted download resumes.
2. **Proves it's genuine.** The SHA256 checksum catches a corrupted download. The cosign signature, checked against Bazzite's public key pinned in this repo, catches a tampered one.
3. **Builds the drive** (see below), then unmounts it, mounts it fresh and **reads every file back** to confirm it landed intact.
4. **Ejects it**, ready to plug into your handheld.

## Why it never asks for a password

Raw-imaging a USB stick (what `dd` does) is something Linux only lets an admin do, which is why some tools pop up a password prompt.

Handheld Forge doesn't raw-image. It asks the desktop's own disk service (udisks, the one your file manager uses) to do the things any logged-in user is already allowed to do with a removable drive: format it, mount it, copy files to it. The drive ends up with:

- a small **FAT32 EFI partition** with the boot loader (shim and GRUB, still Secure Boot signed)
- an **ext4 partition labeled `Bazzite-Live`** holding every file from the ISO

Bazzite's live boot looks for its files by that label, so the stick boots exactly like a flashed one. If an ISO ever has a label too long for ext4, the boot config is pointed at the partition's UUID instead.

Every call tells the system *never* to prompt. If a system's rules would require a password anyway, Handheld Forge stops and says so instead of showing a dialog.

## Safety

- Only **USB** drives are listed. Internal NVMe/SATA disks and SD cards never appear.
- Any disk holding the running system (`/`, `/boot`, `/var`, `/home`, swap and so on) is refused, even on USB, and the drive is re-checked right before anything is erased.
- Erasing always takes a second, explicit confirmation that names the drive.

## Install

Works on Bazzite, SteamOS desktop mode and most Linux desktops. Nothing is installed system-wide.

```bash
git clone https://github.com/Ravenor907/Handheld-Forge.git
cd Handheld-Forge
./install.sh
```

Then open **Handheld Forge** from your app menu. Needs Python 3.10+.

## Command line

```bash
handheld-forge                          # open the window
handheld-forge distros                  # list systems
handheld-forge drives --all             # list disks and why any are blocked
handheld-forge download                 # fetch and verify the latest ISO only
handheld-forge create --device /dev/sdb
```

## Adding another system

Each system is an entry in [`handheld_forge/distros.json`](handheld_forge/distros.json): ISO URL, checksum URL, optional signature URL, and the publisher's public key in `handheld_forge/keys/`. List several images per system and the first one that exists upstream is used.

## Development

```bash
python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/pytest -v
```

CI runs the unit tests, a headless render of every screen, and a full build through a real udisks daemon onto a loop device using an ISO made with Bazzite's own xorriso settings (including a file over 4 GB). The result is compared file by file against the kernel's mount of the ISO.

## License

GPL-3.0. Maintained by [Ravenor907](https://github.com/Ravenor907). Not affiliated with the Bazzite, Valve or CachyOS projects.
