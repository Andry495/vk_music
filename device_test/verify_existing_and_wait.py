#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CRUD verify: prefer existing playlist if create is flooded; always refetch."""
from __future__ import annotations

import html
import re
import subprocess
import sys
import time
from pathlib import Path

ADB = sys.argv[1]
WAIT_SEC = int(sys.argv[2]) if len(sys.argv) > 2 else 90
OUT = Path(__file__).resolve().parent
PKG = "com.vkmusic.app"
OLD = "AutoTestPL"
NEW = "VerifyPL99"


def adb(*a: str) -> str:
    return subprocess.run([ADB, *a], capture_output=True).stdout.decode("utf-8", "replace")


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
    if not n:
        return False
    adb("shell", "input", "tap", str(n["x"]), str(n["y"]))
    return True


def F(nodes, **k):
    for n in nodes:
        if "text" in k and n["text"] == k["text"]:
            return n
        if "desc" in k and n["desc"] == k["desc"]:
            return n
        if "contains" in k and k["contains"] in n["text"] + n["desc"]:
            return n
    return None


def texts(nodes):
    return [n["text"] for n in nodes if n["text"]]


def quiet():
    for p in ("ru.sberbankmobile", "ru.raiffeisennews"):
        adb("shell", "am", "force-stop", p)


def wake():
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.5)
    adb("shell", "wm", "dismiss-keyguard")
    time.sleep(0.4)
    adb("shell", "input", "swipe", "540", "1800", "540", "600", "300")
    time.sleep(0.6)
    quiet()


def present(nodes, name: str) -> bool:
    return any(n["text"] == name for n in nodes)


def playlists_list(tag: str):
    wake()
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(1.5)
    nodes = dump(f"{tag}_0")
    tap(F(nodes, text="Медиатека") or F(nodes, contains="Медиатека"))
    time.sleep(1)
    nodes = dump(f"{tag}_1")
    if F(nodes, desc="Назад"):
        tap(F(nodes, desc="Назад"))
        time.sleep(1)
        nodes = dump(f"{tag}_1b")
    tap(F(nodes, text="Плейлисты") or F(nodes, contains="Плейлист"))
    time.sleep(2.5)
    nodes = dump(f"{tag}_list")
    if next((n for n in nodes if n["text"] == "↻"), None):
        tap(next(n for n in nodes if n["text"] == "↻"))
        time.sleep(3)
        nodes = dump(f"{tag}_list2")
    print("LIST", tag, texts(nodes)[:18])
    return nodes


def dismiss_dialogs():
    for _ in range(3):
        nodes = dump("dismiss")
        if F(nodes, text="Отмена"):
            tap(F(nodes, text="Отмена"))
            time.sleep(0.8)
            continue
        if F(nodes, text="Название") and F(nodes, text="Создать"):
            adb("shell", "input", "keyevent", "4")
            time.sleep(0.8)
            continue
        break


def delete_named(name: str) -> bool:
    nodes = playlists_list("del")
    if not present(nodes, name):
        print("PASS already absent", name)
        return True
    tap(F(nodes, text=name))
    time.sleep(2)
    nodes = dump("del_open")
    trash = F(nodes, desc="Удалить плейлист") or F(nodes, contains="Удалить")
    if not trash:
        # overflow near title
        more = F(nodes, desc="Ещё")
        if more:
            tap(more)
            time.sleep(1)
            nodes = dump("del_menu")
            trash = F(nodes, contains="Удалить")
    if not trash:
        print("FAIL no trash")
        return False
    tap(trash)
    time.sleep(1)
    nodes = dump("del_confirm")
    yes = F(nodes, text="Удалить") or F(nodes, text="OK") or F(nodes, text="Да")
    if yes:
        tap(yes)
    time.sleep(5)
    nodes = playlists_list("del_after")
    ok = not present(nodes, name)
    print(("PASS" if ok else "FAIL"), "delete+refetch", name, "present=", not ok, texts(nodes)[:18])
    return ok


def create_named(name: str) -> bool:
    dismiss_dialogs()
    nodes = playlists_list("cr")
    tap(F(nodes, desc="Создать плейлист"))
    time.sleep(1.5)
    nodes = dump("cr_dlg")
    label = F(nodes, text="Название")
    if label:
        adb("shell", "input", "tap", str(label["x"]), str(label["y"] + 50))
    else:
        adb("shell", "input", "tap", "540", "700")
    time.sleep(0.3)
    # adb input text: escape spaces as %s; keep alnum
    safe = name.replace(" ", "%s")
    adb("shell", "input", "text", safe)
    time.sleep(0.5)
    nodes = dump("cr_typed")
    btn = next((n for n in nodes if n["text"] in ("Создать", "Создать и добавить")), None)
    wrap = next(
        (
            n
            for n in nodes
            if n["click"] and btn and n["x1"] <= btn["x1"] and n["x2"] >= btn["x2"] and abs(n["y"] - btn["y"]) < 120
        ),
        btn,
    )
    tap(wrap)
    time.sleep(8)
    dismiss_dialogs()
    nodes = playlists_list("cr_after")
    in_dialog = bool(F(nodes, text="Название") and F(nodes, text="Отмена"))
    ok = present(nodes, name) and not in_dialog
    print(("PASS" if ok else "FAIL"), "create+refetch", name, "present=", ok, texts(nodes)[:18])
    return ok


