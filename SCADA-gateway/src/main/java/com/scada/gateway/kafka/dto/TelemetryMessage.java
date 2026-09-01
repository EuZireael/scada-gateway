package com.scada.gateway.kafka.dto;

import java.time.Instant;

/**
 * DTO телеметрии для Kafka — минимальный контракт (аналог OPC UA DataValue):
 * значение + качество + метка времени.
 *
 * Адрес тега на проводе НЕ дублируется в теле: он и так есть в Kafka-key
 * (= путь узла = tag.getName()). Всё статическое — единицы измерения, прибор,
 * контроллер, тип — Monitor резолвит из СВОЕГО конфига по ключу, а не читает
 * с провода (проверено по коду runtime: он берёт только .value, а после
 * quality-gate ещё и .quality). Так дисплей монитора не завязан на схему БД
 * шлюза, а поток остаётся лёгким.
 *
 * value шлём ТИПИЗИРОВАННЫМ (число/bool, не строкой): Monitor делает
 * value.asText(), и для аналога это должно давать число, иначе график не строится.
 */
public class TelemetryMessage {
    /** Значение тега, ТИПИЗИРОВАННОЕ (число/bool) — иначе график на мониторе не строится. */
    private Object value;
    /** Качество отсчёта: GOOD/BAD. */
    private String quality;
    /** Момент снятия значения (sourceTime OPC UA / момент чтения Modbus). */
    private Instant timestamp;

    public TelemetryMessage() {}

    public TelemetryMessage(Object value, String quality, Instant timestamp) {
        this.value = value;
        this.quality = quality;
        this.timestamp = timestamp;
    }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
