"""'Starts on its own' for Ventoy (same as the Android app): sets which image Ventoy boots with no
input, through Ventoy's documented /ventoy/ventoy.json "control" settings. Everything else in an
existing ventoy.json is kept exactly, and a broken one is reported, never overwritten."""

from __future__ import annotations

from pathlib import Path

PATH = "ventoy/ventoy.json"
TIMEOUT_SECONDS = 5
DEFAULT = "VTOY_DEFAULT_IMAGE"
TIMEOUT = "VTOY_MENU_TIMEOUT"
SECONDARY = "VTOY_SECONDARY_BOOT_MENU"  # Ventoy's second menu would wait for a button
OURS = {DEFAULT, TIMEOUT, SECONDARY}


class JsonError(ValueError):
    pass


class Num(str):
    """A JSON number kept exactly as written."""


def parse(text: str):
    p = _Parser(text)
    v = p.value()
    p.ws()
    if p.i != len(text):
        p.fail("unexpected text after the end")
    return v


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
        if c == "{":
            return self.obj()
        if c == "[":
            return self.arr()
        if c == '"':
            return self.str_()
        for word, v in (("true", True), ("false", False), ("null", None)):
            if self.s.startswith(word, self.i):
                self.i += len(word)
                return v
        if c == "-" or c.isdigit():
            start = self.i
            while self.i < len(self.s) and (self.s[self.i].isdigit() or self.s[self.i] in "+-.eE"):
                self.i += 1
            return Num(self.s[start : self.i])
        self.fail(f"unexpected '{c}'")

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
                out.append(c)
                continue
            if self.i >= len(self.s):
                self.fail("unfinished escape")
            e = self.s[self.i]
            self.i += 1
            if e in esc:
                out.append(esc[e])
            elif e == "u":
                try:
                    out.append(chr(int(self.s[self.i : self.i + 4], 16)))
                except ValueError:
                    self.fail("bad \\u escape")
                self.i += 4
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


def _root(text: str | None) -> dict | None:
    if text is None or not text.strip():
        return None
    try:
        v = parse(text.removeprefix("﻿"))
    except JsonError as e:
        raise OSError(
            f"ventoy.json on the drive has an error ({e}). Fix or remove it on a computer, then try again."
        ) from None
    if not isinstance(v, dict):
        raise OSError("ventoy.json on the drive isn't a JSON object. Fix or remove it on a computer.")
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
    root = _root(text) or {}
    old = root.get("control")
    if old is None:
        old = []
    elif not isinstance(old, list):
        raise OSError(
            'ventoy.json on the drive has a "control" setting that isn\'t a list. '
            "Fix or remove it on a computer."
        )
    control = [e for e in old if not (isinstance(e, dict) and any(k in OURS for k in e))]
    if image is not None:
        control += [{DEFAULT: f"/{image}"}, {TIMEOUT: str(TIMEOUT_SECONDS)}, {SECONDARY: "0"}]
    if control:
        root["control"] = control
    else:
        root.pop("control", None)
    return write(root) + "\n" if root else None


def read_boot_next(mount: Path) -> str | None:
    f = mount / PATH
    return boot_next(f.read_text("utf-8") if f.exists() else None)


def set_boot_next(mount: Path, image: str | None) -> None:
    """On a mounted Ventoy partition."""
    f = mount / PATH
    old = f.read_text("utf-8") if f.exists() else None
    new = with_boot_next(old, image)
    if new == old:
        return
    if new is None:
        f.unlink(missing_ok=True)
        return
    f.parent.mkdir(exist_ok=True)
    tmp = f.with_name(f.name + ".new")
    tmp.write_text(new, "utf-8")
    tmp.replace(f)
