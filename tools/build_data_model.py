#!/usr/bin/env python3
"""
Модель данных станции: откуда каждый канал берёт значение и можно ли в него писать.

Правит на месте два сгенерированных конфига (раскладку по протоколам и типы полей
не трогает — её делает tools/apply_tag_types.py):
  * plc-simulator/config/replay_config.yaml — источник значения (архив, производное
    от архива или константа) и доступ RO/RW;
  * SCADA-gateway/src/main/resources/controllers.yaml — writable.

Правила:

1. **Запись только по OPC UA и PAC, и только команды и уставки.** Команды: ST
   исполнительных механизмов (клапан, мотор, DO, сирена, лампа), режим M, CMD, выбор
   рецепта/программы. Уставки: P_* приборов, параметры станции, самоочистки, рецепта,
   уставочные параметры линии. Показания датчиков, вычисляемые контроллером значения,
   статистика и всё, что на Modbus, — только чтение.

2. **Измерение берёт серию своей физической величины.** Второй байт cid архива — номер
   подтипа в базе каналов; все 20 групп архива однозначно сопоставлены с группами
   справочника docs/BN1_MCA1-типы-тегов.csv по числу каналов, записей и диапазону.
   Младшие байты — порядковый номер канала в подтипе: у приборов по линиям
   (LINE1TE1, LINE1TE2, LINE2TE1, …), у параметров линии — индекс RT_PAR_F минус 1
   (0006 = RT_PAR_F[7] «текущая концентрация»). Серии чистятся: код обрыва датчика
   (±3276.7, 32.76, 1000) выбрасывается и канал держит последнее нормальное значение,
   смещение нуля (−2 % у закрытого клапана, −0.7 бар у датчика давления) прижимается к 0.

3. **Производные величины считаются из той же серии**, чтобы процесс был согласован:
   объём счётчика — интеграл расхода, «время текущей операции» — возраст номера
   операции, задания подогрева и расхода — рецептурные значения, пока идёт операция,
   положение OPENED/CLOSED — по открытию регулирующего клапана.

4. **Линии 1–3 работают, 4–6 выключены.** Данные в архиве есть только по трём линиям
   (STATE/OPER, параметры, приборы), в базе каналов объекты линий 4–6 отключены.
   Каналы выключенной линии стоят в покое: мотор выключен, клапан закрыт, расход 0,
   температура — температура цеха.

5. **Отрицательные значения только у кодов состояния.** ST прибора и STATE линии в
   ptusa отрицательны при ошибке/паузе — это настоящие данные контроллера. У измерений,
   уставок и счётчиков отрицательных нет.

Уставки и параметры — константы из tools/cip_params.py (имена и индексы из прошивки
ptusa_main). Скрипт идемпотентен: повторный прогон не меняет файлы.

Запуск из корня репозитория:

    python3 tools/build_data_model.py            # переписать конфиги
    python3 tools/build_data_model.py --check    # код 1, если конфиги устарели
"""
import gzip
import pickle
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import numpy as np
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cip_params as cp  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SIM = ROOT / "plc-simulator/config/replay_config.yaml"
CTL = ROOT / "SCADA-gateway/src/main/resources/controllers.yaml"
ARCHIVE = ROOT / "plc-simulator/data/archive_replay.pkl.gz"

ACTIVE_LINES = (1, 2, 3)
WRITE_PROTOCOLS = ("opcua", "pac")
AMBIENT_T = 18.0          # температура цеха на выключенной линии, °C
TANK_VOLUME = 8000.0      # объём танка, л: уровень LT.V = CLEVEL / объём

# Номер подтипа (второй байт cid) по группе справочника.
SS = {
    "V_ST": "01", "VC_V": "05", "M_ST": "0D", "M_V": "13", "LS_ST": "15", "TE_V": "1D",
    "FS_ST": "1F", "FQT_F": "3B", "LT_CLEVEL": "4D", "QT_V": "51", "DI_ST": "58",
    "DO_ST": "5B", "PT_V": "63",
}
LINE_CTRL = {1: "22", 2: "31", 3: "32"}   # Управляющие_каналы_линии_k: 0000 STATE, 0001 OPER
LINE_PAR = {1: "0A", 2: "36", 3: "37"}    # Параметры_линии_k: индекс = RT_PAR_F - 1

