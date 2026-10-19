"""Handheld Forge window: pick a system (or your own file, Ventoy, Format drive), pick a USB
drive, create. The same screens and wording as the Android app.

One panel that changes in place (choose, confirm, working, done, error, Ventoy's files, Labs),
sized for a 7 inch handheld screen, usable by touch, mouse, keyboard or D-pad.
"""

from __future__ import annotations

import subprocess
import sys
import threading
import time
import urllib.error
from pathlib import Path

from PySide6.QtCore import QObject, QPointF, QRectF, QSize, Qt, QThread, QTimer, Signal
from PySide6.QtGui import (
    QColor, QFont, QKeySequence, QLinearGradient, QPainter, QPainterPath, QPen, QShortcut,
)
from PySide6.QtWidgets import (
    QAbstractButton, QApplication, QButtonGroup, QCheckBox, QFileDialog, QHBoxLayout, QLabel,
    QMainWindow, QMenu, QPlainTextEdit, QPushButton, QScrollArea, QSizePolicy, QStackedWidget,
    QToolButton, QVBoxLayout, QWidget,
)

from . import APP_NAME, __version__, jobs, settings
from .builder import BuildError
from .builder import Cancelled as BuildCancelled
from .maker import make_drive
from .rawwrite import Cancelled as RawCancelled
from .rawwrite import RawWriteError
from .udisks import UDisksError
from .config import Distro, experimental_variants, load_boot_menu, load_catalog, load_labs
from .download import Cancelled as DownloadCancelled
from .download import VerificationError, cache_dir, prepare_iso
from .progress import Progress, describe
from .preflight import SleepBlocker, can_start, checks, explain_network, read_state
from .drives import GB, Drive, list_drives

# ---------------------------------------------------------------- palette

SLATE = QColor("#1B2026")
IRON = QColor("#252B33")
IRON_HI = QColor("#2C333C")
SEAM = QColor("#353D47")
ASH = QColor("#98A2AE")
BONE = QColor("#ECEFF2")
MOLTEN_A = QColor("#F5B83D")
MOLTEN_B = QColor("#E4572E")
DANGER = QColor("#F07167")
INK = QColor("#1A1410")


def molten(rect: QRectF) -> QLinearGradient:
    g = QLinearGradient(rect.topLeft(), rect.topRight())
    g.setColorAt(0, MOLTEN_A)
    g.setColorAt(1, MOLTEN_B)
    return g


STYLE = f"""
QWidget {{ background: {SLATE.name()}; color: {BONE.name()}; }}
QLabel {{ background: transparent; }}
QLabel[role="title"] {{ font-size: 24px; font-weight: 600; }}
QLabel[role="hint"] {{ color: {ASH.name()}; font-size: 15px; }}
QLabel[role="brand"] {{ color: {ASH.name()}; font-size: 13px; font-weight: 600; }}
QLabel[role="big"] {{ font-size: 56px; font-weight: 300; }}
QPushButton[role="quiet"] {{
    background: transparent; color: {ASH.name()}; border: 1px solid {SEAM.name()};
    border-radius: 10px; padding: 12px 22px; font-size: 15px;
}}
QPushButton[role="quiet"]:hover, QPushButton[role="quiet"]:focus {{
    color: {BONE.name()}; border-color: {ASH.name()};
}}
QPlainTextEdit {{
    background: {IRON.name()}; border: none; border-radius: 10px; padding: 8px;
    color: {ASH.name()}; font-family: monospace; font-size: 12px;
}}
QScrollArea {{ border: none; }}
QToolButton {{ color: {ASH.name()}; border: none; font-size: 13px; font-weight: 600; padding: 2px 4px; }}
QToolButton:hover, QToolButton:focus {{ color: {BONE.name()}; }}
QToolButton::menu-indicator {{ image: none; }}
QMenu {{ background: {IRON.name()}; border: 1px solid {SEAM.name()}; padding: 6px; }}
QMenu::item {{ padding: 8px 18px; border-radius: 6px; }}
QMenu::item:selected {{ background: {SEAM.name()}; }}
QScrollBar:vertical {{ background: transparent; width: 8px; margin: 0; }}
QScrollBar::handle:vertical {{ background: {SEAM.name()}; border-radius: 4px; min-height: 30px; }}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{ height: 0; }}
QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical {{ background: transparent; }}
QLabel[role="banner"] {{
    background: {IRON.name()}; border: 1px solid {MOLTEN_A.name()}; border-radius: 10px;
    padding: 10px 14px; font-size: 14px;
}}
QWidget#ack {{ background: {IRON.name()}; border: 1px solid {SEAM.name()}; border-radius: 10px; }}
QWidget#ack QLabel, QWidget#ack QCheckBox {{ background: transparent; font-size: 14px; }}
QWidget#card {{ background: {IRON.name()}; border-radius: 14px; }}
QWidget#card QLabel {{ background: transparent; }}
QLabel[role="cardtitle"] {{ font-size: 16px; font-weight: 600; }}
QLabel[role="small"] {{ color: {ASH.name()}; font-size: 13px; }}
QLabel[role="warn"] {{ color: {MOLTEN_A.name()}; font-size: 13px; }}
QLabel[role="error"] {{ color: {DANGER.name()}; font-size: 14px; }}
QToolButton#labs {{ color: {MOLTEN_A.name()}; }}
QPushButton[role="quiet"]:disabled {{ color: {SEAM.name()}; border-color: {SEAM.name()}; }}
"""

# Steps shown while working. The engine's stages map onto these.
STEPS = ["Download", "Verify", "Prepare", "Copy", "Check"]
# Systems written byte for byte show "Write" where the copy builder shows "Copy".
RAW_STEPS = ["Download", "Verify", "Prepare", "Write", "Check"]


def label(text: str = "", role: str | None = None) -> QLabel:
    lab = QLabel(text)
    if role:
        lab.setProperty("role", role)
    lab.setWordWrap(True)
    return lab


def guide_html(steps: list[str], ventoy: bool = False) -> str:
    """The install guide as rich text: boot menu tips, then numbered steps. For Ventoy, how to use
    it first, then the general boot menu tips."""
    def esc(t: str) -> str:
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    head = f'<p style="color:{ASH.name()}; font-size:13px; font-weight:600">'
    lines = load_boot_menu()
    if ventoy:
        lines = [x for x in lines if "Bazzite" not in x and "Secure Boot" not in x]
    menu = "".join(f"<p>{esc(line)}</p>" for line in lines)
    num = f'<span style="color:{MOLTEN_A.name()}">'
    numbered = "".join(f"<p>{num}{i}.</span>&nbsp; {esc(s)}</p>" for i, s in enumerate(steps, 1))
    if ventoy:
        body = f"{head}How to use it</p>{numbered}{head}Open the boot menu</p>{menu}"
    else:
        body = f"{head}Open the boot menu</p>{menu}{head}Then install</p>{numbered}"
    return f'<div style="font-size:14px">{body}</div>'


def quiet_button(text: str) -> QPushButton:
    b = QPushButton(text)
    b.setProperty("role", "quiet")
    b.setCursor(Qt.PointingHandCursor)
    return b


UNLOCK_DISTRO = "bazzite-deck"
UNLOCK_TAPS = 10
TAP_GAP = 3.0  # seconds; a longer pause starts the count again


def _unlock_marker() -> Path:
    from .installer import Paths
    return Paths.current().app_dir / "experimental"


def experimental_unlocked() -> bool:
    try:
        return _unlock_marker().exists()
    except OSError:
        return False


def remember_experimental(on: bool) -> None:
    """Kept in the app's own folder, so uninstalling removes it with everything else."""
    m = _unlock_marker()
    try:
        if not on:
            m.unlink(missing_ok=True)
        elif m.parent.is_dir():  # not installed (running from the source): just this session
            m.write_text("on\n")
    except OSError:
        pass


# ---------------------------------------------------------------- widgets

