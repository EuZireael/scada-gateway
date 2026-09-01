#!/usr/bin/env python3
# ============================================================================
# Генератор СОГЛАСОВАННОЙ пары конфигов для нагрузочного теста SCADA.
#
# Ключевой факт архитектуры: шлюз опрашивает не «что придумал симулятор», а свой
# список из controllers.yaml. Значит, чтобы N тегов реально нагрузили шлюз, нужны
# ДВА конфига с одинаковыми идентификаторами:
#   • sim.yaml         — симулятор СЕРВИТ N точек (OPC-узлы / Modbus-регистры)
#   • controllers.yaml — шлюз ОПРАШИВАЕТ те же N точек
#
# Стыковка (проверена по коду):
#   OPC UA : шлюз nodeId "ns=2;s=<id>"      ↔ sim address "<id>" (ua.NodeId(address, ns=2))
#   Modbus : шлюз modbusAddress 40001+2*j   ↔ sim modbus_address 2*j (gateway читает addr-40001)
#            float32 = 2 регистра, поэтому шаг регистра = 2.
#
# Телеметрия шлюза — firehose: КАЖДЫЙ тег шлётся КАЖДЫЙ цикл (не on-change).
#   ожидаемый поток ≈ tags / (poll_ms/1000)  сообщений/с в Kafka.
#
# Пример:
#   python gen_config.py --tags 1000 --poll-ms 1000 --protocol opcua
#   python gen_config.py --tags 20000 --poll-ms 200 --protocol mix --mix-opc 0.7
# ============================================================================
import argparse
import sys
from pathlib import Path

# Базы идентификаторов синтетических тегов. Разнесены, чтобы OPC и Modbus id
# никогда не пересекались (id → channelId → Kafka-key, должен быть уникален).
OPC_BASE = 3_000_000
MB_BASE = 6_000_000

# Группировка полей в приборы (device). Чисто косметика для браузинга OPC-сервера
# (nodeId уникален по address независимо от группировки), но уменьшает число
# object-node на сервере: 1 прибор на FIELDS_PER_DEVICE полей.
FIELDS_PER_DEVICE = 20

MODBUS_MAX_REG = 65535  # holding-регистры 16-битно адресуемы (datastore симулятора)


def opc_gateway_line(idx: int, poll_ms: int, is_bool: bool) -> str:
    """Строка тега OPC UA для controllers.yaml (сторона шлюза)."""
    cid = OPC_BASE + idx
    dev = idx // FIELDS_PER_DEVICE
    fld = idx % FIELDS_PER_DEVICE
    dtype = "BOOLEAN" if is_bool else "FLOAT"
    extra = "" if is_bool else ", minValue: -50.0, maxValue: 50.0"
    return (
        f'        - {{name: "LOAD.opc.{cid}", nodeId: "ns=2;s={cid}", channelId: {cid}, '
        f'deviceName: "LDEV{dev}", fieldName: "F{fld}", deviceType: "LT", '
        f'dataType: {dtype}, pollingRate: {poll_ms}, enabled: true{extra}}}'
    )


def opc_sim_line(idx: int, is_bool: bool) -> str:
    """Строка тега OPC UA для sim.yaml (сторона симулятора)."""
    cid = OPC_BASE + idx
    dev = idx // FIELDS_PER_DEVICE
    fld = idx % FIELDS_PER_DEVICE
    if is_bool:
        gen = "generator: pulse, generator_params: {period: 10, duty_cycle: 0.5, on_value: 1, off_value: 0}"
        return (
            f'    - {{name: "{cid}", address: "{cid}", type: bool, protocol: opcua, '
            f'device: "LDEV{dev}", field: "F{fld}", dev_type: "LT", access: RO, '
            f'{gen}, noise_enabled: false, drift_enabled: false}}'
        )
    gen = "generator: sine, generator_params: {amplitude: 20, period: 30}"
    return (
        f'    - {{name: "{cid}", address: "{cid}", type: float, protocol: opcua, '
        f'device: "LDEV{dev}", field: "F{fld}", dev_type: "LT", access: RO, '
        f'min: -50, max: 50, {gen}, noise_enabled: false, drift_enabled: false}}'
    )


