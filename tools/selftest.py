#!/usr/bin/env python3
"""Самопроверка приложения на стике: гоняет сценарии через ADB и разбирает логи.

Нужна отладочная сборка (логи с тегом ST пишутся только в ней):
    ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug
    adb -s 192.168.1.107:5555 install -r app/build/outputs/apk/debug/app-debug.apk

Запуск: python3 -I tools/selftest.py [--quick] [--soak СЕКУНД]
--quick — без прогона всех федеральных каналов; --soak — сколько секунд
слушать один канал на предмет затыков звука (по умолчанию 120).
"""
import re
import subprocess
import sys
import time

DEVICE = "192.168.1.107:5555"
PKG = "ru.home.tvchannels"
ACT = f"{PKG}/.MainActivity"

results = []  # (сценарий, ok, подробности)


def adb(*args, timeout=60):
    r = subprocess.run(["adb", "-s", DEVICE, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "ignore")


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def keys(*names, gap=0.0):
    ensure_foreground()
    for n in names:
        sh(f"input keyevent KEYCODE_{n}")
        if gap:
            time.sleep(gap)


def longpress(name):
    ensure_foreground()
    sh(f"input keyevent --longpress KEYCODE_{name}")


def digits(n):
    ensure_foreground()
    sh("input keyevent " + " ".join(f"KEYCODE_{d}" for d in str(n)))


def clear_log():
    sh("logcat -c")


def st_events():
    """События ST: [(имя, {ключ: значение})]."""
    out = []
    for line in sh("logcat -d -s ST:I").splitlines():
        m = re.search(r" ST\s*: (\w+)(.*)$", line)
        if m:
            out.append((m.group(1), dict(re.findall(r"(\S+?)=(\S*)", m.group(2)))))
    return out


def ev(events, name):
    return [kv for n, kv in events if n == name]


def ui_texts():
    sh("uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1")
    return re.findall(r'text="([^"]+)"', sh("cat /data/local/tmp/ui.xml"))


def focused_text():
    sh("uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1")
    x = sh("cat /data/local/tmp/ui.xml")
    for m in re.finditer(r"<node ([^>]*)", x):
        if 'focused="true"' in m.group(1):
            return re.findall(r'text="([^"]+)"', x[m.start():m.start() + 2500])[:2]
    return []


def visible(view_id):
    return bool(re.search(rf"V\S* \S+ \S+ #\w+ app:id/{view_id}\b", sh("dumpsys activity top")))


def check(name, ok, details=""):
    results.append((name, ok, details))
    print(f"[{'OK ' if ok else 'BUG'}] {name}: {details}", flush=True)


def current_channel(events):
    starts = ev(events, "start")
    return int(starts[-1]["ch"].split(":")[0]) if starts else None


def fresh_start():
    sh(f"am force-stop {PKG}")
    clear_log()
    sh(f"am start -n {ACT}")
    time.sleep(10)


class NotOurScreen(Exception):
    pass


def ensure_foreground():
    """Останавливает тест, если на экране не приложение.

    Нажатия ADB уходят в любое окно на экране. Без проверки тест нажимает
    кнопки в системных настройках или в другом приложении.
    """
    top = sh("dumpsys activity activities | grep -m1 topResumedActivity")
    if PKG not in top:
        raise NotOurScreen(top.strip())


def close_overlays():
    for _ in range(2):
        if visible("menu") or visible("guide"):
            keys("BACK")
            time.sleep(0.6)


# ---------------- сценарии ----------------

def t_startup():
    fresh_start()
    e = st_events()
    ready = ev(e, "ready")
    check("Запуск: канал стартует", bool(ready),
          f"канал {current_channel(e)}, до первого кадра {ready[0]['ms']} мс" if ready else "нет события ready")
    check("Запуск: нет крэша", "FATAL" not in sh("logcat -d -b crash"))


def zap(direction, times, gap, label):
    close_overlays()
    clear_log()
    keys(*([direction] * times), gap=gap)
    time.sleep(4)
    e = st_events()
    moves = ev(e, "move")
    autoskips = ev(e, "autoskip")
    lags = [int(k["lag"]) for k in ev(e, "key") if k.get("in") == "tv"]
    plays = [int(t["ms"]) for t in ev(e, "timing") if t["what"] == "play"]
    jank = [int(j["ms"]) for j in ev(e, "jank")]
    user_moves = len(moves) - len(autoskips)
    check(f"{label}: {times} нажатий = {times} переключений", user_moves == times and not autoskips,
          f"move={len(moves)} autoskip={len(autoskips)} lag={lags} play_ms={plays} jank={jank}")
    return e


def t_zapping():
    zap("DPAD_RIGHT", 1, 0, "Вправо один раз")
    zap("DPAD_LEFT", 1, 0, "Влево один раз")
    zap("DPAD_RIGHT", 5, 0.35, "Вправо 5 раз подряд")
    zap("DPAD_LEFT", 5, 0.35, "Влево 5 раз подряд")
    zap("DPAD_RIGHT", 4, 0.05, "Вправо 4 раза очень быстро")
    # придержанная кнопка: пульт шлёт автоповтор — это должно быть одно переключение
    close_overlays()
    clear_log()
    longpress("DPAD_RIGHT")
    time.sleep(3)
    e = st_events()
    check("Придержанное «вправо» = 1 переключение", len(ev(e, "move")) == 1,
          f"move={len(ev(e, 'move'))} repeats={[k['repeat'] for k in ev(e, 'key')]}")


def t_digits():
    close_overlays()
    clear_log()
    digits(13)
    time.sleep(4)
    e = st_events()
    check("Цифры 1-3 → канал 13", current_channel(e) == 13, f"сейчас {current_channel(e)}")
    clear_log()
    digits(13)
    time.sleep(3)
    check("Повторный набор того же номера не перезапускает", not ev(st_events(), "start"))


def t_menu():
    close_overlays()
    keys("DPAD_CENTER")
    time.sleep(1.5)
    check("OK открывает меню", visible("menu"))
    f = focused_text()
    check("Фокус в меню на текущем канале", bool(f), f"фокус: {f}")
    keys("DPAD_UP")
    time.sleep(0.6)
    seen = [tuple(focused_text())]
    for _ in range(6):
        keys("DPAD_RIGHT")
        time.sleep(0.6)
        seen.append(tuple(focused_text()))
    distinct = len(set(seen))
    check("Вкладки листаются по одной", distinct == len(seen) and all(seen), f"{[s[0] if s else None for s in seen]}")
    keys("DPAD_DOWN")
    time.sleep(0.8)
    check("Из вкладок вниз — в сетку", bool(focused_text()), f"фокус: {focused_text()}")
    clear_log()
    keys("DPAD_CENTER")
    time.sleep(2)
    check("Выбор канала в меню закрывает меню и включает канал", not visible("menu") and bool(ev(st_events(), "start")))
    # тот же канал ещё раз — без перезапуска
    keys("DPAD_CENTER")
    time.sleep(1.5)
    clear_log()
    keys("DPAD_CENTER")
    time.sleep(2)
    check("Выбор уже идущего канала не перезапускает поток", not ev(st_events(), "start"))
    keys("DPAD_CENTER")
    time.sleep(1.2)
    longpress("DPAD_CENTER")
    time.sleep(1)
    texts = ui_texts()
    check("Долгое OK — избранное", any("★" == t for t in texts), "звёздочка есть" if "★" in texts else "звёздочки нет")
    longpress("DPAD_CENTER")  # вернуть как было
    time.sleep(0.8)
    keys("BACK")
    time.sleep(0.8)
    check("Назад закрывает меню", not visible("menu"))


def t_recent():
    close_overlays()
    keys("DPAD_DOWN")
    time.sleep(1.5)
    texts = ui_texts()
    check("Вниз открывает «Недавние»", visible("menu") and "Недавние" in texts, f"фокус: {focused_text()}")
    keys("BACK")
    time.sleep(0.6)


def t_guide():
    close_overlays()
    digits(13)  # у СТС программа с Mail.ru точно есть
    time.sleep(4)
    clear_log()
    keys("DPAD_UP")
    time.sleep(6)
    texts = ui_texts()
    has = any(re.match(r"\d\d:\d\d$", t) for t in texts)
    status = [t for t in texts if "телепрограмм" in t.lower()]
    check("Вверх открывает телепрограмму", visible("guide"), f"передачи: {has}, статус: {status}")
    ys = []
    for _ in range(4):
        keys("DPAD_DOWN")
        time.sleep(0.7)
        sh("uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1")
        x = sh("cat /data/local/tmp/ui.xml")
        m = re.search(r'focused="true"[^>]*bounds="\[\d+,(\d+)\]\[\d+,(\d+)\]"', x)
        ys.append((int(m.group(1)) + int(m.group(2))) // 2 if m else None)
    check("Программа: выбранная передача по центру", has and len(set(ys)) == 1 and ys[0] is not None, f"y={ys}")
    keys("DPAD_RIGHT")
    time.sleep(5)
    t2 = ui_texts()
    check("Программа: ▶ — следующий канал", t2[1:2] != texts[1:2], f"{texts[1:2]} → {t2[1:2]}")
    clear_log()
    keys("DPAD_CENTER")
    time.sleep(3)
    check("Программа: OK включает канал", not visible("guide") and bool(ev(st_events(), "start")))
    # канал без Mail.ru: программа с iptvx не должна висеть на «Загружаю…»
    close_overlays()
    digits(204)
    time.sleep(4)
    for attempt in (1, 2):
        keys("DPAD_UP")
        t0 = time.time()
        status = []
        while time.time() - t0 < 15:
            status = [t for t in ui_texts() if "телепрограмм" in t.lower()]
            if not any("Загружаю" in t for t in status):
                break
            time.sleep(0.5)
        check(f"Программа без Mail.ru, попытка {attempt}: не висит", time.time() - t0 < 9,
              f"{time.time() - t0:.1f} с → {status}")
        keys("BACK")
        time.sleep(0.6)


def t_settings():
    close_overlays()
    keys("DPAD_CENTER")
    time.sleep(1.2)
    keys("DPAD_UP")
    time.sleep(0.5)
    for _ in range(20):
        keys("DPAD_RIGHT")
    time.sleep(0.5)
    keys("DPAD_CENTER")
    time.sleep(2)
    top = sh("dumpsys activity activities | grep -m1 topResumedActivity")
    check("Настройки открываются", "SettingsActivity" in top)
    keys("BACK")
    time.sleep(4)
    top = sh("dumpsys activity activities | grep -m1 topResumedActivity")
    check("Из настроек обратно к просмотру", "MainActivity" in top and bool(ev(st_events(), "ready")))


def t_channels(numbers):
    close_overlays()
    for n in numbers:
        clear_log()
        digits(n)
        time.sleep(15)
        e = st_events()
        ready = ev(e, "ready")
        video = ev(e, "video")
        bad = ev(e, "error") + ev(e, "watchdog") + ev(e, "failed")
        res = video[-1]["res"] if video else "?"
        check(f"Канал {n} {e and ev(e, 'start') and ev(e, 'start')[-1]['ch']}",
              bool(ready), f"{ready[0]['ms'] if ready else '—'} мс, {res}, сбоев {len(bad)}, "
              f"underrun {len(ev(e, 'underrun'))}, rebuffer {len(ev(e, 'rebuffer'))}")


def t_soak(seconds, channel):
    close_overlays()
    digits(1)  # уходим с канала, чтобы старт был с нуля
    time.sleep(4)
    clear_log()
    digits(channel)
    time.sleep(seconds)
    e = st_events()
    heights = [int(v["res"].split("x")[1]) for v in ev(e, "video") if "x" in v.get("res", "")]
    check(f"Качество {seconds} с на канале {channel}: не опускается",
          bool(heights) and min(heights) == heights[0],
          f"разрешения по ходу: {[v['res'] for v in ev(e, 'video')]}")
    under = ev(e, "underrun")
    reb = ev(e, "rebuffer")
    bw = [int(b["kbps"]) for b in ev(e, "bandwidth")]
    vid = ev(e, "video")
    jank = [int(j["ms"]) for j in ev(e, "jank")]
    check(f"Звук {seconds} с на канале {channel}: без недогрузов и подгрузок",
          not under and not reb,
          f"underrun={len(under)} rebuffer={[r['ms'] for r in reb]} сеть кбит/с min/avg="
          f"{min(bw) if bw else '?'}/{sum(bw)//len(bw) if bw else '?'} видео={[v['kbps'] for v in vid]} jank={jank[:8]}")


def main():
    quick = "--quick" in sys.argv
    soak = int(sys.argv[sys.argv.index("--soak") + 1]) if "--soak" in sys.argv else 120
    adb("connect", DEVICE)
    if "-debug" not in sh(f"dumpsys package {PKG} | grep -m1 versionName"):
        sys.exit("нужна отладочная сборка (versionName …-debug)")
    scenarios = [t_startup, t_zapping, t_digits, t_menu, t_recent, t_guide, t_settings]
    if not quick:
        scenarios.append(lambda: t_channels(range(1, 21)))
    scenarios.append(lambda: t_soak(soak, 13))
    for scenario in scenarios:
        try:
            if scenario is not t_startup:
                ensure_foreground()
            scenario()
        except NotOurScreen as e:
            check("Приложение на экране", False, f"тест остановлен, на экране: {e}")
            break
    check("За весь прогон нет крэша", "FATAL" not in sh("logcat -d -b crash"))
    bugs = [r for r in results if not r[1]]
    print(f"\nИтого: {len(results)} проверок, проблем: {len(bugs)}")
    for name, _, d in bugs:
        print(f"  BUG {name}: {d}")


if __name__ == "__main__":
    main()