TE_CLEAN = {"valid_min": 0.0, "valid_max": 120.0}      # ±3276.7 — обрыв датчика
PT_CLEAN = {"valid_max": 32.7, "clamp_min": 0.0}       # 32.76 — переполнение шкалы
QT_CLEAN = {"valid_max": 999.0, "clamp_min": 0.0}      # 1000 — выход за шкалу
PERCENT = {"clamp_min": 0.0, "clamp_max": 100.0}

REPLAY_KEYS = ("source", "mode", "valid_min", "valid_max", "above", "on", "off",
               "scale", "bias", "clamp_min", "clamp_max")
CONFIG_KEY = {"clamp_min": "replay_min", "clamp_max": "replay_max"}
FIELD_ORDER = ("name", "address", "type", "protocol", "modbus_address", "modbus_type",
               "device", "field", "dev_type", "access", "initial", "generator",
               *(CONFIG_KEY.get(k, f"replay_{k}") for k in REPLAY_KEYS), "replay_format",
               "noise_enabled", "drift_enabled")
QUOTED = {"name", "address", "device", "field", "dev_type", "replay_source", "replay_mode",
          "replay_format"}


def cid(ss: str, idx: int) -> str:
    return f"2A{ss}{idx:04X}"


@dataclass
class Plan:
    """Что записать в тег: право записи, начальное значение и источник."""
    writable: bool
    initial: object = 0
    replay: Optional[dict] = None
    fmt: Optional[str] = None
    kind: str = "const"  # const | archive | derived — для статистики

    @property
    def source(self) -> Optional[str]:
        return self.replay["source"] if self.replay else None


def const(value, writable=False) -> Plan:
    return Plan(writable, value)


def archive(src, writable=False, **kw) -> Plan:
    return Plan(writable, kw.get("clamp_min") or 0, {"source": src, **kw}, kind="archive")


def derived(src, writable=False, **kw) -> Plan:
    return Plan(writable, kw.get("clamp_min") or 0, {"source": src, **kw}, kind="derived")


# --------------------------------------------------------------------------- контекст

@dataclass
class Context:
    series: set
    v_pool: dict = field(default_factory=dict)    # device -> cid
    di_pool: dict = field(default_factory=dict)
    do_pool: dict = field(default_factory=dict)
    qavs: dict = field(default_factory=dict)      # line -> средняя концентрация щёлочи

    def has(self, src: str) -> bool:
        return src in self.series


def line_of(tag: dict) -> Optional[int]:
    # Объекты линий (Параметры_линии, Статистика_линии, …) — прибор OBJECT<k>;
    # приборы линий — LINE<k>… или L<k>_…; остальное принадлежит станции.
    if tag["dev_type"].endswith("_линии"):
        return _num(r"^OBJECT(\d)$", tag["device"])
    m = re.match(r"(?:LINE|L)(\d)", tag["device"])
    return int(m.group(1)) if m else None


def is_active(tag: dict) -> bool:
    line = line_of(tag)
    return line is None or line in ACTIVE_LINES


