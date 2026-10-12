"""Handheld Forge window: pick a system, pick a USB drive, create.

One panel that changes in place (choose, confirm, working, done, error), sized
for a 7 inch handheld screen, usable by touch, mouse, keyboard or D-pad.
"""

from __future__ import annotations

import sys
import threading
import time

from PySide6.QtCore import QObject, QPointF, QRectF, QSize, Qt, QThread, QTimer, Signal
from PySide6.QtGui import (
    QColor, QFont, QKeySequence, QLinearGradient, QPainter, QPainterPath, QPen, QShortcut,
)
from PySide6.QtWidgets import (
    QAbstractButton, QApplication, QButtonGroup, QHBoxLayout, QLabel, QMainWindow, QPlainTextEdit,
    QPushButton, QScrollArea, QSizePolicy, QStackedWidget, QVBoxLayout, QWidget,
)

from . import APP_NAME, __version__
from .builder import BuildError, build_drive
from .builder import Cancelled as BuildCancelled
from .config import Distro, load_catalog
from .download import Cancelled as DownloadCancelled
from .download import prepare_iso
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
"""

# Steps shown while working. The engine's stages map onto these.
STEPS = ["Download", "Verify", "Prepare", "Copy", "Check"]
STAGE_STEP = {"lookup": 0, "download": 0, "verify": 1, "prepare": 2, "copy": 3, "sync": 3,
              "check": 4, "eject": 4}
STEP_WEIGHT = [40, 8, 4, 36, 12]


def label(text: str = "", role: str | None = None) -> QLabel:
    lab = QLabel(text)
    if role:
        lab.setProperty("role", role)
    lab.setWordWrap(True)
    return lab


def quiet_button(text: str) -> QPushButton:
    b = QPushButton(text)
    b.setProperty("role", "quiet")
    b.setCursor(Qt.PointingHandCursor)
    return b


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
        self.value = 0.0
        self.busy = False
        self.phase = 0.0
        self.setFixedHeight(14)
        self.timer = QTimer(self, interval=40, timeout=self._tick)

    def set_value(self, v: float, busy: bool = False) -> None:
        self.value, self.busy = max(0.0, min(1.0, v)), busy
        if not self.timer.isActive():
            self.timer.start()
        self.update()

    def stop(self) -> None:
        self.timer.stop()
        self.update()

    def _tick(self) -> None:
        self.phase = (self.phase + 0.012) % 1.0
        self.update()

    def paintEvent(self, _) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect())
        p.setPen(Qt.NoPen)
        p.setBrush(IRON)
        p.drawRoundedRect(r, 7, 7)
        w = r.width() * self.value
        if w < 2 and not self.busy:
            return
        fill = QRectF(r.x(), r.y(), max(w, 14), r.height())
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
        self.setFixedHeight(46)

    def set_step(self, i: int, failed: bool = False) -> None:
        self.current, self.failed = i, failed
        self.update()

    def paintEvent(self, _) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        n = len(STEPS)
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
            p.drawText(QRectF(x - 50, y + 12, 100, 20), Qt.AlignCenter, STEPS[i])


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
            img = prepare_iso(self.distro, self.stage.emit, self.log.emit, self.cancel)
            if self.cancel.is_set():
                raise DownloadCancelled()
            build_drive(self.drive, img.path, self.stage.emit, self.log.emit, self.cancel)
            self.finished.emit("ok", "")
        except DownloadCancelled:
            self.finished.emit("cancelled", "Download paused. It picks up where it left off next time.")
        except BuildCancelled:
            self.finished.emit("cancelled", "Stopped. The drive is unfinished, so create it again "
                                            "before booting from it.")
        except BuildError as e:
            self.finished.emit("error", str(e))
        except Exception as e:  # anything unexpected still lands in the window
            self.finished.emit("error", f"{type(e).__name__}: {e}")


# ---------------------------------------------------------------- window

class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle(APP_NAME)
        self.resize(620, 560)
        self.setMinimumSize(480, 480)
        self.catalog = load_catalog()
        self.thread: QThread | None = None
        self.worker: Worker | None = None
        self.drives: list[Drive] = []
        self._rate: tuple[float, int] = (0.0, 0)
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
        head.addWidget(label(f"v{__version__}", "brand"))
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
            t.setProperty("distro", d.id)
            self.os_group.addButton(t)
            row.addWidget(t)
        first = next((b for b in self.os_group.buttons() if b.isEnabled()), None)
        if first:
            first.setChecked(True)
        self.os_group.buttonToggled.connect(lambda *_: self._update_ready())
        lay.addLayout(row)
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
        lay.addStretch(1)
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
        lay.addStretch(2)
        row = QHBoxLayout()
        back = quiet_button("Go back")
        back.clicked.connect(lambda: self.stack.setCurrentWidget(self.choose_page))
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
        lay.addStretch(1)
        self.percent = label("0%", "big")
        lay.addWidget(self.percent)
        self.bar = HeatBar()
        lay.addWidget(self.bar)
        lay.addSpacing(10)
        self.work_status = label("", "hint")
        lay.addWidget(self.work_status)
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
        lay.addStretch(1)
        self.result_mark = StatusMark()
        lay.addWidget(self.result_mark)
        lay.addSpacing(16)
        self.result_title = label("", "title")
        self.result_text = label("", "hint")
        lay.addWidget(self.result_title)
        lay.addSpacing(8)
        lay.addWidget(self.result_text)
        lay.addStretch(2)
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

    def _distro(self) -> Distro | None:
        b = self.os_group.checkedButton()
        return next((d for d in self.catalog if b and d.id == b.property("distro")), None)

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
        self.stack.setCurrentWidget(self.confirm_page)
        self.erase_btn.setFocus()

    def start(self) -> None:
        distro, drive = self._distro(), self._drive()
        if not distro or not drive:
            return
        self.log_view.clear()
        self.log_view.setVisible(False)
        self._append(f"Creating {distro.name} on {drive.path}")
        self.work_title.setText(f"Creating {distro.name} on {drive.model}")
        self.percent.setText("0%")
        self.work_status.setText("Checking for the latest release")
        self.track.set_step(0)
        self._step = 0
        self.bar.set_value(0, busy=True)
        self.cancel_btn.setEnabled(True)
        self.cancel_btn.setText("Stop")
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
        if self.worker:
            self.worker.cancel.set()
            self.cancel_btn.setEnabled(False)
            self.cancel_btn.setText("Stopping")

    def _append(self, msg: str) -> None:
        self.log_view.appendPlainText(f"{time.strftime('%H:%M:%S')}  {msg}")

    def on_stage(self, stage: str, done: int, total: int) -> None:
        step = STAGE_STEP.get(stage, self._step)
        if step != self._step:
            self._rate = (0.0, 0)
        self._step = step
        self.track.set_step(step)
        frac = done / total if total else 0.0
        overall = (sum(STEP_WEIGHT[:step]) + STEP_WEIGHT[step] * frac) / sum(STEP_WEIGHT)
        self.percent.setText(f"{overall * 100:.0f}%")
        self.bar.set_value(overall, busy=not total)

        name = self.worker.distro.name if self.worker else ""
        text = {
            "lookup": "Checking for the latest release",
            "download": f"Downloading {name}",
            "verify": "Making sure the download is genuine",
            "prepare": "Formatting the drive",
            "copy": "Copying files to the drive",
            "sync": "Finishing writes",
            "check": "Reading the drive back to confirm",
            "eject": "Ejecting",
        }.get(stage, stage)
        if total and stage in ("download", "copy"):
            text += f", {done / GB:.1f} of {total / GB:.1f} GB"
            t0, d0 = self._rate
            now = time.monotonic()
            if t0 == 0 or d0 > done:
                self._rate = (now, done)
            elif now - t0 > 2 and done > d0:
                left = (total - done) / ((done - d0) / (now - t0))
                text += f", about {max(1, round(left / 60))} min left"
        self.work_status.setText(text)

    def on_finished(self, status: str, message: str) -> None:
        self.thread.quit()
        self.thread.wait()
        distro, drive = self.worker.distro, self.worker.drive
        self.thread = self.worker = None
        self.bar.stop()
        self.result_log.setPlainText(self.log_view.toPlainText())
        self.result_log.setVisible(False)
        self.result_btn.danger = False
        self.result_mark.set_kind(status)
        if status == "ok":
            self.track.set_step(len(STEPS))
            self.result_title.setText("Ready to boot")
            self.result_text.setText(
                f"{drive.model} now holds {distro.name}. Plug it into your handheld, open the boot "
                "menu and choose the USB drive.")
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

    def _back_to_start(self) -> None:
        self.drives = []
        self.refresh_drives()
        self.stack.setCurrentWidget(self.choose_page)

    def _escape(self) -> None:
        if self.stack.currentWidget() is self.confirm_page:
            self.stack.setCurrentWidget(self.choose_page)

    def closeEvent(self, event) -> None:
        if self.thread is not None:
            self.cancel()
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
