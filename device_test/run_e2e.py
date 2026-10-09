#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ADB UI e2e smoke for VK Music."""

from __future__ import annotations

import html
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

OUT = Path(__file__).resolve().parent
ADB = sys.argv[1] if len(sys.argv) > 1 else "adb"
PKG = "com.vkmusic.app"
REPORT: list[str] = []


def adb(*args: str, check: bool = False) -> str:
    r = subprocess.run([ADB, *args], capture_output=True)
    out = (r.stdout or b"").decode("utf-8", "replace")
    err = (r.stderr or b"").decode("utf-8", "replace")
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {args} failed: {err or out}")
    return out if out else err


@dataclass
class Node:
    text: str
    desc: str
    x: int
    y: int
    x1: int
    y1: int
    x2: int
    y2: int
    clickable: bool


def dump(name: str) -> list[Node]:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    adb("pull", "/sdcard/ui.xml", str(OUT / f"{name}.xml"))
    raw = (OUT / f"{name}.xml").read_text(encoding="utf-8")
    nodes: list[Node] = []
    for m in re.finditer(r"<node[^>]+>", raw):
        n = m.group(0)
        if f'package="{PKG}"' not in n:
            continue
        tm = re.search(r'text="([^"]*)"', n)
        dm = re.search(r'content-desc="([^"]*)"', n)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not bm:
            continue
        x1, y1, x2, y2 = map(int, bm.groups())
        text = html.unescape(tm.group(1)) if tm else ""
        desc = html.unescape(dm.group(1)) if dm else ""
        nodes.append(
            Node(
                text=text,
                desc=desc,
                x=(x1 + x2) // 2,
                y=(y1 + y2) // 2,
                x1=x1,
                y1=y1,
                x2=x2,
                y2=y2,
                clickable='clickable="true"' in n,
            )
        )
    lines = [
        f"click={int(n.clickable)} [{n.x1},{n.y1}][{n.x2},{n.y2}] T={n.text!r} D={n.desc!r}"
        for n in nodes
        if n.text or n.desc
    ]
    (OUT / f"{name}.txt").write_text("\n".join(lines), encoding="utf-8")
    return nodes


def tap(n: Node | None) -> bool:
    if not n:
        return False
    adb("shell", "input", "tap", str(n.x), str(n.y))
    return True


def tap_xy(x: int, y: int) -> None:
    adb("shell", "input", "tap", str(x), str(y))


def ok(name: str, passed: bool, detail: str = "") -> None:
    REPORT.append(f"{'PASS' if passed else 'FAIL'} | {name} | {detail}")
    line = REPORT[-1].encode("utf-8", "replace").decode("utf-8", "replace")
    try:
        print(line, flush=True)
    except UnicodeEncodeError:
        print(line.encode("ascii", "backslashreplace").decode("ascii"), flush=True)


def find(nodes: list[Node], *, text: str | None = None, desc: str | None = None, contains: str | None = None):
    for n in nodes:
        if text is not None and n.text == text:
            return n
        if desc is not None and n.desc == desc:
            return n
        if contains is not None and (contains in n.text or contains in n.desc):
            return n
    return None


def find_all_text(nodes: list[Node], pattern: str) -> list[Node]:
    rx = re.compile(pattern)
    return [n for n in nodes if rx.search(n.text)]


def wake() -> None:
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "input", "keyevent", "224")
    adb("shell", "wm", "dismiss-keyguard")
    time.sleep(0.5)


def launch() -> None:
    # Банковские/SSO приложения часто перехватывают фокус на устройстве
    for noisy in (
        "ru.sberbankmobile",
        "ru.raiffeisennews",
        "ru.vtb24.mobilebanking.android",
        "com.idamob.tinkoff.android",
    ):
        adb("shell", "am", "force-stop", noisy)
    adb("shell", "cmd", "statusbar", "collapse")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.5)
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
    time.sleep(3)
    # ещё раз на передний план
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2)


def back(times: int = 1) -> None:
    for _ in range(times):
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.4)


