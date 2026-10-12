"""One-screen PySide6 window: pick OS, pick USB drive, press Create."""

from __future__ import annotations

import sys
import threading
import time

from PySide6.QtCore import QObject, Qt, QThread, QTimer, Signal
from PySide6.QtGui import QFont, QStandardItemModel
from PySide6.QtWidgets import (
    QApplication, QComboBox, QHBoxLayout, QLabel, QMainWindow, QMessageBox,
    QPlainTextEdit, QProgressBar, QPushButton, QVBoxLayout, QWidget,
)

from . import APP_NAME, __version__
from .config import Distro, load_catalog
from .download import Cancelled, prepare_iso
from .flasher import Drive, flash, list_drives

STAGE_TEXT = {
    "check": "Checking for the latest release",
    "download": "Downloading",
    "verify": "Verifying download",
    "unmount": "Preparing the drive",
    "write": "Writing to USB",
    "sync": "Flushing data to the drive",
    "readback": "Double-checking the drive",
}

STYLE = """
QWidget { background: #14161a; color: #e8e8ea; font-size: 15px; }
QLabel#title { font-size: 26px; font-weight: 700; color: #ffffff; }
QLabel#muted, QLabel#version { color: #8b9099; }
QLabel#section { font-size: 13px; font-weight: 600; color: #8b9099; letter-spacing: 1px; }
QComboBox, QPushButton#secondary {
    background: #1f2228; border: 1px solid #2e323a; border-radius: 8px;
    padding: 10px 12px; min-height: 26px;
}
QComboBox:disabled { color: #5c616b; }
QComboBox QAbstractItemView { background: #1f2228; selection-background-color: #c2410c; }
QPushButton#primary {
    background: #ea580c; color: white; border: none; border-radius: 10px;
    padding: 16px; font-size: 18px; font-weight: 700;
}
QPushButton#primary:hover { background: #f97316; }
QPushButton#primary:disabled { background: #3a2a22; color: #8b7a70; }
QPushButton#secondary:hover { border-color: #ea580c; }
QProgressBar {
    background: #1f2228; border: none; border-radius: 6px; height: 12px; text-align: center;
    color: transparent;
}
QProgressBar::chunk { background: #ea580c; border-radius: 6px; }
QPlainTextEdit { background: #0d0f12; border: 1px solid #2e323a; border-radius: 8px;
                 font-family: monospace; font-size: 12px; color: #a9aeb7; }
"""


