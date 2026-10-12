# Handheld Forge

One click to turn a USB stick into a boot drive for a handheld gaming OS.

Pick the OS, pick the USB drive, press **Create Boot Drive**. Handheld Forge grabs the latest release, proves it is genuine, writes it, and reads the drive back to make sure every byte landed.

| OS | Status |
| --- | --- |
| Bazzite (Handheld, KDE) | Ready |
| SteamOS | Planned |
| CachyOS Handheld | Planned |

## What happens when you press Create

1. **Find the latest release.** Checks the official Bazzite download server for the current `bazzite-deck` (Steam Gaming Mode + KDE) ISO.
2. **Download, only if needed.** ISOs are cached in `~/.cache/handheld-forge`. If you already have the latest one, the download is skipped. Interrupted downloads resume where they stopped.
3. **Verify it.** The SHA256 checksum catches a corrupted download. The ISO's cosign signature is then checked against Bazzite's public key, which is pinned inside this repo, so a tampered ISO is refused even if the download server itself were compromised.
4. **Write it.** After you confirm, the image is written to the drive with admin permission (you get a normal password prompt).
5. **Read it back.** The drive is read back and hashed. If it doesn't match, you're told the stick is bad instead of finding out at boot.
6. **Eject.** The drive is powered off so it's safe to unplug.

## Safety

- Only **USB** drives are ever listed. Internal NVMe/SATA disks and SD cards never show up.
- Any disk holding the running system (`/`, `/boot`, `/var`, `/home`, swap, and so on) is refused, even if it's on USB.
- The privileged writer re-checks the target on its own before writing. It does not trust the window.
- Drives under 16 GB are refused.
- Closing the window mid-write is blocked so you can't end up with a half-written stick by accident.

## Install

Works on Bazzite, SteamOS desktop mode, and most Linux distros. Nothing is installed system-wide.

```bash
git clone https://github.com/Ravenor907/Handheld-Forge.git
cd Handheld-Forge
./install.sh
```

Then open **Handheld Forge** from your app menu.

Needs Python 3.10+ and polkit (`pkexec`). Your account needs an admin password set, since writing a drive asks for it.

## Command line

```bash
handheld-forge                         # open the window
handheld-forge distros                 # list supported OSes
handheld-forge drives --all            # list disks and why any are blocked
handheld-forge download                # just fetch and verify the latest ISO
handheld-forge create --device /dev/sdb
```

## Adding another OS

Every OS is an entry in [`handheld_forge/distros.json`](handheld_forge/distros.json): ISO URL, checksum URL, optional signature URL, and the publisher's public key in `handheld_forge/keys/`. Multiple images can be listed per OS; the first one that exists upstream is used, so a fallback is just a second entry.

## Development

```bash
python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/pytest -v
```

CI runs lint and the full test suite (including a headless GUI check) on every push.

## License

GPL-3.0. Maintained by [Ravenor907](https://github.com/Ravenor907). Not affiliated with the Bazzite, Valve, or CachyOS projects.
