<p align="center">
  <img src="docs/assets/banner.png" alt="Handheld Forge: boot drives for handheld PCs, made in one tap" width="100%">
</p>

<p align="center">
  <a href="https://github.com/Ravenor907/Handheld-Forge/releases"><img src="https://img.shields.io/github/v/release/Ravenor907/Handheld-Forge?filter=android-v*&label=Android%20APK&style=for-the-badge&color=E4572E&labelColor=1B2026" alt="Android APK"></a>
  <a href="https://github.com/Ravenor907/Handheld-Forge/actions/workflows/android.yml"><img src="https://img.shields.io/github/actions/workflow/status/Ravenor907/Handheld-Forge/android.yml?branch=main&label=tests&style=for-the-badge&labelColor=1B2026" alt="Tests"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-F5B83D?style=for-the-badge&labelColor=1B2026" alt="GPL-3.0"></a>
</p>

Plug a USB stick into your phone. Tap a system. Go get a snack. Come back to a boot drive for your Steam Deck, ROG Ally or Legion Go.

No root, no terminal, no `dd` roulette.

<p align="center"><img src="docs/assets/how-it-works.png" alt="Pick, download and verify, write, read back" width="100%"></p>

## What it does

<table>
<tr>
<td width="33%"><img src="docs/assets/icon-systems.png" width="44"><br><b>One-tap systems</b><br>Bazzite, SteamOS and CachyOS, fresh from the source.</td>
<td width="33%"><img src="docs/assets/icon-file.png" width="44"><br><b>Your own file</b><br>Any ISO or disk image on your phone, even <code>.xz</code>, <code>.gz</code>, <code>.bz2</code> or <code>.zip</code>.</td>
<td width="33%"><img src="docs/assets/icon-ventoy.png" width="44"><br><b>Ventoy</b><br>One stick, every OS. Installs the latest release, Secure Boot on or off.</td>
</tr>
<tr>
<td><img src="docs/assets/icon-add.png" width="44"><br><b>Add images</b><br>Drop ISOs onto a Ventoy stick straight from your phone. Compressed ones get unpacked.</td>
<td><img src="docs/assets/icon-auto.png" width="44"><br><b>Starts on its own</b><br>Controller dead in Ventoy's menu? Mark an image and it boots itself.</td>
<td><img src="docs/assets/icon-format.png" width="44"><br><b>Format drive</b><br>Turns a used boot stick back into a normal exFAT drive.</td>
</tr>
<tr>
<td><img src="docs/assets/icon-verify.png" width="44"><br><b>Checked twice</b><br>Downloads are checked, and every byte is read back off the stick.</td>
<td><img src="docs/assets/icon-fast.png" width="44"><br><b>Fast</b><br>Unpacks on every CPU core while the stick writes.</td>
<td><img src="docs/assets/icon-resume.png" width="44"><br><b>Stubborn</b><br>Dropped Wi-Fi resumes. Flaky adapter? It slows down and keeps going.</td>
</tr>
</table>

## Screenshots

<table>
<tr>
<td align="center"><img src="docs/assets/screen-choose.png" width="200"><br>Pick</td>
<td align="center"><img src="docs/assets/screen-working.png" width="200"><br>Write</td>
<td align="center"><img src="docs/assets/screen-done-ventoy.png" width="200"><br>Done</td>
<td align="center"><img src="docs/assets/screen-import.png" width="200"><br>Add to Ventoy</td>
</tr>
</table>

## Get it

**Android:** grab the APK from [Releases](https://github.com/Ravenor907/Handheld-Forge/releases) and plug the stick in with a USB-C adapter. Android asks once if the app can use the stick. Say yes.

**Linux desktop** (Bazzite, SteamOS desktop mode, most distros):

```bash
git clone https://github.com/Ravenor907/Handheld-Forge.git
cd Handheld-Forge && ./install.sh
```

Then open **Handheld Forge** from your app menu. Bazzite needs no password. SteamOS and CachyOS ask for it once, because Linux.

## Systems

| | Android | Linux desktop |
| --- | :---: | :---: |
| Bazzite (Handheld KDE) | ✓ | ✓ |
| SteamOS (Valve recovery image) | ✓ | ✓ |
| CachyOS Handheld | ✓ | ✓ |
| Your own ISO or image | ✓ | |
| Ventoy, add images, format | ✓ | |

## It won't eat your laptop

Only USB drives show up. The drive your system runs from is never offered, and erasing always asks first, by name.

## The fine print

Everything else (how each part works, desktop options, command line, adding a system, development) is in [docs/details.md](docs/details.md).

GPL-3.0. Made by [Ravenor907](https://github.com/Ravenor907). Not affiliated with Bazzite, Valve, CachyOS or Ventoy, just a fan of all four.
