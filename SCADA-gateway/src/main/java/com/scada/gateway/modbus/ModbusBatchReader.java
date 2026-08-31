package com.scada.gateway.modbus;

import com.scada.gateway.model.entity.TagEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Батч-чтение Modbus: вместо одной FC03-транзакции на КАЖДЫЙ тег группирует теги в
 * непрерывные блоки holding-регистров (≤120 за запрос) и читает блок одной FC03,
 * затем режет сырые регистры на значения. Для WAGO (1877 тегов, адреса 40001..43722
 * без дыр) это ~30 запросов за цикл вместо 1877 — главный перф-выигрыш опроса Modbus.
 *
 * <p>Значения декодируются ОДИН-В-ОДИН со старым пер-теговым путём: FLOAT — 2 регистра
 * little-endian по словам; INT/INT16 — регистр как есть (0..65535); BOOLEAN — регистр != 0.
 * Ошибка/обрыв блока → все теги блока получают null (BAD), как и раньше при пер-теговом обрыве.
 */
@Component
public class ModbusBatchReader {

    /** Modbus holding-регистры адресуются с 40001; смещение в 0-based. */
    private static final int ADDRESS_BASE = 40001;
    /** Лимит FC03 — 125 регистров; берём с запасом. */
    private static final int MAX_BLOCK_REGISTERS = 120;

    private final ModbusClientService modbus;

    public ModbusBatchReader(ModbusClientService modbus) {
        this.modbus = modbus;
    }

    /** Результат чтения одного тега: значение (null = BAD). */
    public record Reading(TagEntity tag, Object value) {}

    /**
     * Прочитать значения всех переданных Modbus-тегов минимальным числом FC03-запросов.
     * {@code tags} — уже отфильтрованные enabled Modbus-теги одного контроллера (unitId общий).
     */
    public List<Reading> read(String host, int port, int unitId, List<TagEntity> tags) {
        List<TagEntity> sorted = new ArrayList<>(tags);
        sorted.sort(Comparator.comparingInt(TagEntity::getModbusAddress));

        List<Reading> out = new ArrayList<>(sorted.size());
        int i = 0;
        while (i < sorted.size()) {
            int blockStart = reg0(sorted.get(i));   // 0-based старт блока
            int blockEnd = blockStart;               // exclusive
            int j = i;
            // Набираем теги в блок, пока span от старта не превысит лимит FC03.
            while (j < sorted.size()) {
                int end = reg0(sorted.get(j)) + width(sorted.get(j));
                if (end - blockStart > MAX_BLOCK_REGISTERS) break;
                blockEnd = Math.max(blockEnd, end);
                j++;
            }
            int count = blockEnd - blockStart;
            int[] regs = modbus.readHoldingRegisters(host, port, blockStart, count, unitId);
            for (int k = i; k < j; k++) {
                TagEntity tag = sorted.get(k);
                Object v = (regs == null) ? null : decode(tag, regs, reg0(tag) - blockStart);
                out.add(new Reading(tag, v));
            }
            i = j;
        }
        return out;
    }

    private static int reg0(TagEntity tag) {
        return tag.getModbusAddress() - ADDRESS_BASE;
    }

    private static int width(TagEntity tag) {
        return "FLOAT".equalsIgnoreCase(tag.getDataType()) ? 2 : 1;
    }

    /** Декод сырых регистров блока в значение тега (1-в-1 со старым пер-теговым путём). */
    private static Object decode(TagEntity tag, int[] regs, int off) {
        String dt = tag.getDataType();
        if ("FLOAT".equalsIgnoreCase(dt)) {
            if (off < 0 || off + 1 >= regs.length) return null;
            int reg1 = regs[off], reg2 = regs[off + 1];
            int le = (reg2 << 16) | (reg1 & 0xFFFF);   // little-endian по словам (как в симуляторе)
            return Float.intBitsToFloat(le);
        }
        if (off < 0 || off >= regs.length) return null;
        int reg = regs[off];
        if ("BOOLEAN".equalsIgnoreCase(dt)) {
            return reg != 0;
        }
        return reg;   // INT / INT16 (0..65535)
    }
}
