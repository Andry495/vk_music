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
    return subprocess.run([ADB, *a], capture_output=True).stdout.decode("utf-8", "replace")


def dump(name: str = "t"):
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
            }
        )
    return nodes


def tap(n):
    adb("shell", "input", "tap", str(n["x"]), str(n["y"]))


def F(nodes, **k):
    for n in nodes:
        if "text" in k and n["text"] == k["text"]:
            return n
        if "desc" in k and n["desc"] == k["desc"]:
            return n
        if "contains" in k and k["contains"] in n["text"] + n["desc"]:
            return n
    return None


def main():
    for p in ("ru.sberbankmobile", "ru.raiffeisennews"):
        adb("shell", "am", "force-stop", p)
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "input", "keyevent", "224")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2)
    adb("logcat", "-c")

    nodes = dump("F0")
    tap(F(nodes, text="Медиатека"))
    time.sleep(1)
    nodes = dump("F1")
    tap(F(nodes, text="Моя"))
    time.sleep(2)
    nodes = dump("F2")
    tap(F(nodes, desc="Ещё"))
    time.sleep(1)
    nodes = dump("F3")
    tap(F(nodes, contains="плейлист"))
    time.sleep(3)
    nodes = dump("F4")
    print("PICKER", [n["text"] for n in nodes if n["text"]])
    pl = F(nodes, text="AutoTestPL") or next(
        (
            n
            for n in nodes
            if n["text"]
            and "Добавить" not in n["text"]
            and n["text"] != "Создать плейлист"
            and n["text"] != "Плейлистов пока нет"
        ),
        None,
    )
    print("CHOOSE", pl)
    if not pl:
        raise SystemExit("no playlist in picker")
    tap(pl)
    time.sleep(5)

    nodes = dump("F5")
    tap(F(nodes, text="Плейлисты"))
    time.sleep(3)
    nodes = dump("F6")
    print("LIST", [n["text"] for n in nodes if n["text"]])
    pl = F(nodes, text=pl["text"])
    if pl:
        tap(pl)
        time.sleep(4)
    nodes = dump("F7")
    texts = [n["text"] for n in nodes if n["text"]]
    print("OPEN", texts)
    has = any(re.search(r"\d+:\d{2}", t) for t in texts)
    print("has_tracks", has)

    pid = adb("shell", "pidof", PKG).strip()
    log = adb("logcat", "-d", "-t", "250", f"--pid={pid}")
    (OUT / "add_existing_log.txt").write_text(log, encoding="utf-8")
    for line in log.splitlines():
        if any(
            k in line
            for k in (
                "addTo",
                "createPlaylist",
                "getPlaylists",
                "deletePlaylist",
                "err=",
                "api ",
                "VkMusic",
            )
        ):
            print(line)

    # cleanup delete
    delete = F(nodes, desc="Удалить плейлист")
    if delete:
        tap(delete)
        time.sleep(1)
        nodes = dump("F8")
        confirm = F(nodes, text="Удалить")
        if confirm:
            tap(confirm)
            time.sleep(3)
            print("cleanup delete tapped")


if __name__ == "__main__":
    main()
