"""Handheld Forge window: pick a system, pick a USB drive, create.

One panel that changes in place (choose, confirm, working, done, error), sized
for a 7 inch handheld screen, usable by touch, mouse, keyboard or D-pad.
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
    QAbstractButton, QApplication, QButtonGroup, QCheckBox, QHBoxLayout, QLabel, QMainWindow, QMenu,
    QPlainTextEdit, QPushButton, QScrollArea, QSizePolicy, QStackedWidget, QToolButton, QVBoxLayout,
    QWidget,
)

from . import APP_NAME, __version__
from .builder import BuildError
from .builder import Cancelled as BuildCancelled
from .maker import make_drive
from .rawwrite import Cancelled as RawCancelled
from .rawwrite import RawWriteError
from .udisks import UDisksError
from .config import Distro, experimental_variants, load_boot_menu, load_catalog
from .download import Cancelled as DownloadCancelled
from .download import prepare_iso
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


def guide_html(distro: Distro) -> str:
    """The install guide as rich text: boot menu tips, then numbered steps."""
    def esc(t: str) -> str:
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    head = f'<p style="color:{ASH.name()}; font-size:13px; font-weight:600">'
    menu = "".join(f"<p>{esc(line)}</p>" for line in load_boot_menu())
    num = f'<span style="color:{MOLTEN_A.name()}">'
    steps = "".join(f"<p>{num}{i}.</span>&nbsp; {esc(s)}</p>"
                    for i, s in enumerate(distro.install_steps, 1))
    body = f"{head}Open the boot menu</p>{menu}{head}Then install</p>{steps}"
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
    """Selectable card. Selected = molten edge; disabled = dimmed with a 'Soon' tag."""

    def __init__(self, title: str, subtitle: str, icon: str | None = None, tag: str = "") -> None:
        super().__init__()
        self.title, self.subtitle, self.icon, self.tag = title, subtitle, icon, tag
        self.setCheckable(True)
        self.setCursor(Qt.PointingHandCursor)
        self.setFocusPolicy(Qt.StrongFocus)
        self.setMinimumHeight(84)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)

    def sizeHint(self) -> QSize:
        return QSize(160, 84)

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
        p.drawText(QRectF(x, r.top() + 44, text_w, 22), Qt.AlignLeft | Qt.AlignVCenter, self.subtitle)

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

    def __init__(self) -> None:
        super().__init__()
        self.setMinimumHeight(84)
        self.text = "Plug in a USB drive of 16 GB or more"

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
        p.drawText(r, Qt.AlignCenter, self.text)


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


# ---------------------------------------------------------------- worker

class Worker(QObject):
    stage = Signal(str, int, int)
    log = Signal(str)
    finished = Signal(str, str)  # status: ok | cancelled | error, message

    def __init__(self, distro: Distro, drive: Drive) -> None:
        super().__init__()
        self.distro, self.drive = distro, drive
        self.cancel = threading.Event()

    def run(self) -> None:
        try:
            with SleepBlocker():
                img = prepare_iso(self.distro, self.stage.emit, self.log.emit, self.cancel)
                if self.cancel.is_set():
                    raise DownloadCancelled()
                make_drive(self.distro, self.drive, img.path, self.stage.emit, self.log.emit, self.cancel)
            self.finished.emit("ok", "")
        except DownloadCancelled:
            self.finished.emit("cancelled", "Download paused. It picks up where it left off next time.")
        except (BuildCancelled, RawCancelled):
            self.finished.emit("cancelled", "Stopped. The drive is unfinished, so create it again "
                                            "before booting from it.")
        except (BuildError, RawWriteError, UDisksError) as e:
            self.finished.emit("error", str(e))
        except urllib.error.HTTPError as e:
            self.finished.emit("error", f"The download server answered {e.code}. Try again later.")
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            self.log.emit(f"{type(e).__name__}: {e}")
            self.finished.emit("error", explain_network(str(e)))
        except Exception as e:  # anything unexpected still lands in the window
            self.finished.emit("error", f"{type(e).__name__}: {e}")


# ---------------------------------------------------------------- window

class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle(APP_NAME)
        self.resize(620, 660)
        self.setMinimumSize(480, 480)
        self.catalog = load_catalog()
        self.experimental = experimental_variants(self.catalog)
        self.thread: QThread | None = None
        self.worker: Worker | None = None
        self.drives: list[Drive] = []
        self.progress = Progress()
        self._step = 0

        self.stack = QStackedWidget()
        self.choose_page = self._build_choose()
        self.confirm_page = self._build_confirm()
        self.work_page = self._build_work()
        self.result_page = self._build_result()
        for page in (self.choose_page, self.confirm_page, self.work_page, self.result_page):
            self.stack.addWidget(page)

        root = QWidget()
        lay = QVBoxLayout(root)
        lay.setContentsMargins(32, 22, 32, 28)
        head = QHBoxLayout()
        head.addWidget(label(APP_NAME, "brand"))
        head.addStretch()
        # The version doubles as the app menu: updating and uninstalling live here.
        self.app_menu_btn = QToolButton(text=f"v{__version__}  \u25BE")
        self.app_menu_btn.setPopupMode(QToolButton.InstantPopup)
        self.app_menu_btn.setCursor(Qt.PointingHandCursor)
        menu = QMenu(self.app_menu_btn)
        menu.addAction("Check for updates", lambda: self._run_installer("update"))
        menu.addAction(f"Uninstall {APP_NAME}", lambda: self._run_installer("uninstall"))
        self.hide_exp_action = menu.addAction("Hide experimental options",
                                              lambda: self._set_experimental(False))
        self.hide_exp_action.setVisible(not self.exp_box.isHidden())
        self.app_menu_btn.setMenu(menu)
        head.addWidget(self.app_menu_btn)
        lay.addLayout(head)
        lay.addSpacing(14)
        lay.addWidget(self.stack, 1)
        self.setCentralWidget(root)

        QShortcut(QKeySequence(Qt.Key_Escape), self, activated=self._escape)
        self.refresh_drives()
        self.poll = QTimer(self, interval=2000, timeout=self._poll_drives)
        self.poll.start()

    # ------------------------------------------------------------ pages

    def _build_choose(self) -> QWidget:
        page = QWidget()
        lay = QVBoxLayout(page)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(12)
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
        self._taps, self._last_tap = 0, 0.0
        bazzite = next((b for b in self.os_group.buttons() if b.property("distro") == UNLOCK_DISTRO), None)
        if bazzite:
            bazzite.clicked.connect(self._tap_unlock)
        first = next((b for b in self.os_group.buttons() if b.isEnabled()), None)
        if first:
            first.setChecked(True)
        self.os_group.buttonToggled.connect(lambda *_: self._update_ready())
        lay.addSpacing(10)

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
        ack_text = label("I'll leave the drive plugged in and this window open until it's done. "
                         "This can take 30 minutes or more.")
        ack_text.mousePressEvent = lambda _: self.ack.toggle()
        ack_lay.addWidget(self.ack, 0, Qt.AlignTop)
        ack_lay.addWidget(ack_text, 1)
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
        self.result_guide = label("")
        self.result_guide.setTextFormat(Qt.RichText)
        inner.addWidget(self.result_title)
        inner.addSpacing(8)
        inner.addWidget(self.result_text)
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
        self.result_btn = MoltenButton("Make another")
        self.result_btn.clicked.connect(self._back_to_start)
        row.addWidget(self.result_details)
        row.addWidget(self.result_btn, 1)
        lay.addLayout(row)
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

    def _distro(self) -> Distro | None:
        b = self.os_group.checkedButton()
        return next((d for d in self.catalog + self.experimental if b and d.id == b.property("distro")), None)

    def _drive(self) -> Drive | None:
        b = self.drive_group.checkedButton()
        return next((d for d in self.drives if b and d.path == b.property("path")), None)

    def _update_ready(self) -> None:
        self.create_btn.setEnabled(self._distro() is not None and self._drive() is not None)

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
        for b in self.drive_group.buttons():
            self.drive_group.removeButton(b)
            self.drive_list.removeWidget(b)
            b.hide()
            b.deleteLater()
        for d in drives:
            t = Tile(d.model, d.size_label, icon="usb")
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
        distro, drive = self._distro(), self._drive()
        if not distro or not drive:
            return
        if drive.size < distro.min_drive_gb * GB * 0.93:
            self.empty_hint.setText(f"That drive is too small. {distro.name} needs "
                                    f"{distro.min_drive_gb} GB or more.")
            self.empty_hint.setVisible(True)
            return
        self.confirm_title.setText("Erase this drive?")
        self.confirm_text.setText(f"Everything on it will be replaced with {distro.name}.")
        self.confirm_tile.title, self.confirm_tile.subtitle = drive.model, drive.size_label
        self.confirm_tile.update()
        notes = []
        if distro.needs_password:
            notes.append(f"{distro.name} is written byte for byte, so Linux will ask for your password "
                         "once, when writing starts. Nothing else needs it.")
        if distro.warning:
            notes.append(distro.warning)
        self.confirm_warning.setText("\n\n".join(notes))
        self.confirm_warning.setVisible(bool(notes))
        self.ack.setChecked(False)
        self._refresh_checks()
        self.check_timer.start()
        self.stack.setCurrentWidget(self.confirm_page)
        self.ack.setFocus()

    def _refresh_checks(self, reread: bool = True) -> None:
        if reread or not hasattr(self, "_checks"):
            self._checks = checks(read_state())
        for lab, c in zip(self.check_labels, self._checks, strict=True):
            mark, color = ("✓", MOLTEN_A) if c.ok else (("✕", DANGER) if c.required else ("!", ASH))
            detail_color = ASH if c.ok or not c.required else DANGER
            lab.setText(f'<span style="color:{color.name()}; font-weight:700">{mark}</span>&nbsp; '
                        f"<b>{c.title}</b><br>"
                        f'<span style="color:{detail_color.name()}">{c.detail}</span>')
        self.erase_btn.setEnabled(can_start(self._checks, self.ack.isChecked()))

    def _leave_confirm(self) -> None:
        self.check_timer.stop()
        self.stack.setCurrentWidget(self.choose_page)

    def start(self) -> None:
        distro, drive = self._distro(), self._drive()
        if not distro or not drive or not self.erase_btn.isEnabled():
            return
        self.check_timer.stop()
        self.log_view.clear()
        self.log_view.setVisible(False)
        self._append(f"Creating {distro.name} on {drive.path}")
        self.work_title.setText(f"Creating {distro.name} on {drive.model}")
        self.percent.setText("0%")
        self._last = ("lookup", 0, 0)
        self.work_last.setText("")
        self.status_timer.start()
        self.track.steps = RAW_STEPS if distro.needs_password else STEPS
        self.track.set_step(0)
        self._step = 0
        self.progress = Progress()
        self.bar.set_value(0, busy=True)
        self._render_status()
        self.cancel_btn.setEnabled(True)
        self.cancel_btn.setText("Stop")
        self._stop_armed = False
        self.stack.setCurrentWidget(self.work_page)

        self.thread = QThread(self)
        self.worker = Worker(distro, drive)
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
        distro = self.worker.distro if self.worker else None
        headline, detail = describe(
            stage, done, total, self.progress.elapsed,
            needs_password=bool(distro and distro.needs_password), name=distro.name if distro else "",
            time_left=self.progress.time_left(done, total))
        self.work_status.setText(headline)
        self.work_detail.setText(detail)

    def on_finished(self, status: str, message: str) -> None:
        self.thread.quit()
        self.thread.wait()
        distro, drive = self.worker.distro, self.worker.drive
        self.thread = self.worker = None
        self.bar.stop()
        self.status_timer.stop()
        self.result_log.setPlainText(self.log_view.toPlainText())
        self.result_log.setVisible(False)
        self.result_btn.danger = False
        self.result_mark.set_kind(status)
        self.result_guide.setVisible(False)
        if status == "ok":
            self.track.set_step(len(STEPS))
            self.result_title.setText("Ready to boot")
            self.result_text.setText(
                f"{drive.model} now holds {distro.name}. Plug it into your handheld and follow these steps."
                if distro.install_steps else
                f"{drive.model} now holds {distro.name}. Plug it into your handheld, open the boot "
                "menu and choose the USB drive.")
            self.result_guide.setText(guide_html(distro))
            self.result_guide.setVisible(bool(distro.install_steps))
            self.result_btn.setText("Make another")
        elif status == "cancelled":
            self.result_title.setText("Stopped")
            self.result_text.setText(message)
            self.result_btn.setText("Start over")
        else:
            self.track.set_step(self._step, failed=True)
            self.result_title.setText("The drive wasn't finished")
            self.result_text.setText(message)
            self.result_btn.setText("Try again")
        self.stack.setCurrentWidget(self.result_page)
        self.result_btn.setFocus()

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
        if self.stack.currentWidget() is self.confirm_page:
            self._leave_confirm()

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