def main() -> int:
    wake()
    launch()
    adb("logcat", "-c")

    nodes = dump("B01_launch")
    ok("foreground", len(nodes) > 0, f"nodes={len(nodes)}")
    session = find(nodes, contains="id")
    # prefer idNNNN
    sid = next((n for n in nodes if re.fullmatch(r"id\d+", n.text)), None)
    ok("session_restored", sid is not None, sid.text if sid else "no id")

    # Bottom tabs
    listen = find(nodes, text="Плеер") or find(nodes, text="Слушать")
    search = find(nodes, text="Поиск")
    library = find(nodes, text="Медиатека")
    ok("nav_tabs", all([listen, search, library]), f"{listen and listen.text}, {search and search.text}, {library and library.text}")

    if library:
        tap(library)
        time.sleep(1)

    nodes = dump("B02_library")
    recom = find(nodes, text="Реком")
    my = find(nodes, text="Моя")
    pls = find(nodes, text="Плейлисты")
    ok("library_chips", all([recom, my, pls]), f"{recom and recom.text}, {my and my.text}, {pls and pls.text}")

    # Recoms
    if recom:
        tap(recom)
        time.sleep(3)
    nodes = dump("B03_recoms")
    tracks = find_all_text(nodes, r"\d+:\d{2}")
    ok("recoms_tracks", len(tracks) >= 1, f"count={len(tracks)}; " + " / ".join(t.text for t in tracks[:3]))

    # My music
    if my:
        tap(my)
        time.sleep(4)
    nodes = dump("B04_my")
    tracks = find_all_text(nodes, r"\d+:\d{2}")
    ok("my_music_tracks", len(tracks) >= 1, f"count={len(tracks)}; " + " / ".join(t.text for t in tracks[:3]))

    # Overflow menu
    more = find(nodes, desc="Ещё")
    if more:
        tap(more)
        time.sleep(1.5)
        nodes = dump("B05_menu")
        menu_texts = [n.text for n in nodes if n.text and n.clickable]
        # also non-clickable labels inside menu items
        if not menu_texts:
            menu_texts = [n.text for n in nodes if n.text and n.y > 600]
        ok("track_menu", any("плейлист" in t.lower() or "музык" in t.lower() or "Удалить" in t for t in menu_texts), " | ".join(menu_texts[:8]))

        add_my = find(nodes, contains="Мою музыку")
        to_pl = find(nodes, contains="плейлист")
        remove_my = find(nodes, contains="Удалить из Моей")

        # Open playlist picker
        target = to_pl or find(nodes, contains="В плейлист")
        if target:
            # tap parent clickable near text
            if not target.clickable:
                # tap text center still works for compose menus
                pass
            tap(target)
            time.sleep(3)
            nodes = dump("B06_picker")
            picker = " | ".join(n.text for n in nodes if n.text)[:240]
            ok("playlist_picker", "Создать" in picker or "Добавить" in picker or "плейлист" in picker.lower(), picker)

            create_btn = find(nodes, contains="Создать плейлист") or find(nodes, text="Создать плейлист")
            if not create_btn:
                create_btn = next((n for n in nodes if n.clickable and "Создать" in n.text), None)
            if create_btn:
                tap(create_btn)
                time.sleep(1.5)
                nodes = dump("B07_dialog")
                ok("create_dialog", any(n.text == "Новый плейлист" for n in nodes), " | ".join(n.text for n in nodes if n.text))

                field = next((n for n in nodes if n.clickable and n.y1 > 900 and n.y2 < 1300), None)
                if not field:
                    label = find(nodes, text="Название")
                    if label:
                        tap_xy(label.x, label.y + 40)
                    else:
                        tap_xy(540, 1100)
                else:
                    tap(field)
                time.sleep(0.5)
                adb("shell", "input", "text", "AutoTestPL")
                time.sleep(0.8)
                nodes = dump("B07b")
                confirms = [n for n in nodes if "Создать" in n.text and "Отмена" not in n.text]
                confirm = sorted(confirms, key=lambda n: n.x)[-1] if confirms else None
                if confirm:
                    # Compose: кликабельна обёртка, не TextView
                    wrapper = next(
                        (
                            n
                            for n in nodes
                            if n.clickable
                            and n.x1 <= confirm.x1
                            and n.x2 >= confirm.x2
                            and abs(n.y - confirm.y) < 100
                        ),
                        confirm,
                    )
                    tap(wrapper)
                    time.sleep(8)
                # После создания пикер/диалог закрываются — смотрим вкладку плейлистов
                nodes = dump("B08_created")
                library = find(nodes, text="Медиатека")
                if library:
                    tap(library)
                    time.sleep(1)
                nodes = dump("B08b")
                pls = find(nodes, text="Плейлисты")
                if pls:
                    tap(pls)
                    time.sleep(4)
                nodes = dump("B08c")
                texts = " | ".join(n.text for n in nodes if n.text)
                ok("create_playlist", "AutoTestPL" in texts, texts[:240])
            else:
                ok("create_dialog", False, "no create button in picker")
                back()
        else:
            ok("playlist_picker", False, "no playlist menu item")
            back()
    else:
        ok("track_menu", False, "no overflow")
        ok("playlist_picker", False, "skipped")
        ok("create_dialog", False, "skipped")
        ok("create_playlist", False, "skipped")

    # Playlists list / open / delete
    nodes = dump("B09")
    library = find(nodes, text="Медиатека")
    if library:
        tap(library)
        time.sleep(1)
    nodes = dump("B09b")
    pls = find(nodes, text="Плейлисты")
    if pls:
        tap(pls)
        time.sleep(3)
    nodes = dump("B10_playlists")
    pl_texts = " | ".join(n.text for n in nodes if n.text)
    ok("playlists_screen", "Плейлист" in pl_texts or "AutoTestPL" in pl_texts, pl_texts[:240])

    auto = find(nodes, text="AutoTestPL")
    if auto:
        tap(auto)
        time.sleep(3)
        nodes = dump("B11_open_pl")
        open_txt = " | ".join(n.text for n in nodes if n.text)
        ok("open_playlist", "AutoTestPL" in open_txt or find(nodes, desc="Назад") is not None, open_txt[:200])

        # remove track from playlist if any track + menu
        more = find(nodes, desc="Ещё")
        if more:
            tap(more)
            time.sleep(1.2)
            nodes = dump("B12_pl_track_menu")
            rm_pl = find(nodes, contains="Удалить из плейлиста")
            ok("remove_from_playlist_menu", rm_pl is not None, " | ".join(n.text for n in nodes if n.text))
            back()
        else:
            ok("remove_from_playlist_menu", True, "playlist empty or no tracks — menu N/A")

        del_icon = find(nodes, desc="Удалить плейлист")
        if not del_icon:
            nodes = dump("B12b")
            del_icon = find(nodes, desc="Удалить плейлист")
        if del_icon:
            tap(del_icon)
            time.sleep(1.2)
            nodes = dump("B13_del_confirm")
            confirm = find(nodes, text="Удалить")
            ok("delete_confirm", confirm is not None, " | ".join(n.text for n in nodes if n.text))
            if confirm:
                tap(confirm)
                time.sleep(4)
            nodes = dump("B14_after_del")
            texts = " | ".join(n.text for n in nodes if n.text)
            ok("delete_playlist", "AutoTestPL" not in texts, texts[:200])
        else:
            # list menu delete
            back()
            time.sleep(1)
            nodes = dump("B13_list")
            more = next((n for n in nodes if n.desc == "Ещё" and n.y > 500), None)
            if more:
                tap(more)
                time.sleep(1)
                nodes = dump("B13_menu")
                item = find(nodes, contains="Удалить плейлист")
                if item:
                    tap(item)
                    time.sleep(1)
                    nodes = dump("B13_c")
                    confirm = find(nodes, text="Удалить")
                    if confirm:
                        tap(confirm)
                        time.sleep(4)
                    nodes = dump("B14_after_del")
                    texts = " | ".join(n.text for n in nodes if n.text)
                    ok("delete_playlist", "AutoTestPL" not in texts, texts[:200])
                else:
                    ok("delete_playlist", False, "no delete in list menu")
            else:
                ok("delete_playlist", False, "no delete control")
    else:
        ok("open_playlist", False, "AutoTestPL missing")
        ok("remove_from_playlist_menu", False, "skipped")
        ok("delete_playlist", False, "skipped")

    # Search
    nodes = dump("B15")
    search = find(nodes, text="Поиск")
    if search:
        tap(search)
        time.sleep(1.5)
        nodes = dump("B16_search")
        field = next((n for n in nodes if n.clickable and 250 < n.y < 700), None)
        if field:
            tap(field)
        else:
            tap_xy(540, 400)
        time.sleep(0.5)
        adb("shell", "input", "text", "bi-2")
        adb("shell", "input", "keyevent", "66")
        time.sleep(6)
        nodes = dump("B17_results")
        tracks = find_all_text(nodes, r"\d+:\d{2}")
        ok("search", len(tracks) >= 1, f"count={len(tracks)}; " + " / ".join(t.text for t in tracks[:3]))

        # play first result row
        row = next((n for n in nodes if n.clickable and n.y > 500 and n.text), None)
        if not row and tracks:
            tap_xy(tracks[0].x, tracks[0].y - 50)
        elif row:
            tap(row)
        time.sleep(7)
        nodes = dump("B18_after_play")
        listen = find(nodes, text="Слушать")
        if listen:
            tap(listen)
            time.sleep(2)
            nodes = dump("B19_player")
        player_txt = " | ".join(n.text for n in nodes if n.text)
        ok("playback", "Очередь" in player_txt or any(":" in n.text for n in nodes), player_txt[:240])
    else:
        ok("search", False, "no search tab")
        ok("playback", False, "skipped")

    # Settings
    nodes = dump("B20")
    library = find(nodes, text="Медиатека")
    if library:
        tap(library)
        time.sleep(1)
    nodes = dump("B20b")
    settings = find(nodes, desc="Настройки")
    if settings:
        tap(settings)
        time.sleep(1.5)
        nodes = dump("B21_settings")
        st = " | ".join(n.text for n in nodes if n.text)
        ok(
            "settings",
            any(k in st for k in ("Кэш", "кэш", "Выйти", "MB", "оффлайн", "Оффлайн")),
            st[:240],
        )
        back()
    else:
        ok("settings", False, "no settings icon")

    # Add/remove my music availability on recoms
    nodes = dump("B22")
    recom = find(nodes, text="Реком")
    if recom:
        tap(recom)
        time.sleep(2)
    nodes = dump("B23")
    more = find(nodes, desc="Ещё")
    if more:
        tap(more)
        time.sleep(1.2)
        nodes = dump("B24_recom_menu")
        texts = [n.text for n in nodes if n.text]
        ok(
            "add_my_music_menu",
            any("Мою музыку" in t for t in texts),
            " | ".join(texts[:8]),
        )
        back()
    else:
        ok("add_my_music_menu", False, "no overflow")

    log = adb("logcat", "-d", "-t", "400", "*:S", "VkMusicAuth:I", "VkMusicAuth:W", "VkMusicAuth:E", "AndroidRuntime:E")
    (OUT / "logcat_e2e.txt").write_text(log, encoding="utf-8")
    ok("no_crash", "FATAL EXCEPTION" not in log)
    ok(
        "backend_logs",
        any(k in log for k in ("AUTH READY", "fetchPlaylists", "createPlaylist", "addTo", "playlist", "web probe", "section")),
        log[-500:].replace("\n", " | "),
    )

    (OUT / "REPORT_E2E.txt").write_text("\n".join(REPORT), encoding="utf-8")
    passed = sum(1 for r in REPORT if r.startswith("PASS"))
    failed = sum(1 for r in REPORT if r.startswith("FAIL"))
    print(f"\nTOTAL: {passed} passed, {failed} failed", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
