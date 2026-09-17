"""
Общая настройка pytest: код Python-части лежит в трёх корнях —
plc-simulator/ (пакеты core.*, модуль sim_records), loadtest/ (gen_config) и tools/
(модель данных станции). Добавляем их в sys.path, чтобы тесты импортировали модули
напрямую (`from core.tag import Tag`, `import gen_config`, `import build_data_model`).
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

for sub in ("plc-simulator", "loadtest", "tools"):
    p = str(ROOT / sub)
    if p not in sys.path:
        sys.path.insert(0, p)
