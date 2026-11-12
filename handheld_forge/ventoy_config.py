"""'Starts on its own' for Ventoy (same as the Android app): sets which image Ventoy boots with no
input, through Ventoy's documented /ventoy/ventoy.json "control" settings. Everything else in an
existing ventoy.json is kept exactly, and a broken one is reported, never overwritten."""

from __future__ import annotations

import re
from pathlib import Path

PATH = "ventoy/ventoy.json"
TIMEOUT_SECONDS = 5
DEFAULT = "VTOY_DEFAULT_IMAGE"
TIMEOUT = "VTOY_MENU_TIMEOUT"
SECONDARY = "VTOY_SECONDARY_BOOT_MENU"  # Ventoy's second menu would wait for a button
OURS = {DEFAULT, TIMEOUT, SECONDARY}
MAX_DEPTH = 64  # lists and objects inside one another
_NUMBER = re.compile(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
_HEX = frozenset("0123456789abcdefABCDEF")


class JsonError(ValueError):
    pass


class Num(str):
    """A JSON number kept exactly as written."""


def parse(text: str):
    return _parse(text)[0]


def _parse(text: str):
    """(the value, a name that one object gives twice or None)."""
    p = _Parser(text)
    v = p.value()
    p.ws()
    if p.i != len(text):
        p.fail("unexpected text after the end")
    return v, p.repeated


def write(v, indent: str = "") -> str:
    if v is None:
        return "null"
    if v is True:
        return "true"
    if v is False:
        return "false"
    if isinstance(v, Num):
        return str(v)
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, str):
        return _quote(v)
    inner = indent + "    "
    if isinstance(v, dict):
        if not v:
            return "{}"
        return (
            "{\n"
            + ",\n".join(f"{inner}{_quote(str(k))}: {write(x, inner)}" for k, x in v.items())
            + f"\n{indent}}}"
        )
    if isinstance(v, list):
        if not v:
            return "[]"
        return "[\n" + ",\n".join(f"{inner}{write(x, inner)}" for x in v) + f"\n{indent}]"
    raise TypeError(type(v))


def _quote(s: str) -> str:
    out = ['"']
    for c in s:
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif c == "\n":
            out.append("\\n")
        elif c == "\r":
            out.append("\\r")
        elif c == "\t":
            out.append("\\t")
        elif ord(c) < 0x20:
            out.append(f"\\u{ord(c):04x}")
        else:
            out.append(c)
    out.append('"')
    return "".join(out)


