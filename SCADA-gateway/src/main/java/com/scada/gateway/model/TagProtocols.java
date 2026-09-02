package com.scada.gateway.model;

import com.scada.gateway.model.entity.TagEntity;

/**
 * Классификация протокола тега (OPC UA / Modbus) для маршрутизации опроса и команд.
 *
 * <p>Выделено из god-класса OpcUaClientServiceDB — общие для опроса и команд чистые
 * функции. Живут в пакете model (относятся к {@link TagEntity}, а не к opcua/modbus).
 *
 * <p>ВНИМАНИЕ: логика НАМЕРЕННО мягче встроенных {@link TagEntity#isOpcUa()} /
 * {@link TagEntity#isModbus()}, которые смотрят только на поле protocol. Здесь добавлен
 * фолбэк по наличию nodeId / modbusAddress: тег классифицируется по протоколу, даже
 * если поле protocol не проставлено. Поведение сохранено при выносе один-в-один.
 */
public final class TagProtocols {

    private TagProtocols() {
        // Утилитный класс — не инстанцируем.
    }

    /**
     * OPC UA ли тег. Явный protocol=modbus/pac сразу исключает; иначе OPC UA при
     * protocol=opcua ИЛИ при наличии непустого nodeId (фолбэк без поля protocol).
     *
     * <p>ВАЖНО: pac исключаем явно — у PAC-тегов nodeId непустой (синтетический
     * "pac:9001"), иначе фолбэк по nodeId ошибочно счёл бы их OPC UA.
     */
    public static boolean isOpcUaTag(TagEntity tag) {
        String protocol = tag.getProtocol();
        if ("modbus".equalsIgnoreCase(protocol) || "pac".equalsIgnoreCase(protocol)) return false;
        return "OPCUA".equalsIgnoreCase(protocol) ||
               (tag.getNodeId() != null && !tag.getNodeId().isEmpty());
    }

    /** PAC ли тег (протокол driver-master): строго по полю protocol=pac. */
    public static boolean isPacTag(TagEntity tag) {
        return "pac".equalsIgnoreCase(tag.getProtocol());
    }

    /**
     * Modbus ли тег: protocol=modbus ИЛИ задан положительный modbusAddress
     * (фолбэк, когда поле protocol не проставлено).
     */
    public static boolean isModbusTag(TagEntity tag) {
        return "MODBUS".equalsIgnoreCase(tag.getProtocol()) ||
               (tag.getModbusAddress() != null && tag.getModbusAddress() > 0);
    }
}
