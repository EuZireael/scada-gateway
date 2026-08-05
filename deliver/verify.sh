#!/usr/bin/env bash
# Смоук-тест продюсер-стороны: ждёт готовности стека, дожидается сообщений в
# топике scada-telemetry и печатает примеры. Запускать ПОСЛЕ `docker compose up -d`.
#   ./verify.sh
set -uo pipefail
cd "$(dirname "$0")"

TOPIC=scada-telemetry
KAFKA=scada-kafka
KIMG=confluentinc/cp-kafka:latest

# --- движок ---
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  ENGINE=docker
elif command -v podman >/dev/null 2>&1; then
  ENGINE=podman
else
  echo "Нужен docker или podman." >&2; exit 1
fi
echo "Движок: $ENGINE"

# --- 1. ждём, пока поднимутся все 4 контейнера ---
echo -n "Жду контейнеры "
for i in $(seq 1 60); do
  up=$($ENGINE ps --format '{{.Names}}' 2>/dev/null | grep -cE 'scada-(postgres|kafka|simulator|gateway)')
  [ "${up:-0}" -ge 4 ] && { echo " — все 4 подняты."; break; }
  echo -n "."; sleep 2
done

# --- 2. ждём первые сообщения (шлюзу нужно ~время на старт+опрос) ---
# Проверка через попытку прочитать 1 сообщение — надёжнее подсчёта offset
# (в разных версиях Kafka утилиты offset отличаются).
echo -n "Жду телеметрию в топике $TOPIC "
ready=0
for i in $(seq 1 60); do
  got=$($ENGINE exec "$KAFKA" kafka-console-consumer \
        --bootstrap-server localhost:9092 --topic "$TOPIC" \
        --max-messages 1 --timeout-ms 4000 2>/dev/null | head -1)
  [ -n "$got" ] && { ready=1; echo " — есть."; break; }
  echo -n "."; sleep 2
done

if [ "$ready" -ne 1 ]; then
  echo
  echo "❌ Сообщения не появились. Смотри лог шлюза:  $ENGINE compose logs gateway"
  exit 1
fi

# --- 3. примеры сообщений ---
echo
echo "── Примеры (key ║ value) ─────────────────────────────────────────────"
$ENGINE exec "$KAFKA" kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic "$TOPIC" \
  --from-beginning --max-messages 6 --timeout-ms 15000 \
  --property print.key=true --property key.separator=' ║ ' 2>/dev/null
echo "──────────────────────────────────────────────────────────────────────"

# --- 4. разбивка по контроллерам (дискретные/аналоговые) ---
sample=$($ENGINE exec "$KAFKA" kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic "$TOPIC" \
  --max-messages 200 --timeout-ms 12000 2>/dev/null)
disc=$(printf '%s' "$sample" | grep -cE '"value":(true|false)')
anal=$(printf '%s' "$sample" | grep -cE '"controllerId":2')
echo "В выборке из 200: дискретных (OPC UA) ≈ $disc, аналоговых (Modbus) ≈ $anal"

# --- 5. адрес для монитора: localhost:9094 с хоста ---
echo
echo -n "Проверка адреса монитора localhost:9094 … "
got=$($ENGINE run --rm --network host "$KIMG" \
      timeout 12 kafka-console-consumer --bootstrap-server localhost:9094 \
      --topic "$TOPIC" --max-messages 1 2>/dev/null | head -1)
if [ -n "$got" ]; then
  echo "✓ доступен — монитор с хоста подключается к localhost:9094."
else
  echo "⚠ не прочиталось (в вашем движке нет --network host?); внутри compose-сети используйте kafka:9092."
fi

echo
echo "✅ Проверка пройдена: телеметрия идёт в топик $TOPIC (tagId = node.id)."
