#!/usr/bin/env python3
# ============================================================================
# Разметка writability в конфигах SCADA под РЕАЛЬНУЮ семантику точек ПЛК.
#
# Реальный ПЛК: показание датчика (давление, расход, температура) изменить нельзя —
# только команду актуатора (клапан открыть/закрыть, мотор пуск/стоп, уставку задать).
# До этой правки writability была только у 2 клапанов; шлюз для Modbus её не проверял
# вообще (регистр датчика молча перезаписывался).
#
# ПРАВИЛО АКТУАТОРА (что делаем управляемым):
#   • поле ST на устройствах V (клапан), M (мотор), DO (дискретный выход) — bool-команда;
#   • поле P_ON_TIME на моторе M — float-уставка (пример «задать значение»).
# Всё остальное (измерения M/V/F/P_*, датчики DI/LS/TE/FQT/AI, ВЕСЬ Modbus) — только чтение.
# Правило централизовано в is_writable() — меняется одной функцией.
#
# Применяется СОГЛАСОВАННО к двум файлам (одни и те же каналы):
#   • controllers.yaml (шлюз)     → добавляет `writable: true` актуаторам;
#   • replay_config.yaml (симул.) → ставит `access: RW` и СНИМАЕТ `generator: replay`
#     + `replay_source` (иначе архив каждый цикл затирал бы значение оператора; для RW
#     симулятор читает узел обратно и значение «залипает» — как реальный актуатор).
#
# Оба файла бэкапятся рядом (.bak.<время>). Откат: восстановить из бэкапа.
# ============================================================================
import re
import sys
import time
from pathlib import Path

GW = Path("/home/zireael/Zireael/Projects/repository/scada-gateway")
CONTROLLERS = GW / "SCADA-gateway/src/main/resources/controllers.yaml"
REPLAY = GW / "plc-simulator/config/replay_config.yaml"

ACTUATOR_DEVICES = {"V", "M", "DO"}


def is_writable(dev_type: str, field: str) -> bool:
    """Единственное место правила «что управляемо». dev_type/field — из строки тега."""
    if field == "ST" and dev_type in ACTUATOR_DEVICES:
        return True
    if field == "P_ON_TIME" and dev_type == "M":
        return True
    return False


def backup(path: Path) -> Path:
    dst = path.with_suffix(path.suffix + f".bak.{time.strftime('%Y%m%d_%H%M%S')}")
    dst.write_text(path.read_text())
    return dst


def patch_controllers(path: Path) -> int:
    """Добавить `writable: true` актуаторам в controllers.yaml (сторона шлюза)."""
    changed = 0
    out = []
    for line in path.read_text().splitlines():
        m_dev = re.search(r'deviceType:\s*"([^"]*)"', line)
        m_fld = re.search(r'fieldName:\s*"([^"]*)"', line)
        if m_dev and m_fld and is_writable(m_dev.group(1), m_fld.group(1)):
            if "writable:" not in line:
                # вставляем перед закрывающей } флоу-мапа тега
                line = re.sub(r"\}\s*$", ", writable: true}", line, count=1)
                changed += 1
        out.append(line)
    path.write_text("\n".join(out) + "\n")
    return changed


def patch_replay(path: Path) -> int:
    """Сделать актуаторы RW + снять replay в replay_config.yaml (сторона симулятора)."""
    changed = 0
    out = []
    for line in path.read_text().splitlines():
        m_dev = re.search(r'dev_type:\s*"([^"]*)"', line)
        m_fld = re.search(r'field:\s*"([^"]*)"', line)
        if m_dev and m_fld and is_writable(m_dev.group(1), m_fld.group(1)):
            new = line
            new = re.sub(r'access:\s*RO', 'access: RW', new)
            # если поля access нет вовсе — добавить (у существующих оно всегда есть)
            if "access:" not in new:
                new = re.sub(r"\}\s*$", ", access: RW}", new)
            # снять генератор архива и источник — иначе затрёт значение оператора
            new = re.sub(r'generator:\s*replay,\s*', '', new)
            new = re.sub(r'replay_source:\s*\w+,\s*', '', new)
            if new != line:
                changed += 1
            out.append(new)
        else:
            out.append(line)
    path.write_text("\n".join(out) + "\n")
    return changed


def main():
    for p in (CONTROLLERS, REPLAY):
        if not p.exists():
            sys.exit(f"нет файла {p}")

    b1 = backup(CONTROLLERS)
    b2 = backup(REPLAY)
    print(f"бэкапы: {b1.name}, {b2.name}")

    c = patch_controllers(CONTROLLERS)
    r = patch_replay(REPLAY)
    print(f"controllers.yaml: помечено writable={c}")
    print(f"replay_config.yaml: переведено в RW (сняты replay)={r}")
    if c != r:
        print(f"⚠️  РАСХОЖДЕНИЕ: {c} vs {r} — правило дало разное число точек в двух файлах!")
    else:
        print(f"✅ согласованно: {c} актуаторов управляемы в обоих конфигах")


if __name__ == "__main__":
    main()
