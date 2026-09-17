"""
Движок воспроизведения (replay) архива тегов BN1_MCA1.

Архив — событийный: каждая запись это изменение значения тега в момент времени.
Воспроизведение использует «удержание последнего значения» (zero-order hold):
в момент модельного времени T тег держит значение последней записи с t <= T.

Время архива (5 суток) проецируется на реальное время с коэффициентом
ускорения `speed` и (опционально) зацикливается.

Сырая серия историана не всегда годится каналу как есть: датчик в обрыве пишет
код ±3276.7, у закрытого клапана смещение нуля даёт −2 %, счётчику объёма нужен
интеграл расхода, а «время текущей операции» — возраст последнего изменения
номера операции. Всё это описывает ReplaySpec тега, а ArchiveReplay.evaluate
применяет его к серии.
"""

import gzip
import pickle
import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np

logger = logging.getLogger(__name__)

MODES = ("value", "integral", "age")


def _opt_float(config, key):
    value = config.get(key)
    return None if value is None else float(value)


@dataclass(frozen=True)
class ReplaySpec:
    """Как канал получает значение из серии архива.

    Порядок: из серии выбрасываются точки вне [valid_min, valid_max] (код обрыва
    датчика — канал держит последнее нормальное значение); затем по режиму берётся
    значение (value), интеграл по времени в секундах (integral) или возраст
    последнего изменения в секундах (age). Если задан порог above, серия служит
    условием «активно, когда >= above»: value даёт on/off, integral копит on/off,
    age сбрасывается в off, пока условие не выполнено. Результат масштабируется
    (scale, bias) и обрезается по [clamp_min, clamp_max]."""

    source: str
    offset: float = 0.0
    mode: str = "value"
    valid_min: Optional[float] = None
    valid_max: Optional[float] = None
    above: Optional[float] = None
    on: float = 1.0
    off: float = 0.0
    scale: float = 1.0
    bias: float = 0.0
    clamp_min: Optional[float] = None
    clamp_max: Optional[float] = None

    @classmethod
    def from_config(cls, config: dict, default_source: str) -> "ReplaySpec":
        mode = config.get("replay_mode", "value")
        if mode not in MODES:
            raise ValueError(f"replay_mode={mode!r}, ожидается один из {MODES}")
        return cls(
            source=str(config.get("replay_source", default_source)),
            offset=float(config.get("replay_offset", 0) or 0),
            mode=mode,
            valid_min=_opt_float(config, "replay_valid_min"),
            valid_max=_opt_float(config, "replay_valid_max"),
            above=_opt_float(config, "replay_above"),
            on=float(config.get("replay_on", 1.0)),
            off=float(config.get("replay_off", 0.0)),
            scale=float(config.get("replay_scale", 1.0)),
            bias=float(config.get("replay_bias", 0.0)),
            clamp_min=_opt_float(config, "replay_min"),
            clamp_max=_opt_float(config, "replay_max"),
        )


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
        self._filtered = {}       # (cid, vmin, vmax) -> (t, v) без точек вне диапазона
        self._cumulative = {}     # (cid, vmin, vmax, above, on, off) -> (t, w, C, total)

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

    def current_elapsed(self) -> float:
        """Модельное время с начала воспроизведения, секунды, без зацикливания."""
        if self._wall_start is None:
            self.start()
        return (time.monotonic() - self._wall_start) * self.speed

    def position(self, elapsed: float) -> float:
        """Позиция внутри архива для модельного времени elapsed."""
        if self.loop and self.duration > 0:
            return elapsed % self.duration
        return min(elapsed, self.duration)

    def current_offset(self) -> float:
        """Текущая позиция внутри архива (секунды от начала)."""
        return self.position(self.current_elapsed())

    def value_at(self, cid: str, offset: float, valid_min=None, valid_max=None):
        """Значение тега в позиции offset (zero-order hold)."""
        ser = self._series(cid, valid_min, valid_max)
        if ser is None:
            return None
        t, v = ser
        return v[self._index(t, offset)].item()

    def has(self, cid: str) -> bool:
        """Есть ли в архиве ряд для тега cid."""
        return cid in self.series

    def evaluate(self, spec: ReplaySpec, elapsed: float):
        """Значение канала по его ReplaySpec в модельный момент elapsed (None — серии нет)."""
        ser = self._series(spec.source, spec.valid_min, spec.valid_max)
        if ser is None:
            return None
        t, v = ser
        shifted = elapsed + spec.offset
        pos = self.position(shifted)
        current = v[self._index(t, pos)].item()
        active = spec.above is None or current >= spec.above

        if spec.mode == "integral":
            raw = self._integral(spec, shifted)
        elif spec.mode == "age":
            raw = self._age(t, pos) if active else spec.off
        elif spec.above is not None:
            raw = spec.on if active else spec.off
        else:
            raw = current

        result = raw * spec.scale + spec.bias
        if spec.clamp_min is not None:
            result = max(spec.clamp_min, result)
        if spec.clamp_max is not None:
            result = min(spec.clamp_max, result)
        return result

    @staticmethod
    def _index(t, pos) -> int:
        # До первой записи держим первое значение.
        return max(int(np.searchsorted(t, pos, side="right")) - 1, 0)

    def _series(self, cid, valid_min, valid_max):
        if valid_min is None and valid_max is None:
            return self.series.get(cid)
        key = (cid, valid_min, valid_max)
        if key not in self._filtered:
            ser = self.series.get(cid)
            if ser is None:
                self._filtered[key] = None
            else:
                t, v = ser
                keep = np.ones(len(v), dtype=bool)
                if valid_min is not None:
                    keep &= v >= valid_min
                if valid_max is not None:
                    keep &= v <= valid_max
                self._filtered[key] = (t[keep], v[keep]) if keep.any() else None
        return self._filtered[key]

    def _age(self, t, pos) -> float:
        idx = int(np.searchsorted(t, pos, side="right")) - 1
        if idx >= 0:
            return float(pos - t[idx])
        # Позиция раньше первого изменения — отсчёт идёт с последнего изменения
        # предыдущего круга архива.
        return float(pos + (self.duration - t[-1])) if self.loop else float(pos)

    def _integral(self, spec: ReplaySpec, elapsed: float) -> float:
        t, w, cum, total = self._cumulative_for(spec)
        if self.loop and self.duration > 0:
            loops, pos = divmod(max(elapsed, 0.0), self.duration)
        else:
            loops, pos = 0, min(max(elapsed, 0.0), self.duration)
        idx = int(np.searchsorted(t, pos, side="right")) - 1
        if idx < 0:
            part = w[0] * pos
        else:
            part = cum[idx] + w[idx] * (pos - t[idx])
        return float(loops * total + part)

    def _cumulative_for(self, spec: ReplaySpec):
        key = (spec.source, spec.valid_min, spec.valid_max, spec.above, spec.on, spec.off)
        if key not in self._cumulative:
            t, v = self._series(spec.source, spec.valid_min, spec.valid_max)
            t = np.asarray(t, dtype=float)
            v = np.asarray(v, dtype=float)
            w = v if spec.above is None else np.where(v >= spec.above, spec.on, spec.off)
            # cum[i] — интеграл от 0 до t[i]; до первой записи действует w[0].
            spans = np.diff(t)
            cum = np.empty(len(t))
            cum[0] = w[0] * t[0]
            cum[1:] = cum[0] + np.cumsum(w[:-1] * spans)
            total = cum[-1] + w[-1] * (self.duration - t[-1])
            self._cumulative[key] = (t, w, cum, total)
        return self._cumulative[key]
