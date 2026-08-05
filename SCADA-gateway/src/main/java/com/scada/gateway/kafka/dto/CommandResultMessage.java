package com.scada.gateway.kafka.dto;

import java.time.Instant;

/**
 * Результат выполнения команды, отправляемый обратно в Monitor Srv
 * (топик scada-command-results). Поля совпадают с scada.core.dto.CommandResultDTO.
 */
public class CommandResultMessage {
    private String commandId;
    private Long tagId;
    private String tagName;
    private String status;      // APPLIED | REJECTED_UNKNOWN_TAG | REJECTED_NOT_WRITABLE |
                                // REJECTED_TYPE_MISMATCH | REJECTED_PROTOCOL_UNSUPPORTED |
                                // FAILED_NO_CONNECTION | FAILED_WRITE (A5)
    private boolean success;
    private String message;
    private Object appliedValue;
    private Instant timestamp;

    public CommandResultMessage() {}

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }

    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Object getAppliedValue() { return appliedValue; }
    public void setAppliedValue(Object appliedValue) { this.appliedValue = appliedValue; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
