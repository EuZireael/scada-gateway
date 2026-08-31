package com.scada.gateway.command;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;

/**
 * Порт «текущий OPC UA-клиент по контроллеру» для {@link CommandService} (инверсия
 * зависимостей). Живым соединением владеет OpcUaClientServiceDB (жизненный цикл
 * соединений); здесь только чтение. {@code null} = контроллер не подключён.
 */
public interface OpcUaClientRegistry {
    OpcUaClient forController(Long controllerId);
}
