#!/usr/bin/env python3
"""
Возврат генерации: включает воспроизведение архива на всех 2517 тегах трёх
протоколов (OPC UA/Modbus/PAC). Правит `plc-simulator/config/replay_config.yaml`
на месте, построчно — до этого (PR #74) генератора не было вообще, каждый
канал стоял на нуле своего типа.

Пулы серий переиспользуются из `tools/fix_tag_types.py` (load_series_pools):
архивная серия, где ХОТЬ ОДНА точка отрицательна, ЦЕЛИКОМ исключается — этим
закрыт баг "отрицательные вернулись" (см. память channel-db-binding /
david-config-bugs). Поэтому пулы маленькие — в архиве всего 170 серий на
5 суток, из них после фильтра остаются 16 аналоговых / 102 дискретных /
2 малых целых / 3 счётчика — и раздача идёт по кругу: одна серия на много
каналов. Это свойство источника данных, не дефект скрипта.

Раскладка по типу тега (`type:` в конфиге, источник — docs/BN1_MCA1-типы-тегов.csv):
  float                          -> аналоговая серия (плавная, дробная)
  int,  поле в DISCRETE_FIELDS   -> дискретная серия (0/1, активная)
  int,  поле в COUNTER_FIELDS    -> счётчик (более широкий целочисленный диапазон)
  int,  остальное                -> малое целое (0..20)
  string                         -> без генератора (в архиве нет строковых
                                     серий; канал остаётся пустой строкой до
                                     записи оператора — как и сейчас)

Затем ставит `replay.enabled: true` (скорость 1.0 — реальное время, решение
пользователя 14.09.2026 — оставить как было).

Идемпотентность: скрипт ожидает конфиг БЕЗ generator/replay_source (как сейчас,
после PR #74) и падает с ошибкой, если находит уже размеченную строку.

Запуск из корня репозитория:

    python3 tools/enable_replay.py --dry-run   # только статистика, файл не трогает
    python3 tools/enable_replay.py             # пишет replay_config.yaml
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fix_tag_types import load_series_pools  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SIM = ROOT / "plc-simulator/config/replay_config.yaml"

# Поля с бинарной/малозначной семантикой (флаг ручного режима, состояние
# прибора как код — по прецеденту PR #72, где ST тоже ушёл в дискретный пул).
DISCRETE_FIELDS = {"ST", "M", "BLINK", "NAMUR_ST", "OPENED", "CLOSED", "R", "EST", "NODEENABLED"}
# Поля с более широким целочисленным диапазоном (см. docs/BN1_MCA1-типы-тегов.csv:
# "save_device пишет %d, STATE −41…1, OPER 0…555" + счётчик времени наработки).
COUNTER_FIELDS = {"STATE", "OPER", "P_ON_TIME"}

OLD_HEADER = (
    "# Генерации нет: каждый канал стоит на нуле своего типа, значение задаёт запись\n"
    "# монитора и она же возвращается в телеметрию. Писать можно в любой канал.\n"
)
NEW_HEADER = (
    "# Генерация включена (tools/enable_replay.py): каждый канал ведёт архивная серия,\n"
    "# пока оператор не запишет своё значение — тогда тег защёлкивается на записи (латч\n"
    "# в core/plc.py). Писать можно в любой канал, включая Modbus.\n"
)


def base_field(name: str) -> str:
    return name.split("[")[0]


def main() -> None:
    dry_run = "--dry-run" in sys.argv
    discrete, smallint, counter, analog = load_series_pools()
    print(f"пулы серий: дискретных {len(discrete)}, малых целых {len(smallint)}, "
          f"счётчиков {len(counter)}, аналоговых {len(analog)}")

    cursors: dict[str, int] = {}

    def take(name: str, pool: list) -> str:
        idx = cursors.get(name, 0)
        cursors[name] = idx + 1
        return pool[idx % len(pool)]

    stat = {"float": 0, "discrete": 0, "smallint": 0, "counter": 0, "string": 0}
    out = []
    for line in SIM.read_text(encoding="utf-8").splitlines(keepends=True):
        if not line.lstrip().startswith("- {"):
            out.append(line)
            continue
        if "generator:" in line:
            sys.exit(f"строка уже размечена, похоже скрипт уже прогнан: {line.strip()}")

        type_m = re.search(r"\btype: (float|int|string)\b", line)
        if not type_m:
            out.append(line)
            continue
        tag_type = type_m.group(1)

        if tag_type == "string":
            stat["string"] += 1
            out.append(line)
            continue

        if tag_type == "float":
            cid = take("analog", analog)
            stat["float"] += 1
        else:
            field_m = re.search(r'field: "([^"]+)"', line)
            field = base_field(field_m.group(1)) if field_m else ""
            if field in DISCRETE_FIELDS:
                cid = take("discrete", discrete)
                stat["discrete"] += 1
            elif field in COUNTER_FIELDS:
                cid = take("counter", counter)
                stat["counter"] += 1
            else:
                cid = take("smallint", smallint)
                stat["smallint"] += 1

        line = re.sub(r'(address: "\d+", )', rf"\1replay_source: {cid}, ", line, count=1)
        line = re.sub(r"(access: \w+, )", r"\1generator: replay, ", line, count=1)
        out.append(line)

    text = "".join(out)
    if OLD_HEADER not in text:
        sys.exit("не нашёл ожидаемый блок комментария в шапке — конфиг изменился, проверь вручную")
    text = text.replace(OLD_HEADER, NEW_HEADER)
    text = text.replace("replay:\n  enabled: false", "replay:\n  enabled: true")

    print(f"размечено: float->analog {stat['float']}, int->discrete {stat['discrete']}, "
          f"int->counter {stat['counter']}, int->smallint {stat['smallint']}, "
          f"string без генератора {stat['string']}")

    if dry_run:
        print("--dry-run: файл не изменён")
        return
    SIM.write_text(text, encoding="utf-8")
    print(f"записано: {SIM}")


if __name__ == "__main__":
    main()