def build_context(tags: list, archive_data: dict) -> Context:
    ctx = Context(series=set(archive_data["series"]))
    by_type = {}
    for t in sorted(tags, key=lambda t: int(t["address"])):
        if t["field"] == "ST":
            by_type.setdefault(t["dev_type"], []).append(t)

    # Клапаны: порядковый номер в подтипе V_ST по возрастанию node.id, сначала линия 1,
    # затем 2 (37 + 29 = 66 каналов ↔ индексы архива 0..64). Индекса нет в архиве —
    # клапан за 5 суток не переключался.
    ordinal = 0
    for line in (1, 2):
        for t in (t for t in by_type.get("V", []) if line_of(t) == line):
            src = cid(SS["V_ST"], ordinal)
            if ctx.has(src):
                ctx.v_pool[t["device"]] = src
            ordinal += 1

    # DI/DO: в архиве подтипы шире нашей базы (индексы до 102/117), поэтому серии
    # раздаются по порядку каналам работающих линий и станции, без повторов.
    for dev_type, ss, pool in (("DI", SS["DI_ST"], ctx.di_pool), ("DO", SS["DO_ST"], ctx.do_pool)):
        sources = sorted(c for c in ctx.series if c[2:4] == ss)
        devices = [t["device"] for t in by_type.get(dev_type, []) if is_active(t)]
        pool.update(zip(devices, sources))

    for line, ss in LINE_PAR.items():
        t_v = archive_data["series"].get(cid(ss, 6))
        if t_v is not None:
            v = np.asarray(t_v["v"], dtype=float)
            ctx.qavs[line] = round(float(v[v > 0].mean()), 2) if (v > 0).any() else 0.0
    return ctx


# ---------------------------------------------------------------------- классификация

def plan_for(tag: dict, ctx: Context) -> Plan:
    dev_type = re.sub(r"_\d$", "", tag["dev_type"])
    f = tag["field"]
    if f == "M":
        return const(0, writable=True)   # ручной режим — команда оператора, штатно авто
    handler = HANDLERS.get(dev_type)
    if handler is None:
        raise ValueError(f"нет правила для подтипа {dev_type!r} (канал {tag['address']})")
    plan = handler(tag, f, ctx)
    if plan is None:
        raise ValueError(f"нет правила для {dev_type}.{f} (канал {tag['address']})")
    if plan.replay and not ctx.has(plan.source):
        raise ValueError(f"серии {plan.source} нет в архиве (канал {tag['address']})")
    return plan


def device_param(f: str, dev_type: str) -> Optional[Plan]:
    if f in ("P_MIN_V", "P_MAX_V"):
        lo, hi = cp.SENSOR_RANGE[dev_type]
        return const(lo if f == "P_MIN_V" else hi, writable=True)
    if f in cp.DEVICE_PARAMS:
        return const(cp.DEVICE_PARAMS[f], writable=True)
    return None


def _num(pattern: str, text: str) -> Optional[int]:
    m = re.search(pattern, text)
    return int(m.group(1)) if m else None


def valve(tag, f, ctx):
    if f == "ST":
        src = ctx.v_pool.get(tag["device"])
        return archive(src, writable=True) if src else const(0, writable=True)


def discrete_out(tag, f, ctx):
    if f == "ST":
        src = ctx.do_pool.get(tag["device"])
        return archive(src, writable=True) if src else const(0, writable=True)


def discrete_in(tag, f, ctx):
    if f == "ST":
        src = ctx.di_pool.get(tag["device"])
        return archive(src) if src else const(0)


def button(tag, f, ctx):
    if f == "ST":
        return const(0)


def signal(tag, f, ctx):
    if f == "ST":
        return const(0, writable=True)


def sensor_state(tag) -> Plan:
    return const(1 if is_active(tag) else 0)


def line_device(tag, prefix: str) -> Optional[tuple]:
    """(линия, номер) для прибора работающей линии вида LINE2TE1, иначе None."""
    m = re.fullmatch(r"LINE(\d)" + prefix + r"(\d+)", tag["device"])
    if m and int(m.group(1)) in ACTIVE_LINES:
        return int(m.group(1)), int(m.group(2))
    return None


def temperature(tag, f, ctx):
    if f == "V":
        ld = line_device(tag, "TE")
        return archive(cid(SS["TE_V"], 2 * (ld[0] - 1) + ld[1] - 1), **TE_CLEAN) if ld else const(AMBIENT_T)
    if f == "ST":
        return sensor_state(tag)
    return device_param(f, "TE")


def pressure(tag, f, ctx):
    if f == "V":
        ld = line_device(tag, "PT")
        return archive(cid(SS["PT_V"], ld[0] - 1), **PT_CLEAN) if ld else const(0.0)
    if f == "ST":
        return sensor_state(tag)
    return device_param(f, "PT")


