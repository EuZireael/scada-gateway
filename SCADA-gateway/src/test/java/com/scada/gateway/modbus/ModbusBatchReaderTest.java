package com.scada.gateway.modbus;

import com.scada.gateway.model.entity.TagEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Тесты батч-чтения Modbus на моке ModbusClientService.readHoldingRegisters.
 * Проверяем: декод FLOAT/BOOLEAN/INT из сырых регистров, группировку в блоки и
 * поведение при обрыве блока. ModbusClientService (реальный сокет) замокан.
 */
@ExtendWith(MockitoExtension.class)
class ModbusBatchReaderTest {

    @Mock ModbusClientService modbus;

    private static TagEntity tag(int address, String dataType) {
        TagEntity t = new TagEntity();
        t.setModbusAddress(address);
        t.setModbusUnitId(1);
        t.setDataType(dataType);
        t.setProtocol("modbus");
        return t;
    }

    @Test
    @DisplayName("Один блок: FLOAT/BOOLEAN/INT декодируются из сырых регистров")
    void decodes_block() {
        ModbusBatchReader reader = new ModbusBatchReader(modbus);
        // 40001..40004 (0-based 0..3): FLOAT(0-1), BOOL(2), INT(3).
        List<TagEntity> tags = List.of(tag(40001, "FLOAT"), tag(40003, "BOOLEAN"), tag(40004, "INT"));
        // 3.14f = 0x4048F5C3 → little-endian по словам: reg[0]=0xF5C3, reg[1]=0x4048.
        when(modbus.readHoldingRegisters("h", 502, 0, 4, 1))
                .thenReturn(new int[]{0xF5C3, 0x4048, 1, 42});

        List<ModbusBatchReader.Reading> out = reader.read("h", 502, 1, tags);

        assertEquals(3, out.size());
        assertEquals(3.14f, (Float) out.get(0).value(), 1e-4f);
        assertEquals(Boolean.TRUE, out.get(1).value());
        assertEquals(42, out.get(2).value());
    }

    @Test
    @DisplayName("Большой разрыв адресов → два отдельных FC03-запроса")
    void splits_when_span_exceeds_limit() {
        ModbusBatchReader reader = new ModbusBatchReader(modbus);
        // 40001 (0) и 40200 (199): span 200 > 120 → два блока.
        List<TagEntity> tags = List.of(tag(40001, "INT"), tag(40200, "INT"));
        when(modbus.readHoldingRegisters(eq("h"), eq(502), anyInt(), anyInt(), eq(1)))
                .thenReturn(new int[]{7});

        reader.read("h", 502, 1, tags);

        verify(modbus).readHoldingRegisters("h", 502, 0, 1, 1);
        verify(modbus).readHoldingRegisters("h", 502, 199, 1, 1);
    }

    @Test
    @DisplayName("Обрыв блока (null) → все теги блока BAD (value=null)")
    void block_failure_marks_all_null() {
        ModbusBatchReader reader = new ModbusBatchReader(modbus);
        List<TagEntity> tags = List.of(tag(40001, "INT"), tag(40002, "INT"));
        when(modbus.readHoldingRegisters(eq("h"), eq(502), anyInt(), anyInt(), eq(1))).thenReturn(null);

        List<ModbusBatchReader.Reading> out = reader.read("h", 502, 1, tags);

        assertEquals(2, out.size());
        assertNull(out.get(0).value());
        assertNull(out.get(1).value());
    }
}
