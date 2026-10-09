#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import html
import re
import subprocess
import sys
import time
from pathlib import Path

ADB = sys.argv[1]
OUT = Path(__file__).resolve().parent


def adb(*a: str) -> str:
    r = subprocess.run([ADB, *a], capture_output=True)
    return (r.stdout or b"").decode("utf-8", "replace")


def dump(name: str):
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    adb("pull", "/sdcard/ui.xml", str(OUT / f"{name}.xml"))
    raw = (OUT / f"{name}.xml").read_text(encoding="utf-8")
    nodes = []
    for m in re.finditer(r"<node[^>]+>", raw):
        n = m.group(0)
        if "com.vkmusic.app" not in n:
            continue
        tm = re.search(r'text="([^"]*)"', n)
        dm = re.search(r'content-desc="([^"]*)"', n)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not bm:
            continue
        x1, y1, x2, y2 = map(int, bm.groups())
        nodes.append(
            {
                "text": html.unescape(tm.group(1)) if tm else "",
                "desc": html.unescape(dm.group(1)) if dm else "",
                "x": (x1 + x2) // 2,
                "y": (y1 + y2) // 2,
                "x1": x1,
                "y1": y1,
                "x2": x2,
                "y2": y2,
                "click": 'clickable="true"' in n,
            }
        )
    (OUT / f"{name}.txt").write_text(
        "\n".join(
            f"c={int(n['click'])} [{n['x1']},{n['y1']}][{n['x2']},{n['y2']}] {n['text']!r}/{n['desc']!r}"
            for n in nodes
            if n["text"] or n["desc"]
        ),
        encoding="utf-8",
    )
    return nodes


def tap(n):
    adb("shell", "input", "tap", str(n["x"]), str(n["y"]))


def main():
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "input", "keyevent", "224")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", "com.vkmusic.app/.MainActivity")
    time.sleep(2)
    for _ in range(3):
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.3)

    nodes = dump("D0")
    lib = next((n for n in nodes if n["text"] == "Медиатека" and n["y"] > 2000), None)
    if not lib:
        lib = next((n for n in nodes if n["text"] == "Медиатека"), None)
    print("lib", lib)
    tap(lib)
    time.sleep(1)

    nodes = dump("D1")
    tap(next(n for n in nodes if n["text"] == "Моя"))
    time.sleep(3)

    nodes = dump("D2")
    tap(next(n for n in nodes if n["desc"] == "Ещё"))
    time.sleep(1.2)

    nodes = dump("D3")
    tap(next(n for n in nodes if "плейлист" in n["text"].lower()))
    time.sleep(2.5)

    nodes = dump("D4")
    tap(next(n for n in nodes if "Создать плейлист" in n["text"]))
    time.sleep(1.5)

    nodes = dump("D5")
    label = next((n for n in nodes if n["text"] == "Название"), None)
    if label:
        adb("shell", "input", "tap", str(label["x"]), str(label["y"] + 50))
    else:
        adb("shell", "input", "tap", "540", "700")
    time.sleep(0.4)
    adb("shell", "input", "text", "AutoTestPL")
    time.sleep(0.6)

    nodes = dump("D6")
    btn_text = next(n for n in nodes if "Создать" in n["text"] and "Отмена" not in n["text"])
    clickable = next(
        (
            n
            for n in nodes
            if n["click"]
            and n["x1"] <= btn_text["x1"]
            and n["x2"] >= btn_text["x2"]
            and abs(n["y"] - btn_text["y"]) < 100
        ),
        btn_text,
    )
    print("create tap", clickable)
    tap(clickable)
    time.sleep(7)

    nodes = dump("D7")
    print("AFTER:", " | ".join(n["text"] for n in nodes if n["text"])[:400])

    pls = next((n for n in nodes if n["text"] == "Плейлисты"), None)
    if pls:
        tap(pls)
        time.sleep(4)
    nodes = dump("D8")
    print("PLAYLISTS:", " | ".join(n["text"] for n in nodes if n["text"])[:400])

    app_pid = adb("shell", "pidof", "com.vkmusic.app").strip()
    print("app_pid", app_pid)
    log = adb("logcat", "-d", "-t", "400", f"--pid={app_pid}", "*:V") if app_pid else ""
    (OUT / "create_log.txt").write_text(log, encoding="utf-8")
    interesting = [
        line
        for line in log.splitlines()
        if any(
            k in line
            for k in (
                "VkMusic",
                "createPlaylist",
                "fetchPlaylist",
                "addTo",
                "save_playlist",
                "Не удалось",
                "AndroidRuntime",
                "Exception",
            )
        )
    ]
    (OUT / "create_log_filtered.txt").write_text("\n".join(interesting), encoding="utf-8")
    print("LOG LINES", len(interesting))
    for line in interesting[-40:]:
        print(line)


if __name__ == "__main__":
    main()
