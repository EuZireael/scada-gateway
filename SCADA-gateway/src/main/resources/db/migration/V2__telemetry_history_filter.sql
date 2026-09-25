-- Фильтр локальной истории (TelemetryHistoryFilter, 25.09.2026): переопределения
-- gateway.history.* для отдельных тегов. NULL — взять умолчание.
ALTER TABLE tags ADD COLUMN IF NOT EXISTS history_deadband double precision;
ALTER TABLE tags ADD COLUMN IF NOT EXISTS history_deadband_percent double precision;
ALTER TABLE tags ADD COLUMN IF NOT EXISTS history_min_interval_ms bigint;
ALTER TABLE tags ADD COLUMN IF NOT EXISTS history_max_interval_ms bigint;

-- История читается по тегу за интервал (TelemetryRepository.findByTagIdAndTimeBetween) и
-- чистится по времени — без индекса и то и другое читает всю таблицу.
CREATE INDEX IF NOT EXISTS telemetry_tag_time_idx ON telemetry (tag_id, time);
CREATE INDEX IF NOT EXISTS telemetry_time_idx ON telemetry (time);