def conductivity(tag, f, ctx):
    ld = line_device(tag, "QT")
    if f == "V":
        return archive(cid(SS["QT_V"], ld[0] - 1), **QT_CLEAN) if ld else const(0.0)
    if f == "T":
        # Термокомпенсация концентратомера стоит на той же трубе, что датчик подачи.
        return derived(cid(SS["TE_V"], 2 * (ld[0] - 1)), **TE_CLEAN) if ld else const(AMBIENT_T)
    if f == "ST":
        return sensor_state(tag)
    return device_param(f, "QT")


def control_valve(tag, f, ctx):
    ld = line_device(tag, "VC")
    src = cid(SS["VC_V"], ld[0] - 1) if ld else None
    if f == "V":
        return archive(src, **PERCENT) if src else const(0.0)
    if f == "ST":
        return derived(src, writable=True, above=5.0, on=1, off=0) if src else const(0, writable=True)
    if f == "OPENED":
        return derived(src, above=95.0, on=1, off=0) if src else const(0)
    if f == "CLOSED":
        return derived(src, above=5.0, on=0, off=1) if src else const(1)
    if f == "NAMUR_ST":
        return sensor_state(tag)
    if f == "BLINK":
        return const(0)


def flowmeter(tag, f, ctx):
    ld = line_device(tag, "FQT")
    src = cid(SS["FQT_F"], ld[0] - 1) if ld else None
    # Счётчики в литрах (P_SUM_OP линии тоже в литрах), расход в м³/ч.
    number = ld[0] if ld else (line_of(tag) or 7)
    if f == "F":
        return archive(src) if src else const(0.0)
    if f in ("V", "ABS_V"):
        base = (12_000.0 if f == "V" else 480_000.0) * number
        if not src:
            return const(base)
        return derived(src, mode="integral", scale=round(1000 / 3600, 9), bias=base)
    if f == "ST":
        return sensor_state(tag)
    return device_param(f, "FQT")


def motor(tag, f, ctx):
    m = re.fullmatch(r"LINE(\d)M(1|100\d)", tag["device"])
    line = int(m.group(1)) if m and int(m.group(1)) in ACTIVE_LINES else None
    supply = line is not None and m.group(2) == "1"
    st_src = cid(SS["M_ST"], line - 1) if line else None
    v_src = cid(SS["M_V"], line - 1) if line else None
    if f == "ST":
        if supply:
            return archive(st_src, writable=True)
        if line:  # возвратный насос работает вместе с подающим
            return derived(st_src, writable=True, above=0.5, on=1, off=0)
        return const(0, writable=True)
    if f == "V":
        if not line:
            return const(0.0)
        return (archive if supply else derived)(v_src, **PERCENT)
    if f == "FRQ":
        return derived(v_src, scale=0.5, clamp_min=0.0, clamp_max=50.0) if line else const(0.0)
    if f == "RPM":
        return derived(v_src, scale=29.5, clamp_min=0.0, clamp_max=2950.0) if line else const(0.0)
    if f == "EST":
        return const(0)
    if f == "R":
        return const(0, writable=True)
    return device_param(f, "M")


def level_switch(tag, f, ctx):
    ld = line_device(tag, "LS")
    src = cid(SS["LS_ST"], 3 * (ld[0] - 1) + ld[1] - 1) if ld and ld[1] <= 3 else None
    if f == "ST":
        return archive(src) if src else const(0)
    if f == "V":
        return derived(src) if src else const(0.0)
    return device_param(f, "LS")


def flow_switch(tag, f, ctx):
    ld = line_device(tag, "FS")
    if f == "ST":
        return archive(cid(SS["FS_ST"], ld[0] - 1)) if ld else const(0)
    return device_param(f, "FS")


def tank_level(tag, f, ctx):
    n = _num(r"^LT(\d)$", tag["device"])
    src = cid(SS["LT_CLEVEL"], n - 1) if n else None
    if f == "CLEVEL":
        return archive(src) if src else const(0.0)
    if f == "V":
        return derived(src, scale=round(100 / TANK_VOLUME, 9), **PERCENT) if src else const(0.0)
    return device_param(f, "LT")


