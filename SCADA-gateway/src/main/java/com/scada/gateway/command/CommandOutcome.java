package com.scada.gateway.command;

/**
 * Исход записи тега для формирования CommandResultMessage.
 *
 * <p>Вынесен из god-класса OpcUaClientServiceDB (шаг 2a декомпозиции): словарь
 * команд (этот класс + {@link CommandStatus}) получает свой пакет, а внешний
 * потребитель ({@code CommandConsumer}) перестаёт зависеть от внутренних типов
 * сервиса опроса. Неизменяемый носитель результата — поля final, без сеттеров.
 */
public final class CommandOutcome {
    public final boolean success;
    public final CommandStatus status;
    public final String message;
    public final Object appliedValue;

    public CommandOutcome(boolean success, CommandStatus status, String message, Object appliedValue) {
        this.success = success;
        this.status = status;
        this.message = message;
        this.appliedValue = appliedValue;
    }
}
