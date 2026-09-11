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

Генерация значений при этом снимается полностью: ни `generator`, ни
`replay_source` у тегов не остаётся, блок `replay` выключается. Теги стоят на
начальном значении, и OPC UA-теги меняются только тем, что записал оператор.

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

SIM_TYPE = {"INT32": "int", "FLOAT": "float", "STRING": "string"}


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
             f'type: {tag["type"]}', f'protocol: {tag["protocol"]}']
    if tag["protocol"] == "modbus":
        parts += [f'modbus_address: {tag["modbus_address"]}',
                  f'modbus_type: {tag["modbus_type"]}']
    parts += [f'device: {quote(tag["device"])}', f'field: {quote(tag["field"])}',
              f'dev_type: {quote(tag["dev_type"])}', f'access: {tag["access"]}']
    if tag["type"] == "string":
        parts.append('initial: ""')
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
#   PAC Demo          — каналов {pac}: демонстрация третьего протокола, значения синтетические
# Типы полей заданы справочником docs/BN1_MCA1-типы-тегов.csv; раскладку делает
# tools/apply_tag_types.py. Писать можно только по OPC UA: Modbus здесь на чтение.
# Воспроизведение архива ВЫКЛЮЧЕНО — теги стоят на начальном значении и меняются
# только записью оператора. channelId = node.id из базы каналов.
"""

CTL_HEADER = """# Три контроллера: Phoenix (OPC UA, каналов {opc}), WAGO (Modbus TCP, каналов {mod})
# и PAC Demo (каналов {pac}). Целый прибор на один контроллер; tagId = channelId = node.id;
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

    station, pac = [], []
    for server in ctl_doc["opcua"]["servers"]:
        for t in server["tags"]:
            (station if t["name"].startswith(PREFIX) else pac).append(t)
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
        # Строка на Modbus нереализуема — такие теги остаются на OPC UA.
        on_modbus = read_only and declared != "STRING"
        plan.append({
            "tag": t, "type": declared,
            "protocol": "modbus" if on_modbus else "opcua",
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

    opc_items = [i for i in plan if i["protocol"] == "opcua"]
    mod_items = [i for i in plan if i["protocol"] == "modbus"]

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
        else:
            out["nodeId"] = f"ns=2;s={cid}"
        return out

    ctl_lines = CTL.read_text(encoding="utf-8").splitlines(keepends=True)

    def ctl_section(line, current):
        return (current or 0) + 1 if line.lstrip().startswith("- id:") else current

    ctl_blocks = [
        [ctl_line(ctl_tag(i)) for i in opc_items],
        [ctl_line(ctl_tag(i)) for i in mod_items],
        [l for l in ctl_lines if l.lstrip().startswith('- {name: "PAC_DEMO')],
    ]
    spliced = splice(ctl_lines, tag_groups(ctl_lines, "- {name:", ctl_section), ctl_blocks)
    CTL.write_text("".join(rewrite_headers(spliced, CTL_HEADER, len(opc_items),
                                           len(mod_items), len(pac))), encoding="utf-8")

    # --- Конфиг симулятора ----------------------------------------------------
    def sim_tag(item):
        cid = str(item["tag"]["channelId"])
        src = sim_by_id[cid]
        out = {"address": cid, "type": SIM_TYPE[item["type"]],
               "protocol": item["protocol"], "device": src["device"],
               "field": src["field"], "dev_type": src["dev_type"],
               "access": "RW" if item["writable"] else "RO"}
        if item["protocol"] == "modbus":
            out.update(modbus_address=item["modbus_register"],
                       modbus_type=item["modbus_type"])
        return out

    sim_lines = SIM.read_text(encoding="utf-8").splitlines(keepends=True)

    def sim_section(line, current):
        return "pac" if "protocol: pac" in line else ("station" if line.lstrip().startswith("- {name:") else current)

    runs = tag_groups(sim_lines, "- {name:", sim_section)
    # Вторая группа — демонстрационные PAC-теги, их не трогаем.
    blocks = [[sim_line(sim_tag(i)) for i in plan]] + [[sim_lines[i] for i in r] for r in runs[1:]]
    sim_lines = splice(sim_lines, runs, blocks)
    # Генерации больше нет — выключаем и сам движок реплея.
    sim_lines = [l.replace("  enabled: true", "  enabled: false")
                 if l.startswith("  enabled: true") else l for l in sim_lines]
    # Демонстрационный PAC-блок жил на реплее, а движок теперь выключен. Чтобы
    # третий протокол не застыл, переводим его теги состояния на меандр 0/1.
    pac_periods = iter((20, 35, 50))
    fixed = []
    for line in sim_lines:
        if "protocol: pac" in line and "generator: replay" in line:
            line = re.sub(r"replay_source: \w+, ", "", line)
            line = line.replace(
                "generator: replay",
                "generator: pulse, generator_params: {period: %d, duty_cycle: 0.5, "
                "on_value: 1, off_value: 0}" % next(pac_periods))
        fixed.append(line)
    sim_lines = fixed
    SIM.write_text("".join(rewrite_headers(sim_lines, SIM_HEADER, len(opc_items),
                                           len(mod_items), len(pac))), encoding="utf-8")

    print(f"станционных тегов: {len(plan)}")
    print(f"  OPC UA: {len(opc_items)} ({len(opc_items)/len(plan)*100:.1f}%), "
          f"из них с записью {sum(1 for i in opc_items if i['writable'])}")
    print(f"  Modbus: {len(mod_items)} ({len(mod_items)/len(plan)*100:.1f}%), только чтение")
    print(f"  регистров занято: {register} (40001..{MODBUS_BASE + register - 1})")
    types = {}
    for i in plan:
        types[i["type"]] = types.get(i["type"], 0) + 1
    print(f"  типы: {types}")
    print(f"демонстрационных PAC-тегов сохранено: {len(pac)}")


if __name__ == "__main__":
    main()
