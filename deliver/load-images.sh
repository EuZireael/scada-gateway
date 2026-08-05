#!/usr/bin/env bash
# Загружает ВСЕ образы (postgres, kafka, шлюз, симулятор) из tar.
# Полностью оффлайн — ничего не тянется из сети. Работает с docker и podman.
set -euo pipefail
cd "$(dirname "$0")"

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  ENGINE=docker
elif command -v podman >/dev/null 2>&1; then
  ENGINE=podman
else
  echo "Нужен docker или podman." >&2; exit 1
fi
echo "Движок: $ENGINE"

for f in images/postgres-16.tar.gz \
         images/cp-kafka.tar.gz \
         images/scada-simulator-1.0.tar.gz \
         images/scada-gateway-1.0.tar.gz; do
  echo "Загружаю $f ..."
  $ENGINE load -i "$f"
done

echo
echo "Готово. Загружены образы:"
$ENGINE images | grep -E 'scada-(gateway|simulator)|cp-kafka|postgres' || true
echo
echo "Дальше:  $ENGINE compose up -d"
