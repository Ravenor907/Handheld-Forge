# Handheld Forge: full details

The [README](../README.md) is the short version. This is everything else: how each part works, the desktop app's options, the command line and development.


Pick a system, pick a USB drive, press **Create boot drive**. No terminal, no password.

| System | Linux desktop | Android |
| --- | --- | --- |
| Bazzite (Handheld KDE) | Ready | Ready |
| SteamOS (Steam Deck recovery image) | Ready (asks for your password once) | Ready |
| CachyOS Handheld | Ready (asks for your password once) | Ready |
| Your own file, Ventoy, Format drive | Ready | Ready |
| Labs: Batocera, drive test, check a drive | Ready | Ready |

Both apps work the same way: a short "Before you start" check (battery, internet, metered connection) and a tick box to leave it alone, a reminder while it works, Stop asks before stopping, downloads resume through dropped connections, and when the drive is ready they show how to open the boot menu and get through that system's installer.

## What it does

1. **Gets the latest release** from the official Bazzite server. It's cached in `~/.cache/handheld-forge`, so if you already have the newest one it isn't downloaded again, and an interrupted download resumes, including after a dropped connection. It checks for free space first, and keeps the computer from sleeping until the drive is done.
2. **Proves it's genuine.** The SHA256 checksum catches a corrupted download. The cosign signature, checked against Bazzite's public key pinned in this repo, catches a tampered one.
3. **Builds the drive** (see below), then unmounts it, mounts it fresh and **reads every file back** to confirm it landed intact.
4. **Ejects it**, ready to plug into your handheld.

## Why Bazzite never asks for a password

Raw-imaging a USB stick (what `dd` does) is something Linux only lets an admin do, which is why some tools pop up a password prompt.

Handheld Forge doesn't raw-image. It asks the desktop's own disk service (udisks, the one your file manager uses) to do the things any logged-in user is already allowed to do with a removable drive: format it, mount it, copy files to it. The drive ends up with:

- a small **FAT32 EFI partition** with the boot loader (shim and GRUB, still Secure Boot signed)
- an **ext4 partition labeled `Bazzite-Live`** holding every file from the ISO

Bazzite's live boot looks for its files by that label, so the stick boots exactly like a flashed one. If an ISO ever has a label too long for ext4, the boot config is pointed at the partition's UUID instead.

Every call tells the system *never* to prompt. If a system's rules would require a password anyway, Handheld Forge stops and says so instead of showing a dialog.

SteamOS and CachyOS are different: SteamOS's recovery image is a whole disk image, and CachyOS expects its ISO written as is, so both need a byte-for-byte write. Linux only allows that after an admin password, so for these two the desktop app asks udisks to open the drive and your desktop shows its own password window, once, when writing starts. Nothing else needs it, and the app never sees your password. The image is then written (SteamOS's is decompressed on the fly), the whole drive is read back bypassing the cache and compared, and the drive is ejected. On SteamOS desktop mode, set a password first with `passwd`. The Android app writes them with no password, since the phone talks to the stick directly over USB.

### Experimental options

The desktop app hides its untested options until you tap the **Bazzite** tile 10 times in a row (a pause of more than 3 seconds starts the count again). The last few taps count down at the bottom of the window. They then appear under **Experimental, untested** and stay on between launches; **Hide experimental options** in the version menu (top right) turns them off again. Uninstalling removes the setting with everything else.

### Experimental: Bazzite with the account filled in

The experimental section has **Bazzite, Login: deck**. It makes the drive from Bazzite's Legacy ISO the same password-free way as regular Bazzite, then adds a small installer settings file (a kickstart, `forge-user.ks`) to the stick and points the installer's boot entries at it. That file pulls in the image's own installer defaults unchanged and adds one admin account: login `deck` (shown as Deck), password `deck123`. The installer opens with the account already set, so on the handheld you only pick the internal drive. Choosing the drive is never automated, so the stick can't erase a machine by itself. Change the password after setup with `passwd`.

Bazzite publishes no signature for the Legacy ISO, so only its checksum is checked. The option hasn't been tried on a handheld yet; if the installer complains, make the drive with the regular Bazzite option. The Android app writes images exactly as published, and a published ISO can't take an extra file, so this option is desktop only for now. From the command line it's `--distro bazzite-deck-deck`.

### Experimental: CachyOS with no password

The experimental section also has **CachyOS, No password**. It skips the password by copying CachyOS's files instead of writing the ISO byte for byte: the drive gets one FAT32 partition holding every file from the ISO. CachyOS is built with Arch's archiso, whose boot loader and startup look for a marker file (`/boot/<ISO UUID>.uuid`) on any drive rather than a drive label, which is what makes a copied stick findable. If the ISO's label is too long for FAT, the boot entries that use it are pointed at the partition's UUID. Files over 4 GB can't go on FAT32, so the app refuses before erasing if a future ISO has one. Secure Boot must be off, as for the regular CachyOS option.

It hasn't been tried on a handheld yet. If the drive doesn't start, make it again with the regular CachyOS option. From the command line it's `--distro cachyos-handheld-copy`.

## Your own file, Ventoy, Format and Labs on the desktop

