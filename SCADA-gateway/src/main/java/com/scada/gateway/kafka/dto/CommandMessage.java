package com.scada.gateway.kafka.dto;

import java.time.Instant;

/**
 * Команда управления тегом, приходящая из Monitor Srv (топик scada-commands).
 * Поля совпадают с scada.core.dto.CommandDTO на стороне Monitor.
 */
public class CommandMessage {
    /** Уникальный id команды — по нему монитор коррелирует результат (CommandResultMessage). */
    private String commandId;
    /** id контроллера (может быть null — тег находим по tagId/tagName). */
    private Long controllerId;
    /** id тега; основной способ адресации. */
    private Long tagId;
    /** Имя тега (путь канала) — фолбэк-адресация, если tagId не задан. */
    private String tagName;
    /** Тип значения для приведения перед записью. */
    private String dataType;   // BOOL | INT | FLOAT
    /** Записываемое значение (сырое из JSON, приводится по dataType). */
    private Object value;
    /** Кто инициировал команду (оператор/система) — для аудита. */
    private String requestedBy;
    /** Время формирования команды на стороне монитора. */
    private Instant timestamp;

    public CommandMessage() {}

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }

    public Long getControllerId() { return controllerId; }
    public void setControllerId(Long controllerId) { this.controllerId = controllerId; }

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }

    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
