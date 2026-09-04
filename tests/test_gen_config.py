"""
Тесты генератора пары конфигов для нагрузочного стенда (loadtest/gen_config.py).

Смысл gen_config — выдать ДВА согласованных конфига (шлюз опрашивает ровно то, что
сервит симулятор). Здесь проверяем именно эту согласованность как инвариант:
  * Modbus: шлюз modbusAddress = 40001 + 2*idx, симулятор modbus_address = 2*idx
    → gateway.modbusAddress - 40001 == sim.modbus_address (шлюз читает addr-40001),
    шаг регистра = 2 (float32 занимает 2 регистра);
  * OPC UA: шлюз nodeId "ns=2;s=<cid>" ↔ симулятор address "<cid>";
  * базы идентификаторов OPC и Modbus не пересекаются (id уникален как Kafka-key).
"""
import yaml

import gen_config
from gen_config import OPC_BASE, MB_BASE, build

MODBUS_BASE_ADDR = 40001


def gw_tags(gw_text):
    """Все теги из всех серверов сгенерированного controllers.yaml."""
    cfg = yaml.safe_load(gw_text)
    tags = []
    for server in cfg["opcua"]["servers"]:
        tags.extend(server.get("tags", []))
    return tags


def sim_tags(sim_text):
    cfg = yaml.safe_load(sim_text)
    return cfg["plc"]["data_blocks"][0]["tags"]


def test_modbus_address_alignment_gateway_vs_sim():
    gw_text, sim_text = build(n_opc=0, n_mb=3, poll_ms=1000, bool_ratio=0.0)
    gw = [t for t in gw_tags(gw_text) if "modbusAddress" in t]
    sim = {int(t["address"]): t["modbus_address"] for t in sim_tags(sim_text)}

    assert len(gw) == 3
    for t in gw:
        sim_addr = sim[t["channelId"]]
        # Шлюз вычитает 40001 из modbusAddress, симулятор сервит по этому адресу.
        assert t["modbusAddress"] - MODBUS_BASE_ADDR == sim_addr


def test_modbus_float32_register_step_is_two():
    gw_text, _ = build(n_opc=0, n_mb=4, poll_ms=1000, bool_ratio=0.0)
    addrs = sorted(t["modbusAddress"] for t in gw_tags(gw_text))
    assert addrs == [40001, 40003, 40005, 40007]
    assert all(b - a == 2 for a, b in zip(addrs, addrs[1:]))


def test_opcua_nodeid_matches_sim_address():
    gw_text, sim_text = build(n_opc=3, n_mb=0, poll_ms=1000, bool_ratio=0.0)
    gw = gw_tags(gw_text)
    sim_addr = {t["address"] for t in sim_tags(sim_text)}

    assert len(gw) == 3
    for t in gw:
        cid = t["channelId"]
        assert t["nodeId"] == f"ns=2;s={cid}"
        # Симулятор сервит OPC-узел по address == str(cid) (ua.NodeId(address, ns=2)).
        assert str(cid) in sim_addr


def test_bool_ratio_distribution():
    # bool_ratio=0.5 → каждый 2-й тег BOOLEAN (round(1/0.5)=2) → 5 из 10.
    gw_text, _ = build(n_opc=10, n_mb=0, poll_ms=1000, bool_ratio=0.5)
    booleans = [t for t in gw_tags(gw_text) if t["dataType"] == "BOOLEAN"]
    assert len(booleans) == 5


def test_id_bases_do_not_overlap():
    gw_text, _ = build(n_opc=5, n_mb=5, poll_ms=1000, bool_ratio=0.0)
    opc_ids = [t["channelId"] for t in gw_tags(gw_text) if str(t["nodeId"]).startswith("ns=")]
    mb_ids = [t["channelId"] for t in gw_tags(gw_text) if "modbusAddress" in t]

    assert all(OPC_BASE <= i < MB_BASE for i in opc_ids)
    assert all(i >= MB_BASE for i in mb_ids)
    assert set(opc_ids).isdisjoint(mb_ids)
