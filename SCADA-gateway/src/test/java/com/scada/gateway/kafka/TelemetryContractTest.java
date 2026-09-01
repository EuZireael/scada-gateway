package com.scada.gateway.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.scada.gateway.kafka.dto.TelemetryMessage;
import com.scada.gateway.kafka.producer.TelemetryProducer;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.service.EventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * КОНТРАКТНЫЙ тест телеметрии шлюз→монитор (топик scada.tags).
 *
 * <p>Монитор — ОТДЕЛЬНЫЙ сервис (Bidway/scada-editor runtime), он не пересобирается вместе
 * со шлюзом. Контракт между ними держится «на честном слове» и раньше уже расходился (в
 * спеке был неверный топик scada-telemetry). Этот тест фиксирует три части контракта, чтобы
 * рассинхрон ловил CI, а не оператор у монитора:
 *   1) ТЕЛО — ровно {value, quality, timestamp}, без «толстых» полей (tagId/metadata/…);
 *   2) value ТИПИЗИРОВАН (число/bool, не строка) — монитор строит график по числу;
 *   3) МАРШРУТИЗАЦИЯ — продюсер шлёт в настроенный топик с ключом = путь канала (tag.getName()).
 *
 * <p>Чистый unit-тест: тело сверяем сериализацией Jackson (форма DTO = форма провода),
 * маршрут — мок KafkaTemplate. Ни Kafka, ни Spring-контекст не нужны.
 */
class TelemetryContractTest {

    // Отдельный mapper с JavaTimeModule (как в проекте) — отражает JSON-форму DTO.
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("Тело телеметрии — ровно {value, quality, timestamp}, без лишних полей")
    void wireBody_hasExactlyTheThreeContractFields() throws Exception {
        TelemetryMessage msg = new TelemetryMessage(1.07, "GOOD", Instant.now());

        JsonNode json = mapper.readTree(mapper.writeValueAsString(msg));

        // Ровно три поля: если кто-то добавит поле в DTO — тест упадёт (контракт менять осознанно).
        int count = 0;
        for (Iterator<String> it = json.fieldNames(); it.hasNext(); it.next()) count++;
        assertEquals(3, count, "на проводе должно быть ровно 3 поля, а не «толстое» сообщение");

        assertTrue(json.has("value"), "нет поля value");
        assertTrue(json.has("quality"), "нет поля quality");
        assertTrue(json.has("timestamp"), "нет поля timestamp");

        // Явная защита от возврата «толстого» формата, который был в спеке.
        for (String fat : new String[]{"tagId", "tagName", "metadata", "numericValue", "stringValue"}) {
            assertFalse(json.has(fat), "поле '" + fat + "' не должно уходить на провод");
        }
    }

    @Test
    @DisplayName("Числовое значение остаётся числом (не строкой) — иначе график монитора не построится")
    void wireBody_numericValueStaysNumeric() throws Exception {
        TelemetryMessage msg = new TelemetryMessage(1.07, "GOOD", Instant.now());

        JsonNode value = mapper.readTree(mapper.writeValueAsString(msg)).get("value");

        assertTrue(value.isNumber(), "value должен сериализоваться как число, а не строка");
        assertEquals(1.07, value.asDouble(), 1e-9);
    }

    @Test
    @DisplayName("Boolean-значение остаётся boolean (типизированный дискрет)")
    void wireBody_booleanValueStaysTyped() throws Exception {
        TelemetryMessage msg = new TelemetryMessage(true, "GOOD", Instant.now());

        JsonNode value = mapper.readTree(mapper.writeValueAsString(msg)).get("value");

        assertTrue(value.isBoolean(), "value должен сериализоваться как boolean");
        assertTrue(value.asBoolean());
    }

    @Test
    @DisplayName("Продюсер шлёт в настроенный топик с ключом = путь канала (tag.getName())")
    void producer_routesToConfiguredTopicKeyedByChannelPath() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        // send(...) возвращает future; код вешает на него whenComplete — отдаём незавершённый.
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(new CompletableFuture<SendResult<String, Object>>());

        TelemetryProducer producer = new TelemetryProducer(kafka, "scada.tags", mock(EventLogService.class));
        // kafkaEnabled — @Value-поле, по умолчанию false (тогда метод — no-op). Включаем.
        ReflectionTestUtils.setField(producer, "kafkaEnabled", true);

        TagEntity tag = new TagEntity();
        tag.setName("Барановичи-1.BN1_MCA1.V_M_1.LINE1V0.M");   // путь канала = Kafka-key

        Instant ts = Instant.now();
        producer.sendTelemetry(tag, 1.07, "GOOD", ts);

        // Топик — тот, что внедрён (в проде = scada.tags из application.yaml); ключ — путь канала.
        var msgCaptor = org.mockito.ArgumentCaptor.forClass(TelemetryMessage.class);
        verify(kafka).send(eq("scada.tags"), eq("Барановичи-1.BN1_MCA1.V_M_1.LINE1V0.M"), msgCaptor.capture());

        TelemetryMessage sent = msgCaptor.getValue();
        assertEquals(1.07, sent.getValue());
        assertEquals("GOOD", sent.getQuality());
        assertEquals(ts, sent.getTimestamp());
    }
}