def analog_in(tag, f, ctx):
    if f == "V":
        return const(0.0)
    if f == "ST":
        return sensor_state(tag)


def analog_out(tag, f, ctx):
    if f in ("V", "ST"):
        return const(0.0 if f == "V" else 0, writable=True)


def system(tag, f, ctx):
    if f == "CMD":
        return const(0, writable=True)
    if f.startswith("NODEENABLED"):
        return const(1)
    if tag["type"] == "string":
        return const("")
    if f in cp.SYSTEM_PARAMS:
        return const(cp.SYSTEM_PARAMS[f], writable=True)


def station_params(tag, f, ctx):
    i = _num(r"^PAR_MAIN\[(\d+)\]", f)
    if i is not None:
        return const(cp.PAR_MAIN[i][1], writable=True)
    if f in cp.SYSTEM_PARAMS:
        return const(cp.SYSTEM_PARAMS[f], writable=True)


def selfclean_params(tag, f, ctx):
    i = _num(r"^PAR_SELFCLEAN\[(\d+)\]", f)
    if i is not None:
        return const(cp.PAR_SELFCLEAN[i][1], writable=True)


def recipe(tag, f, ctx):
    i = _num(r"^REC_PAR\[(\d+)\]", f)
    if i is not None:
        return const(cp.RECIPE[i - 1][1] if is_active(tag) else 0.0, writable=True)


def line_stats(tag, f, ctx):
    i = _num(r"^RT_PAR_F\[(\d+)\]", f)
    if i is None or i not in cp.LINE_STATS:
        return None
    if not is_active(tag):
        return const(0.0)
    name, value = cp.LINE_STATS[i]
    return const(ctx.qavs.get(line_of(tag), 0.0) if name == "STP_QAVS" else value)


def line_params(tag, f, ctx):
    i = _num(r"^RT_PAR_F\[(\d+)\]", f)
    if i is None:
        return None
    name, kind = cp.LINE_PARAMS.get(i, ("P_RESERVED", "set"))
    writable = kind in ("cmd", "set")
    if not is_active(tag):
        return const(0.0, writable=writable)
    line = line_of(tag)
    oper = cid(LINE_CTRL[line], 1)
    par = LINE_PAR[line]
    washing = {"above": 1.0, "off": 0.0}   # идёт операция мойки: OPER >= 1
    rv = cp.RECIPE_BY_NAME
    run = {
        "P_CONC_RATE": lambda: const(0.0),
        "P_ZAD_PODOGR": lambda: derived(oper, on=rv["RV_T_S"], **washing),
        "P_ZAD_FLOW": lambda: derived(oper, on=rv["RV_FLOW"], **washing),
        "P_VRAB": lambda: derived(oper, on=rv["RV_V1"], **washing),
        "P_MAX_OPER_TM": lambda: derived(oper, on=300.0, **washing),
        "P_OP_TIME_LEFT": lambda: derived(oper, mode="age", **washing),
        "P_CONC": lambda: archive(cid(par, 6)),
        "P_SUM_OP": lambda: archive(cid(par, 7)),
        "P_ZAD_CONC": lambda: derived(oper, on=cp.PAR_MAIN[1][1], **washing),
        "P_LOADED_RECIPE": lambda: archive(cid(par, 9)),
        "P_PROGRAM": lambda: derived(oper, on=33.0, **washing),  # щёлочь + ополаскивание
        "P_RET_STATE": lambda: derived(oper, on=1.0, **washing),
        "P_FLOW": lambda: derived(cid(SS["FQT_F"], line - 1)),
        "P_OS": lambda: derived(oper, on=1.0, **washing),
        "P_OBJ_EMPTY": lambda: const(0.0),
        "PIDP_Uk": lambda: derived(cid(SS["VC_V"], line - 1), **PERCENT),
        "PIDF_Uk": lambda: derived(cid(SS["M_V"], line - 1), **PERCENT),
    }
    if kind == "run":
        return run[name]()
    if name == "P_CUR_REC":
        return derived(cid(par, 9), writable=True, clamp_min=1.0)
    if kind == "cmd":
        return const(0.0, writable=True)
    return const(cp.RECIPE[i - 14][1] if i >= 16 else 0.0, writable=True)


