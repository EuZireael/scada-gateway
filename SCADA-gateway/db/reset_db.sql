-- ============================================================================
--  СБРОС БД ПЕРЕД ЗАПУСКОМ ШЛЮЗА  (рекомендуемый способ)
-- ============================================================================
--  Удаляет таблицы приложения. При следующем старте шлюз сам пересоздаст
--  схему (ddl-ауto: update) и зальёт 170 тегов из application.yaml,
--  телеметрия наберётся заново из replay архива.
--
--  Лечит ошибку:
--    duplicate key value violates unique constraint "tags_pkey"
--    (рассинхрон IDENTITY-последовательности из-за старых данных в БД)
--
--  Запуск на сервере:
--    psql -h localhost -p 5433 -U scada_user -d scada_db -f reset_db.sql
--  (порт/имя — как в application.yaml; пароль scada_password)
-- ============================================================================

DROP TABLE IF EXISTS telemetry  CASCADE;
DROP TABLE IF EXISTS event_log  CASCADE;
DROP TABLE IF EXISTS tags       CASCADE;
DROP TABLE IF EXISTS controllers CASCADE;

-- Проверка: после сброса этих таблиц быть не должно
\dt
