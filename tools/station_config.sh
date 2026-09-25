#!/usr/bin/env bash
# ============================================================================
# controllers.yaml РЕАЛЬНОЙ станции из её базы каналов (.cdbx) и проекта ПЛК ptusa.
#
# Имена каналов (Kafka-key) строит код импорта монитора — исходники берутся из соседнего
# репозитория scada-editor-backend и компилируются вместе с генератором, поэтому ключи
# совпадают с объектной базой каналов монитора. Все каналы — PAC (driver-master); адрес
# контроллера подставляется при развёртывании: PAC_HOST (в публичный репозиторий не пишется).
#
#   tools/station_config.sh <база.cdbx> <папка проекта ПЛК> [площадка] [проект] [выход]
#     площадка/проект — как при импорте .cdbx в монитор (по умолчанию Барановичи-1 / BN1_MCA1)
#     выход — по умолчанию config/stations/<проект>.yaml
#   MONITOR_REPO=… — путь к scada-editor-backend (по умолчанию ../scada-editor-backend)
#   tools/station_config.sh --derive-rules <папка проекта ПЛК>   # пересобрать writable-rules.tsv
#
# Проверка против настоящей прошивки: tools/check_station_config.py (эмулятор ptusa).
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

MONITOR_REPO="${MONITOR_REPO:-../scada-editor-backend}"
IMPORTER="$MONITOR_REPO/channel/src/main/java/com/example/channel/importer"
[ -d "$IMPORTER" ] || { echo "Нет исходников импорта монитора: $IMPORTER (задайте MONITOR_REPO)" >&2; exit 1; }

BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
javac -nowarn -encoding UTF-8 -d "$BUILD" \
  "$IMPORTER"/{CdbxChannel,CdbxFile,CdbxParser,PlcProject,PlcProjectParser,ObjectPathMapper,DataTypeResolver}.java \
  tools/station-config/StationConfig.java tools/station-config/DeriveRules.java

RULES=tools/station-config/writable-rules.tsv
if [ "${1:-}" = "--derive-rules" ]; then
  java -cp "$BUILD" DeriveRules SCADA-gateway/src/main/resources/controllers.yaml "$2" "$RULES"
  exit 0
fi

CDBX="$1"; PROJECT_DIR="$2"
SITE="${3:-Барановичи-1}"; PROJECT="${4:-BN1_MCA1}"
OUT="${5:-config/stations/$PROJECT.yaml}"
mkdir -p "$(dirname "$OUT")"
java -cp "$BUILD" StationConfig "$CDBX" "$PROJECT_DIR" "$SITE" "$PROJECT" \
  "pac-$(echo "$PROJECT" | tr '[:upper:]_' '[:lower:]-')" 'pac://${PAC_HOST}:${PAC_PORT:10000}' "$RULES" "$OUT"
