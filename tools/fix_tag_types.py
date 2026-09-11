#!/usr/bin/env python3
"""
Приведение типов тегов и оживление «мёртвых» каналов в сгенерированных конфигах.

Правит два файла на месте, построчно, чтобы диф оставался читаемым:
  * plc-simulator/config/replay_config.yaml  — конфиг симулятора;
  * SCADA-gateway/src/main/resources/controllers.yaml — конфиг шлюза.

Что делает и почему:

1. **Поле ST — целое, а не флаг.** В модели приборов ptusa `ST` это состояние
   прибора (код), поэтому `type: bool|float` → `int` в симуляторе и
   `dataType: BOOLEAN|FLOAT` → `INT` в шлюзе. Ширина Modbus не меняется:
   ST-теги и так лежат в одном регистре (`int16`), адреса не сдвигаются.

2. **RW-актуаторы больше не стоят на месте.** У них не было ни `generator`,
   ни `replay_source`, поэтому тег вечно отдавал начальное значение. Вешаем
   `generator: replay` и дискретную серию архива. Ручную уставку оператора
   сохраняет латч в `core/plc.py`: реплей ведёт тег только до первой записи.

3. **Каналы бэкафилла подключены к архиву.** У них `generator: replay` стоял,
   а `replay_source` отсутствовал. Движок реплея ищет серию по ключу и, не найдя,
   пропускает тег — отсюда вечный ноль.

4. **Параметры с целой семантикой получают целые значения.** Тип остаётся
   `float`: на контроллере это массив `RT_PAR_F`, где номер программы хранится
   как `3.0`. Меняется только источник — вместо плавной аналоговой серии
   подставляется серия архива с целыми значениями подходящего диапазона.
   Что именно считать целым, берётся из описаний каналов в базе каналов
   (таблицы `channel.node` и `channel.param`, тип параметра 8 = «Описание»).

Запуск из корня репозитория:

    python3 tools/fix_tag_types.py <путь-к-дампу-базы-каналов.sql>

Скрипт печатает статистику по каждому виду правки. Он не идемпотентен по
пулам серий: повторный запуск на уже поправленном конфиге снова перетасует
`replay_source`, поэтому прогонять его следует на исходных конфигах.
"""
import gzip
import pickle
import re
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
SIM = ROOT / "plc-simulator/config/replay_config.yaml"
CTL = ROOT / "SCADA-gateway/src/main/resources/controllers.yaml"
ARCHIVE = ROOT / "plc-simulator/data/archive_replay.pkl.gz"

# Описания каналов, значения которых по смыслу целые. Источник — база каналов.
FLAG01 = {
    "Ручной режим.",
    "Активность рецепта",
    "Подпитывать ли бачок во время циркуляции",
    "Обратная связь(готовность объекта к мойке)",
    "Сигнал 'объект опорожнен'",
}
SMALL = {
    "Программа мойки",
    "Номер выбранного рецепта на линии",
    "Выбор рецепта",
    "Выбор программы",
    "Тип объекта (танк, линия и пр.)",
    "Состояние возвратного насоса",
    "Номер возвратного насоса",
    "Номер управляющего сигнала",
    "Включить клапан №",
    "Выключить клапан №",
    "Управление рецептом (1-сброс, 2-копировать, 3-вставить)",
}
COUNT = {
    "Маска доступных режимов мойки",
    "Количество пропущенных операций",
    "Шаг, на котором сбросили",
    "Количество ошибок",
    "предыдущая операция",
    "количество подпиток на щелочи",
    "количество подпиток на кислоте",
    "количество подпиток на воде",
}

# Дискретные поля приборов в блоке бэкафилла и поле кода ошибки.
BACKFILL_DISCRETE = ("ST", "M", "BLINK", "NAMUR_ST", "OPENED", "CLOSED", "R", "EST")

# Серии для накопленного времени работы механизма (секунды).
ANALOG_TIME = ["2A4D0000", "2A4D0001", "2A4D0002"]

# Минимум переключений за архив (5 суток), чтобы тег не выглядел замороженным.
MIN_TRANSITIONS = 10


def load_series_pools():
    """Разложить серии архива по характеру значений: 0/1, малые целые, счётчики, аналог."""
    series = pickle.load(gzip.open(ARCHIVE, "rb"))["series"]
    discrete, smallint, counter, analog = [], [], [], []
    for cid, s in sorted(series.items()):
        v = np.asarray(s["v"], dtype=float)
        integral = bool(np.all(np.abs(v - np.round(v)) < 1e-6))
        if v.min() < 0:
            # Серии с сентинелами ошибок (-1, -100) не годятся ни под что: именно
            # они когда-то дали отрицательные значения на измерительных каналах.
            continue
        if not integral:
            analog.append(cid)
            continue
        transitions = int((np.diff(v) != 0).sum())
        if len(np.unique(v)) <= 2:
            if transitions >= MIN_TRANSITIONS:
                discrete.append((transitions, cid))
        elif v.max() <= 20:
            smallint.append(cid)
        elif v.max() <= 600:
            counter.append(cid)
    # Самые активные первыми: при раздаче по кругу живые серии достаются большему
    # числу тегов, и на стенде видно, что клапаны действительно переключаются.
    discrete = [cid for _, cid in sorted(discrete, reverse=True)]
    return discrete, smallint, counter, analog


