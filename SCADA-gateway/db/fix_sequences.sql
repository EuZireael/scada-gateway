-- ============================================================================
--  АЛЬТЕРНАТИВА reset_db.sql — БЕЗ удаления данных
-- ============================================================================
--  Если телеметрию хочется сохранить: не дропаем таблицы, а просто чиним
--  IDENTITY-последовательности, чтобы автоинкремент пошёл с max(id)+1.
--  Этого достаточно, чтобы убрать "duplicate key ... tags_pkey".
--  Стаpые лишние теги шлюз сам удалит при синхронизации с application.yaml.
--
--  Запуск:
--    psql -h localhost -p 5433 -U scada_user -d scada_db -f fix_sequences.sql
-- ============================================================================

SELECT setval(pg_get_serial_sequence('controllers','id'), GREATEST(COALESCE(MAX(id),0),1)) FROM controllers;
SELECT setval(pg_get_serial_sequence('tags','id'),        GREATEST(COALESCE(MAX(id),0),1)) FROM tags;
SELECT setval(pg_get_serial_sequence('telemetry','id'),   GREATEST(COALESCE(MAX(id),0),1)) FROM telemetry;
SELECT setval(pg_get_serial_sequence('event_log','id'),   GREATEST(COALESCE(MAX(id),0),1)) FROM event_log;