class Worker(QObject):
    stage = Signal(str, int, int)
    log = Signal(str)
    finished = Signal(bool, str)

    def __init__(self, distro: Distro, drive: Drive) -> None:
        super().__init__()
        self.distro, self.drive = distro, drive
        self.cancel = threading.Event()

    def run(self) -> None:
        try:
            img = prepare_iso(self.distro, self.stage.emit, self.log.emit, self.cancel)
            if self.cancel.is_set():
                raise Cancelled()
            self.stage.emit("unmount", 0, 0)
            flash(img.path, img.sha256, self.drive, self.distro.min_drive_gb,
                  self.stage.emit, self.log.emit)
            self.finished.emit(True, f"{self.distro.name} boot drive is ready.")
        except Cancelled:
            self.finished.emit(False, "Cancelled. Your download progress is kept for next time.")
        except Exception as e:  # surface every failure in the window
            self.finished.emit(False, str(e))


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle(APP_NAME)
        self.setMinimumWidth(520)
        self.catalog = load_catalog()
        self.thread: QThread | None = None
        self.worker: Worker | None = None
        self._rate = (0.0, 0)
        self._drive_paths: tuple[str, ...] = ()

        root = QWidget()
        lay = QVBoxLayout(root)
        lay.setContentsMargins(28, 24, 28, 24)
        lay.setSpacing(10)

        head = QHBoxLayout()
        title = QLabel(APP_NAME, objectName="title")
        version = QLabel(f"v{__version__}", objectName="version")
        head.addWidget(title)
        head.addStretch()
        head.addWidget(version, alignment=Qt.AlignBottom)
        lay.addLayout(head)
        lay.addSpacing(8)

        lay.addWidget(QLabel("OPERATING SYSTEM", objectName="section"))
        self.distro_box = QComboBox()
        model = QStandardItemModel()
        self.distro_box.setModel(model)
        for d in self.catalog:
            self.distro_box.addItem(d.name, d.id)
            if not d.enabled:
                model.item(self.distro_box.count() - 1).setEnabled(False)
        self.distro_desc = QLabel(objectName="muted", wordWrap=True)
        self.distro_box.currentIndexChanged.connect(self._distro_changed)
        lay.addWidget(self.distro_box)
        lay.addWidget(self.distro_desc)
        lay.addSpacing(8)

        lay.addWidget(QLabel("USB DRIVE", objectName="section"))
        row = QHBoxLayout()
        self.drive_box = QComboBox()
        refresh = QPushButton("Refresh", objectName="secondary")
        refresh.clicked.connect(self.refresh_drives)
        row.addWidget(self.drive_box, 1)
        row.addWidget(refresh)
        lay.addLayout(row)
        self.drive_hint = QLabel(objectName="muted", wordWrap=True)
        lay.addWidget(self.drive_hint)
        lay.addSpacing(14)

        self.create_btn = QPushButton("Create Boot Drive", objectName="primary")
        self.create_btn.clicked.connect(self.start)
        lay.addWidget(self.create_btn)

        self.status = QLabel("Ready", objectName="muted")
        self.progress = QProgressBar()
        self.progress.setRange(0, 1000)
        self.progress.setValue(0)
        lay.addWidget(self.status)
        lay.addWidget(self.progress)

        bottom = QHBoxLayout()
        self.details_btn = QPushButton("Show details", objectName="secondary")
        self.details_btn.setCheckable(True)
        self.cancel_btn = QPushButton("Cancel", objectName="secondary")
        self.cancel_btn.setVisible(False)
        self.cancel_btn.clicked.connect(self.cancel)
        bottom.addWidget(self.details_btn)
        bottom.addStretch()
        bottom.addWidget(self.cancel_btn)
        lay.addLayout(bottom)

        self.log_view = QPlainTextEdit(readOnly=True)
        self.log_view.setVisible(False)
        self.log_view.setMinimumHeight(160)
        self.details_btn.toggled.connect(self._toggle_details)
        lay.addWidget(self.log_view)
        lay.addStretch()

        self.setCentralWidget(root)
        self._distro_changed()
        self.refresh_drives()

        # Notice drives being plugged in or pulled out while idle.
        self.poll = QTimer(self, interval=3000, timeout=self._poll_drives)
        self.poll.start()

    # ------------------------------------------------------------ helpers

    def _distro(self) -> Distro:
        return next(d for d in self.catalog if d.id == self.distro_box.currentData())

    def _distro_changed(self) -> None:
        d = self._distro()
        self.distro_desc.setText(d.description)
        self._update_enabled()

    def _toggle_details(self, on: bool) -> None:
        self.log_view.setVisible(on)
        self.details_btn.setText("Hide details" if on else "Show details")
        self.adjustSize()

    def append_log(self, msg: str) -> None:
        self.log_view.appendPlainText(f"[{time.strftime('%H:%M:%S')}] {msg}")

    def _busy(self) -> bool:
        return self.thread is not None

    def _update_enabled(self) -> None:
        ready = (not self._busy() and self._distro().enabled
                 and self.drive_box.currentData() is not None)
        self.create_btn.setEnabled(ready)
        self.distro_box.setEnabled(not self._busy())
        self.drive_box.setEnabled(not self._busy())

    def _poll_drives(self) -> None:
        if not self._busy():
            self.refresh_drives(quiet=True)

    def refresh_drives(self, quiet: bool = False) -> None:
        try:
            drives = list_drives()
        except Exception as e:
            self.drive_box.clear()
            self.drive_hint.setText(f"Could not list drives: {e}")
            self._update_enabled()
            return
        paths = tuple(d.path for d in drives)
        if quiet and paths == self._drive_paths:
            return
        self._drive_paths = paths
        current = self.drive_box.currentData().path if self.drive_box.currentData() else None
        self.drive_box.clear()
        for d in drives:
            self.drive_box.addItem(d.label, d)
            if d.path == current:
                self.drive_box.setCurrentIndex(self.drive_box.count() - 1)
        if drives:
            self.drive_hint.setText("Only USB drives are shown. Internal disks are never listed.")
        else:
            self.drive_box.addItem("No USB drive found", None)
            self.drive_hint.setText("Plug in a USB drive (16 GB or larger). It shows up automatically.")
        self._update_enabled()

    def closeEvent(self, event) -> None:
        if self._busy():
            QMessageBox.warning(self, APP_NAME, "Please wait until the drive is finished. "
                                                "Closing now could leave it unusable.")
            event.ignore()
            return
        event.accept()

    # ------------------------------------------------------------ actions

    def start(self) -> None:
        drive: Drive = self.drive_box.currentData()
        distro = self._distro()
        if drive is None:
            return
        if drive.size < distro.min_drive_gb * 1000**3 * 0.93:
            QMessageBox.warning(self, APP_NAME, f"This drive is too small. Use one that is "
                                                f"{distro.min_drive_gb} GB or larger.")
            return

        box = QMessageBox(self)
        box.setIcon(QMessageBox.Warning)
        box.setWindowTitle("Erase this drive?")
        box.setText(f"<b>Everything on this drive will be erased:</b><br><br>{drive.label}")
        box.setInformativeText(f"It will become a {distro.name} boot drive.")
        erase = box.addButton("Erase and Create", QMessageBox.DestructiveRole)
        keep = box.addButton("Cancel", QMessageBox.RejectRole)
        box.setDefaultButton(keep)
        box.exec()
        if box.clickedButton() is not erase:
            return

        self.log_view.clear()
        self.append_log(f"Creating {distro.name} on {drive.path}")
        self.progress.setValue(0)
        self.cancel_btn.setVisible(True)
        self.cancel_btn.setEnabled(True)

        self.thread = QThread(self)
        self.worker = Worker(distro, drive)
        self.worker.moveToThread(self.thread)
        self.thread.started.connect(self.worker.run)
        self.worker.stage.connect(self.on_stage)
        self.worker.log.connect(self.append_log)
        self.worker.finished.connect(self.on_finished)
        self.thread.start()
        self._update_enabled()

    def cancel(self) -> None:
        if self.worker:
            self.worker.cancel.set()
            self.cancel_btn.setEnabled(False)
            self.status.setText("Cancelling...")

    def on_stage(self, stage: str, done: int, total: int) -> None:
        text = STAGE_TEXT.get(stage, stage)
        # Interrupting a write leaves a half-written drive, so only allow
        # cancelling before writing starts.
        if stage in ("unmount", "write", "sync", "readback"):
            self.cancel_btn.setEnabled(False)
        if total:
            self.progress.setRange(0, 1000)
            self.progress.setValue(int(1000 * done / total))
            t0, d0 = self._rate
            now = time.monotonic()
            if d0 > done or now - t0 > 5:
                self._rate = (now, done)
            elif now - t0 > 1:
                speed = (done - d0) / (now - t0)
                eta = (total - done) / speed if speed > 0 else 0
                text += (f"  {done / 1e9:.1f} of {total / 1e9:.1f} GB, "
                         f"{speed / 1e6:.0f} MB/s, about {eta / 60:.0f} min left")
        else:
            self.progress.setRange(0, 0)  # busy animation
            self._rate = (0.0, 0)
        self.status.setText(text)

    def on_finished(self, ok: bool, message: str) -> None:
        self.append_log(message)
        self.thread.quit()
        self.thread.wait()
        self.thread = self.worker = None
        self.cancel_btn.setVisible(False)
        self.progress.setRange(0, 1000)
        self.progress.setValue(1000 if ok else 0)
        self.status.setText(message if ok else "Failed. See details below.")
        if not ok:
            self.details_btn.setChecked(True)
            QMessageBox.critical(self, APP_NAME, message)
        else:
            QMessageBox.information(self, APP_NAME, f"{message}\n\nPlug it into your handheld, "
                                                    "then boot from USB.")
        self.refresh_drives()


def run() -> int:
    app = QApplication.instance() or QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setDesktopFileName("handheld-forge")
    app.setStyleSheet(STYLE)
    f = QFont()
    f.setPointSize(11)
    app.setFont(f)
    win = MainWindow()
    win.show()
    return app.exec()
