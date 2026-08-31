package com.scada.gateway.command;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Тесты классификации OPC UA StatusCode в исход команды.
 * Чистая функция → строим коды напрямую, без Spring/сети.
 */
class CommandStatusClassifierTest {

    @Test
    @DisplayName("Bad_NotWritable / Bad_WriteNotSupported → REJECTED_NOT_WRITABLE")
    void not_writable() {
        assertEquals(CommandStatus.REJECTED_NOT_WRITABLE,
                CommandStatusClassifier.classify(new StatusCode(StatusCodes.Bad_NotWritable)));
        assertEquals(CommandStatus.REJECTED_NOT_WRITABLE,
                CommandStatusClassifier.classify(new StatusCode(StatusCodes.Bad_WriteNotSupported)));
    }

    @Test
    @DisplayName("Bad_TypeMismatch / Bad_OutOfRange → REJECTED_TYPE_MISMATCH")
    void type_mismatch() {
        assertEquals(CommandStatus.REJECTED_TYPE_MISMATCH,
                CommandStatusClassifier.classify(new StatusCode(StatusCodes.Bad_TypeMismatch)));
        assertEquals(CommandStatus.REJECTED_TYPE_MISMATCH,
                CommandStatusClassifier.classify(new StatusCode(StatusCodes.Bad_OutOfRange)));
    }

    @Test
    @DisplayName("Прочие Bad-коды → FAILED_WRITE (дефолтная ветка)")
    void other_bad_is_failed_write() {
        assertEquals(CommandStatus.FAILED_WRITE,
                CommandStatusClassifier.classify(new StatusCode(StatusCodes.Bad_InternalError)));
        assertEquals(CommandStatus.FAILED_WRITE,
                CommandStatusClassifier.classify(new StatusCode(StatusCodes.Bad_Timeout)));
    }
}
