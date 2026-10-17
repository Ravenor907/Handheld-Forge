#!/usr/bin/env python3
# ruff: noqa: E501  (mostly inline HTML and SVG)
"""Renders the README graphics (docs/assets/*.png) from HTML, in the app's own colors and type.

Needs Playwright with Chromium and the Inter font. Screenshots of the app come from CI's
emulator run (android/ci/screens.sh); point SCREENS at a folder of them.

    SCREENS=/path/to/screens python3 docs/art/render.py
"""
import base64
import os
import pathlib

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "assets"
SCREENS = pathlib.Path(os.environ.get("SCREENS", "screens"))
INTER = pathlib.Path(os.environ.get("INTER_DIR", "/usr/share/fonts/opentype/inter"))

SLATE, IRON, IRON_HI, SEAM = "#1B2026", "#252B33", "#2C333C", "#353D47"
ASH, BONE, MOLTEN_A, MOLTEN_B = "#98A2AE", "#ECEFF2", "#F5B83D", "#E4572E"


def font_face():
    faces = []
    for weight, name in [(400, "Inter-Regular"), (500, "Inter-Medium"), (600, "Inter-SemiBold"), (700, "Inter-Bold")]:
        data = base64.b64encode((INTER / f"{name}.otf").read_bytes()).decode()
        faces.append(f"@font-face{{font-family:Inter;font-weight:{weight};src:url(data:font/otf;base64,{data})}}")
    for weight, name in [(600, "InterDisplay-SemiBold"), (700, "InterDisplay-Bold"), (800, "InterDisplay-ExtraBold")]:
        data = base64.b64encode((INTER / f"{name}.otf").read_bytes()).decode()
        faces.append(f"@font-face{{font-family:InterDisplay;font-weight:{weight};src:url(data:font/otf;base64,{data})}}")
    return "\n".join(faces)


def screen(name):
    data = base64.b64encode((SCREENS / f"{name}.png").read_bytes()).decode()
    return f"data:image/png;base64,{data}"


BASE_CSS = f"""
{{FONTS}}
*{{box-sizing:border-box;margin:0;padding:0}}
html,body{{background:transparent}}
body{{font-family:Inter,sans-serif;color:{BONE};-webkit-font-smoothing:antialiased}}
.molten{{background:linear-gradient(90deg,{MOLTEN_A},{MOLTEN_B});-webkit-background-clip:text;background-clip:text;color:transparent}}
.phone{{background:#0A0D11;border-radius:46px;padding:11px;box-shadow:0 0 0 1.5px #3A424D,0 30px 60px rgba(0,0,0,.55),0 8px 18px rgba(0,0,0,.35)}}
.phone img{{display:block;border-radius:36px;width:100%}}
"""

# The app icon: a USB stick glowing like hot metal (same shapes as the launcher icon).
STICK = f"""
<svg viewBox="22 36 64 36" xmlns="http://www.w3.org/2000/svg">
  <defs><linearGradient id="g" x1="0" x2="1"><stop offset="0" stop-color="{MOLTEN_A}"/><stop offset="1" stop-color="{MOLTEN_B}"/></linearGradient></defs>
  <path fill="url(#g)" d="M31,40 h32 a6,6 0 0 1 6,6 v16 a6,6 0 0 1 -6,6 h-32 a6,6 0 0 1 -6,-6 v-16 a6,6 0 0 1 6,-6 z"/>
  <path fill="{ASH}" d="M69,45 h12 a2,2 0 0 1 2,2 v14 a2,2 0 0 1 -2,2 h-12 z"/>
  <path fill="{SLATE}" d="M74,49 h4 v3 h-4 z M74,56 h4 v3 h-4 z"/>
</svg>"""


def embers(n, w, h, seed=7):
    import random
    r = random.Random(seed)
    dots = []
    for _ in range(n):
        x, y = r.uniform(0, w * 0.55), r.uniform(h * 0.35, h)
        s = r.uniform(1.2, 3.4)
        o = r.uniform(0.15, 0.6) * (y / h)
        c = MOLTEN_A if r.random() < 0.5 else MOLTEN_B
        dots.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{s:.1f}" fill="{c}" opacity="{o:.2f}"/>')
    return f'<svg width="{w}" height="{h}" style="position:absolute;inset:0">{"".join(dots)}</svg>'