class _Parser:
    def __init__(self, s: str) -> None:
        self.s, self.i = s, 0
        self.depth = 0
        self.repeated: str | None = None  # the first name an object gives twice

    def fail(self, msg: str):
        line = self.s[: self.i].count("\n") + 1
        raise JsonError(f"{msg} on line {line}")

    def ws(self) -> None:
        while self.i < len(self.s) and self.s[self.i].isspace():
            self.i += 1

    def value(self):
        self.ws()
        if self.i >= len(self.s):
            self.fail("unexpected end")
        c = self.s[self.i]
        if c in "{[":
            self.depth += 1
            if self.depth > MAX_DEPTH:
                self.fail("lists or objects nested too deeply")
            v = self.obj() if c == "{" else self.arr()
            self.depth -= 1
            return v
        if c == '"':
            return self.str_()
        for word, v in (("true", True), ("false", False), ("null", None)):
            if self.s.startswith(word, self.i):
                self.i += len(word)
                return v
        m = _NUMBER.match(self.s, self.i)
        if m:  # whatever follows must be a comma or a closing bracket, which the caller checks
            self.i = m.end()
            return Num(m.group())
        self.fail(f"unexpected '{c}'")

    def hex4(self) -> int:
        """The four hex digits of a \\u escape."""
        h = self.s[self.i : self.i + 4]
        if len(h) != 4 or not _HEX.issuperset(h):
            self.fail("bad \\u escape")
        self.i += 4
        return int(h, 16)

    def str_(self) -> str:
        self.i += 1
        out = []
        esc = {'"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f", "n": "\n", "r": "\r", "t": "\t"}
        while True:
            if self.i >= len(self.s):
                self.fail("unfinished text in quotes")
            c = self.s[self.i]
            self.i += 1
            if c == '"':
                return "".join(out)
            if c != "\\":
                if c < " ":
                    self.i -= 1
                    self.fail("a line break or control character inside quotes")
                out.append(c)
                continue
            if self.i >= len(self.s):
                self.fail("unfinished escape")
            e = self.s[self.i]
            self.i += 1
            if e in esc:
                out.append(esc[e])
            elif e == "u":
                u = self.hex4()
                if 0xD800 <= u <= 0xDBFF and self.s.startswith("\\u", self.i):
                    # The first half of a pair: with its second half it's one character.
                    self.i += 2
                    low = self.hex4()
                    if not 0xDC00 <= low <= 0xDFFF:
                        self.fail("bad \\u escape (half of a character pair)")
                    u = 0x10000 + ((u - 0xD800) << 10) + (low - 0xDC00)
                elif 0xD800 <= u <= 0xDFFF:
                    self.fail("bad \\u escape (half of a character pair)")
                out.append(chr(u))
            else:
                self.fail(f"bad escape \\{e}")

    def obj(self) -> dict:
        self.i += 1
        m: dict = {}
        self.ws()
        if self.s[self.i : self.i + 1] == "}":
            self.i += 1
            return m
        while True:
            self.ws()
            if self.s[self.i : self.i + 1] != '"':
                self.fail("expected a name in quotes")
            k = self.str_()
            self.ws()
            if self.s[self.i : self.i + 1] != ":":
                self.fail("expected ':'")
            self.i += 1
            if k in m and self.repeated is None:
                self.repeated = k
            m[k] = self.value()
            self.ws()
            c = self.s[self.i : self.i + 1]
            self.i += 1
            if c == ",":
                continue
            if c == "}":
                return m
            self.i -= 1
            self.fail("expected ',' or '}'")

    def arr(self) -> list:
        self.i += 1
        a: list = []
        self.ws()
        if self.s[self.i : self.i + 1] == "]":
            self.i += 1
            return a
        while True:
            a.append(self.value())
            self.ws()
            c = self.s[self.i : self.i + 1]
            self.i += 1
            if c == ",":
                continue
            if c == "]":
                return a
            self.i -= 1
            self.fail("expected ',' or ']'")


def _root(text: str | None, rewriting: bool = False) -> dict | None:
    """The file's top object. For rewriting, a file that gives a name twice in one object is refused:
    only one of the two values would be written back."""
    if text is None or not text.strip():
        return None
    try:
        v, repeated = _parse(text.removeprefix("﻿"))
    except JsonError as e:
        raise OSError(
            f"ventoy.json on the drive has an error ({e}). Fix or remove it on a computer, then try again."
        ) from None
    if not isinstance(v, dict):
        raise OSError("ventoy.json on the drive isn't a JSON object. Fix or remove it on a computer.")
    if rewriting and repeated is not None:
        raise OSError(
            f'ventoy.json on the drive has "{repeated}" twice in one object. '
            "Fix or remove it on a computer, then try again."
        )
    return v


def boot_next(text: str | None) -> str | None:
    root = _root(text)
    if not root or not isinstance(root.get("control"), list):
        return None
    for e in root["control"]:
        if isinstance(e, dict) and isinstance(e.get(DEFAULT), str):
            return e[DEFAULT].removeprefix("/") or None
    return None


def with_boot_next(text: str | None, image: str | None) -> str | None:
    """The new ventoy.json, or None when nothing is left in it (the file can go)."""
    root = _root(text, rewriting=True) or {}
    old = root.get("control")
    if old is None:
        old = []
    elif not isinstance(old, list):
        raise OSError(
            'ventoy.json on the drive has a "control" setting that isn\'t a list. '
            "Fix or remove it on a computer."
        )
    control = []
    for e in old:
        if isinstance(e, dict) and any(k in OURS for k in e):
            e = {k: v for k, v in e.items() if k not in OURS}  # other settings sharing the entry stay
            if not e:
                continue
        control.append(e)
    if image is not None:
        control += [{DEFAULT: f"/{image}"}, {TIMEOUT: str(TIMEOUT_SECONDS)}, {SECONDARY: "0"}]
    if control:
        root["control"] = control
    else:
        root.pop("control", None)
    return write(root) + "\n" if root else None


def _read(f: Path) -> str | None:
    if not f.exists():
        return None
    try:
        return f.read_text("utf-8")
    except UnicodeDecodeError:
        raise OSError(
            "ventoy.json on the drive isn't UTF-8 text. Fix or remove it on a computer, then try again."
        ) from None


def read_boot_next(mount: Path) -> str | None:
    return boot_next(_read(mount / PATH))


def set_boot_next(mount: Path, image: str | None) -> None:
    """On a mounted Ventoy partition."""
    f = mount / PATH
    old = _read(f)
    new = with_boot_next(old, image)
    if new == old:
        return
    if new is None:
        f.unlink(missing_ok=True)
        return
    f.parent.mkdir(exist_ok=True)
    tmp = f.with_name(f.name + ".new")
    try:
        tmp.write_text(new, "utf-8")
        tmp.replace(f)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
