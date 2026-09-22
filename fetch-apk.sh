#!/usr/bin/env bash
# Скачивает APK последнего зелёного билда в папку проекта.
# Держит только один актуальный файл: Mini-UNA.apk (старый заменяется).
set -euo pipefail
cd "$(dirname "$0")"

export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0=safe.directory
export GIT_CONFIG_VALUE_0='*'

RUN_ID=$(gh run list --repo MaxSC2/Mini-Una \
  --workflow "Build Mini-UNA APK" --status success --limit 1 \
  --json databaseId --jq '.[0].databaseId')

if [ -z "${RUN_ID:-}" ]; then
  echo "Нет успешных сборок."
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

gh run download "$RUN_ID" --repo MaxSC2/Mini-Una -n mini-una-release -D "$TMP"
APK="$(find "$TMP" -name '*.apk' | head -n 1)"
if [ -z "${APK:-}" ]; then
  echo "APK не найден в артефактах рана $RUN_ID."
  exit 1
fi

mv "$APK" ./Mini-UNA.apk
ls -la ./Mini-UNA.apk
echo "Готово: ран $RUN_ID -> Mini-UNA.apk"