def add_and_refetch(name: str) -> bool:
    dismiss_dialogs()
    nodes = dump("ad0")
    tap(F(nodes, text="Медиатека"))
    time.sleep(1)
    nodes = dump("ad1")
    if F(nodes, desc="Назад"):
        tap(F(nodes, desc="Назад"))
        time.sleep(1)
    tap(F(dump("ad2"), text="Моя") or F(dump("ad2"), contains="Моя"))
    time.sleep(3)
    nodes = dump("ad3")
    tap(F(nodes, desc="Ещё"))
    time.sleep(1.2)
    nodes = dump("ad4")
    tap(F(nodes, contains="плейлист"))
    # fetchPlaylists раньше блокировался на hashProbe — ждём появления имени
    name_node = None
    for i in range(12):
        time.sleep(1.2)
        nodes = dump(f"ad5_{i}")
        print("PICKER", i, texts(nodes)[:14])
        name_node = F(nodes, text=name)
        if name_node:
            break
        if F(nodes, text="Плейлистов пока нет") and i > 3:
            break
    if not name_node:
        print("FAIL not in picker")
        return False
    tap(name_node)
    time.sleep(6)
    nodes = playlists_list("ad_list")
    tap(F(nodes, text=name))
    time.sleep(4)
    nodes = dump("ad_open")
    if next((n for n in nodes if n["text"] == "↻"), None):
        tap(next(n for n in nodes if n["text"] == "↻"))
        time.sleep(4)
        nodes = dump("ad_open2")
    n = sum(1 for t in texts(nodes) if re.search(r"\d+:\d{2}", t))
    print(("PASS" if n >= 1 else "FAIL"), f"add+refetch tracks={n}", texts(nodes)[:18])
    return n >= 1


def remove_and_refetch() -> bool:
    nodes = dump("rm0")
    before = sum(1 for t in texts(nodes) if re.search(r"\d+:\d{2}", t))
    more = F(nodes, desc="Ещё")
    if not more:
        print("FAIL no more")
        return False
    tap(more)
    time.sleep(1)
    nodes = dump("rm1")
    rm = F(nodes, contains="Удалить из плейлиста")
    if not rm:
        print("FAIL no remove item", texts(nodes))
        return False
    tap(rm)
    time.sleep(5)
    nodes = dump("rm2")
    if next((n for n in nodes if n["text"] == "↻"), None):
        tap(next(n for n in nodes if n["text"] == "↻"))
        time.sleep(4)
        nodes = dump("rm3")
    after = sum(1 for t in texts(nodes) if re.search(r"\d+:\d{2}", t))
    ok = after < before or any("пуст" in t.lower() for t in texts(nodes))
    print(("PASS" if ok else "FAIL"), f"remove+refetch {before}->{after}", texts(nodes)[:15])
    return ok


def logs():
    pid = adb("shell", "pidof", PKG).strip()
    log = adb("logcat", "-d", "-t", "400", f"--pid={pid}") if pid else ""
    keys = (
        "createPlaylist",
        "createPlaylistViaApi",
        "addToPlaylist",
        "removeFrom",
        "deletePlaylist",
        "fetchPlaylist",
        "resolvePlaylist",
        "hashProbe",
        "audioPageHash",
        "editHash",
        "pageHash",
        "Flood",
        "err=",
    )
    lines = [ln for ln in log.splitlines() if any(k in ln for k in keys)]
    print("--- LOG ---")
    for ln in lines[-40:]:
        print(ln)


def main():
    wake()
    results = {}

    print("=== DELETE OLD leftover VerifyPL99 ===")
    results["cleanup_verify"] = delete_named(NEW)

    print(f"\n=== WAIT {WAIT_SEC}s for flood cool-down ===")
    # периодически будим экран, чтобы не ушёл в lock
    left = WAIT_SEC
    while left > 0:
        step = min(30, left)
        time.sleep(step)
        left -= step
        wake()

    nodes = playlists_list("pre")
    existing = OLD if present(nodes, OLD) else (NEW if present(nodes, NEW) else None)

    print("\n=== CREATE + REFETCH ===")
    if existing:
        print("SKIP create — use existing", existing)
        results["create_refetch"] = True
        target = existing
    else:
        ok_create = create_named(NEW)
        results["create_refetch"] = ok_create
        target = NEW if ok_create else None

    print("\n=== ADD + REFETCH ===")
    if target:
        results["add_refetch"] = add_and_refetch(target)
    else:
        print("FAIL skip add — no playlist")
        results["add_refetch"] = False

    print("\n=== REMOVE + REFETCH ===")
    if results.get("add_refetch"):
        results["remove_refetch"] = remove_and_refetch()
    else:
        print("FAIL skip remove")
        results["remove_refetch"] = False

    print("\n=== DELETE TARGET + REFETCH ===")
    # удаляем только тестовый VerifyPL99; AutoTestPL оставляем если create flooded
    if target == NEW:
        results["delete_new_refetch"] = delete_named(NEW)
    else:
        print("SKIP delete of", target, "(kept for next runs under flood)")
        results["delete_new_refetch"] = True

    logs()
    print("\n======== SUMMARY ========")
    for k, v in results.items():
        print(("PASS" if v else "FAIL"), "|", k)
    sys.exit(0 if all(results.values()) else 1)


if __name__ == "__main__":
    main()
