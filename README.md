# Handheld Forge

Pick a system, pick a USB drive, press **Create boot drive**. No terminal, no password.

| System | Linux desktop | Android |
| --- | --- | --- |
| Bazzite (Handheld KDE) | Ready | Ready |
| SteamOS (Steam Deck recovery image) | Use Android | Ready |
| CachyOS Handheld | Use Android | Ready |

Both apps work the same way: a short "Before you start" check (battery, internet, metered connection) and a tick box to leave it alone, a reminder while it works, Stop asks before stopping, downloads resume through dropped connections, and when the drive is ready they show how to open the boot menu and get through that system's installer.

## What it does

1. **Gets the latest release** from the official Bazzite server. It's cached in `~/.cache/handheld-forge`, so if you already have the newest one it isn't downloaded again, and an interrupted download resumes, including after a dropped connection. It checks for free space first, and keeps the computer from sleeping until the drive is done.
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

That's also why SteamOS and CachyOS are Android only. SteamOS's recovery image is a whole disk image, and CachyOS expects its ISO written as is, so both need a byte-for-byte write, which Linux only allows with an admin password. The phone talks to the stick directly over USB, so it can write them without one.

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

Run these in a terminal (Konsole in desktop mode); double-clicking `install.sh` in a file manager won't run it. Then open **Handheld Forge** from your app menu. Needs Python 3.10+.

If Handheld Forge doesn't show up in the app launcher (under Utilities), run `handheld-forge fix-menu`. It rewrites the menu entry, removes any saved launcher edit that hides it (KDE keeps those even after an uninstall), rebuilds KDE's launcher cache and says what it found, for example that the install ran inside a distrobox or toolbox container, whose apps don't reach your desktop's menu.

### Update

In the app, click the version number at the top right and choose **Check for updates**. You can also right-click Handheld Forge in your app menu and choose **Update Handheld Forge**, or run `handheld-forge update`. It tells you which version you have and which is newest, and reinstalls even when the version number is the same.

Running `./install.sh` again in your copy also updates: it pulls the latest code first.

### Uninstall

Click the version number at the top right and choose **Uninstall Handheld Forge**, or right-click it in your app menu and choose **Uninstall**. From a terminal: `handheld-forge uninstall`, or `./install.sh --uninstall` in your copy (which works even if the install is broken).

It lists everything it will delete, with sizes, and asks once. Then it removes it all, leaving nothing behind:

- the app and its Python libraries (`~/.local/share/handheld-forge`)
- the app menu entry and the `handheld-forge` command
- downloaded system images (`~/.cache/handheld-forge`)
- the copy of this code you installed from (the folder you cloned)

Installing never leaves files in pip's download cache, so there's nothing to clean up there. Add `--keep-downloads` or `--keep-source` to keep those. Your USB drives aren't touched.

## Android

There's also an Android app in [`android/`](android/). It makes the same boot drive from a phone: plug a USB stick into the phone with a USB-C adapter, pick a system, tap **Create boot drive**.

- **Download:** [the latest APK is on the Releases page](https://github.com/Ravenor907/Handheld-Forge/releases). Android will ask you to allow installs from your browser or file manager the first time.
- **No root.** The app talks to the stick directly over USB, writes the verified image, then reads the whole drive back to confirm it.
- **One Android prompt.** The first time you plug a stick in, Android asks whether Handheld Forge may access it. Tick "always" and it won't ask again for that stick. That's Android's own USB permission; every app gets it.
- **Space:** about 10 GB free on the phone for the download.
- **Leave the phone alone while it works.** Android pauses or cuts off apps it thinks you've stopped using, which can stop the download or leave the drive half written. Before it erases anything, the app checks a short list and asks you to agree to leave it be:
  - Battery at 30% or more, or charging (required: the phone also powers the drive)
  - Connected to the internet (required)
  - On Wi-Fi rather than mobile data (recommended: the download is several GB)
  - Allowed to run in the background (recommended: tap it to turn off battery optimization for the app)

  While it works, the screen stays on and a reminder stays at the top. If you leave anyway, it keeps going with a notification and warns you on the way out; if Android does interrupt it, the error says so, and the download resumes where it left off next time.
- **Install help.** When the drive is ready, the app shows how to open the boot menu on ROG Ally, Ally X, ROG Xbox Ally and Steam Deck handhelds, then the steps for the system you picked. The desktop app and `handheld-forge create` print the same steps.
- **CachyOS Handheld** is Android only for now. The built-in controls don't work in CachyOS's installer on ROG Ally handhelds (they do in Bazzite's and SteamOS's), so the app warns before erasing and walks you through it on the touchscreen, starting with setting the display scale to 150%. The phone writes the ISO byte for byte, which is how CachyOS expects it; the desktop app's file-copy method hasn't been tested with CachyOS's boot setup yet. The checksum CachyOS publishes is checked; its GPG signature isn't yet.
- **SteamOS** is Android only for now. It writes Valve's Steam Deck recovery image, a bzip2-compressed disk image that's decompressed on the fly while writing. Valve publishes no checksum for it, so a damaged download is caught by bzip2's own block checksums instead, and the drive is still read back in full. A new image is downloaded when the server's file size or version tag changes.

It reads the same `handheld_forge/distros.json` and Bazzite key as the desktop app, so adding an ISO-based system adds it to both.

## Command line

```bash
handheld-forge                          # open the window
handheld-forge distros                  # list systems
handheld-forge drives --all             # list disks and why any are blocked
handheld-forge download                 # fetch and verify the latest ISO only
handheld-forge create --device /dev/sdb
```

## Adding another system

Each system is an entry in [`handheld_forge/distros.json`](handheld_forge/distros.json): ISO URL, checksum URL, optional signature URL, and the publisher's public key in `handheld_forge/keys/`. List several images per system and the first one that exists upstream is used. `"format": "raw-bz2"` marks a bzip2-compressed raw disk image (like SteamOS); those are written by the Android app only, and `checksum_url` may be `null` for them. `"desktop": false` keeps an ISO off the desktop app (like CachyOS). `install_steps` is what to do after booting the drive, `warning` is shown before erasing, and the top-level `boot_menu` lists how to open the boot menu on common handhelds. CachyOS names each build by date and removes old ones, so its entry points at the current build and needs a bump when a new one ships; CI checks every download link on each push, so a dead link shows up as a failed `catalog-links` job.

## Development

```bash
python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/pytest -v
```

CI runs the unit tests, a headless render of every screen, and a full build through a real udisks daemon onto a loop device using an ISO made with Bazzite's own xorriso settings (including a file over 4 GB). The result is compared file by file against the kernel's mount of the ISO.

## License

GPL-3.0. Maintained by [Ravenor907](https://github.com/Ravenor907). Not affiliated with the Bazzite, Valve or CachyOS projects.