def mb_gateway_line(idx: int, poll_ms: int, is_bool: bool) -> str:
    """Строка тега Modbus для controllers.yaml (сторона шлюза)."""
    cid = MB_BASE + idx
    reg = idx * 2                    # шаг 2 регистра (float32 занимает 2)
    addr = 40001 + reg
    if is_bool:
        return (
            f'        - {{name: "LOAD.mb.{cid}", nodeId: "modbus:{addr}", channelId: {cid}, '
            f'protocol: modbus, modbusAddress: {addr}, modbusType: int16, modbusUnitId: 1, '
            f'dataType: BOOLEAN, pollingRate: {poll_ms}, enabled: true}}'
        )
    return (
        f'        - {{name: "LOAD.mb.{cid}", nodeId: "modbus:{addr}", channelId: {cid}, '
        f'protocol: modbus, modbusAddress: {addr}, modbusType: float32, modbusUnitId: 1, '
        f'dataType: FLOAT, pollingRate: {poll_ms}, enabled: true, minValue: -50.0, maxValue: 50.0}}'
    )


def mb_sim_line(idx: int, is_bool: bool) -> str:
    """Строка тега Modbus для sim.yaml (сторона симулятора)."""
    cid = MB_BASE + idx
    reg = idx * 2
    if is_bool:
        gen = "generator: pulse, generator_params: {period: 10, duty_cycle: 0.5, on_value: 1, off_value: 0}"
        return (
            f'    - {{name: "{cid}", address: "{cid}", type: bool, protocol: modbus, '
            f'modbus_address: {reg}, modbus_type: int16, access: RO, '
            f'{gen}, noise_enabled: false, drift_enabled: false}}'
        )
    gen = "generator: sine, generator_params: {amplitude: 20, period: 30}"
    return (
        f'    - {{name: "{cid}", address: "{cid}", type: float, protocol: modbus, '
        f'modbus_address: {reg}, modbus_type: float32, access: RO, '
        f'min: -50, max: 50, {gen}, noise_enabled: false, drift_enabled: false}}'
    )


def build(n_opc: int, n_mb: int, poll_ms: int, bool_ratio: float):
    """Собрать текст обоих конфигов. Возвращает (gateway_yaml, sim_yaml)."""
    def is_bool(i: int) -> bool:
        # Детерминированное распределение bool-тегов (каждый k-й), без random —
        # чтобы конфиги были воспроизводимы между запусками.
        if bool_ratio <= 0:
            return False
        every = max(1, round(1 / bool_ratio))
        return i % every == 0

    # ----- controllers.yaml (шлюз) -----
    gw = [
        "# АВТОГЕНЕРАЦИЯ loadtest/gen_config.py — НЕ РЕДАКТИРОВАТЬ ВРУЧНУЮ.",
        f"# Нагрузочный профиль: OPC={n_opc}, Modbus={n_mb}, poll={poll_ms}ms.",
        "opcua:",
        "  servers:",
    ]
    if n_opc > 0:
        gw += [
            "    - id: phoenix-load",
            '      name: "Phoenix LOAD"',
            '      endpoint: "opc.tcp://${SIM_HOST:127.0.0.1}:4840"',
            '      security: "NONE"',
            "      enabled: true",
            "      tags:",
        ]
        gw += [opc_gateway_line(i, poll_ms, is_bool(i)) for i in range(n_opc)]
    if n_mb > 0:
        gw += [
            "    - id: wago-load",
            '      name: "WAGO LOAD"',
            '      endpoint: "modbus://${SIM_HOST:127.0.0.1}:5020"',
            '      security: "NONE"',
            "      enabled: true",
            "      tags:",
        ]
        gw += [mb_gateway_line(j, poll_ms, is_bool(j)) for j in range(n_mb)]

    # ----- sim.yaml (симулятор) -----
    sim = [
        "# АВТОГЕНЕРАЦИЯ loadtest/gen_config.py — НЕ РЕДАКТИРОВАТЬ ВРУЧНУЮ.",
        "plc:",
        "  id: BN1-MCA1",
        "  name: LOADTEST",
        "  endpoint: opc.tcp://0.0.0.0:4840",
        "  update_rate: 0.5",
        "  simulation:",
        "    enable_noise: false",
        "    enable_drift: false",
        "  data_blocks:",
        "  - db_number: 1",
        "    name: LOAD",
        "    tags:",
    ]
    sim += [opc_sim_line(i, is_bool(i)) for i in range(n_opc)]
    sim += [mb_sim_line(j, is_bool(j)) for j in range(n_mb)]

    return "\n".join(gw) + "\n", "\n".join(sim) + "\n"


