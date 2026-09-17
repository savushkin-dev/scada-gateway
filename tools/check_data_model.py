#!/usr/bin/env python3
"""
Проверка адекватности данных симулятора по ВСЕМУ архиву, а не по окну из Kafka.

Короткое окно телеметрии покрывает доли процента пятисуточного архива и пропускает
редкие выбросы (так уже было: «0 отрицательных» на 3 минутах и 7 тысяч на следующих).
Здесь для каждого канала с источником в архиве берутся все точки его серии, к ним
применяется ReplaySpec канала — и получаются ровно те значения, которые канал может
принять за круг архива. Проверяются:

  * запись: RW только на OPC UA и PAC; флаг writable шлюза совпадает с доступом в
    симуляторе;
  * знак: отрицательные значения разрешены только кодам состояния (ST, STATE);
  * физические диапазоны измерений (температура, давление, расход, %, уровень…);
  * дискретные сигналы принимают только 0/1;
  * счётчики (интеграл) не убывают;
  * уставки и параметры не меняются сами — их задаёт оператор.

Запуск из корня репозитория:  python3 tools/check_data_model.py
Код возврата 1 при нарушениях.
"""
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import yaml

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "plc-simulator"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from core.archive_replay import ArchiveReplay, ReplaySpec  # noqa: E402
import cip_params as cp  # noqa: E402

SIM = ROOT / "plc-simulator/config/replay_config.yaml"
CTL = ROOT / "SCADA-gateway/src/main/resources/controllers.yaml"
ARCHIVE = ROOT / "plc-simulator/data/archive_replay.pkl.gz"

# Физические диапазоны измерений: (подтип, поле) -> (минимум, максимум).
RANGES = {
    ("TE", "V"): (0.0, 120.0), ("QT", "T"): (0.0, 120.0),       # °C
    ("PT", "V"): (0.0, 16.0),                                   # бар, шкала датчика
    ("QT", "V"): (0.0, 200.0),                                  # мСм/см
    ("FQT", "F"): (0.0, 80.0),                                  # м³/ч, шкала расходомера
    ("VC", "V"): (0.0, 100.0), ("M", "V"): (0.0, 100.0), ("LT", "V"): (0.0, 100.0),
    ("LT", "CLEVEL"): (0.0, 8000.0),                            # л, объём танка
    ("M", "FRQ"): (0.0, 50.0), ("M", "RPM"): (0.0, 3000.0),
    ("Параметры_линии", "P_CONC"): (0.0, 3.0),                  # %
    ("Параметры_линии", "P_SUM_OP"): (0.0, 20000.0),            # л за операцию
    ("Параметры_линии", "P_LOADED_RECIPE"): (0.0, 32.0),
    ("Параметры_линии", "P_CUR_REC"): (0.0, 32.0),              # 0 — линия выключена
    ("Параметры_линии", "P_FLOW"): (0.0, 80.0),
    ("Параметры_линии", "PIDP_Uk"): (0.0, 100.0), ("Параметры_линии", "PIDF_Uk"): (0.0, 100.0),
    ("Параметры_линии", "P_OP_TIME_LEFT"): (0.0, 5 * 86400.0),
    ("Управляющие_каналы_линии", "OPER"): (0.0, 555.0),
}
# Коды состояния: отрицательное значение = ошибка/пауза прибора или линии в ptusa.
STATE_CODES = {("M", "ST"): (-100.0, 2.0), ("Управляющие_каналы_линии", "STATE"): (-100.0, 2.0)}
BINARY_FIELDS = {"ST", "OPENED", "CLOSED", "NAMUR_ST", "BLINK"}
BINARY_TYPES = {"V", "DO", "DI", "LS", "FS", "SB", "HA", "HL", "VC", "AI", "AO", "TE", "PT",
                "QT", "FQT"}


@dataclass
class Report:
    checked: int = 0
    violations: list = field(default_factory=list)
    extremes: dict = field(default_factory=dict)

    def fail(self, channel, message):
        self.violations.append(f"канал {channel}: {message}")


SETPOINT_OBJECTS = {"Параметры_станции", "Параметры_самоочистки_станции", "Редактируемый_рецепт_линии"}


def is_setpoint(tag: dict, dev_type: str, f: str) -> bool:
    if dev_type in SETPOINT_OBJECTS or f.startswith("P_") and dev_type not in ("Параметры_линии", "Статистика_линии"):
        return True
    m = re.fullmatch(r"RT_PAR_F\[(\d+)\]", tag["field"])
    return dev_type == "Параметры_линии" and bool(m) and \
        cp.LINE_PARAMS.get(int(m.group(1)), ("", "set"))[1] == "set"


def kind_of(tag: dict):
    dev_type = re.sub(r"_\d$", "", tag["dev_type"])
    f = tag["field"]
    m = re.fullmatch(r"RT_PAR_F\[(\d+)\]", f)
    if dev_type == "Параметры_линии" and m:
        f = cp.LINE_PARAMS.get(int(m.group(1)), ("P_RESERVED",))[0]
    return dev_type, f


