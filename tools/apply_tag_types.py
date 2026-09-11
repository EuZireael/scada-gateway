#!/usr/bin/env python3
"""
Раскладка тегов станции по типам и протоколам из справочника `BN1_MCA1-типы-тегов.csv`.

Перестраивает два сгенерированных конфига:
  * plc-simulator/config/replay_config.yaml
  * SCADA-gateway/src/main/resources/controllers.yaml

Три правила, заданные заказчиком интеграции:

1. **Тип берётся из CSV.** Колонка «Предлагаемый тип» — источник истины:
   `INT32`, `FLOAT` или `STRING`. Группа в CSV может быть шаблоном с суффиксом
   `_N` (`V_ST_N` покрывает `V_ST_1`, `V_ST_2`, …), а поле — с индексом-звёздочкой
   (`RT_PAR_F[*]` покрывает `RT_PAR_F[11]`).

2. **Пишем только по OPC UA.** Тег, в который оператор осмысленно пишет
   (состояние прибора, ручной режим, команда, любая уставка), живёт на OPC UA и
   помечен `writable`. Тег, который прибор измеряет или контроллер считает сам,
   писать некуда — он уезжает на Modbus и остаётся только на чтение.

3. **Modbus держит около десяти процентов каналов.** Приоритет у OPC UA:
   там и обратное чтение записи, и целые, и строки. На Modbus остаётся слой
   «сырых» сигналов поля. Строковый тег на Modbus невозможен в принципе
   (регистр шестнадцатибитный, кодировки строк нет ни в симуляторе, ни в шлюзе),
   поэтому строки всегда остаются на OPC UA, даже если писать в них нечего.

Данные идут по ВСЕМ трём протоколам: контроллер, который ничего не передаёт, —
это не контроллер. Каждому каналу назначается серия пятисуточного архива по
смыслу поля: дискретная для состояний и режимов, аналоговая для измеряемых
величин, целочисленная для кодов и счётчиков. Серии с отрицательными значениями
не берутся вообще — именно они когда-то дали минус на расходе и уровне.

Одна серия достаётся многим каналам, поэтому каждому проставляется свой
`replay_offset` — сдвиг точки воспроизведения внутри архива. Без него группа
каналов меняется синхронно и сразу читается как подделка.

Архив числовой, а часть каналов по базе строковые, поэтому им дополнительно
проставляется `replay_format`: число из архива превращается в метку вида
"REC-07". Запись оператора по-прежнему перебивает реплей через латч в
`core/plc.py`: тег ведётся архивом до первой команды, дальше держит ручное.

Карта Modbus-регистров перекладывается с нуля, подряд от 40001: `FLOAT` занимает
два регистра (`float32`), `INT32` — один (`int16`). Так карта остаётся плотной и
без коллизий после того, как из неё ушли полторы тысячи каналов.

Запуск из корня репозитория:

    python3 tools/apply_tag_types.py docs/BN1_MCA1-типы-тегов.csv
"""
import csv
import io
import re
import sys
from pathlib import Path

import gzip
import pickle

import numpy as np
import yaml

ROOT = Path(__file__).resolve().parent.parent
SIM = ROOT / "plc-simulator/config/replay_config.yaml"
CTL = ROOT / "SCADA-gateway/src/main/resources/controllers.yaml"
PREFIX = "Барановичи-1.BN1_MCA1."
MODBUS_BASE = 40001

# Поля, которые прибор измеряет или контроллер считает сам. Писать в них нечего,
# поэтому они и уезжают на Modbus.
READ_ONLY_FIELDS = {
    "V", "ABS_V", "T", "F", "FRQ", "RPM", "EST", "P_ERR", "P_ON_TIME",
    "DAY_T1", "DAY_T2", "OPENED", "CLOSED", "NAMUR_ST", "BLINK", "R",
    "STATE", "PRG_LIST", "REC_LIST", "LOADED_REC", "CMD_ANSWER", "UP_TIME",
    "NODEENABLED",
}
# Статистика линии целиком считается контроллером.
READ_ONLY_GROUPS = ("Статистика_линии",)

