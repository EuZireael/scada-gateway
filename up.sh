#!/usr/bin/env bash
# ============================================================================
# Поднять ВЕСЬ стек SCADA одной командой: postgres + kafka + simulator + gateway.
#
#   ./up.sh            — собрать jar (если надо) и поднять стек в фоне
#   ./up.sh --logs     — то же + прицепиться к логам шлюза
#   ./up.sh down       — остановить (данные БД сохранятся)
#   ./up.sh down -v    — остановить и стереть данные БД
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

# На Fedora рабочий бэкенд — нативный podman (Docker Desktop QEMU падает).
# `docker compose` требует живой Docker-API сокет; у podman это podman.socket
# (user-сервис). Поднимаем его, если не запущен, и указываем на него DOCKER_HOST.
PODMAN_SOCK="/run/user/$(id -u)/podman/podman.sock"
if command -v podman >/dev/null 2>&1; then
  if [ ! -S "$PODMAN_SOCK" ] && command -v systemctl >/dev/null 2>&1; then
    echo "▶ Поднимаю podman.socket…"
    systemctl --user enable --now podman.socket >/dev/null 2>&1 || true
    sleep 1
  fi
  [ -S "$PODMAN_SOCK" ] && export DOCKER_HOST="unix://$PODMAN_SOCK"
fi

# Выбираем доступный compose-фронтенд.
if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif podman compose version >/dev/null 2>&1; then
  COMPOSE="podman compose"
else
  echo "Не найден ни 'docker compose', ни 'podman compose'." >&2
  exit 1
fi

# down / stop — прокинуть как есть.
if [ "${1:-}" = "down" ] || [ "${1:-}" = "stop" ]; then
  exec $COMPOSE "$@"
fi

JAR=$(ls SCADA-gateway/target/SCADA-gateway-*.jar 2>/dev/null | grep -vE 'sources|javadoc' | head -1 || true)
if [ -z "$JAR" ]; then
  echo "▶ jar шлюза не найден — собираю (mvn -DskipTests package)…"
  ( cd SCADA-gateway && mvn -q -DskipTests package )
else
  echo "▶ jar шлюза уже собран: $JAR"
fi

echo "▶ Поднимаю стек ($COMPOSE up -d --build)…"
$COMPOSE up -d --build

echo
echo "✅ Стек поднят. Порты на хост:"
echo "   gateway  http://localhost:8888/actuator/health"
echo "   kafka    localhost:9094   (топики: scada-telemetry / scada-events / scada-alarms)"
echo "   postgres localhost:5433   (scada_db / scada_user)"
echo
echo "   Логи шлюза:   $COMPOSE logs -f gateway"
echo "   Остановить:   ./up.sh down        (стереть БД: ./up.sh down -v)"

if [ "${1:-}" = "--logs" ]; then
  exec $COMPOSE logs -f gateway
fi
