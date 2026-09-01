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
     * OPC UA ли тег. Явный protocol=modbus сразу исключает; иначе OPC UA при
     * protocol=opcua ИЛИ при наличии непустого nodeId (фолбэк без поля protocol).
     */
    public static boolean isOpcUaTag(TagEntity tag) {
        if ("modbus".equalsIgnoreCase(tag.getProtocol())) return false;
        return "OPCUA".equalsIgnoreCase(tag.getProtocol()) ||
               (tag.getNodeId() != null && !tag.getNodeId().isEmpty());
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