class Tile(QAbstractButton):
    """Selectable card. Selected = molten edge; disabled = dimmed with a 'Soon' tag. A long
    subtitle wraps onto a second line when the tile is made taller (two_lines)."""

    def __init__(self, title: str, subtitle: str, icon: str | None = None, tag: str = "",
                 two_lines: bool = False) -> None:
        super().__init__()
        self.title, self.subtitle, self.icon, self.tag = title, subtitle, icon, tag
        self.two_lines = two_lines
        self.setCheckable(True)
        self.setCursor(Qt.PointingHandCursor)
        self.setFocusPolicy(Qt.StrongFocus)
        self.setMinimumHeight(self._height())
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)

    def _height(self) -> int:
        return 104 if self.two_lines else 84

    def sizeHint(self) -> QSize:
        return QSize(160, self._height())

    def paintEvent(self, _) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(1.5, 1.5, -1.5, -1.5)
        if not self.isEnabled():
            p.setOpacity(0.45)
        p.setPen(Qt.NoPen)
        p.setBrush(IRON_HI if self.underMouse() and self.isEnabled() else IRON)
        p.drawRoundedRect(r, 14, 14)
        if self.isChecked():
            p.setPen(QPen(molten(r), 2.5))
            p.setBrush(Qt.NoBrush)
            p.drawRoundedRect(r, 14, 14)
        elif self.hasFocus():
            p.setPen(QPen(ASH, 1.5, Qt.DashLine))
            p.setBrush(Qt.NoBrush)
            p.drawRoundedRect(r.adjusted(3, 3, -3, -3), 11, 11)

        x = 20.0
        if self.icon == "usb":
            self._usb(p, QPointF(x, r.center().y()))
            x += 44
        f = QFont(self.font())
        f.setPixelSize(18)
        f.setWeight(QFont.DemiBold)
        p.setFont(f)
        p.setPen(BONE)
        text_w = int(r.width() - x - 16)
        title = p.fontMetrics().elidedText(self.title, Qt.ElideRight, text_w)
        p.drawText(QRectF(x, r.top() + 18, text_w, 26), Qt.AlignLeft | Qt.AlignVCenter, title)
        f.setPixelSize(14)
        f.setWeight(QFont.Normal)
        p.setFont(f)
        p.setPen(ASH)
        if self.two_lines:
            p.drawText(QRectF(x, r.top() + 46, text_w, 42), Qt.AlignLeft | Qt.AlignTop | Qt.TextWordWrap,
                       self.subtitle)
        else:
            sub = p.fontMetrics().elidedText(self.subtitle, Qt.ElideRight, text_w)
            p.drawText(QRectF(x, r.top() + 44, text_w, 22), Qt.AlignLeft | Qt.AlignVCenter, sub)

        if self.tag:
            f.setPixelSize(12)
            f.setWeight(QFont.DemiBold)
            p.setFont(f)
            tw = p.fontMetrics().horizontalAdvance(self.tag) + 16
            tr = QRectF(r.right() - tw - 10, r.top() + 10, tw, 22)
            p.setPen(Qt.NoPen)
            p.setBrush(SEAM)
            p.drawRoundedRect(tr, 11, 11)
            p.setPen(BONE)
            p.drawText(tr, Qt.AlignCenter, self.tag)

    def _usb(self, p: QPainter, c: QPointF) -> None:
        """A USB stick, drawn sideways."""
        body = QRectF(c.x(), c.y() - 10, 22, 20)
        plug = QRectF(c.x() + 22, c.y() - 6, 9, 12)
        p.setPen(Qt.NoPen)
        p.setBrush(molten(body) if self.isChecked() else ASH)
        p.drawRoundedRect(body, 4, 4)
        p.setBrush(SEAM.lighter(160))
        p.drawRoundedRect(plug, 2, 2)
        p.setBrush(SLATE)
        p.drawRect(QRectF(plug.x() + 3, plug.y() + 3, 3, 2))
        p.drawRect(QRectF(plug.x() + 3, plug.y() + 7, 3, 2))


class EmptySlot(QWidget):
    """Dashed placeholder where a drive will appear once plugged in."""

    def __init__(self, text: str = "Plug in a USB drive of 16 GB or more") -> None:
        super().__init__()
        self.setMinimumHeight(84)
        self.text = text

    def paintEvent(self, _) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(1.5, 1.5, -1.5, -1.5)
        p.setPen(QPen(SEAM, 1.5, Qt.DashLine))
        p.setBrush(Qt.NoBrush)
        p.drawRoundedRect(r, 14, 14)
        f = QFont(self.font())
        f.setPixelSize(15)
        p.setFont(f)
        p.setPen(ASH)
        p.drawText(r.adjusted(16, 0, -16, 0), Qt.AlignCenter | Qt.TextWordWrap, self.text)


class StatusMark(QWidget):
    """Large ring with a check (done), a bang (error) or a bar (stopped)."""

    def __init__(self) -> None:
        super().__init__()
        self.kind = "ok"
        self.setFixedSize(64, 64)

    def set_kind(self, kind: str) -> None:
        self.kind = kind
        self.update()

    def paintEvent(self, _) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(4, 4, 56, 56)
        brush = {"ok": molten(r), "error": DANGER, "cancelled": ASH}[self.kind]
        p.setPen(QPen(brush, 4))
        p.setBrush(Qt.NoBrush)
        p.drawEllipse(r)
        pen = QPen(brush, 5, Qt.SolidLine, Qt.RoundCap, Qt.RoundJoin)
        p.setPen(pen)
        if self.kind == "ok":
            path = QPainterPath(QPointF(20, 33))
            path.lineTo(28, 41)
            path.lineTo(44, 24)
            p.drawPath(path)
        elif self.kind == "error":
            p.drawLine(QPointF(32, 19), QPointF(32, 35))
            p.drawPoint(QPointF(32, 44))
        else:
            p.drawLine(QPointF(22, 32), QPointF(42, 32))


class MoltenButton(QAbstractButton):
    def __init__(self, text: str) -> None:
        super().__init__()
        self.setText(text)
        self.setCursor(Qt.PointingHandCursor)
        self.setFocusPolicy(Qt.StrongFocus)
        self.setMinimumHeight(64)
        self.danger = False

    def sizeHint(self) -> QSize:
        return QSize(240, 64)

    def paintEvent(self, _) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(1, 1, -1, -1)
        if not self.isEnabled():
            p.setPen(Qt.NoPen)
            p.setBrush(IRON)
            p.drawRoundedRect(r, 16, 16)
            p.setPen(ASH.darker(130))
        else:
            p.setPen(Qt.NoPen)
            if self.danger:
                p.setBrush(DANGER)
            else:
                p.setBrush(molten(r))
            if self.isDown():
                p.setOpacity(0.85)
            p.drawRoundedRect(r, 16, 16)
            p.setOpacity(1)
            if self.hasFocus():
                p.setPen(QPen(BONE, 2))
                p.setBrush(Qt.NoBrush)
                p.drawRoundedRect(r.adjusted(4, 4, -4, -4), 12, 12)
            p.setPen(QColor("#1A1410"))
        f = QFont(self.font())
        f.setPixelSize(19)
        f.setWeight(QFont.Bold)
        p.setFont(f)
        p.drawText(r, Qt.AlignCenter, self.text())


class HeatBar(QWidget):
    """Progress bar that glows: the filled part is molten with a slow heat shimmer."""

    def __init__(self) -> None:
        super().__init__()
        self.value = 0.0  # what's drawn; glides toward target, left to right
        self.target = 0.0
        self.busy = False
        self.phase = 0.0
        self.setFixedHeight(14)
        self.timer = QTimer(self, interval=40, timeout=self._tick)

    def set_value(self, v: float, busy: bool = False) -> None:
        self.target, self.busy = max(0.0, min(1.0, v)), busy
        if self.target < self.value:  # a new job: start again from the left
            self.value = self.target
        if not self.timer.isActive():
            self.timer.start()
        self.update()

    def stop(self) -> None:
        self.timer.stop()
        self.update()

    def _tick(self) -> None:
        self.phase = (self.phase + 0.012) % 1.0
        if self.value < self.target:
            self.value = min(self.target, self.value + max(0.002, (self.target - self.value) * 0.15))
        self.update()

    def paintEvent(self, _) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect())
        p.setPen(Qt.NoPen)
        p.setBrush(IRON)
        p.drawRoundedRect(r, 7, 7)
        w = r.width() * self.value
        if w < 1:
            return
        fill = QRectF(r.x(), r.y(), max(w, r.height()), r.height())
        clip = QPainterPath()
        clip.addRoundedRect(fill, 7, 7)
        p.setClipPath(clip)
        p.setBrush(molten(r))
        p.drawRect(fill)
        if self.timer.isActive():
            sx = r.x() + (self.phase * 1.6 - 0.3) * r.width()
            g = QLinearGradient(QPointF(sx - 60, 0), QPointF(sx + 60, 0))
            g.setColorAt(0, QColor(255, 255, 255, 0))
            g.setColorAt(0.5, QColor(255, 240, 200, 90))
            g.setColorAt(1, QColor(255, 255, 255, 0))
            p.setBrush(g)
            p.drawRect(fill)


class StepTrack(QWidget):
    """Download, Verify, Prepare, Copy, Check: done steps solid, current one molten."""

    def __init__(self) -> None:
        super().__init__()
        self.current = 0
        self.failed = False
        self.steps = STEPS
        self.setFixedHeight(46)

    def set_step(self, i: int, failed: bool = False) -> None:
        self.current, self.failed = i, failed
        self.update()

    def paintEvent(self, _) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        n = len(self.steps)
        w = self.width()
        xs = [w * (i + 0.5) / n for i in range(n)]
        y = 10.0
        p.setPen(QPen(SEAM, 2))
        p.drawLine(QPointF(xs[0], y), QPointF(xs[-1], y))
        f = QFont(self.font())
        f.setPixelSize(13)
        for i, x in enumerate(xs):
            dot = QRectF(x - 6, y - 6, 12, 12)
            p.setPen(Qt.NoPen)
            if i < self.current:
                p.setBrush(ASH)
            elif i == self.current:
                p.setBrush(DANGER if self.failed else molten(dot))
            else:
                p.setBrush(SEAM)
            p.drawEllipse(dot)
            f.setWeight(QFont.DemiBold if i == self.current else QFont.Normal)
            p.setFont(f)
            p.setPen(BONE if i == self.current else ASH)
            p.drawText(QRectF(x - 50, y + 12, 100, 20), Qt.AlignCenter, self.steps[i])