def banner(w=1280, h=440, social=False):
    chips = "".join(f'<span class="chip">{c}</span>' for c in ["Bazzite", "SteamOS", "CachyOS", "Ventoy", "Your own ISO"])
    return f"""
<style>
.card{{position:relative;width:{w}px;height:{h}px;border-radius:{0 if social else 28}px;overflow:hidden;
  background:radial-gradient(900px 520px at 8% 115%,rgba(228,87,46,.38),transparent 60%),
             radial-gradient(700px 420px at 30% 120%,rgba(245,184,61,.20),transparent 60%),
             linear-gradient(160deg,#20262D,{SLATE} 55%,#161A1F)}}
.grid{{position:absolute;inset:0;background-image:linear-gradient(rgba(255,255,255,.035) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.035) 1px,transparent 1px);background-size:40px 40px;
  -webkit-mask-image:linear-gradient(90deg,rgba(0,0,0,.9),transparent 70%)}}
.left{{position:absolute;left:72px;top:{(h - 300) // 2}px;width:620px}}
.mark{{width:118px;height:66px;filter:drop-shadow(0 0 22px rgba(245,140,50,.55))}}
h1{{font-family:InterDisplay;font-weight:800;font-size:{74 if social else 68}px;letter-spacing:-1.5px;line-height:1.02;margin-top:22px}}
.tag{{color:{ASH};font-size:{24 if social else 22}px;line-height:1.4;margin-top:16px;max-width:560px}}
.chips{{margin-top:26px;display:flex;flex-wrap:wrap;gap:10px}}
.chip{{font-size:15px;font-weight:600;color:{BONE};padding:8px 15px;border-radius:999px;background:rgba(37,43,51,.85);box-shadow:inset 0 0 0 1.5px {SEAM}}}
.phones{{position:absolute;right:{40 if social else 30}px;top:{70 if social else 52}px;width:560px;height:{h}px}}
.phones .phone{{position:absolute;width:236px}}
.p1{{left:0;top:70px;transform:rotate(-9deg);opacity:.92}}
.p2{{left:162px;top:0;z-index:2}}
.p3{{left:326px;top:70px;transform:rotate(9deg);opacity:.92}}
</style>
<div class="card" id="shot">
  <div class="grid"></div>
  {embers(70, w, h)}
  <div class="left">
    <div class="mark">{STICK}</div>
    <h1>Handheld <span class="molten">Forge</span></h1>
    <p class="tag">Boot drives for handheld PCs, made in one tap from your Android phone or Linux desktop.</p>
    <div class="chips">{chips}</div>
  </div>
  <div class="phones">
    <div class="phone p1"><img src="{screen('working')}"></div>
    <div class="phone p2"><img src="{screen('choose')}"></div>
    <div class="phone p3"><img src="{screen('done-ventoy')}"></div>
  </div>
</div>"""


ICONS = {
    # 48x48 line icons, drawn with the molten gradient.
    "systems": '<rect x="5" y="15" width="38" height="20" rx="8"/><rect x="17" y="19" width="14" height="12" rx="1.5"/><path d="M11 25h4M13 23v4"/><circle cx="36" cy="23.5" r="1.4"/><circle cx="36" cy="27.5" r="1.4"/>',
    "file": '<path d="M13 5h15l9 9v27a2 2 0 0 1-2 2H13a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"/><path d="M28 5v9h9"/><path d="M24 22v12M19 29l5 5 5-5"/>',
    "ventoy": '<rect x="7" y="8" width="34" height="32" rx="5"/><path d="M13 17h22M13 24h22M13 31h14"/><circle cx="35" cy="31" r="2.2"/>',
    "add": '<rect x="6" y="18" width="26" height="16" rx="4"/><path d="M32 22h6a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-6"/><path d="M19 4v10M14 9h10"/>',
    "auto": '<circle cx="24" cy="26" r="15"/><path d="M24 26V17M24 26l6 4"/><path d="M19 5h10"/><path d="M38 12l3-3"/>',
    "format": '<path d="M36 5L25 23"/><path d="M17 21l12 7-3 14H11l-5-8z"/><path d="M13 35l3 7M19 33l2 9"/>',
    "verify": '<path d="M24 5l15 6v10c0 10-6.5 17-15 21-8.5-4-15-11-15-21V11z"/><path d="M17 24l5 5 9-10"/>',
    "fast": '<path d="M27 4L11 27h12l-3 17 17-24H25z"/>',
    "shield": '<rect x="9" y="21" width="30" height="20" rx="4"/><path d="M15 21v-6a9 9 0 0 1 18 0v6"/><circle cx="24" cy="31" r="2.5"/>',
    "secure": '<path d="M24 5l15 6v10c0 10-6.5 17-15 21-8.5-4-15-11-15-21V11z"/><rect x="18" y="22" width="12" height="9" rx="1.5"/><path d="M20.5 22v-3a3.5 3.5 0 0 1 7 0v3"/>',
    "desktop": '<rect x="5" y="8" width="38" height="25" rx="3"/><path d="M17 41h14M24 33v8"/>',
    "resume": '<path d="M38 24a14 14 0 1 1-4.1-9.9"/><path d="M38 8v8h-8"/>',
}