# Приборы первой линии ведёт PAC-контроллер Savushkin: его протокол объектный
# (devices['1V1']={type='V',fields={'ST','M'}}), а это ровно приборные каналы.
# Строки PAC не умеет — там только числа, — но их среди приборных полей и нет.
PAC_DEVICE_PREFIX = "LINE1"
PARAMETER_GROUPS = ("Параметры", "Редактируемый", "Статистика", "Управляющие")


def on_pac(group, device):
    """Прибор первой линии, а не параметрическая группа."""
    if group.startswith(PARAMETER_GROUPS) or group == "SYSTEM":
        return False
    return (device or "").startswith(PAC_DEVICE_PREFIX)

SIM_TYPE = {"INT32": "int", "FLOAT": "float", "STRING": "string"}

ARCHIVE = ROOT / "plc-simulator/data/archive_replay.pkl.gz"

# Целые поля, которые по сути двоичные: состояние, режим, концевик, мигалка.
BINARY_FIELDS = {"ST", "M", "R", "EST", "NAMUR_ST", "BLINK", "OPENED", "CLOSED",
                 "NODEENABLED"}

# Архив числовой, поэтому строковым каналам подставляем метку по числу из архива.
STRING_FORMATS = {
    "CUR_REC": "REC-%02d", "LOADED_REC": "REC-%02d", "REC_LIST": "REC-01..REC-%02d",
    "CUR_PRG": "PRG-%02d", "PRG_LIST": "PRG-01..PRG-%02d",
    "UP_TIME": "%d ч", "CMD_ANSWER": "OK-%d",
}

# Минимум переключений за архив, иначе дискретный канал выглядит замороженным.
MIN_TRANSITIONS = 10


def series_pools():
    """Серии архива по характеру значений. Отрицательные не берём вообще:
    именно они когда-то дали минус на расходе и уровне, где его быть не может."""
    data = pickle.load(gzip.open(ARCHIVE, "rb"))
    series, duration = data["series"], data["duration"]
    discrete, small, counter, analog = [], [], [], []
    for cid, s in sorted(series.items()):
        v = np.asarray(s["v"], dtype=float)
        if v.min() < 0:
            continue
        if not np.all(np.abs(v - np.round(v)) < 1e-6):
            analog.append(cid)
            continue
        if len(np.unique(v)) <= 2:
            if int((np.diff(v) != 0).sum()) >= MIN_TRANSITIONS:
                discrete.append((int((np.diff(v) != 0).sum()), cid))
        elif v.max() <= 20:
            small.append(cid)
        elif v.max() <= 600:
            counter.append(cid)
    discrete = [cid for _, cid in sorted(discrete, reverse=True)]
    return {"discrete": discrete, "small": small, "counter": counter,
            "analog": analog}, duration


def load_spec(csv_path):
    """(группа, поле) -> тип из CSV."""
    raw = csv_path.read_text(encoding="utf-8-sig")
    rows = csv.DictReader(io.StringIO(raw), delimiter=";")
    return {(r["Группа (подтип драйвера)"], r["Поле"]): r["Предлагаемый тип"].strip()
            for r in rows}


def resolve_type(spec, group, field):
    """Тип тега с учётом шаблонов `_N` в группе и `[*]` в поле."""
    for g in (group, re.sub(r"_\d+$", "_N", group)):
        for f in (field, re.sub(r"\[\d+\]", "[*]", field)):
            if (g, f) in spec:
                return spec[(g, f)]
    return None


def is_read_only(group, field):
    return field in READ_ONLY_FIELDS or group.startswith(READ_ONLY_GROUPS)


def quote(value):
    return '"' + str(value).replace('"', '\\"') + '"'