class Switch(QAbstractButton):
    """The on/off pill from the Android app's Labs screen."""

    def __init__(self) -> None:
        super().__init__()
        self.setCheckable(True)
        self.setCursor(Qt.PointingHandCursor)
        self.setFocusPolicy(Qt.StrongFocus)
        self.setFixedSize(48, 28)

    def paintEvent(self, _) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        p.setPen(QPen(BONE, 1.5) if self.hasFocus() else Qt.NoPen)
        p.setBrush(molten(r) if self.isChecked() else SEAM)
        p.drawRoundedRect(r, 14, 14)
        x = r.right() - 25 if self.isChecked() else r.left() + 3
        knob = QRectF(x, r.top() + 3, 22, 22)
        p.setPen(Qt.NoPen)
        p.setBrush(INK if self.isChecked() else ASH)
        p.drawEllipse(knob)


class Card(QWidget):
    """A dark rounded row: a title, a line of detail, an optional amber note, and a control on
    the right. Used for Labs settings and tools, and for files on the Ventoy screen."""

    clicked = Signal()

    def __init__(self, title: str, detail: str = "", control: QWidget | None = None) -> None:
        super().__init__()
        self.setObjectName("card")
        self.setAttribute(Qt.WA_StyledBackground, True)
        self.selected = False
        row = QHBoxLayout(self)
        row.setContentsMargins(16, 12, 10, 12)
        col = QVBoxLayout()
        col.setSpacing(3)
        self.title = label(title, "cardtitle")
        self.detail = label(detail, "small")
        self.note = label("", "warn")
        self.note.setVisible(False)
        col.addWidget(self.title)
        col.addWidget(self.detail)
        col.addWidget(self.note)
        row.addLayout(col, 1)
        if control is not None:
            row.addSpacing(10)
            row.addWidget(control, 0, Qt.AlignVCenter)
        self.control = control

    def set_note(self, text: str | None) -> None:
        self.note.setText(text or "")
        self.note.setVisible(bool(text))

    def set_selected(self, on: bool) -> None:
        self.selected = on
        self.update()

    def mousePressEvent(self, e) -> None:
        if e.button() == Qt.LeftButton:
            self.clicked.emit()
        super().mousePressEvent(e)

    def paintEvent(self, e) -> None:
        super().paintEvent(e)
        if self.selected:
            p = QPainter(self)
            p.setRenderHint(QPainter.Antialiasing)
            r = QRectF(self.rect()).adjusted(1.25, 1.25, -1.25, -1.25)
            p.setPen(QPen(molten(r), 2.5))
            p.setBrush(Qt.NoBrush)
            p.drawRoundedRect(r, 14, 14)


def gb(n: int) -> str:
    return f"{n / 1e9:.1f} GB"


# ---------------------------------------------------------------- worker

SYSTEM, FILE, VENTOY, FORMAT = "system", "file", "ventoy", "format"
IMPORT, TEST, CHECK = "import", "test", "check"
RAW_JOBS = {FILE, VENTOY, TEST, CHECK}


def ventoy_steps(secure_boot: bool) -> list[str]:
    return [
        "Add images: click Add images below, or copy ISO files onto the drive's Ventoy partition.",
        "Plug the drive into your handheld and start it from USB.",
        "If Secure Boot is on, the first start shows a blue key screen: choose Enroll key from disk, "
        "open VTOYEFI, pick ENROLL_THIS_KEY_IN_MOKMANAGER.cer, confirm, and restart. Only needed once "
        "per handheld."
        if secure_boot else
        "This drive was made without Secure Boot support, so turn Secure Boot off in the BIOS first.",
        "Ventoy shows a menu of the images on the drive. Pick one to start it. If the controls don't work in "
        "that menu, click an image under Add images and Ventoy will start it on its own.",
    ]


class Worker(QObject):
    stage = Signal(str, int, int)
    log = Signal(str)
    finished = Signal(str, str)  # status: ok | cancelled | error, message

    def __init__(self, distro: Distro | None, drive: Drive, job: str = SYSTEM, **args) -> None:
        super().__init__()
        self.distro, self.drive, self.job, self.args = distro, drive, job, args
        self.cancel = threading.Event()
        self.result = None  # what the job returned (Ventoy's version, a test report, names added)
        self.settings = settings.load()

    def _run_job(self):
        s, st, lg, c = self.settings, self.stage.emit, self.log.emit, self.cancel
        if self.job == SYSTEM:
            stream = s.streaming and self.distro.needs_password
            img = prepare_iso(self.distro, st, lg, c, stream=stream)
            if c.is_set():
                raise DownloadCancelled()
            jobs.log_drive(self.drive, lg)
            make_drive(self.distro, self.drive, img.path, st, lg, c, verify=s.verify, live=img.live)
            return None
        if self.job == FILE:
            return jobs.write_own_file(self.drive, self.args["file"], st, lg, c, verify=s.verify)
        if self.job == VENTOY:
            return jobs.install_ventoy(self.drive, self.args["secure_boot"], st, lg, c)
        if self.job == FORMAT:
            return jobs.format_drive(self.drive, st, lg, c)
        if self.job == IMPORT:
            return jobs.import_images(self.drive, self.args["plans"], st, lg, c, verify=s.verify)
        if self.job == TEST:
            return jobs.test_drive(self.drive, st, lg, c)
        if self.job == CHECK:
            return jobs.check_drive(self.drive, self.args["image"], st, lg, c)
        raise ValueError(self.job)

    def run(self) -> None:
        try:
            with SleepBlocker():
                self.result = self._run_job()
            self.finished.emit("ok", "")
        except DownloadCancelled:
            self.finished.emit("cancelled", "Download paused. It picks up where it left off next time.")
        except (BuildCancelled, RawCancelled):
            if self.job == TEST:
                msg = "Stopped. The drive holds test data now; use Format drive to make it usable."
            elif self.job == CHECK:
                msg = "Stopped. Nothing on the drive was changed."
            elif self.job == IMPORT:
                msg = "Stopped. Images that finished copying are on the drive; the one in progress isn't."
            else:
                msg = "Stopped. The drive is unfinished, so create it again before booting from it."
            self.finished.emit("cancelled", msg)
        except (BuildError, RawWriteError, UDisksError, VerificationError) as e:
            self.finished.emit("error", str(e))
        except urllib.error.HTTPError as e:
            self.finished.emit("error", f"The download server answered {e.code}. Try again later.")
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            self.log.emit(f"{type(e).__name__}: {e}")
            self.finished.emit("error", explain_network(str(e)))
        except Exception as e:  # anything unexpected still lands in the window
            self.log.emit(f"{type(e).__name__}: {e}")
            self.finished.emit("error", str(e) or type(e).__name__)


class Task(QObject):
    """Runs a quick drive operation (reading Ventoy's files, removing one) off the window's thread."""

    done = Signal(object, object)  # result, error

    def __init__(self, fn, parent: QObject) -> None:
        super().__init__(parent)
        self.fn = fn

    def start(self) -> None:
        def run():
            try:
                r, err = self.fn(), None
            except Exception as e:  # shown in the window
                r, err = None, e
            self.done.emit(r, err)
        threading.Thread(target=run, daemon=True).start()


LABS_TAPS = 10


# ---------------------------------------------------------------- window