def load_descriptions(dump_path):
    """channelId -> описание канала из дампа базы каналов."""
    def copy_block(table):
        rows, inside = [], False
        with open(dump_path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if line.startswith(f"COPY {table} "):
                    inside = True
                    continue
                if inside and line.startswith("\\."):
                    break
                if inside:
                    rows.append(line.rstrip("\n").split("\t"))
        return rows

    nodes = {int(r[0]): r[1] for r in copy_block("channel.node") if r and r[0].isdigit()}
    texts = {r[1]: r[3] for r in copy_block("channel.param")
             if len(r) > 3 and r[2] == "8" and r[3].strip()}
    return {cid: texts[path] for cid, path in nodes.items() if path in texts}


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    dump_path = Path(sys.argv[1])
    if not dump_path.is_file():
        sys.exit(f"не найден дамп базы каналов: {dump_path}")

    discrete, smallint, counter, analog = load_series_pools()
    print(f"пулы серий: дискретных {len(discrete)}, малых целых {len(smallint)}, "
          f"счётчиков {len(counter)}, аналоговых {len(analog)}")

    descriptions = load_descriptions(dump_path)
    plan = {}
    for channel_id, text in descriptions.items():
        text = text.strip()
        if text in FLAG01:
            plan[channel_id] = ("flag", discrete)
        elif text in SMALL:
            plan[channel_id] = ("small", smallint)
        elif text in COUNT:
            plan[channel_id] = ("count", counter)
    print(f"каналов с целой семантикой: {len(plan)}")

    # Отдельный курсор на каждый вид правки: так серии распределяются по кругу
    # внутри своей группы и раздача не зависит от порядка встречи строк.
    cursors = {}

    def take(name, pool):
        idx = cursors.get(name, 0)
        cursors[name] = idx + 1
        return pool[idx % len(pool)]

    stat = dict(st_type=0, st_revived=0, st_reseries=0, on_time=0, params=0, backfill=0)
    out = []
    for line in SIM.read_text(encoding="utf-8").splitlines(keepends=True):
        if not line.lstrip().startswith("- {"):
            out.append(line)
            continue
        addr = re.search(r'address: "(\d+)"', line)
        channel_id = int(addr.group(1)) if addr else None

        if 'field: "ST"' in line:
            retyped = re.sub(r"\btype: (bool|float)\b", "type: int", line)
            if retyped != line:
                stat["st_type"] += 1
            line = retyped
            source = re.search(r"replay_source: (\w+)", line)
            if "generator:" not in line:
                line = re.sub(r'(address: "\d+", )', rf"\1replay_source: {take('st', discrete)}, ",
                              line, count=1)
                line = re.sub(r"(access: \w+, )", r"\1generator: replay, ", line, count=1)
                stat["st_revived"] += 1
            elif source is None:
                line = re.sub(r'(address: "\d+", )', rf"\1replay_source: {take('st', discrete)}, ",
                              line, count=1)
                stat["st_reseries"] += 1
            elif source.group(1) not in discrete:
                line = line.replace(f"replay_source: {source.group(1)}",
                                    f"replay_source: {take('st', discrete)}")
                stat["st_reseries"] += 1

        elif 'field: "P_ON_TIME"' in line and "generator:" not in line:
            cid = ANALOG_TIME[stat["on_time"] % len(ANALOG_TIME)]
            line = re.sub(r'(address: "\d+", )', rf"\1replay_source: {cid}, ", line, count=1)
            line = re.sub(r"(access: \w+, )", r"\1generator: replay, ", line, count=1)
            stat["on_time"] += 1

        elif "generator: replay" in line and "replay_source:" not in line:
            field = re.search(r'field: "([^"]+)"', line)
            name = field.group(1) if field else ""
            pool = discrete if name in BACKFILL_DISCRETE else smallint if name == "P_ERR" else analog
            line = re.sub(r'(address: "\d+", )', rf"\1replay_source: {take('backfill', pool)}, ", line, count=1)
            stat["backfill"] += 1

        elif channel_id in plan and "replay_source:" in line:
            line = re.sub(r"replay_source: \w+", f"replay_source: {take(*plan[channel_id])}",
                          line, count=1)
            stat["params"] += 1

        out.append(line)
    SIM.write_text("".join(out), encoding="utf-8")

    controllers, retyped_count = [], 0
    for line in CTL.read_text(encoding="utf-8").splitlines(keepends=True):
        if 'fieldName: "ST"' in line:
            retyped = re.sub(r"\bdataType: (BOOLEAN|FLOAT)\b", "dataType: INT", line)
            if retyped != line:
                retyped_count += 1
            line = retyped
        controllers.append(line)
    CTL.write_text("".join(controllers), encoding="utf-8")

    print(f"симулятор: ST в int {stat['st_type']}, оживлено RW-ST {stat['st_revived']}, "
          f"ST на дискретную серию {stat['st_reseries']}, оживлено P_ON_TIME {stat['on_time']}, "
          f"параметров переназначено {stat['params']}, бэкафилл подключён {stat['backfill']}")
    print(f"шлюз: dataType ST в INT {retyped_count}")


if __name__ == "__main__":
    main()
