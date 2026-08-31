package com.scada.gateway.command;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;

/**
 * Классификация неудачного OPC UA {@link StatusCode} записи в фиксированный
 * перечень исходов команды ({@link CommandStatus}, A5).
 *
 * <p>Выделено из god-класса OpcUaClientServiceDB. Чистая функция (StatusCode → исход),
 * без состояния и I/O — покрывается модульными тестами прямым конструированием кодов
 * (см. {@code CommandStatusClassifierTest}). Интерпретация кодов OPC UA — часть
 * командного домена, поэтому класс живёт в пакете command.
 */
public final class CommandStatusClassifier {

    private CommandStatusClassifier() {
        // Утилитный класс — не инстанцируем.
    }

    /** Неудачный StatusCode записи → осмысленный для оператора исход команды. */
    public static CommandStatus classify(StatusCode status) {
        long code = status.getValue();
        if (code == StatusCodes.Bad_NotWritable || code == StatusCodes.Bad_WriteNotSupported) {
            return CommandStatus.REJECTED_NOT_WRITABLE;
        }
        if (code == StatusCodes.Bad_TypeMismatch || code == StatusCodes.Bad_OutOfRange) {
            return CommandStatus.REJECTED_TYPE_MISMATCH;
        }
        return CommandStatus.FAILED_WRITE;
    }
}
