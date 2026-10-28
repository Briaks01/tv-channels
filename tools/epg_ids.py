#!/usr/bin/env python3
"""Проставляет каналам идентификатор телепрограммы iptvx (атрибут x-epg).

Программу приложение берёт с epg.iptvx.one/id/<id> само, по запросу, —
здесь только сопоставление: сначала tvg-id из исходного плейлиста (это и есть
id iptvx), если такого нет — совпадение названия с названиями каналов iptvx.
Правит app/src/main/assets/channels.m3u на месте; запускать после prepare.py.

Запуск: python3 -I tools/epg_ids.py sources/iptvru.m3u
"""
import html
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLAYLIST = os.path.join(ROOT, "app", "src", "main", "assets", "channels.m3u")
UA = "Mozilla/5.0"


def key(name):
    k = name.lower().replace("ё", "е")
    k = re.sub(r"\b(hd|fhd|uhd|4k|tv|тв|телеканал|канал)\b", "", k)
    return re.sub(r"[^\wа-я]+", "", k)


def iptvx_channels():
    """{id: название} из списка каналов на любой странице epg.iptvx.one."""
    page = subprocess.run(["curl", "-sL", "-m", "30", "-A", UA, "https://epg.iptvx.one/id/sts"],
                          capture_output=True).stdout.decode("utf-8", "ignore")
    return {i: html.unescape(n).strip()
            for i, n in re.findall(r'<option value="\.\./id/([^"]+)">([^<]+)</option>', page)}


def tvg_ids(path):
    """{ключ названия: tvg-id} из исходного плейлиста."""
    out = {}
    for line in open(path, encoding="utf-8"):
        if line.startswith("#EXTINF"):
            m = re.search(r'tvg-id="([^"]+)"', line)
            if m:
                out.setdefault(key(line.rsplit(",", 1)[-1]), m.group(1))
    return out


def main():
    ids = iptvx_channels()
    # без региональных сдвигов (+2, +4…) и пометок вроде [DE]
    by_name = {}
    for i, n in ids.items():
        if not re.search(r"\(\+\d+\)|\[[A-Z]{2}]", n):
            by_name.setdefault(key(n), i)
    tvg = {}
    for src in sys.argv[1:]:
        tvg.update({k: v for k, v in tvg_ids(src).items() if k not in tvg})

    lines = open(PLAYLIST, encoding="utf-8").read().splitlines()
    matched = total = 0
    for n, line in enumerate(lines):
        if not line.startswith("#EXTINF"):
            continue
        total += 1
        line = re.sub(r' x-epg="[^"]*"', "", line)
        k = key(line.rsplit(",", 1)[-1])
        epg = tvg.get(k) if tvg.get(k) in ids else by_name.get(k)
        if not epg:
            # «Nickelodeon Россия» в iptvx просто «Nickelodeon»
            epg = by_name.get(re.sub(r"(россия|russia|рус)$", "", k))
        if epg:
            matched += 1
            head, _, name = line.rpartition(",")
            line = f'{head} x-epg="{epg}",{name}'
        lines[n] = line
    with open(PLAYLIST, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"iptvx channels: {len(ids)}, matched: {matched}/{total}")


if __name__ == "__main__":
    main()
