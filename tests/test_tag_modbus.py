"""
Тесты упаковки значения тега в Modbus-регистры (core/tag.py: Tag.get_modbus_registers).

Это точка стыка с реальным контроллером WAGO: шлюз читает holding-регистры и
декодирует их обратно, поэтому раскладка байт (float32 little-endian по 2 регистра,
знаковые int с обрезкой по маске) должна быть ровно такой.

Детерминизм: у тега с generator="replay" свойство value отдаёт _value как есть,
без шума/дрейфа/аномалий (см. Tag.value) — на этом строятся все проверки ниже.
"""
import struct

from core.tag import Tag


def make_tag(modbus_type, value, addr=0, dtype="float"):
    """Собрать replay-тег с заданным modbus_type и зафиксированным значением."""
    tag = Tag({
        "name": f"T_{modbus_type}",
        "type": dtype,
        "protocol": "modbus",
        "modbus_address": addr,
        "modbus_type": modbus_type,
        "generator": "replay",   # value == _value, без шума
    })
    tag.set_replay_value(value)
    return tag


def test_float32_is_two_registers_little_endian():
    # 1.0f == 0x3F800000; little-endian по словам: reg0=младшее, reg1=старшее.
    regs = make_tag("float32", 1.0, addr=100).get_modbus_registers()
    assert regs == [(100, 0x0000), (101, 0x3F80)]


def test_float32_roundtrip():
    # Обратная сборка регистров во float должна дать исходное значение.
    for v in (0.0, 3.14, -50.0, 42.5):
        regs = make_tag("float32", v, addr=0).get_modbus_registers()
        lo = regs[0][1]
        hi = regs[1][1]
        raw = struct.pack("<HH", lo, hi)
        assert struct.unpack("<f", raw)[0] == struct.unpack("<f", struct.pack("<f", v))[0]


def test_int16():
    assert make_tag("int16", 5, addr=10, dtype="int").get_modbus_registers() == [(10, 5)]


def test_int16_negative_wraps_to_unsigned():
    # -1 → 0xFFFF (обрезка по 16 бит через & 0xFFFF).
    assert make_tag("int16", -1, addr=10, dtype="int").get_modbus_registers() == [(10, 0xFFFF)]


def test_int32_low_high_words():
    # 0x00012345 → low=0x2345, high=0x0001 (два регистра, младшее слово первым).
    regs = make_tag("int32", 0x00012345, addr=20, dtype="int").get_modbus_registers()
    assert regs == [(20, 0x2345), (21, 0x0001)]


def test_bool_true_false():
    assert make_tag("bool", 1, addr=7, dtype="bool").get_modbus_registers() == [(7, 1)]
    assert make_tag("bool", 0, addr=7, dtype="bool").get_modbus_registers() == [(7, 0)]


def test_no_address_returns_none():
    tag = Tag({"name": "opc_only", "type": "float", "protocol": "opcua", "generator": "replay"})
    assert tag.get_modbus_registers() is None
