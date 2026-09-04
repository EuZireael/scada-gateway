"""
Общая настройка pytest: код Python-части лежит в двух корнях —
plc-simulator/ (пакеты core.*, модуль sim_records) и loadtest/ (gen_config).
Добавляем оба в sys.path, чтобы тесты импортировали их напрямую
(`from core.tag import Tag`, `import sim_records`, `import gen_config`).
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

for sub in ("plc-simulator", "loadtest"):
    p = str(ROOT / sub)
    if p not in sys.path:
        sys.path.insert(0, p)
