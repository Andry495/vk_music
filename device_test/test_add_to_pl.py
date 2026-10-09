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
PKG = "com.vkmusic.app"


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
        if PKG not in n:
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
    return nodes


def tap(n):
    adb("shell", "input", "tap", str(n["x"]), str(n["y"]))


def find(nodes, **kw):
    for n in nodes:
        if "text" in kw and n["text"] == kw["text"]:
            return n
        if "desc" in kw and n["desc"] == kw["desc"]:
            return n
        if "contains" in kw and kw["contains"] in (n["text"] + n["desc"]):
            return n
    return None


def ensure_fg():
    for p in ("ru.sberbankmobile", "ru.raiffeisennews"):
        adb("shell", "am", "force-stop", p)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2)


def main():
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "input", "keyevent", "224")
    adb("logcat", "-c")
    ensure_fg()
    for _ in range(2):
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.3)
    ensure_fg()

    nodes = dump("E0")
    tap(find(nodes, text="Медиатека"))
    time.sleep(1)
    nodes = dump("E1")
    # create empty playlist from +
    tap(find(nodes, text="Плейлисты"))
    time.sleep(2)
    nodes = dump("E2")
    add = find(nodes, desc="Создать плейлист")
    tap(add)
    time.sleep(1)
    nodes = dump("E3")
    label = find(nodes, text="Название")
    adb("shell", "input", "tap", str(label["x"]), str(label["y"] + 50))
    time.sleep(0.3)
    adb("shell", "input", "text", "AddTrackPL")
    time.sleep(0.4)
    nodes = dump("E4")
    btn = next(n for n in nodes if n["text"] == "Создать")
    wrap = next(
        (
            n
            for n in nodes
            if n["click"] and n["x1"] <= btn["x1"] and n["x2"] >= btn["x2"] and abs(n["y"] - btn["y"]) < 100
        ),
        btn,
    )
    tap(wrap)
    time.sleep(5)

    # My music -> add track to AddTrackPL
    nodes = dump("E5")
    tap(find(nodes, text="Моя"))
    time.sleep(3)
    nodes = dump("E6")
    tap(find(nodes, desc="Ещё"))
    time.sleep(1)
    nodes = dump("E7")
    tap(find(nodes, contains="плейлист"))
    time.sleep(3)
    nodes = dump("E8")
    target = find(nodes, text="AddTrackPL")
    print("picker has AddTrackPL:", target is not None, "texts:", [n["text"] for n in nodes if n["text"]][:12])
    if not target:
        raise SystemExit("playlist missing in picker")
    tap(target)
    time.sleep(5)

    # open playlist
    nodes = dump("E9")
    tap(find(nodes, text="Плейлисты") or find(nodes, text="Медиатека"))
    time.sleep(1)
    nodes = dump("E10")
    if find(nodes, text="Плейлисты"):
        tap(find(nodes, text="Плейлисты"))
        time.sleep(3)
    nodes = dump("E11")
    tap(find(nodes, text="AddTrackPL"))
    time.sleep(4)
    nodes = dump("E12")
    texts = [n["text"] for n in nodes if n["text"]]
    print("OPEN PL:", " | ".join(texts)[:300])
    tracks = [t for t in texts if re.search(r"\d+:\d{2}", t)]
    print("TRACKS IN PL:", len(tracks), tracks[:3])

    # remove from playlist if present
    more = find(nodes, desc="Ещё")
    if more and tracks:
        tap(more)
        time.sleep(1)
        nodes = dump("E13")
        rm = find(nodes, contains="Удалить из плейлиста")
        print("remove menu:", rm)
        if rm:
            tap(rm)
            time.sleep(4)
            nodes = dump("E14")
            texts2 = [n["text"] for n in nodes if n["text"]]
            tracks2 = [t for t in texts2 if re.search(r"\d+:\d{2}", t)]
            print("AFTER REMOVE tracks:", len(tracks2))

    # delete playlist
    nodes = dump("E15")
    delete = find(nodes, desc="Удалить плейлист")
    if delete:
        tap(delete)
        time.sleep(1)
        nodes = dump("E16")
        confirm = find(nodes, text="Удалить")
        if confirm:
            tap(confirm)
            time.sleep(4)
    nodes = dump("E17")
    left = find(nodes, text="AddTrackPL")
    print("deleted:", left is None)

    app_pid = adb("shell", "pidof", PKG).strip()
    log = adb("logcat", "-d", "-t", "300", f"--pid={app_pid}")
    (OUT / "add_log.txt").write_text(log, encoding="utf-8")
    for line in log.splitlines():
        if any(k in line for k in ("addTo", "createPlaylist", "removeFrom", "deletePlaylist", "getPlaylists", "VkMusic")):
            print(line)


if __name__ == "__main__":
    main()