class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle(APP_NAME)
        self.resize(620, 700)
        self.setMinimumSize(480, 480)
        self.catalog = load_catalog()
        self.experimental = experimental_variants(self.catalog)
        self.labs_systems = load_labs()
        self.settings = settings.load()
        self.thread: QThread | None = None
        self.worker: Worker | None = None
        self.drives: list[Drive] = []
        self.ventoy_paths: set[str] = set()
        self.own_file: jobs.OwnFile | None = None
        self.picked: list[jobs.ImportPlan] = []
        self.ventoy_info: jobs.VentoyInfo | None = None
        self.secure_boot = True
        self.progress = Progress()
        self._step = 0
        self._tasks: list[Task] = []

        self.stack = QStackedWidget()
        self.choose_page = self._build_choose()
        self.confirm_page = self._build_confirm()
        self.work_page = self._build_work()
        self.result_page = self._build_result()
        self.ventoy_page = self._build_ventoy()
        self.labs_page = self._build_labs()
        for page in (self.choose_page, self.confirm_page, self.work_page, self.result_page,
                     self.ventoy_page, self.labs_page):
            self.stack.addWidget(page)

        root = QWidget()
        lay = QVBoxLayout(root)
        lay.setContentsMargins(32, 22, 32, 28)
        head = QHBoxLayout()
        head.setSpacing(6)
        head.addWidget(label(APP_NAME, "brand"))
        head.addStretch()
        self.labs_btn = QToolButton(text="Labs")
        self.labs_btn.setObjectName("labs")
        self.labs_btn.setCursor(Qt.PointingHandCursor)
        self.labs_btn.clicked.connect(self._open_labs)
        head.addWidget(self.labs_btn)
        # Tapping the version ten times turns Labs on, as on Android. The arrow opens the app menu.
        self.version_btn = QToolButton(text=f"v{__version__}")
        self.version_btn.setCursor(Qt.PointingHandCursor)
        self.version_btn.clicked.connect(self._tap_version)
        head.addWidget(self.version_btn)
        self.app_menu_btn = QToolButton(text="▾")
        self.app_menu_btn.setPopupMode(QToolButton.InstantPopup)
        self.app_menu_btn.setCursor(Qt.PointingHandCursor)
        menu = QMenu(self.app_menu_btn)
        menu.addAction("Check for updates", lambda: self._run_installer("update"))
        menu.addAction(f"Uninstall {APP_NAME}", lambda: self._run_installer("uninstall"))
        self.hide_exp_action = menu.addAction("Hide experimental options",
                                              lambda: self._set_experimental(False))
        self.hide_exp_action.setVisible(not self.exp_box.isHidden())
        self.labs_off_action = menu.addAction("Turn off Labs", lambda: self._set_labs(False))
        self.app_menu_btn.setMenu(menu)
        head.addWidget(self.app_menu_btn)
        lay.addLayout(head)
        lay.addSpacing(14)
        lay.addWidget(self.stack, 1)
        self.setCentralWidget(root)
        self._vtaps, self._last_vtap = 0, 0.0
        self._show_labs(self.settings.labs)

        QShortcut(QKeySequence(Qt.Key_Escape), self, activated=self._escape)
        self.refresh_drives()
        self.poll = QTimer(self, interval=2000, timeout=self._poll_drives)
        self.poll.start()

    # ------------------------------------------------------------ pages

    def _build_choose(self) -> QWidget:
        page = QWidget()
        lay = QVBoxLayout(page)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(10)
        lay.addWidget(label("Pick a system and a USB drive.", "title"))
        lay.addSpacing(6)

        row = QHBoxLayout()
        row.setSpacing(12)
        self.os_group = QButtonGroup(self)
        self.os_group.setExclusive(True)
        for d in self.catalog:
            t = Tile(d.name, d.edition, tag="" if d.enabled else "Soon")
            t.setEnabled(d.enabled)
            if d.needs_password:
                t.setToolTip(f"{d.name} is written to the drive byte for byte, which Linux only allows "
                             "after your password. You'll be asked once, when writing starts.")
            t.setProperty("distro", d.id)
            self.os_group.addButton(t)
            row.addWidget(t)
        lay.addLayout(row)
        # Untested options stay hidden until the Bazzite tile is tapped UNLOCK_TAPS times in a row,
        # like Android's developer options, so nobody picks one by accident.
        self.exp_box = QWidget()
        box = QVBoxLayout(self.exp_box)
        box.setContentsMargins(0, 0, 0, 0)
        box.setSpacing(12)
        box.addWidget(label("Experimental, untested", "hint"))
        row = QHBoxLayout()
        row.setSpacing(12)
        for d in self.experimental:
            t = Tile(d.name, d.edition)
            t.setToolTip(d.warning or "")
            t.setProperty("distro", d.id)
            self.os_group.addButton(t)
            row.addWidget(t)
        row.addStretch(1)
        box.addLayout(row)
        lay.addWidget(self.exp_box)
        self.exp_box.setVisible(bool(self.experimental) and experimental_unlocked())
        # Labs systems (Batocera): a full-width row each, tagged, once Labs is on.
        self.labs_box = QWidget()
        box = QVBoxLayout(self.labs_box)
        box.setContentsMargins(0, 0, 0, 0)
        box.setSpacing(10)
        for d in self.labs_systems:
            t = Tile(d.name, d.edition, tag="Labs")
            t.setToolTip(d.warning or "")
            t.setProperty("distro", d.id)
            self.os_group.addButton(t)
            box.addWidget(t)
        lay.addWidget(self.labs_box)
        self._taps, self._last_tap = 0, 0.0
        bazzite = next((b for b in self.os_group.buttons() if b.property("distro") == UNLOCK_DISTRO), None)
        if bazzite:
            bazzite.clicked.connect(self._tap_unlock)

        # Anything else: an ISO or disk image already on this computer.
        self.file_tile = Tile("Your own file", "Pick an ISO or disk image on this computer")
        self.file_tile.setProperty("tool", FILE)
        self.file_tile.pressed.connect(lambda: setattr(self, "_file_was_checked", self.file_tile.isChecked()))
        self.file_tile.clicked.connect(self._file_tile_clicked)
        self.os_group.addButton(self.file_tile)
        lay.addWidget(self.file_tile)
        row = QHBoxLayout()
        row.setSpacing(12)
        self.ventoy_tile = Tile("Ventoy", "Multi-boot: many images, one drive", two_lines=True)
        self.ventoy_tile.setProperty("tool", VENTOY)
        self.format_tile = Tile("Format drive", "Empty exFAT drive for files", two_lines=True)
        self.format_tile.setProperty("tool", FORMAT)
        for t in (self.ventoy_tile, self.format_tile):
            self.os_group.addButton(t)
            row.addWidget(t)
        lay.addLayout(row)

        first = next((b for b in self.os_group.buttons() if b.isEnabled()), None)
        if first:
            first.setChecked(True)
        self.os_group.buttonToggled.connect(lambda *_: self._update_ready())
        lay.addSpacing(4)

        self.drive_box = QWidget()
        self.drive_list = QVBoxLayout(self.drive_box)
        self.drive_list.setContentsMargins(0, 0, 0, 0)
        self.drive_list.setSpacing(10)
        self.empty_slot = EmptySlot()
        self.drive_list.addWidget(self.empty_slot)
        self.drive_list.addStretch()
        self.drive_group = QButtonGroup(self)
        self.drive_group.setExclusive(True)
        self.drive_group.buttonToggled.connect(lambda *_: self._update_ready())
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(self.drive_box)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        lay.addWidget(scroll, 1)

        # A Ventoy drive can take more images without being erased.
        self.ventoy_bar = QWidget()
        self.ventoy_bar.setObjectName("card")
        self.ventoy_bar.setAttribute(Qt.WA_StyledBackground, True)
        bar = QHBoxLayout(self.ventoy_bar)
        bar.setContentsMargins(16, 8, 8, 8)
        bar.addWidget(label("This drive has Ventoy. Add OS images to it without erasing anything.",
                            "hint"), 1)
        add = quiet_button("Add images")
        add.clicked.connect(self._open_ventoy)
        bar.addWidget(add)
        self.ventoy_bar.setVisible(False)
        lay.addWidget(self.ventoy_bar)

        self.empty_hint = label("", "hint")
        lay.addWidget(self.empty_hint)

        self.create_btn = MoltenButton("Create boot drive")
        self.create_btn.clicked.connect(self._ask_confirm)
        lay.addWidget(self.create_btn)
        return page

    def _build_confirm(self) -> QWidget:
        page = QWidget()
        lay = QVBoxLayout(page)
        lay.setContentsMargins(0, 0, 0, 0)
        self.confirm_title = label("", "title")
        self.confirm_text = label("", "hint")
        lay.addWidget(self.confirm_title)
        lay.addSpacing(8)
        lay.addWidget(self.confirm_text)
        lay.addSpacing(20)
        self.confirm_tile = Tile("", "", icon="usb")
        self.confirm_tile.setChecked(True)
        self.confirm_tile.setFocusPolicy(Qt.NoFocus)
        self.confirm_tile.setAttribute(Qt.WA_TransparentForMouseEvents)
        lay.addWidget(self.confirm_tile)
        lay.addSpacing(14)
        # Notes, checks and the tick box scroll when they don't fit (small screens, long notes).
        body = QWidget()
        outer, lay = lay, QVBoxLayout(body)
        lay.setContentsMargins(0, 0, 8, 0)
        # Ventoy: with or without Secure Boot support, as on Android.
        self.sb_box = QWidget()
        sb = QVBoxLayout(self.sb_box)
        sb.setContentsMargins(0, 0, 0, 10)
        sb.addWidget(label("Secure Boot support", "brand"))
        row = QHBoxLayout()
        row.setSpacing(12)
        self.sb_group = QButtonGroup(self)
        self.sb_on = Tile("On", "Recommended. Enroll a key once", two_lines=True)
        self.sb_off = Tile("Off", "Needs Secure Boot off in BIOS", two_lines=True)
        for t, on in ((self.sb_on, True), (self.sb_off, False)):
            self.sb_group.addButton(t)
            row.addWidget(t)
            t.clicked.connect(lambda _=False, v=on: self._set_secure_boot(v))
        sb.addLayout(row)
        self.sb_text = label("", "hint")
        sb.addWidget(self.sb_text)
        lay.addWidget(self.sb_box)
        self.confirm_warning = label("", "banner")
        lay.addWidget(self.confirm_warning)
        lay.addSpacing(6)
        lay.addWidget(label("Before you start", "brand"))
        self.check_labels = []
        for _ in range(3):
            lab = label("")
            lab.setTextFormat(Qt.RichText)
            self.check_labels.append(lab)
            lay.addWidget(lab)
        lay.addSpacing(8)
        # QCheckBox can't wrap its text, so the box and a wrapping label share a framed row.
        ack_row = QWidget()
        ack_row.setObjectName("ack")
        ack_lay = QHBoxLayout(ack_row)
        ack_lay.setContentsMargins(12, 10, 12, 10)
        self.ack = QCheckBox()
        self.ack_text = label("I'll leave the drive plugged in and this window open until it's done. "
                              "This can take 30 minutes or more.")
        self.ack_text.mousePressEvent = lambda _: self.ack.toggle()
        ack_lay.addWidget(self.ack, 0, Qt.AlignTop)
        ack_lay.addWidget(self.ack_text, 1)
        self.ack.toggled.connect(lambda _: self._refresh_checks(reread=False))
        lay.addWidget(ack_row)
        lay.addStretch(1)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(body)
        lay = outer
        lay.addWidget(scroll, 1)
        lay.addSpacing(12)
        # Battery and network change while the person reads; keep the list current.
        self.check_timer = QTimer(self, interval=3000, timeout=self._refresh_checks)
        row = QHBoxLayout()
        back = quiet_button("Go back")
        back.clicked.connect(self._leave_confirm)
        self.erase_btn = MoltenButton("Erase and create")
        self.erase_btn.clicked.connect(self.start)
        row.addWidget(back)
        row.addWidget(self.erase_btn, 1)
        lay.addLayout(row)
        return page

    def _build_work(self) -> QWidget:
        page = QWidget()
        lay = QVBoxLayout(page)
        lay.setContentsMargins(0, 0, 0, 0)
        self.work_title = label("", "title")
        lay.addWidget(self.work_title)
        lay.addSpacing(12)
        lay.addWidget(label("Leave the drive plugged in and this window open until it finishes. "
                            "The computer won't go to sleep meanwhile.", "banner"))
        lay.addStretch(1)
        self.percent = label("0%", "big")
        lay.addWidget(self.percent)
        self.bar = HeatBar()
        lay.addWidget(self.bar)
        lay.addSpacing(10)
        self.work_status = label("")  # "Step 4 of 5: Copying files to the drive"
        self.work_status.setStyleSheet("font-size: 16px; font-weight: 600;")
        lay.addWidget(self.work_status)
        lay.addSpacing(4)
        self.work_detail = label("", "hint")  # amounts and time left, or what's going on
        lay.addWidget(self.work_detail)
        lay.addSpacing(8)
        self.work_last = label("")  # the latest thing that happened, from the log
        self.work_last.setStyleSheet(f"color: {ASH.name()}; font-size: 12px;")
        lay.addWidget(self.work_last)
        # Redraws the status every second so timers keep moving between engine updates.
        self.status_timer = QTimer(self, interval=1000, timeout=self._render_status)
        lay.addStretch(1)
        self.track = StepTrack()
        lay.addWidget(self.track)
        lay.addSpacing(18)
        row = QHBoxLayout()
        self.details_btn = quiet_button("Details")
        self.details_btn.clicked.connect(lambda: self.log_view.setVisible(not self.log_view.isVisible()))
        self.cancel_btn = quiet_button("Stop")
        self.cancel_btn.clicked.connect(self.cancel)
        row.addWidget(self.details_btn)
        row.addStretch()
        row.addWidget(self.cancel_btn)
        lay.addLayout(row)
        self.log_view = QPlainTextEdit(readOnly=True)
        self.log_view.setVisible(False)
        self.log_view.setMaximumHeight(150)
        lay.addWidget(self.log_view)
        return page

    def _build_result(self) -> QWidget:
        page = QWidget()
        lay = QVBoxLayout(page)
        lay.setContentsMargins(0, 0, 0, 0)
        # The install guide can be longer than the window, so the top part scrolls.
        body = QWidget()
        inner = QVBoxLayout(body)
        inner.setContentsMargins(0, 0, 8, 0)
        inner.addStretch(1)
        self.result_mark = StatusMark()
        inner.addWidget(self.result_mark)
        inner.addSpacing(16)
        self.result_title = label("", "title")
        self.result_text = label("", "hint")
        self.result_lines = label("")  # a test report or the images added
        self.result_lines.setStyleSheet("font-size: 15px;")
        self.result_after = label("", "hint")
        self.result_guide = label("")
        self.result_guide.setTextFormat(Qt.RichText)
        inner.addWidget(self.result_title)
        inner.addSpacing(8)
        inner.addWidget(self.result_text)
        inner.addSpacing(10)
        inner.addWidget(self.result_lines)
        inner.addWidget(self.result_after)
        inner.addSpacing(14)
        inner.addWidget(self.result_guide)
        inner.addStretch(2)
        self.result_scroll = QScrollArea()
        self.result_scroll.setWidgetResizable(True)
        self.result_scroll.setWidget(body)
        lay.addWidget(self.result_scroll, 1)
        lay.addSpacing(12)
        self.result_log = QPlainTextEdit(readOnly=True)
        self.result_log.setVisible(False)
        self.result_log.setMaximumHeight(160)
        lay.addWidget(self.result_log)
        row = QHBoxLayout()
        self.result_details = quiet_button("Details")
        self.result_details.clicked.connect(
            lambda: self.result_log.setVisible(not self.result_log.isVisible()))
        self.result_done = quiet_button("Done")
        self.result_done.clicked.connect(self._back_to_start)
        self.result_btn = MoltenButton("Make another")
        self.result_btn.clicked.connect(self._result_action)
        row.addWidget(self.result_details)
        row.addWidget(self.result_done)
        row.addWidget(self.result_btn, 1)
        lay.addLayout(row)
        return page

    def _build_ventoy(self) -> QWidget:
        page = QWidget()
        lay = QVBoxLayout(page)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(label("Add images to Ventoy", "title"))
        lay.addSpacing(8)
        self.v_info = label("", "hint")
        lay.addWidget(self.v_info)
        self.v_error = label("", "error")
        self.v_error.setVisible(False)
        lay.addWidget(self.v_error)
        lay.addSpacing(10)
        body = QWidget()
        self.v_body = QVBoxLayout(body)
        self.v_body.setContentsMargins(0, 0, 8, 0)
        self.v_body.setSpacing(8)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(body)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        lay.addWidget(scroll, 1)
        self.v_space = label("", "error")
        self.v_space.setVisible(False)
        lay.addWidget(self.v_space)
        lay.addSpacing(12)
        row = QHBoxLayout()
        row.setSpacing(12)
        back = quiet_button("Back")
        back.clicked.connect(self._back_to_start)
        self.v_more = quiet_button("Pick more")
        self.v_more.clicked.connect(self._pick_images)
        self.v_go = MoltenButton("Pick images")
        self.v_go.clicked.connect(self._ventoy_go)
        row.addWidget(back)
        row.addWidget(self.v_more)
        row.addWidget(self.v_go, 1)
        lay.addLayout(row)
        self._confirm_remove: str | None = None
        self._v_busy = False
        return page

    def _build_labs(self) -> QWidget:
        page = QWidget()
        lay = QVBoxLayout(page)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(label("Labs", "title"))
        lay.addSpacing(8)
        lay.addWidget(label("Experimental settings and tools. They work, but they've had less testing than "
                            "the rest of the app.", "hint"))
        lay.addSpacing(14)
        body = QWidget()
        b = QVBoxLayout(body)
        b.setContentsMargins(0, 0, 8, 0)
        b.setSpacing(10)
        b.addWidget(label("Settings", "brand"))
        self.stream_switch = Switch()
        self.stream_card = Card(
            "Write while downloading",
            "Starts writing as the download comes in, so most of the download time disappears. The download "
            "is still checked at the end; if it fails, make the drive again. For systems written byte "
            "for byte.",
            self.stream_switch)
        self.skip_switch = Switch()
        self.skip_card = Card(
            "Skip the read-back check",
            "Saves close to half the time after writing. A fake or failing drive won't be caught, so only "
            "use it with a drive you trust.", self.skip_switch)
        for card, sw, key in ((self.stream_card, self.stream_switch, "stream"),
                              (self.skip_card, self.skip_switch, "skip_check")):
            sw.setChecked(getattr(self.settings, key))
            sw.toggled.connect(lambda on, k=key: self._set_setting(k, on))
            card.clicked.connect(sw.toggle)
            b.addWidget(card)
        b.addSpacing(10)
        b.addWidget(label("Tools", "brand"))
        self.test_btn = quiet_button("Test")
        self.test_btn.clicked.connect(self._labs_test)
        self.test_card = Card("Test a drive", "", self.test_btn)
        self.check_btn = quiet_button("Check")
        self.check_btn.clicked.connect(self._labs_check)
        self.check_card = Card("Check a drive", "", self.check_btn)
        b.addWidget(self.test_card)
        b.addWidget(self.check_card)
        self.labs_hint = label("", "hint")
        b.addWidget(self.labs_hint)
        b.addStretch(1)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(body)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        lay.addWidget(scroll, 1)
        lay.addSpacing(12)
        back = quiet_button("Back")
        back.clicked.connect(self._back_to_start)
        lay.addWidget(back)
        self._ask_test = False
        return page

    # ------------------------------------------------------------ state

    def _tap_unlock(self) -> None:
        if not self.exp_box.isHidden() or not self.experimental:
            return
        now = time.monotonic()
        self._taps = self._taps + 1 if now - self._last_tap < TAP_GAP else 1
        self._last_tap = now
        left = UNLOCK_TAPS - self._taps
        if left <= 0:
            self._set_experimental(True)
            self.empty_hint.setText("Experimental options are on. Turn them off from the version menu.")
        elif left <= 5:
            self.empty_hint.setText(f"{left} more tap{'s' if left > 1 else ''} to show experimental options")
        else:
            return
        self.empty_hint.setVisible(True)

    def _set_experimental(self, on: bool) -> None:
        self.exp_box.setVisible(on)
        self.hide_exp_action.setVisible(on)
        self._taps = 0
        remember_experimental(on)
        if not on:
            d = self._distro()
            if d and d.experimental:  # a hidden choice can't stay picked
                next(b for b in self.os_group.buttons() if b.isEnabled()).setChecked(True)
            self.empty_hint.setVisible(False)

    def _tap_version(self) -> None:
        """Ten taps in a row on the version turn Labs on, like the Android app."""
        if self.settings.labs:
            self._open_labs()
            return
        now = time.monotonic()
        self._vtaps = self._vtaps + 1 if now - self._last_vtap < TAP_GAP else 1
        self._last_vtap = now
        left = LABS_TAPS - self._vtaps
        if left <= 0:
            self._set_labs(True)
            text = "Labs is on. Open it from Labs at the top, next to the version."
        elif left <= 5:
            text = f"{left} more tap{'s' if left > 1 else ''} to turn on Labs"
        else:
            return
        if self.stack.currentWidget() is self.choose_page:
            self.empty_hint.setText(text)
            self.empty_hint.setVisible(True)

    def _set_labs(self, on: bool) -> None:
        self.settings.labs = on
        settings.save(self.settings)
        self._vtaps = 0
        self._show_labs(on)
        if not on:
            d = self._distro()
            if d and d.labs:
                next(b for b in self.os_group.buttons() if b.isEnabled()).setChecked(True)
            if self.stack.currentWidget() is self.labs_page:
                self._back_to_start()

    def _show_labs(self, on: bool) -> None:
        self.labs_btn.setVisible(on)
        self.labs_off_action.setVisible(on)
        self.labs_box.setVisible(on and bool(self.labs_systems))

    def _set_setting(self, key: str, on: bool) -> None:
        setattr(self.settings, key, on)
        settings.save(self.settings)

    def _choice(self) -> str:
        b = self.os_group.checkedButton()
        return (b.property("tool") or SYSTEM) if b else SYSTEM

    def _distro(self) -> Distro | None:
        b = self.os_group.checkedButton()
        every = self.catalog + self.experimental + self.labs_systems
        return next((d for d in every if b and d.id == b.property("distro")), None)

    def _drive(self) -> Drive | None:
        b = self.drive_group.checkedButton()
        return next((d for d in self.drives if b and d.path == b.property("path")), None)

    def _update_ready(self) -> None:
        choice, drive = self._choice(), self._drive()
        ok = {SYSTEM: self._distro() is not None, FILE: self.own_file is not None}.get(choice, True)
        self.create_btn.setEnabled(ok and drive is not None)
        self.create_btn.setText({FILE: "Write to drive", VENTOY: "Install Ventoy",
                                 FORMAT: "Format drive"}.get(choice, "Create boot drive"))
        self.ventoy_bar.setVisible(drive is not None and drive.path in self.ventoy_paths)

    def _file_tile_clicked(self) -> None:
        if self.own_file is None or getattr(self, "_file_was_checked", False):
            self._pick_own_file()

    def _pick_own_file(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self, "Pick an ISO or disk image", str(Path.home()),
            "Images (*.iso *.img *.raw *.bin *.gz *.xz *.bz2 *.zip *.vhd *.vhdx);;All files (*)")
        if path:
            self.set_own_file(Path(path))
        elif self.own_file is None:
            next(b for b in self.os_group.buttons() if b.isEnabled()).setChecked(True)

    def set_own_file(self, path: Path) -> None:
        try:
            f = jobs.look_at(path)
        except (OSError, RawWriteError) as e:
            self.empty_hint.setText(str(e))
            self.empty_hint.setVisible(True)
            return
        self.own_file = f
        self.file_tile.title = f.path.name
        self.file_tile.subtitle = f"{f.kind.label}, {gb(f.size)}"
        self.file_tile.tag = "Change"
        self.file_tile.setChecked(True)
        self.file_tile.update()
        self.empty_hint.setVisible(False)
        self._update_ready()

    def _poll_drives(self) -> None:
        if self.stack.currentWidget() is self.choose_page:
            self.refresh_drives()

    def refresh_drives(self) -> None:
        try:
            drives = list_drives()
        except Exception as e:
            drives = []
            self.empty_slot.text = f"Couldn't read the drive list: {e}"
        if [d.path for d in drives] == [d.path for d in self.drives] and self.drive_group.buttons():
            return
        selected = self._drive().path if self._drive() else None
        self.drives = drives
        self.ventoy_paths = set()
        for d in drives:
            try:
                if jobs.ventoy_parts(d):
                    self.ventoy_paths.add(d.path)
            except Exception:  # noqa: BLE001 - only a hint on the tile
                pass
        for b in self.drive_group.buttons():
            self.drive_group.removeButton(b)
            self.drive_list.removeWidget(b)
            b.hide()
            b.deleteLater()
        for d in drives:
            sub = d.size_label + (", has Ventoy" if d.path in self.ventoy_paths else "")
            t = Tile(d.model, sub, icon="usb")
            t.setProperty("path", d.path)
            self.drive_group.addButton(t)
            self.drive_list.insertWidget(self.drive_list.count() - 2, t)
            if d.path == selected or len(drives) == 1:
                t.setChecked(True)
        self.empty_slot.setVisible(not drives)
        self.empty_hint.setVisible(False)
        self._update_ready()

    # ------------------------------------------------------------ flow

    def _ask_confirm(self) -> None:
        choice, distro, drive = self._choice(), self._distro(), self._drive()
        if not drive or (choice == SYSTEM and not distro) or (choice == FILE and not self.own_file):
            return
        if choice == SYSTEM and drive.size < distro.min_drive_gb * GB * 0.93:
            self.empty_hint.setText(f"That drive is too small. {distro.name} needs "
                                    f"{distro.min_drive_gb} GB or more.")
            self.empty_hint.setVisible(True)
            return
        if choice == VENTOY and drive.size < 1 * GB:
            self.empty_hint.setText("Ventoy needs a drive of at least 1 GB.")
            self.empty_hint.setVisible(True)
            return
        self.confirm_title.setText("Erase this drive?")
        name = self.own_file.path.name if choice == FILE else distro.name if distro else ""
        self.confirm_text.setText({
            FORMAT: "Everything on it will be erased, leaving one empty exFAT drive named USB DRIVE that "
                    "works on Windows, Mac, Linux and SteamOS.",
            VENTOY: "Everything on it will be replaced with the latest Ventoy. Afterwards add images with "
                    "this app (or by copying files) and choose one when you start from it.",
        }.get(choice, f"Everything on it will be replaced with {name}."))
        self.confirm_tile.title, self.confirm_tile.subtitle = drive.model, drive.size_label
        self.confirm_tile.update()
        self.sb_box.setVisible(choice == VENTOY)
        self._set_secure_boot(self.secure_boot)
        notes = []
        if choice == SYSTEM and distro.needs_password or choice in (FILE, VENTOY):
            what = "Ventoy is installed" if choice == VENTOY else f"{name} is written"
            notes.append(f"{what} byte for byte, so Linux will ask for your password once, when writing "
                         "starts. Nothing else needs it.")
        if choice == SYSTEM and distro.warning:
            notes.append(distro.warning)
        if choice == FILE and self.own_file.warning:
            notes.append(self.own_file.warning)
        self.confirm_warning.setText("\n\n".join(notes))
        self.confirm_warning.setVisible(bool(notes))
        long_job = choice in (SYSTEM, FILE)
        self.ack_text.setText("I'll leave the drive plugged in and this window open until it's done."
                              + (" This can take 30 minutes or more." if long_job else ""))
        self.erase_btn.setText({FORMAT: "Erase and format", VENTOY: "Erase and install",
                                FILE: "Erase and write"}.get(choice, "Erase and create"))
        self.ack.setChecked(False)
        self._refresh_checks()
        self.check_timer.start()
        self.stack.setCurrentWidget(self.confirm_page)
        self.ack.setFocus()

    def _set_secure_boot(self, on: bool) -> None:
        self.secure_boot = on
        (self.sb_on if on else self.sb_off).setChecked(True)
        self.sb_text.setText(
            "Starts whether Secure Boot is on or off. On a handheld with Secure Boot on, the first start "
            "asks you to enroll Ventoy's key once." if on else
            "No key step, but the drive only starts once Secure Boot is turned off in the BIOS. The Steam "
            "Deck doesn't use Secure Boot, so either works there.")

    def _needs_download(self) -> bool:
        return self._choice() in (SYSTEM, VENTOY)

    def _refresh_checks(self, reread: bool = True) -> None:
        if reread or not hasattr(self, "_checks"):
            self._checks = checks(read_state(), download=self._needs_download())
        for i, lab in enumerate(self.check_labels):
            if i >= len(self._checks):
                lab.setVisible(False)
                continue
            c = self._checks[i]
            mark, color = ("✓", MOLTEN_A) if c.ok else (("✕", DANGER) if c.required else ("!", ASH))
            detail_color = ASH if c.ok or not c.required else DANGER
            lab.setText(f'<span style="color:{color.name()}; font-weight:700">{mark}</span>&nbsp; '
                        f"<b>{c.title}</b><br>"
                        f'<span style="color:{detail_color.name()}">{c.detail}</span>')
            lab.setVisible(True)
        self.erase_btn.setEnabled(can_start(self._checks, self.ack.isChecked()))

    def _leave_confirm(self) -> None:
        self.check_timer.stop()
        self.stack.setCurrentWidget(self.choose_page)

    def start(self) -> None:
        if not self.erase_btn.isEnabled():
            return
        choice, distro, drive = self._choice(), self._distro(), self._drive()
        if not drive:
            return
        self.check_timer.stop()
        if choice == SYSTEM:
            if not distro:
                return
            self._run(SYSTEM, distro, drive, f"Creating {distro.name} on {drive.model}")
        elif choice == FILE:
            self._run(FILE, None, drive, f"Writing {self.own_file.path.name} to {drive.model}",
                      file=self.own_file)
        elif choice == VENTOY:
            self._run(VENTOY, None, drive, f"Installing Ventoy on {drive.model}",
                      secure_boot=self.secure_boot)
        else:
            self._run(FORMAT, None, drive, f"Formatting {drive.model}")

    def _run(self, job: str, distro: Distro | None, drive: Drive, title: str, **args) -> None:
        self.log_view.clear()
        self.log_view.setVisible(False)
        self._append(title)
        self.work_title.setText(title)
        self.percent.setText("0%")
        self._last = ("lookup", 0, 0) if job in (SYSTEM, VENTOY) else ("prepare", 0, 0)
        self.work_last.setText("")
        self.status_timer.start()
        raw = job in RAW_JOBS or (job == SYSTEM and distro.needs_password)
        self.track.steps = RAW_STEPS if raw or job == FORMAT else STEPS
        self.track.set_step(0)
        self._step = 0
        self.progress = Progress()
        self.bar.set_value(0, busy=True)
        self.cancel_btn.setEnabled(True)
        self.cancel_btn.setText("Stop")
        self._stop_armed = False
        self.stack.setCurrentWidget(self.work_page)

        self.thread = QThread(self)
        self.worker = Worker(distro, drive, job, **args)
        self._render_status()
        self.worker.moveToThread(self.thread)
        self.thread.started.connect(self.worker.run)
        self.worker.stage.connect(self.on_stage)
        self.worker.log.connect(self._append)
        self.worker.finished.connect(self.on_finished)
        self.thread.start()

    def cancel(self) -> None:
        """The Stop button: the first click only asks, so a stray tap can't ruin a half-written drive."""
        if not self.worker:
            return
        if not getattr(self, "_stop_armed", False):
            self._stop_armed = True
            self.cancel_btn.setText("Click again to stop")
            QTimer.singleShot(4000, self._disarm_stop)
            return
        self.stop_now()

    def _disarm_stop(self) -> None:
        if self.worker and self._stop_armed and not self.worker.cancel.is_set():
            self._stop_armed = False
            self.cancel_btn.setText("Stop")

    def stop_now(self) -> None:
        if self.worker:
            self.worker.cancel.set()
            self.cancel_btn.setEnabled(False)
            self.cancel_btn.setText("Stopping")

    def _append(self, msg: str) -> None:
        self.log_view.appendPlainText(f"{time.strftime('%H:%M:%S')}  {msg}")
        self.work_last.setText(msg)

    def on_stage(self, stage: str, done: int, total: int) -> None:
        self.progress.update(stage, done, total)
        self._step = self.progress.step
        self.track.set_step(self._step)
        self.percent.setText(f"{self.progress.percent}%")
        self.bar.set_value(self.progress.fraction, busy=not total)
        self._last = (stage, done, total)
        self._render_status()

    def _render_status(self) -> None:
        stage, done, total = getattr(self, "_last", ("lookup", 0, 0))
        w = self.worker
        job = getattr(w, "job", SYSTEM)
        distro = w.distro if w else None
        password = job in RAW_JOBS or bool(distro and distro.needs_password)
        name = distro.name if distro else "Ventoy" if job == VENTOY else ""
        headline, detail = describe(
            stage, done, total, self.progress.elapsed, needs_password=password, name=name,
            time_left=self.progress.time_left(done, total))
        if job == IMPORT:
            headline = headline.replace("Copying files to the drive", "Copying images to the drive")
        if job == TEST:
            headline = headline.replace("Writing the image to the drive", "Writing test data")
            headline = headline.replace("Reading the drive back to confirm", "Reading the test data back")
        if job == CHECK:
            headline = headline.replace("Reading the drive back to confirm",
                                        "Comparing the drive with the image")
        self.work_status.setText(headline)
        self.work_detail.setText(detail)

    def on_finished(self, status: str, message: str) -> None:
        self.thread.quit()
        self.thread.wait()
        w = self.worker
        distro, drive = w.distro, w.drive
        job, result = getattr(w, "job", SYSTEM), getattr(w, "result", None)
        args = getattr(w, "args", {})
        self.thread = self.worker = None
        self._finished_job = (job, drive, args)
        self.bar.stop()
        self.status_timer.stop()
        self.result_log.setPlainText(self.log_view.toPlainText())
        self.result_log.setVisible(False)
        self.result_btn.danger = False
        self.result_mark.set_kind(status)
        self.result_guide.setVisible(False)
        self.result_lines.setVisible(False)
        self.result_after.setVisible(False)
        self.result_done.setVisible(False)
        ok = status == "ok"
        steps: list[str] = []
        bad_test = job == TEST and ok and result is not None and (result.fake or result.failing)
        if job == CHECK and ok and result is not None:  # a difference is a finding, shown as an error
            status, ok = "error", False
            self.result_mark.set_kind("error")
            message = (f"The drive differs from {Path(args['image']).name} starting at byte {result:,}. Make "
                       "the drive again, or try another drive if it keeps happening.")
        if ok:
            self.track.set_step(len(STEPS))
            if job == TEST:
                self.result_title.setText("This drive failed the test" if bad_test else "Drive test results")
                if bad_test:
                    self.result_mark.set_kind("error")
                self.result_text.setText(f"{drive.model}, measured. It's been formatted again, so it's "
                                         "ready to use:")
                self.result_lines.setText("\n\n".join(result.lines()) if result else "")
                self.result_lines.setVisible(True)
            elif job == CHECK:
                self.result_title.setText("The drive matches")
                self.result_text.setText(f"Every byte on {drive.model} matches {Path(args['image']).name}.")
            elif job == IMPORT:
                self.result_title.setText("Added to Ventoy")
                self.result_text.setText(f"These are on {drive.model} and will show in Ventoy's menu:")
                self.result_lines.setText("\n".join(result or []))
                self.result_lines.setVisible(True)
                self.result_after.setText("Plug the drive into your handheld, start it from USB and pick one "
                                          "from Ventoy's menu.")
                self.result_after.setVisible(True)
            elif job == FORMAT:
                self.result_title.setText("Drive formatted")
                self.result_text.setText(f"{drive.model} is now one empty exFAT drive named USB DRIVE. "
                                         "Unplug it and plug it back in to use it for files.")
            elif job == VENTOY:
                sb = args.get("secure_boot", True)
                self.result_title.setText("Ventoy is installed")
                self.result_text.setText(f"{drive.model} now has Ventoy {result or ''}, "
                                         f"{'with' if sb else 'without'} Secure Boot support.")
                steps = ventoy_steps(sb)
            else:
                name = args["file"].path.name if job == FILE else distro.name
                steps = [] if job == FILE else list(distro.install_steps)
                self.result_title.setText("Ready to boot")
                self.result_text.setText(
                    f"{drive.model} now holds {name}. Plug it into your handheld and follow these steps."
                    if steps else
                    f"{drive.model} now holds {name}. Plug it into your handheld, open the boot "
                    "menu and choose the USB drive.")
            if steps:
                self.result_guide.setText(guide_html(steps, ventoy=job == VENTOY))
                self.result_guide.setVisible(True)
        elif status == "cancelled":
            self.result_title.setText("Stopped")
            self.result_text.setText(message)
        else:
            self.track.set_step(self._step, failed=True)
            self.result_title.setText({IMPORT: "The images weren't all added", TEST: "The test didn't finish",
                                       CHECK: "The drive doesn't match"}.get(job,
                                                                              "The drive wasn't finished"))
            self.result_text.setText(message)
        # Buttons, as on Android.
        if ok and job in (VENTOY, IMPORT):
            self.result_done.setVisible(True)
            self.result_btn.setText("Add more" if job == IMPORT else "Add images")
        elif job == IMPORT:
            self.result_btn.setText("Back to the drive")
        elif job in (TEST, CHECK):
            self.result_btn.setText("Done")
        else:
            again = {"ok": "Make another", "cancelled": "Start over"}
            self.result_btn.setText(again.get(status, "Try again"))
        self.stack.setCurrentWidget(self.result_page)
        self.result_btn.setFocus()

    def _result_action(self) -> None:
        job, drive, _ = getattr(self, "_finished_job", (SYSTEM, None, {}))
        if job in (VENTOY, IMPORT) and drive is not None and self.result_btn.text() != "Try again":
            self.picked = [] if self.result_btn.text() != "Back to the drive" else self.picked
            self._open_ventoy(drive)
            return
        self._back_to_start()

    # ------------------------------------------------------------ Ventoy files

    def _open_ventoy(self, drive: Drive | None = None) -> None:
        drive = drive or self._drive()
        if drive is None:
            return
        self._v_drive = drive
        self._confirm_remove = None
        self.ventoy_info = None
        self.v_error.setVisible(False)
        self.v_info.setText("Reading the drive...")
        self.stack.setCurrentWidget(self.ventoy_page)
        self._render_ventoy()
        self._background(lambda: jobs.ventoy_info(drive), self._ventoy_loaded)

    def _background(self, fn, then) -> None:
        self._v_busy = True
        t = Task(fn, self)
        self._tasks.append(t)

        def done(r, err):
            self._tasks.remove(t)
            self._v_busy = False
            then(r, err)

        t.done.connect(done, Qt.QueuedConnection)  # back on the window's thread
        t.start()

    def _ventoy_loaded(self, info, err) -> None:
        if err is not None or info is None:
            self.ventoy_info = None
            self.v_info.setText(str(err) if err else "This drive doesn't have Ventoy on it.")
        else:
            self.ventoy_info = info
            sb = "" if info.secure_boot is None else (
                ", Secure Boot support " + ("on" if info.secure_boot else "off"))
            self.v_info.setText(f"{self._v_drive.model}: Ventoy {info.version or ''}".rstrip()
                                + f"{sb}. {gb(info.free)} free.")
        self._render_ventoy()

    def _clear_body(self) -> None:
        while self.v_body.count():
            item = self.v_body.takeAt(0)
            if item.widget():
                item.widget().hide()
                item.widget().deleteLater()

    def _render_ventoy(self) -> None:
        self._clear_body()
        b = self.v_body
        b.addWidget(label("To add", "brand"))
        if not self.picked:
            b.addWidget(EmptySlot("Pick ISOs or disk images on this computer. Compressed ones are unpacked "
                                  "on the way in."))
        for p in self.picked:
            notes = [p.kind.label]
            if p.source.exists():
                notes.append(gb(p.source.stat().st_size))
            if p.unpacks:
                notes.append("unpacked onto the drive")
            remove = quiet_button("Remove")
            card = Card(p.source.name, ", ".join(notes), remove)
            unlisted = None if p.bootable else "Ventoy won't list this in its menu."
            card.set_note(p.content.warning or unlisted)
            remove.clicked.connect(lambda _=False, x=p: self._unpick(x))
            b.addWidget(card)
        b.addSpacing(10)
        b.addWidget(label("On the drive", "brand"))
        info = self.ventoy_info
        if info is not None and not info.files:
            b.addWidget(label("Nothing yet.", "hint"))
        elif info is not None:
            b.addWidget(label(
                "Ventoy starts the marked image on its own after 5 seconds. Click it again to go back to "
                "choosing from the menu." if info.boot_next else
                "Click an image to have Ventoy start it on its own, no buttons needed.", "small"))
            for f in info.files:
                asking = self._confirm_remove == f.name
                auto = f.name == info.boot_next
                remove = quiet_button("Remove?" if asking else "Remove")
                card = Card(f.name, gb(f.size) + (", starts on its own" if auto else ""), remove)
                card.set_selected(auto)
                card.set_note("Click Remove again to delete it from the drive." if asking else None)
                if f.bootable:
                    card.setCursor(Qt.PointingHandCursor)
                    card.clicked.connect(lambda n=f.name, a=auto: self._set_boot_next(None if a else n))
                remove.clicked.connect(lambda _=False, n=f.name: self._remove(n))
                b.addWidget(card)
        b.addStretch(1)
        # Space and buttons.
        space = None
        if info is not None:
            known = sum(p.size for p in self.picked if p.size >= 0)
            if known > info.free:
                space = f"That's more than the {gb(info.free)} free on the drive."
        self.v_space.setText(space or "")
        self.v_space.setVisible(bool(space))
        self.v_more.setVisible(bool(self.picked))
        self.v_more.setEnabled(not self._v_busy)
        n = len(self.picked)
        self.v_go.setText("Pick images" if not n else "Copy to drive" if n == 1 else f"Copy {n} to drive")
        self.v_go.setEnabled(info is not None and not self._v_busy and space is None)

    def _pick_images(self) -> None:
        paths, _ = QFileDialog.getOpenFileNames(
            self, "Pick ISOs or disk images", str(Path.home()),
            "Images (*.iso *.img *.raw *.wim *.vhd *.vhdx *.efi *.vtoy *.gz *.xz *.bz2 *.zip);;All files (*)")
        self.add_picked([Path(p) for p in paths])

    def add_picked(self, paths: list[Path]) -> None:
        errors = []
        for p in paths:
            if any(x.source == p for x in self.picked):
                continue
            try:
                self.picked.append(jobs.plan_import(p))
            except OSError as e:
                errors.append(str(e))
        self.v_error.setText("\n".join(errors))
        self.v_error.setVisible(bool(errors))
        self._render_ventoy()

    def _unpick(self, p) -> None:
        self.picked = [x for x in self.picked if x is not p]
        self._render_ventoy()

    def _ventoy_go(self) -> None:
        if not self.picked:
            self._pick_images()
            return
        drive = self._v_drive
        n = len(self.picked)
        self._run(IMPORT, None, drive, f"Adding {n} image{'s' if n > 1 else ''} to {drive.model}",
                  plans=list(self.picked))
        self.picked = []

    def _set_boot_next(self, name: str | None) -> None:
        if self._v_busy:
            return
        drive = self._v_drive
        self.v_error.setVisible(False)
        self._background(lambda: jobs.set_boot_next(drive, name, log=lambda m: None),
                         lambda _, err: self._after_change(err, "Couldn't change what starts on its own"))

    def _remove(self, name: str) -> None:
        if self._v_busy:
            return
        if self._confirm_remove != name:
            self._confirm_remove = name
            self._render_ventoy()
            return
        self._confirm_remove = None
        drive = self._v_drive
        self._background(lambda: jobs.remove_image(drive, name, log=lambda m: None),
                         lambda _, err: self._after_change(err, f"Couldn't remove {name}"))

    def _after_change(self, err, what: str) -> None:
        if err is not None:
            self.v_error.setText(f"{what}: {err}")
            self.v_error.setVisible(True)
        self._background(lambda: jobs.ventoy_info(self._v_drive), self._ventoy_loaded)

    # ------------------------------------------------------------ Labs

    def _open_labs(self) -> None:
        if self.thread is not None:
            return
        self._ask_test = False
        self._render_labs()
        self.stack.setCurrentWidget(self.labs_page)

    def _check_source(self) -> tuple[str, Path] | None:
        """What Check a drive compares with: your own file, or the picked system's last download."""
        choice, distro = self._choice(), self._distro()
        if choice == FILE and self.own_file:
            return self.own_file.path.name, self.own_file.path
        if choice == SYSTEM and distro:
            try:
                for img in distro.images:
                    f = cache_dir() / img.filename
                    if f.exists() and f.with_name(f.name + ".verified").exists():
                        return distro.name, f
            except OSError:
                return None
        return None

    def _render_labs(self) -> None:
        drive = self._drive()
        self.test_card.detail.setText(
            (f"{drive.model}: " if drive else "") + "real write and read speed, and whether it really holds "
            "what it claims. Takes 1 to 6 minutes. Erases the drive, then formats it so it's usable again.")
        self.test_card.set_note(f"Click again to erase {drive.model if drive else 'the drive'} and test it."
                                if self._ask_test else None)
        self.test_btn.setText("Erase and test" if self._ask_test else "Test")
        self.test_btn.setEnabled(drive is not None)
        src = self._check_source()
        if src is None:
            self.check_card.detail.setText("Pick a system you've made a drive with, or your own file, on the "
                                           "main screen first, then compare a drive with it.")
        else:
            self.check_card.detail.setText((f"Compare {drive.model} with {src[0]}" if drive else
                                            f"Compare a drive with {src[0]}")
                                           + ", byte for byte, without changing anything. Handy when a "
                                           "handheld won't start from it.")
        self.check_btn.setEnabled(drive is not None and src is not None)
        self.labs_hint.setText("" if drive else
                               "Plug in a USB drive and pick it on the main screen to use the tools.")
        self.labs_hint.setVisible(drive is None)

    def _labs_test(self) -> None:
        drive = self._drive()
        if drive is None:
            return
        if not self._ask_test:
            self._ask_test = True
            self._render_labs()
            return
        self._ask_test = False
        self._run(TEST, None, drive, f"Testing {drive.model}")

    def _labs_check(self) -> None:
        drive, src = self._drive(), self._check_source()
        if drive is None or src is None:
            return
        self._run(CHECK, None, drive, f"Checking {drive.model}", image=src[1])

    # ------------------------------------------------------------ app

    def _run_installer(self, cmd: str) -> None:
        """Runs `handheld-forge update/uninstall --ask` on its own, so its windows ask and report."""
        if self.thread is not None:
            self.work_status.setText("Finish or stop the current drive first")
            return
        subprocess.Popen([sys.executable, "-m", "handheld_forge", cmd, "--ask"],
                         stdin=subprocess.DEVNULL, start_new_session=True)

    def _back_to_start(self) -> None:
        self.drives = []
        self.refresh_drives()
        self.stack.setCurrentWidget(self.choose_page)

    def _escape(self) -> None:
        page = self.stack.currentWidget()
        if page is self.confirm_page:
            self._leave_confirm()
        elif page in (self.ventoy_page, self.labs_page):
            self._back_to_start()

    def closeEvent(self, event) -> None:
        if self.thread is not None:
            self.stop_now()
            self.work_status.setText("Stopping safely before closing")
            event.ignore()
            QTimer.singleShot(400, self._close_when_idle)
            return
        event.accept()

    def _close_when_idle(self) -> None:
        if self.thread is None:
            self.close()
        else:
            QTimer.singleShot(400, self._close_when_idle)


def run() -> int:
    app = QApplication.instance() or QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setDesktopFileName("handheld-forge")
    app.setStyleSheet(STYLE)
    win = MainWindow()
    win.show()
    return app.exec()