def line_control(tag, f, ctx):
    line = line_of(tag)
    active = line in ACTIVE_LINES
    if f == "STATE":
        return archive(cid(LINE_CTRL[line], 0)) if active else const(0)
    if f == "OPER":
        return archive(cid(LINE_CTRL[line], 1)) if active else const(0)
    if f == "CMD":
        return const(0, writable=True)
    if f in ("CUR_REC", "LOADED_REC") and active:
        plan = derived(cid(LINE_PAR[line], 9), clamp_min=1.0 if f == "CUR_REC" else None)
        plan.fmt = "Рецепт %d"
        return plan
    if tag["type"] == "string":
        return const("")


HANDLERS = {
    "V": valve, "DO": discrete_out, "DI": discrete_in, "SB": button, "HA": signal, "HL": signal,
    "TE": temperature, "PT": pressure, "QT": conductivity, "VC": control_valve,
    "FQT": flowmeter, "M": motor, "LS": level_switch, "FS": flow_switch, "LT": tank_level,
    "AI": analog_in, "AO": analog_out, "SYSTEM": system,
    "Параметры_станции": station_params, "Параметры_самоочистки_станции": selfclean_params,
    "Редактируемый_рецепт_линии": recipe, "Статистика_линии": line_stats,
    "Параметры_линии": line_params, "Управляющие_каналы_линии": line_control,
}


# -------------------------------------------------------------------------- запись

def typed(value, tag_type):
    if tag_type == "string":
        return value if isinstance(value, str) else ""
    if tag_type == "int":
        return int(round(float(value)))
    return float(value)


def fmt_value(key, value) -> str:
    if key in QUOTED or (key == "initial" and isinstance(value, str)):
        return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float):
        return repr(round(value, 9))
    return str(value)


def apply_plan(tag: dict, plan: Plan) -> dict:
    out = {k: v for k, v in tag.items()
           if k not in ("generator", "replay_format") and not k.startswith("replay_")}
    writable = plan.writable and tag["protocol"] in WRITE_PROTOCOLS and tag["type"] != "string"
    out["access"] = "RW" if writable else "RO"
    out["initial"] = typed(plan.initial, tag["type"])
    if plan.replay:
        out["generator"] = "replay"
        for key in REPLAY_KEYS:
            value = plan.replay.get(key)
            if value is None or (key == "mode" and value == "value"):
                continue
            out[CONFIG_KEY.get(key, f"replay_{key}")] = float(value) if key not in ("source", "mode") else value
    if plan.fmt:
        out["replay_format"] = plan.fmt
    return out


def render(tag: dict, indent: str) -> str:
    keys = [k for k in FIELD_ORDER if k in tag] + [k for k in tag if k not in FIELD_ORDER]
    body = ", ".join(f"{k}: {fmt_value(k, tag[k])}" for k in keys)
    return f"{indent}- {{{body}}}\n"


SIM_HEADER = """\
# ============================================================================
# Симулятор = ТРИ КОНТРОЛЛЕРА с родными форматами. Целый прибор на один контроллер.
#   Phoenix / OPC UA  — приборы как объекты, поля = типизированные узлы
#   WAGO / Modbus TCP — поля = регистры (INT32 → int16, FLOAT → float32, 2 рег.)
#   PAC Savushkin     — приборы первой линии, протокол driver-master
# Типы полей и раскладка по протоколам — tools/apply_tag_types.py (справочник
# docs/BN1_MCA1-типы-тегов.csv). Источник значения и доступ — tools/build_data_model.py:
#   * измерения — серии архива BN1_MCA1 своей физической величины (очищенные от
#     кодов обрыва датчика), производные — из тех же серий (интеграл, возраст, порог);
#   * уставки и параметры — наладочные константы (tools/cip_params.py, ptusa_main);
#   * линии 1–3 работают, 4–6 выключены (в архиве и базе каналов только три линии).
# RW — команды и уставки на OPC UA и PAC; Modbus и показания датчиков — только чтение.
# channelId = node.id из базы каналов.
# ============================================================================
"""