def icon(name, size=96):
    return f"""
<div id="shot" style="width:{size}px;height:{size}px;border-radius:{size * 0.26}px;background:linear-gradient(160deg,{IRON_HI},{IRON});
  box-shadow:inset 0 0 0 1.5px {SEAM};display:flex;align-items:center;justify-content:center">
  <svg width="{size * 0.56}" height="{size * 0.56}" viewBox="0 0 48 48" fill="none" stroke="url(#g)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
    <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{MOLTEN_A}"/><stop offset="1" stop-color="{MOLTEN_B}"/></linearGradient></defs>
    {ICONS[name]}
  </svg>
</div>"""


def phone(name, width=300):
    return f'<div id="shot" style="padding:24px 26px 44px"><div class="phone" style="width:{width}px"><img src="{screen(name)}"></div></div>'


def flow(w=1280):
    steps = [
        ("Pick", "A system or your own image, and the USB drive."),
        ("Download &amp; verify", "Latest release, checked against the publisher's checksum and signature."),
        ("Write", "Straight to the stick over USB. No root, no password on the phone."),
        ("Read back", "Every byte on the drive is compared with the image."),
    ]
    cards = "".join(
        f'<div class="step"><div class="num">{i + 1}</div><h3>{t}</h3><p>{d}</p></div>' for i, (t, d) in enumerate(steps)
    )
    return f"""
<style>
.wrap{{width:{w}px;padding:34px 40px;border-radius:28px;background:linear-gradient(160deg,#20262D,{SLATE});position:relative}}
.line{{position:absolute;left:74px;width:916px;top:62px;height:3px;background:linear-gradient(90deg,{MOLTEN_A},{MOLTEN_B});opacity:.55;border-radius:2px}}
.steps{{display:grid;grid-template-columns:repeat(4,1fr);gap:22px;position:relative}}
.step{{text-align:left;padding:0 6px}}
.num{{width:56px;height:56px;border-radius:50%;background:linear-gradient(135deg,{MOLTEN_A},{MOLTEN_B});color:#1A1410;font-family:InterDisplay;
  font-weight:800;font-size:26px;display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 7px {SLATE},0 0 26px rgba(245,140,50,.45)}}
h3{{font-family:InterDisplay;font-weight:700;font-size:25px;margin-top:20px}}
p{{color:{ASH};font-size:17px;line-height:1.45;margin-top:8px}}
</style>
<div class="wrap" id="shot"><div class="line"></div><div class="steps">{cards}</div></div>"""


def render(page, html, out, transparent=True):
    css = BASE_CSS.replace("{FONTS}", FONTS)
    page.set_content(f"<!doctype html><html><head><meta charset='utf-8'><style>{css}</style></head><body>{html}</body></html>")
    page.wait_for_timeout(150)
    page.locator("#shot").screenshot(path=str(out), omit_background=transparent)
    print("wrote", out.relative_to(ROOT))


STORE = ROOT / "android" / "fastlane" / "metadata" / "android" / "en-US" / "images"

SHOTS = [
    ("choose", "Tap a system.", "Get a boot drive."),
    ("working", "It downloads, checks and writes.", "You get a snack."),
    ("done", "Then it shows you", "how to boot it."),
    ("confirm-ventoy", "Ventoy, with Secure Boot", "on or off."),
    ("import", "Add ISOs to Ventoy", "from your phone."),
    ("file", "Your own ISO,", "even compressed."),
    ("confirm", "Checks your battery and Wi-Fi", "before it erases anything."),
]


