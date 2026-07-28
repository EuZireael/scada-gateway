-- ============================================================================
--  СОЗДАНИЕ РОЛИ И БАЗЫ  (нужно только если их ещё нет на сервере)
-- ============================================================================
--  Запускать от суперпользователя postgres:
--    sudo -u postgres psql -f init_db.sql
--  или:
--    psql -h localhost -p 5433 -U postgres -f init_db.sql
--
--  Значения совпадают с application.yaml:
--    jdbc:postgresql://localhost:5433/scada_db , scada_user / scada_password
-- ============================================================================

-- Роль (если уже есть — пропустит)
DO $$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'scada_user') THEN
      CREATE ROLE scada_user LOGIN PASSWORD 'scada_password';
   END IF;
END $$;

-- База (CREATE DATABASE нельзя в DO-блоке; \gexec создаёт её, только если нет)
SELECT 'CREATE DATABASE scada_db OWNER scada_user'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'scada_db')\gexec