CTL_HEADER = """\
# ============================================================================
# Три контроллера: Phoenix (OPC UA), WAGO (Modbus TCP) и PAC Savushkin (приборы первой
# линии). Целый прибор на один контроллер; tagId = channelId = node.id; device/field/type
# уезжают в Kafka как метаданные, монитор собирает из них прибор.
# Типы полей и раскладка — tools/apply_tag_types.py. writable — tools/build_data_model.py:
# запись только по OPC UA и PAC и только в команды и уставки; Modbus — только чтение.
# ============================================================================
"""


def replace_header(text: str, header: str, first_key: str) -> str:
    start = text.index(f"\n{first_key}:") + 1 if not text.startswith(f"{first_key}:") else 0
    return header + text[start:]


def build(sim_text: str, ctl_text: str, archive_data: dict):
    lines = sim_text.splitlines(keepends=True)
    tags = [yaml.safe_load(l.strip()[2:]) for l in lines if l.lstrip().startswith("- {")]
    ctx = build_context(tags, archive_data)

    plans, out = {}, []
    for line in lines:
        if not line.lstrip().startswith("- {"):
            out.append(line)
            continue
        tag = yaml.safe_load(line.strip()[2:])
        plan = plan_for(tag, ctx)
        new = apply_plan(tag, plan)
        plans[int(tag["address"])] = (tag, plan, new)
        out.append(render(new, line[: len(line) - len(line.lstrip())]))
    sim_new = replace_header("".join(out), SIM_HEADER, "plc")

    def set_writable(match):
        channel = int(re.search(r"channelId: (\d+)", match.group(0)).group(1))
        if channel not in plans:
            raise ValueError(f"канал {channel} есть в шлюзе, но нет в симуляторе")
        rw = plans[channel][2]["access"] == "RW"
        return re.sub(r"writable: (true|false)", f"writable: {'true' if rw else 'false'}", match.group(0))

    ctl_new = re.sub(r"^\s*- \{name: .*channelId: \d+.*writable: (?:true|false).*$",
                     set_writable, ctl_text, flags=re.M)
    ctl_new = replace_header(ctl_new, CTL_HEADER, "opcua")
    gateway_channels = {int(c) for c in re.findall(r"channelId: (\d+)", ctl_new)}
    if gateway_channels != set(plans):
        raise ValueError("наборы каналов шлюза и симулятора расходятся")
    return sim_new, ctl_new, plans


def summary(plans: dict) -> str:
    kinds = Counter()
    for tag, plan, new in plans.values():
        kinds[(tag["protocol"], new["access"], plan.kind)] += 1
    rows = [f"  {p:6} {a} {k:8} {n:5}" for (p, a, k), n in sorted(kinds.items())]
    return "протокол доступ источник каналов\n" + "\n".join(rows)


def main() -> None:
    check = "--check" in sys.argv
    archive_data = pickle.load(gzip.open(ARCHIVE, "rb"))
    sim_old = SIM.read_text(encoding="utf-8")
    ctl_old = CTL.read_text(encoding="utf-8")
    sim_new, ctl_new, plans = build(sim_old, ctl_old, archive_data)
    print(summary(plans))
    if check:
        stale = [p.name for p, old, new in ((SIM, sim_old, sim_new), (CTL, ctl_old, ctl_new)) if old != new]
        if stale:
            sys.exit(f"устарели: {', '.join(stale)} — запусти tools/build_data_model.py")
        print("конфиги актуальны")
        return
    SIM.write_text(sim_new, encoding="utf-8")
    CTL.write_text(ctl_new, encoding="utf-8")
    print(f"записано: {SIM.relative_to(ROOT)}, {CTL.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