def main():
    """CLI: разбирает аргументы (tags/poll/protocol/…), раскидывает теги по протоколам,
    проверяет потолок Modbus-регистров и пишет пару конфигов + оценку потока телеметрии."""
    ap = argparse.ArgumentParser(description="Генератор пары конфигов для нагрузочного теста SCADA")
    ap.add_argument("--tags", type=int, required=True, help="всего тегов")
    ap.add_argument("--poll-ms", type=int, default=1000, help="частота опроса, мс (пол 100 в коде шлюза)")
    ap.add_argument("--protocol", choices=["opcua", "modbus", "mix"], default="opcua")
    ap.add_argument("--mix-opc", type=float, default=0.5, help="доля OPC при protocol=mix (0..1)")
    ap.add_argument("--bool-ratio", type=float, default=0.3, help="доля BOOLEAN тегов (0..1)")
    ap.add_argument("--out-gateway", default=str(Path(__file__).parent / "generated" / "controllers.yaml"))
    ap.add_argument("--out-sim", default=str(Path(__file__).parent / "generated" / "sim.yaml"))
    args = ap.parse_args()

    if args.protocol == "opcua":
        n_opc, n_mb = args.tags, 0
    elif args.protocol == "modbus":
        n_opc, n_mb = 0, args.tags
    else:
        n_opc = round(args.tags * args.mix_opc)
        n_mb = args.tags - n_opc

    # Защита: Modbus-регистры адресуются 16-битно (шаг 2 на тег).
    if n_mb > 0 and (n_mb - 1) * 2 + 1 > MODBUS_MAX_REG:
        max_mb = MODBUS_MAX_REG // 2
        sys.exit(f"❌ Modbus тегов {n_mb} > потолка {max_mb} (65535 регистров, шаг 2). "
                 f"Уменьши долю Modbus или число тегов.")

    if args.poll_ms < 100:
        print(f"⚠️  poll-ms={args.poll_ms} < 100: шлюз всё равно ограничит цикл снизу 100 мс "
              f"(max(pollingRate,100) в коде). Для более частого — правь пол в OpcUaClientServiceDB.")

    gw_text, sim_text = build(n_opc, n_mb, args.poll_ms, args.bool_ratio)
    Path(args.out_gateway).write_text(gw_text)
    Path(args.out_sim).write_text(sim_text)

    eff_poll = max(args.poll_ms, 100)
    est_rate = args.tags / (eff_poll / 1000.0)
    print(f"✅ Сгенерировано: OPC={n_opc}, Modbus={n_mb}, всего={args.tags}, poll={args.poll_ms}ms")
    print(f"   gateway → {args.out_gateway}")
    print(f"   sim     → {args.out_sim}")
    print(f"   ожидаемый поток телеметрии ≈ {est_rate:,.0f} сообщений/с (firehose: каждый тег/цикл)")
    if n_mb > 0:
        print(f"   ⚠️  Modbus читается ПОСЛЕДОВАТЕЛЬНО (1 round-trip/тег/поток). При {n_mb} тегах "
              f"реальный цикл может НЕ уложиться в {eff_poll}ms — это ожидаемая стена.")


if __name__ == "__main__":
    main()
