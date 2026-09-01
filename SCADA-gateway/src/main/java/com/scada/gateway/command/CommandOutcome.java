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
    /** Короткий флаг успеха (= status == APPLIED); удобно для ветвлений. */
    public final boolean success;
    /** Машиночитаемый исход (уходит на провод как .name()). */
    public final CommandStatus status;
    /** Человекочитаемая деталь для оператора/лога. */
    public final String message;
    /** Фактически записанное значение (после приведения к типу тега); null при отказе. */
    public final Object appliedValue;

    public CommandOutcome(boolean success, CommandStatus status, String message, Object appliedValue) {
        this.success = success;
        this.status = status;
        this.message = message;
        this.appliedValue = appliedValue;
    }
}
