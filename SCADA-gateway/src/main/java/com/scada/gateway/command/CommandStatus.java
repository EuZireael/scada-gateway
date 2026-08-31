package com.scada.gateway.command;

/**
 * Фиксированный перечень исходов команды записи (A5). Даёт оператору различить
 * ошибку конфигурации («канала нет») от эксплуатационной («нет связи») без
 * парсинга текста message. На провод уходит .name().
 */
public enum CommandStatus {
    APPLIED,
    REJECTED_UNKNOWN_TAG,
    REJECTED_NOT_WRITABLE,
    REJECTED_TYPE_MISMATCH,
    REJECTED_PROTOCOL_UNSUPPORTED,
    FAILED_NO_CONNECTION,
    FAILED_WRITE
}
