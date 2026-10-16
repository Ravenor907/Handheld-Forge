#!/usr/bin/env python3
"""Tiny UI driver over adb + uiautomator, for the emulator test.

  ui.py has  TEXT...          exit 0 if every TEXT is on screen (substring of text or description)
  ui.py tap  TEXT [TEXT...]   tap the first on-screen element matching any TEXT, in order of preference
  ui.py dump                  print every visible text
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=60).stdout


def nodes():
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("shell", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in xml:
            break
        time.sleep(1)
    else:
        return []
    root = ET.fromstring(xml[xml.index("<hierarchy"):])
    out = []
    for n in root.iter("node"):
        label = (n.get("text") or "") + " | " + (n.get("content-desc") or "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m and label.strip(" |"):
            x1, y1, x2, y2 = map(int, m.groups())
            out.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def main():
    cmd, *args = sys.argv[1:]
    ns = nodes()
    if cmd == "dump":
        for label, _, _ in ns:
            print(label)
        return 0
    if cmd == "has":
        missing = [a for a in args if not any(a in label for label, _, _ in ns)]
        if missing:
            print("missing:", missing)
        return 1 if missing else 0
    if cmd == "tap":
        for want in args:
            for label, x, y in ns:
                if want in label:
                    adb("shell", "input", "tap", str(x), str(y))
                    print(f"tapped {want!r}")
                    return 0
        return 1
    return 2


sys.exit(main())