def reachable(replay: ArchiveReplay, spec: ReplaySpec):
    """Все значения канала за круг архива (до приведения к типу) и признак монотонности."""
    ser = replay._series(spec.source, spec.valid_min, spec.valid_max)
    if ser is None:
        return None, True
    t, v = (np.asarray(a, dtype=float) for a in ser)
    active = np.ones(len(v), dtype=bool) if spec.above is None else v >= spec.above
    monotonic = True
    if spec.mode == "integral":
        w = v if spec.above is None else np.where(active, spec.on, spec.off)
        monotonic = bool((w * spec.scale >= 0).all())
        total = replay._cumulative_for(spec)[3]
        raw = np.array([0.0, total])            # за круг: от 0 до полного интеграла
    elif spec.mode == "age":
        gaps = np.diff(np.append(t, replay.duration + t[0]))
        raw = np.append(0.0, np.where(active, gaps, spec.off))
    elif spec.above is not None:
        raw = np.where(active, spec.on, spec.off)
    else:
        raw = v
    out = raw * spec.scale + spec.bias
    if spec.clamp_min is not None:
        out = np.maximum(out, spec.clamp_min)
    if spec.clamp_max is not None:
        out = np.minimum(out, spec.clamp_max)
    return out, monotonic


def check(sim_text: str, ctl_text: str, replay: ArchiveReplay) -> Report:
    report = Report()
    tags = [yaml.safe_load(l.strip()[2:]) for l in sim_text.splitlines() if l.lstrip().startswith("- {")]
    gateway_writable = {int(c): w == "true" for c, w in
                        re.findall(r"channelId: (\d+).*?writable: (true|false)", ctl_text)}

    for tag in tags:
        channel = int(tag["address"])
        dev_type, f = kind_of(tag)
        report.checked += 1
        rw = tag["access"] == "RW"
        if rw and tag["protocol"] not in ("opcua", "pac"):
            report.fail(channel, f"запись разрешена по {tag['protocol']}")
        if gateway_writable.get(channel) != rw:
            report.fail(channel, f"writable шлюза {gateway_writable.get(channel)} ≠ доступ {tag['access']}")
        if tag["type"] == "string":
            continue
        if tag.get("generator") and is_setpoint(tag, dev_type, f):
            report.fail(channel, f"уставка {dev_type}.{f} меняется сама (источник {tag.get('replay_source')})")

        if tag.get("generator") == "replay":
            spec = ReplaySpec.from_config(tag, tag["address"])
            values, monotonic = reachable(replay, spec)
            if values is None:
                report.fail(channel, f"серии {spec.source} нет в архиве")
                continue
            if not monotonic:
                report.fail(channel, "счётчик убывает")
        else:
            values = np.array([float(tag["initial"])])
        if tag["type"] == "int":
            values = np.round(values)

        lo, hi = float(values.min()), float(values.max())
        key = (dev_type, f)
        prev = report.extremes.get(key)
        report.extremes[key] = (min(lo, prev[0]), max(hi, prev[1])) if prev else (lo, hi)

        if key in STATE_CODES:
            bounds = STATE_CODES[key]
        else:
            bounds = RANGES.get(key)
            if lo < 0:
                report.fail(channel, f"{dev_type}.{f} уходит в минус: {lo}")
        if bounds and (lo < bounds[0] or hi > bounds[1]):
            report.fail(channel, f"{dev_type}.{f} вне [{bounds[0]}, {bounds[1]}]: {lo}..{hi}")
        if f in BINARY_FIELDS and dev_type in BINARY_TYPES and key not in STATE_CODES:
            if not set(np.unique(values)) <= {0.0, 1.0}:
                report.fail(channel, f"{dev_type}.{f} не 0/1: {sorted(set(np.unique(values)))[:5]}")
    return report


def load_replay() -> ArchiveReplay:
    replay = ArchiveReplay(data_path=str(ARCHIVE), loop=True)
    replay.load()
    return replay


def main() -> None:
    report = check(SIM.read_text(encoding="utf-8"), CTL.read_text(encoding="utf-8"), load_replay())
    print(f"проверено каналов: {report.checked}")
    print("диапазоны измерений и кодов за весь архив:")
    for (dev_type, f), (lo, hi) in sorted(report.extremes.items()):
        if (dev_type, f) in RANGES or (dev_type, f) in STATE_CODES:
            print(f"  {dev_type}.{f}: {lo:g} .. {hi:g}")
    if report.violations:
        print(f"НАРУШЕНИЙ: {len(report.violations)}")
        for line in report.violations[:50]:
            print("  " + line)
        sys.exit(1)
    print("нарушений нет")


if __name__ == "__main__":
    main()
