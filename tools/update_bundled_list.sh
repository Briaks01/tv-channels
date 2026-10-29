#!/usr/bin/env bash
# Обновляет список каналов в APK из репозитория tv-channels-list.
#
# Приложение скачивает свежий список с GitHub при каждом запуске.
# Список в APK нужен, только если GitHub не отвечает.
# Запускайте скрипт перед релизной сборкой.
set -euo pipefail

REPO="https://github.com/Briaks01/tv-channels-list.git"
ASSETS="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

git clone -q --depth 1 "$REPO" "$TMP/list"
cp "$TMP/list/channels.m3u" "$ASSETS/channels.m3u"
rm -rf "$ASSETS/logos"
cp -r "$TMP/list/logos" "$ASSETS/logos"

echo "Каналов в APK: $(grep -c '^#EXTINF' "$ASSETS/channels.m3u")"