def sim_line(tag):
    """Строка тега в конфиге симулятора, компактным потоковым отображением."""
    parts = [f'name: {quote(tag["address"])}', f'address: {quote(tag["address"])}',
             f'replay_source: {tag["replay_source"]}',
             f'type: {tag["type"]}', f'protocol: {tag["protocol"]}']
    if tag["protocol"] == "modbus":
        parts += [f'modbus_address: {tag["modbus_address"]}',
                  f'modbus_type: {tag["modbus_type"]}']
    parts += [f'device: {quote(tag["device"])}', f'field: {quote(tag["field"])}',
              f'dev_type: {quote(tag["dev_type"])}', f'access: {tag["access"]}',
              "generator: replay", f'replay_offset: {tag["replay_offset"]}']
    if tag.get("replay_format"):
        parts.append(f'replay_format: {quote(tag["replay_format"])}')
    parts += ["noise_enabled: false", "drift_enabled: false"]
    return "    - {" + ", ".join(parts) + "}\n"


def ctl_line(tag):
    """Строка тега в конфиге шлюза."""
    parts = [f'name: {quote(tag["name"])}', f'nodeId: {quote(tag["nodeId"])}',
             f'channelId: {tag["channelId"]}', f'deviceName: {quote(tag["deviceName"])}',
             f'fieldName: {quote(tag["fieldName"])}', f'deviceType: {quote(tag["deviceType"])}']
    if tag.get("protocol") == "modbus":
        parts += ["protocol: modbus", f'modbusAddress: {tag["modbusAddress"]}',
                  f'modbusType: {tag["modbusType"]}', "modbusUnitId: 1"]
    elif tag.get("protocol") == "pac":
        parts.append("protocol: pac")
    parts += [f'dataType: {tag["dataType"]}', "pollingRate: 2000", "enabled: true"]
    if tag.get("writable"):
        parts.append("writable: true")
    return "        - {" + ", ".join(parts) + "}\n"


def tag_groups(lines, marker, section_of):
    """Индексы строк-тегов, сгруппированные по секции файла.

    Группировать по непрерывности нельзя: между строками тегов встречаются
    комментарии, и один список распадался бы на несколько кусков.
    """
    groups = {}
    section = None
    for i, line in enumerate(lines):
        section = section_of(line, section)
        if line.lstrip().startswith(marker):
            groups.setdefault(section, []).append(i)
    return [groups[k] for k in sorted(groups, key=lambda k: groups[k][0])]


def splice(lines, runs, blocks):
    """Заменить каждый участок строк-тегов своим блоком, остальное сохранить."""
    assert len(runs) == len(blocks), f"участков {len(runs)}, блоков {len(blocks)}"
    out, cut = [], {}
    for run, block in zip(runs, blocks):
        cut[run[0]] = block
        for i in run[1:]:
            cut[i] = None
    for i, line in enumerate(lines):
        if i in cut:
            if cut[i] is not None:
                out.extend(cut[i])
        else:
            out.append(line)
    return out


SIM_HEADER = """# Симулятор = ТРИ КОНТРОЛЛЕРА с родными форматами. Целый прибор на один контроллер.
#   Phoenix / OPC UA  — каналов {opc}: приборы как объекты, поля = типизированные узлы
#   WAGO / Modbus TCP — каналов {mod}: поля = регистры (INT32 → int16, FLOAT → float32, 2 рег.)
#   PAC Savushkin     — каналов {pac}: приборы первой линии, протокол driver-master
# Типы полей заданы справочником docs/BN1_MCA1-типы-тегов.csv; раскладку делает
# tools/apply_tag_types.py. Писать можно только по OPC UA: Modbus здесь на чтение.
# Значения и тайминг — из пятисуточного архива BN1_MCA1, у каждого канала свой
# сдвиг внутри записи. Запись оператора перебивает архив (латч в core/plc.py).
# channelId = node.id из базы каналов.
"""

CTL_HEADER = """# Три контроллера: Phoenix (OPC UA, каналов {opc}), WAGO (Modbus TCP, каналов {mod})
# и PAC Savushkin (каналов {pac}, приборы первой линии). Целый прибор на один контроллер; tagId = channelId = node.id;
# device/field/type уезжают в Kafka как метаданные, монитор собирает из них прибор.
# Типы полей заданы справочником docs/BN1_MCA1-типы-тегов.csv, раскладку делает
# tools/apply_tag_types.py. Запись разрешена только по OPC UA.
"""


