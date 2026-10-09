#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка плейлистов с перезапросом состояния после каждой операции.
"""
from __future__ import annotations

import html
import re
import subprocess
import sys
import time
from pathlib import Path

ADB = sys.argv[1]
OUT = Path(__file__).resolve().parent
PKG = "com.vkmusic.app"
PL_NAME = "VerifyPL99"


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
    (OUT / f"{name}.txt").write_text(
        "\n".join(
            f"{n['text']!r}/{n['desc']!r} @({n['x']},{n['y']})"
            for n in nodes
            if n["text"] or n["desc"]
        ),
        encoding="utf-8",
    )
    return nodes


def tap(n) -> bool:
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
        if "contains" in k and k["contains"] in (n["text"] + n["desc"]):
            return n
    return None


def texts(nodes):
    return [n["text"] for n in nodes if n["text"]]


def quiet_noise():
    for p in ("ru.sberbankmobile", "ru.raiffeisennews", "ru.vtb24.mobilebanking.android"):
        adb("shell", "am", "force-stop", p)
    adb("shell", "cmd", "statusbar", "collapse")


def ensure_app():
    quiet_noise()
    adb("shell", "input", "keyevent", "224")
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2)
    # back out of dialogs
    for _ in range(2):
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.3)
    quiet_noise()
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2)


def go_library_playlists():
    nodes = dump("V_lib")
    tap(F(nodes, text="Медиатека"))
    time.sleep(1)
    nodes = dump("V_lib2")
    # if inside playlist, go back
    back = F(nodes, desc="Назад")
    if back:
        tap(back)
        time.sleep(1)
        nodes = dump("V_lib3")
    tap(F(nodes, text="Плейлисты") or F(nodes, contains="Плейлист"))
    time.sleep(3)
    return dump("V_pl_list")


def reload_playlists():
    """Нажать ↻ и снова прочитать список."""
    nodes = dump("V_before_reload")
    reload = next((n for n in nodes if n["text"] == "↻"), None)
    if reload:
        tap(reload)
        time.sleep(4)
    else:
        # re-tap chip
        tap(F(nodes, text="Плейлисты"))
        time.sleep(3)
    return dump("V_after_reload")


def playlist_present(nodes, name: str) -> bool:
    return any(n["text"] == name for n in nodes)


def open_playlist(name: str):
    nodes = go_library_playlists()
    pl = F(nodes, text=name)
    if not pl:
        nodes = reload_playlists()
        pl = F(nodes, text=name)
    if not pl:
        return None
    tap(pl)
    time.sleep(4)
    return dump("V_open")


def track_count(nodes) -> int:
    return sum(1 for t in texts(nodes) if re.search(r"\d+:\d{2}", t))


def create_playlist(name: str) -> bool:
    nodes = go_library_playlists()
    add = F(nodes, desc="Создать плейлист")
    if not add:
        print("FAIL no create button", texts(nodes)[:15])
        return False
    tap(add)
    time.sleep(1.5)
    nodes = dump("V_create_dlg")
    label = F(nodes, text="Название")
    if label:
        adb("shell", "input", "tap", str(label["x"]), str(label["y"] + 50))
    else:
        adb("shell", "input", "tap", "540", "700")
    time.sleep(0.4)
    adb("shell", "input", "text", name)
    time.sleep(0.5)
    nodes = dump("V_create_typed")
    btn = next((n for n in nodes if n["text"] in ("Создать", "Создать и добавить")), None)
    if not btn:
        print("FAIL no create confirm", texts(nodes))
        return False
    wrap = next(
        (
            n
            for n in nodes
            if n["click"]
            and n["x1"] <= btn["x1"]
            and n["x2"] >= btn["x2"]
            and abs(n["y"] - btn["y"]) < 120
        ),
        btn,
    )
    tap(wrap)
    time.sleep(6)
    # ПЕРЕЗАПРОС списка
    nodes = go_library_playlists()
    nodes = reload_playlists()
    ok = playlist_present(nodes, name)
    print(("PASS" if ok else "FAIL"), "create+refetch present=", ok, "list=", [t for t in texts(nodes) if t][:20])
    return ok


def add_track_from_my_music(pl_name: str) -> bool:
    nodes = dump("V_add0")
    tap(F(nodes, text="Медиатека"))
    time.sleep(1)
    nodes = dump("V_add1")
    back = F(nodes, desc="Назад")
    if back:
        tap(back)
        time.sleep(1)
    nodes = dump("V_add2")
    tap(F(nodes, text="Моя"))
    time.sleep(3)
    nodes = dump("V_add3")
    more = F(nodes, desc="Ещё")
    if not more:
        print("FAIL no overflow")
        return False
    tap(more)
    time.sleep(1.2)
    nodes = dump("V_add4")
    item = F(nodes, contains="плейлист")
    if not item:
        print("FAIL no playlist menu", texts(nodes))
        return False
    tap(item)
    time.sleep(3)
    nodes = dump("V_picker")
    print("picker:", texts(nodes)[:15])
    pl = F(nodes, text=pl_name)
    if not pl:
        print("FAIL playlist not in picker")
        return False
    tap(pl)
    time.sleep(6)
    # ПЕРЕЗАПРОС содержимого плейлиста
    opened = open_playlist(pl_name)
    if opened is None:
        print("FAIL cannot open after add")
        return False
    # reload inside
    reload = next((n for n in opened if n["text"] == "↻"), None)
    if reload:
        tap(reload)
        time.sleep(4)
        opened = dump("V_open_reload")
    ntracks = track_count(opened)
    empty = any("пуст" in t.lower() for t in texts(opened))
    print(
        ("PASS" if ntracks >= 1 else "FAIL"),
        f"add+refetch tracks={ntracks} empty_label={empty}",
        texts(opened)[:20],
    )
    return ntracks >= 1


def remove_track_from_open_playlist() -> bool:
    nodes = dump("V_rm0")
    more = F(nodes, desc="Ещё")
    if not more:
        print("FAIL no track menu for remove")
        return False
    before = track_count(nodes)
    tap(more)
    time.sleep(1.2)
    nodes = dump("V_rm1")
    rm = F(nodes, contains="Удалить из плейлиста")
    if not rm:
        print("FAIL no remove-from-playlist item", texts(nodes))
        adb("shell", "input", "keyevent", "4")
        return False
    tap(rm)
    time.sleep(5)
    nodes = dump("V_rm2")
    reload = next((n for n in nodes if n["text"] == "↻"), None)
    if reload:
        tap(reload)
        time.sleep(4)
        nodes = dump("V_rm3")
    after = track_count(nodes)
    ok = after < before or any("пуст" in t.lower() for t in texts(nodes))
    print(("PASS" if ok else "FAIL"), f"remove+refetch before={before} after={after}", texts(nodes)[:15])
    return ok


def delete_playlist(name: str) -> bool:
    nodes = dump("V_del0")
    # if open, use trash; else list menu
    trash = F(nodes, desc="Удалить плейлист")
    if trash:
        tap(trash)
    else:
        nodes = go_library_playlists()
        # open then trash, or list overflow near playlist
        pl = F(nodes, text=name)
        if pl:
            # try nearby more - open first
            tap(pl)
            time.sleep(3)
            nodes = dump("V_del1")
            trash = F(nodes, desc="Удалить плейлист")
            if trash:
                tap(trash)
            else:
                print("FAIL no delete icon")
                return False
        else:
            print("FAIL playlist gone before delete?")
            return True
    time.sleep(1.2)
    nodes = dump("V_del_confirm")
    confirm = F(nodes, text="Удалить")
    if not confirm:
        print("FAIL no confirm", texts(nodes))
        return False
    tap(confirm)
    time.sleep(5)
    # ПЕРЕЗАПРОС списка
    nodes = go_library_playlists()
    nodes = reload_playlists()
    present = playlist_present(nodes, name)
    print(("PASS" if not present else "FAIL"), "delete+refetch present=", present, texts(nodes)[:20])
    return not present


def grab_logs():
    pid = adb("shell", "pidof", PKG).strip()
    log = adb("logcat", "-d", "-t", "400", f"--pid={pid}") if pid else ""
    (OUT / "verify_crud_log.txt").write_text(log, encoding="utf-8")
    interesting = [
        ln
        for ln in log.splitlines()
        if any(
            k in ln
            for k in (
                "createPlaylist",
                "addToPlaylist",
                "removeFrom",
                "deletePlaylist",
                "getPlaylists",
                "fetchPlaylist",
                "playlist ",
                "err=",
                "enriched",
            )
        )
    ]
    (OUT / "verify_crud_log_filtered.txt").write_text("\n".join(interesting), encoding="utf-8")
    print("--- LOG ---")
    for ln in interesting[-50:]:
        print(ln)


def main():
    report = []
    ensure_app()
    adb("logcat", "-c")

    # cleanup leftover
    nodes = go_library_playlists()
    if playlist_present(nodes, PL_NAME):
        print("cleanup old", PL_NAME)
        open_playlist(PL_NAME)
        delete_playlist(PL_NAME)
        time.sleep(3)

    print("\n=== 1 CREATE ===")
    ok_create = create_playlist(PL_NAME)
    report.append(("create_refetch", ok_create))
    time.sleep(2)

    print("\n=== 2 ADD TRACK + REFETCH ===")
    ok_add = False
    if ok_create:
        ok_add = add_track_from_my_music(PL_NAME)
    else:
        print("SKIP add — create failed")
    report.append(("add_refetch", ok_add))
    time.sleep(2)

    print("\n=== 3 REMOVE TRACK + REFETCH ===")
    ok_rm = False
    if ok_add:
        ok_rm = remove_track_from_open_playlist()
    else:
        print("SKIP remove — add failed / empty")
    report.append(("remove_refetch", ok_rm))
    time.sleep(2)

    print("\n=== 4 DELETE + REFETCH ===")
    # ensure we can delete even if add failed
    nodes = dump("V_predel")
    if F(nodes, desc="Назад") or F(nodes, text=PL_NAME) or playlist_present(go_library_playlists(), PL_NAME):
        ok_del = delete_playlist(PL_NAME)
    else:
        ok_del = True
        print("PASS delete already absent")
    report.append(("delete_refetch", ok_del))

    grab_logs()
    print("\n======== SUMMARY ========")
    for name, ok in report:
        print(f"{'PASS' if ok else 'FAIL'} | {name}")
    (OUT / "VERIFY_CRUD_REPORT.txt").write_text(
        "\n".join(f"{'PASS' if ok else 'FAIL'} | {name}" for name, ok in report),
        encoding="utf-8",
    )
    return 0 if all(ok for _, ok in report) else 1


if __name__ == "__main__":
    raise SystemExit(main())