def store_shot(name, top, hot, w=540, h=960):
    return f"""
<style>
.s{{position:relative;width:{w}px;height:{h}px;overflow:hidden;
  background:radial-gradient(520px 420px at 50% 112%,rgba(228,87,46,.40),transparent 62%),linear-gradient(170deg,#222830,{SLATE} 60%,#15191E)}}
.cap{{position:absolute;left:0;right:0;top:46px;text-align:center;font-family:InterDisplay;font-weight:800;font-size:31px;line-height:1.18;letter-spacing:-.4px;padding:0 30px}}
.ph{{position:absolute;left:50%;top:152px;width:340px;transform:translateX(-50%)}}
</style>
<div class="s" id="shot">{embers(40, w, h, seed=len(name))}
  <div class="cap">{top}<br><span class="molten">{hot}</span></div>
  <div class="phone ph"><img src="{screen(name)}"></div>
</div>"""


def store_icon(size=512):
    return f"""<div id="shot" style="width:{size}px;height:{size}px;background:radial-gradient(circle at 50% 60%,#262D35,{SLATE} 70%);
  display:flex;align-items:center;justify-content:center"><div style="width:{size * 0.70}px;filter:drop-shadow(0 0 {size * 0.05}px rgba(245,140,50,.55))">{STICK}</div></div>"""


def feature_graphic(w=1024, h=500):
    return f"""
<style>
.f{{position:relative;width:{w}px;height:{h}px;overflow:hidden;
  background:radial-gradient(700px 420px at 5% 115%,rgba(228,87,46,.40),transparent 60%),linear-gradient(160deg,#20262D,{SLATE} 55%,#161A1F)}}
.f .l{{position:absolute;left:60px;top:130px;width:520px}}
.f .mark{{width:96px;filter:drop-shadow(0 0 18px rgba(245,140,50,.55))}}
.f h1{{font-family:InterDisplay;font-weight:800;font-size:58px;letter-spacing:-1.2px;margin-top:16px}}
.f p{{color:{ASH};font-size:21px;line-height:1.4;margin-top:12px}}
.f .p{{position:absolute;width:200px}}
</style>
<div class="f" id="shot">{embers(50, w, h)}
  <div class="l"><div class="mark">{STICK}</div><h1>Handheld <span class="molten">Forge</span></h1>
  <p>Boot drives for your handheld PC,<br>made in one tap from your phone.</p></div>
  <div class="phone p" style="left:600px;top:60px;transform:rotate(-7deg)"><img src="{screen('working')}"></div>
  <div class="phone p" style="left:790px;top:30px;transform:rotate(6deg)"><img src="{screen('choose')}"></div>
</div>"""


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    FONTS = font_face()
    with sync_playwright() as p:
        b = p.chromium.launch()
        page = b.new_page(viewport={"width": 1400, "height": 900}, device_scale_factor=2)
        render(page, banner(), OUT / "banner.png")
        page.set_viewport_size({"width": 1400, "height": 800})
        render(page, banner(1280, 640, social=True), OUT / "social-preview.png", transparent=False)
        render(page, flow(), OUT / "how-it-works.png")
        for name in ICONS:
            render(page, icon(name), OUT / f"icon-{name}.png")
        page.set_viewport_size({"width": 500, "height": 900})
        for name in ["choose", "file", "confirm-ventoy", "working", "done-ventoy", "import", "done-import"]:
            render(page, phone(name), OUT / f"screen-{name}.png")
        # Google Play listing (exact sizes: 512 icon, 1024 x 500 feature graphic, 1080 x 1920 screenshots).
        (STORE / "phoneScreenshots").mkdir(parents=True, exist_ok=True)
        page = b.new_page(viewport={"width": 1100, "height": 1000}, device_scale_factor=1)
        render(page, store_icon(), STORE / "icon.png", transparent=False)
        render(page, feature_graphic(), STORE / "featureGraphic.png", transparent=False)
        page = b.new_page(viewport={"width": 600, "height": 1000}, device_scale_factor=2)
        for i, (name, top, hot) in enumerate(SHOTS, 1):
            render(page, store_shot(name, top, hot), STORE / "phoneScreenshots" / f"{i}.png", transparent=False)
        b.close()