def rewrite_headers(lines, header, opc, mod, pac):
    """Заменить первый блок комментариев между линейками на актуальный."""
    body = header.format(opc=opc, mod=mod, pac=pac).splitlines(keepends=True)
    rule = "# " + "=" * 76 + "\n"
    out, seen, skipping = [], False, False
    for line in lines:
        if not seen and line.startswith("# ==="):
            if not skipping:
                skipping = True
                out.append(rule)
                out.extend(body)
                continue
            skipping = False
            seen = True
            out.append(rule)
            continue
        if skipping:
            continue
        out.append(line)
    return out


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    spec = load_spec(Path(sys.argv[1]))

    ctl_doc = yaml.safe_load(CTL.read_text(encoding="utf-8"))
    sim_doc = yaml.safe_load(SIM.read_text(encoding="utf-8"))
    sim_by_id = {str(t["address"]): t for t in sim_doc["plc"]["data_blocks"][0]["tags"]}

    station = [t for server in ctl_doc["opcua"]["servers"] for t in server["tags"]
               if t["name"].startswith(PREFIX)]
    # Порядок вывода не должен зависеть от того, как теги лежали во входном файле,
    # иначе повторный прогон переставляет строки и перекладывает карту регистров.
    station.sort(key=lambda t: t["channelId"])

    # --- Решение по каждому тегу: тип, протокол, право записи -----------------
    plan = []
    for t in station:
        group = t["name"][len(PREFIX):].split(".")[0]
        field = t["fieldName"]
        declared = resolve_type(spec, group, field)
        if declared is None:
            sys.exit(f"в CSV нет типа для {group}.{field}")
        read_only = is_read_only(group, field)
        if on_pac(group, t["deviceName"]):
            # Прибор целиком принадлежит одному контроллеру, включая его
            # измеряемые каналы: делить прибор между протоколами бессмысленно.
            protocol = "pac"
        elif read_only and declared != "STRING":
            # Строка на Modbus нереализуема — такие теги остаются на OPC UA.
            protocol = "modbus"
        else:
            protocol = "opcua"
        plan.append({
            "tag": t, "type": declared, "protocol": protocol,
            "writable": not read_only,
        })

    # --- Карта Modbus-регистров заново, подряд -------------------------------
    register = 0
    for item in plan:
        if item["protocol"] != "modbus":
            continue
        item["modbus_register"] = register
        item["modbus_type"] = "float32" if item["type"] == "FLOAT" else "int16"
        register += 2 if item["type"] == "FLOAT" else 1

    # --- Источник данных: каждому каналу серия архива по смыслу поля ---------
    pools, duration = series_pools()
    cursors = {}

    def take(name, pool):
        idx = cursors.get(name, 0)
        cursors[name] = idx + 1
        return pool[idx % len(pool)]

    for item in plan:
        field, declared = item["tag"]["fieldName"], item["type"]
        if declared == "STRING":
            pool = "small"
        elif declared == "FLOAT":
            pool = "analog"
        elif field in BINARY_FIELDS:
            pool = "discrete"
        else:
            pool = "counter" if pools["counter"] else "small"
        item["pool"] = pool
        item["replay_source"] = take(pool, pools[pool])
        if declared == "STRING":
            item["replay_format"] = STRING_FORMATS.get(field, "%d")
    # Каналы, сидящие на одной серии, разводим по времени: иначе вся группа
    # меняется синхронно и на мнемосхеме это читается как подделка.
    groups = {}
    for item in plan:
        groups.setdefault(item["replay_source"], []).append(item)
    for members in groups.values():
        step = duration / len(members)
        for n, item in enumerate(members):
            item["replay_offset"] = round(n * step, 1)

    opc_items = [i for i in plan if i["protocol"] == "opcua"]
    mod_items = [i for i in plan if i["protocol"] == "modbus"]
    pac_items = [i for i in plan if i["protocol"] == "pac"]

    # --- Конфиг шлюза ---------------------------------------------------------
    def ctl_tag(item):
        t, cid = item["tag"], item["tag"]["channelId"]
        out = {"name": t["name"], "channelId": cid, "deviceName": t["deviceName"],
               "fieldName": t["fieldName"], "deviceType": t["deviceType"],
               "dataType": item["type"], "writable": item["writable"]}
        if item["protocol"] == "modbus":
            address = MODBUS_BASE + item["modbus_register"]
            out.update(nodeId=f"modbus:{address}", protocol="modbus",
                       modbusAddress=address, modbusType=item["modbus_type"])
        elif item["protocol"] == "pac":
            out.update(nodeId=f"pac:{cid}", protocol="pac")
        else:
            out["nodeId"] = f"ns=2;s={cid}"
        return out

    ctl_lines = CTL.read_text(encoding="utf-8").splitlines(keepends=True)

    def ctl_section(line, current):
        return (current or 0) + 1 if line.lstrip().startswith("- id:") else current

    ctl_blocks = [
        [ctl_line(ctl_tag(i)) for i in opc_items],
        [ctl_line(ctl_tag(i)) for i in mod_items],
        [ctl_line(ctl_tag(i)) for i in pac_items],
    ]
    spliced = splice(ctl_lines, tag_groups(ctl_lines, "- {name:", ctl_section), ctl_blocks)
    # Контроллер больше не демонстрационный: он везёт реальные приборы линии.
    # Идентификатор оставляем прежним, чтобы не плодить строку в таблице контроллеров.
    spliced = [l.replace('name: "PAC Demo"', 'name: "PAC Savushkin"') for l in spliced]
    CTL.write_text("".join(rewrite_headers(spliced, CTL_HEADER, len(opc_items),
                                           len(mod_items), len(pac_items))), encoding="utf-8")

    # --- Конфиг симулятора ----------------------------------------------------
    def sim_tag(item):
        cid = str(item["tag"]["channelId"])
        src = sim_by_id.get(cid) or {
            "device": item["tag"]["deviceName"], "field": item["tag"]["fieldName"],
            "dev_type": item["tag"]["deviceType"]}
        out = {"address": cid, "type": SIM_TYPE[item["type"]],
               "protocol": item["protocol"], "device": src["device"],
               "field": src["field"], "dev_type": src["dev_type"],
               "access": "RW" if item["writable"] else "RO",
               "replay_source": item["replay_source"],
               "replay_offset": item["replay_offset"],
               "replay_format": item.get("replay_format")}
        if item["protocol"] == "modbus":
            out.update(modbus_address=item["modbus_register"],
                       modbus_type=item["modbus_type"])
        return out

    sim_lines = SIM.read_text(encoding="utf-8").splitlines(keepends=True)

    # Демонстрационные PAC-теги убраны: PAC везёт реальные приборы первой линии,
    # поэтому у симулятора остаётся один общий список каналов станции.
    runs = tag_groups(sim_lines, "- {name:", lambda line, current: "station")
    blocks = [[sim_line(sim_tag(i)) for i in plan]]
    sim_lines = splice(sim_lines, runs, blocks)
    # Данные идут по всем трём протоколам, значит движок реплея включён.
    sim_lines = [l.replace("  enabled: false", "  enabled: true")
                 if l.startswith("  enabled: false") else l for l in sim_lines]
    SIM.write_text("".join(rewrite_headers(sim_lines, SIM_HEADER, len(opc_items),
                                           len(mod_items), len(pac_items))), encoding="utf-8")

    print(f"станционных тегов: {len(plan)}")
    print(f"  OPC UA: {len(opc_items)} ({len(opc_items)/len(plan)*100:.1f}%), "
          f"из них с записью {sum(1 for i in opc_items if i['writable'])}")
    print(f"  Modbus: {len(mod_items)} ({len(mod_items)/len(plan)*100:.1f}%), только чтение")
    print(f"  регистров занято: {register} (40001..{MODBUS_BASE + register - 1})")
    types = {}
    for i in plan:
        types[i["type"]] = types.get(i["type"], 0) + 1
    print(f"  типы: {types}")
    print(f"  PAC:    {len(pac_items)} ({len(pac_items)/len(plan)*100:.1f}%), "
          f"приборы линии {PAC_DEVICE_PREFIX[4:]}")


if __name__ == "__main__":
    main()
