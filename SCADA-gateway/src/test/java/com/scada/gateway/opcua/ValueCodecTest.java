package com.scada.gateway.opcua;

import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Первые настоящие модульные тесты проекта — на чистые конвертеры {@link ValueCodec}.
 *
 * <p>Заметь: ни @SpringBootTest, ни Postgres/Kafka, ни моков. Это быстрые тесты чистых
 * функций — именно поэтому конвертеры и были выбраны первым «швом» декомпозиции: их
 * можно проверить в изоляции за миллисекунды. Запуск только этого класса:
 * {@code mvn test -Dtest=ValueCodecTest}.
 */
class ValueCodecTest {

    // ---------- toBool ----------

    @Test
    @DisplayName("toBool: Boolean возвращается как есть")
    void toBool_boolean_passthrough() {
        assertTrue(ValueCodec.toBool(true));
        assertFalse(ValueCodec.toBool(false));
    }

    @Test
    @DisplayName("toBool: число != 0 → true, 0 → false")
    void toBool_number() {
        assertTrue(ValueCodec.toBool(1));
        assertTrue(ValueCodec.toBool(-3.5));
        assertFalse(ValueCodec.toBool(0));
        assertFalse(ValueCodec.toBool(0.0));
    }

    @Test
    @DisplayName("toBool: строки — только \"true\" (регистронезависимо), с обрезкой пробелов")
    void toBool_string() {
        assertTrue(ValueCodec.toBool("true"));
        assertTrue(ValueCodec.toBool("TRUE"));
        assertTrue(ValueCodec.toBool("  true  "));
        assertFalse(ValueCodec.toBool("false"));
        // ВАЖНО и неочевидно: строка "1" НЕ является true — Boolean.parseBoolean
        // распознаёт только слово "true". Числовая 1 — да, а строковая "1" — нет.
        assertFalse(ValueCodec.toBool("1"));
    }

    // ---------- toInt ----------

    @Test
    @DisplayName("toInt: число (дробное усекается), Integer как есть")
    void toInt_number() {
        assertEquals(5, ValueCodec.toInt(5));
        assertEquals(42, ValueCodec.toInt(Integer.valueOf(42)));
        // Дробь усекается (intValue()), а не округляется: 5.9 → 5.
        assertEquals(5, ValueCodec.toInt(5.9));
    }

    @Test
    @DisplayName("toInt: строка парсится, с обрезкой пробелов")
    void toInt_string() {
        assertEquals(7, ValueCodec.toInt("7"));
        assertEquals(7, ValueCodec.toInt("  7  "));
    }

    @Test
    @DisplayName("toInt: неразбираемая строка → NumberFormatException")
    void toInt_bad_string_throws() {
        // Так проверяют, что метод БРОСАЕТ ожидаемое исключение.
        assertThrows(NumberFormatException.class, () -> ValueCodec.toInt("abc"));
    }

    // ---------- toFloat / toDouble ----------

    @Test
    @DisplayName("toFloat: число и строка")
    void toFloat_works() {
        assertEquals(3.0f, ValueCodec.toFloat(3), 1e-6f);
        assertEquals(3.14f, ValueCodec.toFloat("3.14"), 1e-6f);
    }

    @Test
    @DisplayName("toDouble: число и строка")
    void toDouble_works() {
        assertEquals(2.0, ValueCodec.toDouble(2), 1e-9);
        assertEquals(2.5, ValueCodec.toDouble("2.5"), 1e-9);
    }

    // ---------- toVariant ----------

    @Test
    @DisplayName("toVariant: тип тега определяет обёртку Variant")
    void toVariant_by_type() {
        assertEquals(Boolean.TRUE, ValueCodec.toVariant("BOOL", true).getValue());
        assertEquals(5, ValueCodec.toVariant("INT", 5).getValue());
        assertInstanceOf(Float.class, ValueCodec.toVariant("FLOAT", 3.5).getValue());
        assertInstanceOf(Float.class, ValueCodec.toVariant("REAL", 3.5).getValue());   // REAL — синоним FLOAT
        assertInstanceOf(Double.class, ValueCodec.toVariant("DOUBLE", 2.5).getValue());
    }

    @Test
    @DisplayName("toVariant: имя типа режется и приводится к верхнему регистру")
    void toVariant_type_is_trimmed_and_uppercased() {
        // "  bool  " → BOOL-ветка; числовая 1 → true.
        assertEquals(Boolean.TRUE, ValueCodec.toVariant("  bool  ", 1).getValue());
    }

    @Test
    @DisplayName("toVariant: null или неизвестный тип → значение без конвертации")
    void toVariant_passthrough() {
        assertEquals("x", ValueCodec.toVariant(null, "x").getValue());
        assertEquals("hi", ValueCodec.toVariant("STRING", "hi").getValue());
    }

    // ---------- extractValue ----------

    @Test
    @DisplayName("extractValue: null и NULL_VALUE → null")
    void extractValue_null() {
        assertNull(ValueCodec.extractValue(null));
        assertNull(ValueCodec.extractValue(Variant.NULL_VALUE));
    }

    @Test
    @DisplayName("extractValue: беззнаковый UInteger → Long")
    void extractValue_uinteger_to_long() {
        Object result = ValueCodec.extractValue(new Variant(uint(5)));
        assertInstanceOf(Long.class, result);   // развёрнут в обычный Java-Number
        assertEquals(5L, result);
    }

    @Test
    @DisplayName("extractValue: обычное значение возвращается как есть")
    void extractValue_passthrough() {
        Object result = ValueCodec.extractValue(new Variant(42));
        assertInstanceOf(Integer.class, result);
        assertEquals(42, result);
    }
}