The desktop app has everything the Android app has (see [Android](#android) for how each one works), with the same screens and wording:

- **Your own file**, **Ventoy** (with the Secure Boot choice) and **Format drive** are tiles under the systems. A drive with Ventoy on it says so, and **Add images** opens its files: pick images, mark one to start on its own, remove one.
- **Password.** Anything that rewrites the whole drive (your own file, installing Ventoy, the drive test, checking a drive) asks for your password once, like SteamOS. Adding images, starting on its own and removing go through the desktop's own mount of the drive, with no password, and each image is read back from the drive (not from memory) after copying. Format drive uses the system's exFAT tools with no password, and falls back to the app's own formatter (one password) if they're missing.
- **Labs.** Tap the version number at the top right 10 times; the arrow next to it opens the app menu. Labs shows Batocera and a **Labs** button with the same two settings and two tools as on Android. The settings live in `~/.config/handheld-forge/settings.json`; **Turn off Labs** in the app menu hides it all again.
- **Speed.** `.bz2` images unpack on every core, and the log says how long the write took, where the time went, and the USB speed the drive is connected at.

Both apps share `handheld_forge/distros.json`, `handheld_forge/labs.json` and the Bazzite key, and CI runs both apps' Format and Ventoy drives through the same checks.

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

There's also an Android app in [`android/`](../android/). It makes the same boot drive from a phone: plug a USB stick into the phone with a USB-C adapter, pick a system, tap **Create boot drive**.

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
- **Progress you can read.** The percentage runs from 0 to 100 and never goes backwards, and the bar fills left to right. Under it: which of the four steps it's on ("Step 3 of 4: Writing to the drive"), how much is done and roughly how long is left, and the latest thing that happened. Steps that can't measure themselves say what's going on, with a running timer so it never looks frozen. The notification shows the same percentage and step.
- **Write speed.** The phone talks to the stick itself over USB. The app keeps up to 8 transfers in flight, sends each as large as the phone allows (up to 1 MB) and writes in runs of up to 1 MB per command, so a fast stick isn't left waiting. Sticks or adapters that choke on that get smaller commands and transfers automatically. The phone's own USB port still sets the ceiling: many phones only have USB 2.0 (about 30 to 40 MB/s at best), and so do many USB-C adapters and hubs. The log shows the transfer size in use.
- **Install help.** When the drive is ready, the app shows how to open the boot menu on ROG Ally, Ally X, ROG Xbox Ally, Legion Go and Steam Deck handhelds, then the steps for the system you picked. The desktop app and `handheld-forge create` print the same steps.
- **CachyOS Handheld**: the built-in controls don't work in CachyOS's installer on ROG Ally handhelds (they do in Bazzite's and SteamOS's), so the app warns before erasing and walks you through it on the touchscreen, starting with setting the display scale to 150%. Both apps write the ISO byte for byte, which is how CachyOS expects it (the desktop app asks for your password for that). The checksum CachyOS publishes is checked; its GPG signature isn't yet.
- **SteamOS**: both apps write Valve's Steam Deck recovery image, a bzip2-compressed disk image that's decompressed on the fly while writing. Valve publishes no checksum for it, so a damaged download is caught by bzip2's own block checksums instead, and the drive is still read back in full. A new image is downloaded when the server's file size or version tag changes.

- **Your own file.** Tap **Your own file** to write any file from the phone's storage. The app looks at the file itself to see what it is: a USB-bootable ISO, a disk image (`.img`, `.raw`), or one compressed as `.bz2`, `.gz`, `.xz` or `.zip` (unpacked while writing). An ISO made only for DVDs gets a warning, since most handhelds won't start from it. It's written byte for byte and read back, but there's no publisher checksum to check it against.
- **Ventoy.** Installs the latest [Ventoy](https://github.com/ventoy/Ventoy) release, checked against the release's `sha256.txt`, laid out exactly as Ventoy's own installer does it (CI compares the two byte for byte and boots both). It asks whether to keep **Secure Boot support**: on (the default) works with Secure Boot left on after enrolling Ventoy's key once; off leaves out shim and MokManager exactly as `Ventoy2Disk.sh -S` does, and needs Secure Boot off in the BIOS.
- **Add images to Ventoy.** With a Ventoy drive plugged in, tap **Add images** and pick ISOs or disk images on the phone, any number at once. Compressed ones (`.xz`, `.gz`, `.bz2`, `.zip`) are unpacked once, straight onto the drive, since Ventoy can't boot them packed, and each file gets a name Ventoy lists (`.iso` or `.img`). Files go on as one unbroken run each (Ventoy needs that), nothing else on the drive is touched, a file with the same name is replaced, and everything is read back and checked afterwards. The same screen lists what's on the drive and can remove files. Tap an image there to make it **start on its own**: Ventoy shows its menu for 5 seconds and then boots it, so a handheld whose buttons don't work in Ventoy's menu (or one with no keyboard) still gets there. This uses Ventoy's own `ventoy/ventoy.json` settings and keeps anything else already in that file; tap the image again to go back to choosing. CI adds a plain, an `.xz`, a `.bz2` and a `.zip` ISO this way, checks the drive with `fsck.exfat`, boots one from Ventoy's menu, and boots a drive set to start on its own with no key presses.
- **Format drive.** Erases the drive to one empty exFAT partition named USB DRIVE that works on Windows, macOS, Linux and Android, with no 4 GB file limit. Handy after a stick has been a boot drive and no longer shows up properly.
- **Fast unpacking.** `.bz2` images (like SteamOS's) are unpacked on all of the phone's cores at once, the way lbzip2 does it, and other formats are unpacked on their own thread while the USB writes go on. CI checks that Valve's real SteamOS image comes out byte for byte the same as with `bzip2 -d`.
- **Where the time goes.** After writing, the log says how long it took, how fast it was, and how much of that time was spent waiting on the drive versus waiting on the image (downloading or unpacking). It also logs the USB speed the drive is connected at: a USB 3 stick behind a USB 2 port or adapter tops out around 40 MB/s.
- **Labs.** Tap the version number 10 times to show experimental systems (Batocera) and a **Labs** button at the top. Labs has two settings and two tools:
  - *Write while downloading:* starts writing as the download comes in, so most of the download time disappears. The download is still saved, checked against the checksum and signature at the end, and kept for next time; if it fails, the drive has to be made again.
  - *Skip the read-back check:* saves close to half the time after writing, at the cost of not catching a fake or failing drive.
  - *Test a drive:* writes 1 to 4 GB from the start of the drive (about 90 seconds' worth), timing each 256 MB so a stick that slows down once its cache fills shows it, then puts 1 MB markers across the rest of the drive up to its last megabyte and reads everything back. It reports real write and read speed, whether the full capacity is real, and how long a SteamOS image would take. It erases the drive, then formats it as exFAT.
  - *Check a drive:* compares a drive with the selected system's image or your own file, byte for byte, without writing anything, and says where they first differ.
- **Writing that survives hiccups.** If the stick or adapter stumbles, the app resets it, steps down (one transfer at a time, then smaller commands, then smaller transfers) and retries the same spot instead of failing. The log notes each step down.

It reads the same `handheld_forge/distros.json` and Bazzite key as the desktop app, so adding an ISO-based system adds it to both.

## Command line

```bash
handheld-forge                          # open the window
handheld-forge distros                  # list systems
handheld-forge drives --all             # list disks and why any are blocked
handheld-forge download                 # fetch and verify the latest ISO only
handheld-forge create --device /dev/sdb
handheld-forge write --device /dev/sdb my.img.xz        # your own file
handheld-forge format --device /dev/sdb                 # one exFAT partition
handheld-forge ventoy install --device /dev/sdb         # add --no-secure-boot to leave it out
handheld-forge ventoy list --device /dev/sdb
handheld-forge ventoy add --device /dev/sdb a.iso b.img.bz2 [--boot-next a.iso]
handheld-forge ventoy boot-next --device /dev/sdb --image a.iso   # --image "" for the menu
handheld-forge ventoy remove --device /dev/sdb --image a.iso
handheld-forge labs on                                  # or: labs stream on, labs skip-check on
handheld-forge test-drive --device /dev/sdb             # Labs
handheld-forge check-drive --device /dev/sdb my.img.xz  # Labs
```

## Adding another system

Each system is an entry in [`handheld_forge/distros.json`](../handheld_forge/distros.json): ISO URL, checksum URL, optional signature URL, and the publisher's public key in `handheld_forge/keys/`. List several images per system and the first one that exists upstream is used. `"format": "raw-bz2"` marks a bzip2-compressed raw disk image (like SteamOS), and `checksum_url` may be `null` for it. Those, and ISOs marked `"write": "raw"` (like CachyOS), are written byte for byte; the desktop app asks for the password for that. Other ISOs are copied file by file on the desktop, with no password. `install_steps` is what to do after booting the drive, `warning` is shown before erasing, `experimental` lists untested variants shown in their own section on the desktop (an image by label, `write`/`layout` overrides, a preset installer `user`, a `note` shown before erasing), and the top-level `boot_menu` lists how to open the boot menu on common handhelds. CachyOS names each build by date and removes old ones, so its entry points at the current build and needs a bump when a new one ships; CI checks every download link on each push, so a dead link shows up as a failed `catalog-links` job.

## Development

```bash
python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/pytest -v
```

CI runs the unit tests, a headless render of every screen, and a full build through a real udisks daemon onto a loop device using an ISO made with Bazzite's own xorriso settings (including a file over 4 GB). The result is compared file by file against the kernel's mount of the ISO. Every other desktop job (Format both ways, your own file, Check a drive, Ventoy, adding images, starting on its own, removing, the drive test) also runs through the real udisks daemon, the desktop's Format and Ventoy drives get the same `drive-images` checks and QEMU boots as the Android app's, and Valve's real SteamOS image is unpacked with the desktop's parallel bzip2 decoder and compared with `bzip2 -d`.

## License

GPL-3.0. Maintained by [Ravenor907](https://github.com/Ravenor907). Not affiliated with the Bazzite, Valve or CachyOS projects.
