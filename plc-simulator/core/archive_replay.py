"""
Движок воспроизведения (replay) архива тегов BN1_MCA1.

Архив — событийный: каждая запись это изменение значения тега в момент времени.
Воспроизведение использует «удержание последнего значения» (zero-order hold):
в момент модельного времени T тег держит значение последней записи с t <= T.

Время архива (5 суток) проецируется на реальное время с коэффициентом
ускорения `speed` и (опционально) зацикливается.
"""

import gzip
import pickle
import logging
import time
from pathlib import Path

import numpy as np

logger = logging.getLogger(__name__)


class ArchiveReplay:
    """Проигрыватель предпосчитанного архива (data/archive_replay.pkl.gz).

    Хранит по тегу пары (массив времён, массив значений) и по текущему модельному
    времени отдаёт значение методом zero-order hold. Модельное время = реальное,
    умноженное на speed, с опциональным зацикливанием."""

    def __init__(self, data_path: str, speed: float = 1.0, loop: bool = True,
                 base_dir: Path = None):
        """Готовит проигрыватель: путь к архиву (относительный резолвится от base_dir),
        коэффициент ускорения speed и флаг зацикливания. Данные грузятся в load()."""
        self.speed = float(speed)
        self.loop = bool(loop)

        path = Path(data_path)
        if not path.is_absolute() and base_dir is not None:
            path = base_dir / path
        self.data_path = path

        self.series = {}          # cid -> (t: np.ndarray, v: np.ndarray)
        self.duration = 0.0       # длительность архива в секундах
        self.start_epoch = 0.0
        self._wall_start = None   # реальное время старта воспроизведения

    def load(self):
        """Читает .pkl.gz в память: ряды значений по тегам, start_epoch и длительность."""
        if not self.data_path.exists():
            raise FileNotFoundError(f"Архив replay не найден: {self.data_path}")
        with gzip.open(self.data_path, "rb") as f:
            data = pickle.load(f)
        self.start_epoch = data["start_epoch"]
        self.duration = data["duration"]
        for cid, arr in data["series"].items():
            self.series[cid] = (arr["t"], arr["v"])
        logger.info(
            "Загружен архив replay: %d тегов, длительность %.2f сут, "
            "speed=%.1f, loop=%s",
            len(self.series), self.duration / 86400, self.speed, self.loop,
        )

    def start(self):
        """Отсчитывает нулевую точку воспроизведения от текущего момента (monotonic)."""
        self._wall_start = time.monotonic()

    def current_offset(self) -> float:
        """Текущая позиция внутри архива (секунды от начала)."""
        if self._wall_start is None:
            self.start()
        elapsed = (time.monotonic() - self._wall_start) * self.speed
        if self.loop and self.duration > 0:
            return elapsed % self.duration
        return min(elapsed, self.duration)

    def value_at(self, cid: str, offset: float):
        """Значение тега в позиции offset (zero-order hold)."""
        ser = self.series.get(cid)
        if ser is None:
            return None
        t, v = ser
        idx = int(np.searchsorted(t, offset, side="right")) - 1
        if idx < 0:
            idx = 0  # до первой записи держим первое значение
        return v[idx].item()

    def has(self, cid: str) -> bool:
        """Есть ли в архиве ряд для тега cid."""
        return cid in self.series
