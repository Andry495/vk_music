# Плейлисты: статус и находки (2026-10-09)

## Цель

CRUD плейлистов без flood Kate API: веб (al_audio / m.vk) + `edit_hash`, проверка факта через перезапрос списка/треков.

## Что сделано в коде

- Вход только через Kate WebView (`remixsid` + cookies).
- Список / треки / add / remove / delete — `VkWebAudioClient` (веб).
- После мутаций ViewModel делает refetch и сверяет факт (трек есть/нет, плейлист в списке).
- `edit_hash` кэшируется на сессию (`editHashCache`).
- Create: сначала веб (`save_playlist` / `create_playlist` + page hash), иначе один вызов `audio.createPlaylist` (Kate API) — без циклов `getPlaylists`/`addToPlaylist`.
- Resolve без hash: страница плейлиста → `load_section` → точечно `audio.getPlaylistById`.
- `isMutationSuccess`: отвергает `Ошибка доступа`, flood и SPA-HTML заглушки (`payload[0]` + огромный HTML ≠ успех).
- `fetchPlaylists` больше не блокируется на `ensureAudioPageHash` (иначе пикер пустой 4–8 с).

## Находки с устройства

| Наблюдение | Деталь |
|---|---|
| Нет `AudioPage` / `edit_hash` в HTML | `vk.ru/audios*`, `vk.ru/audio`, `m.vk.ru/audio?act=audio_playlists*` — SPA, `AudioPage=false`, `edit_hash=false`, при этом cookies живые (`login=false`) |
| `audioPageHash` = пусто | Desktop UA не помогает; классический hash со страницы не извлекается |
| Kate API flood | `audio.createPlaylist` → `error_code:9 Flood control` после серии тестов |
| Ложный PASS create | UI-диалог оставлял текст названия в dump; тест мог принять его за строку списка |
| Пикер vs список | Список показывал `AutoTestPL`, пикер — только «Создать…», пока `fetchPlaylists` ждал hashProbe |
| Экран блокировки / банк | uiautomator ловил lockscreen; в тестах нужен wake + force-stop банковских приложений |

## Что работает / нет (на момент фиксации)

| Операция | Статус |
|---|---|
| Список плейлистов (веб) | Работает |
| Треки плейлиста | Работает |
| Create | Блокируется: нет page hash + API flood |
| Add / remove / delete | Код готов (нужен `edit_hash`); e2e на устройстве не закрыт из‑за create/flood и пустого пикера до фикса списка |

## Следующие шаги

1. Добыть `edit_hash` без page hash: разобрать `section=playlists` / страницу плейлиста (log `hashHints[*]`), либо дождаться снятия flood и один `getPlaylistById`.
2. Повторить e2e: `device_test/verify_existing_and_wait.py <adb> [wait_sec]` на существующем плейлисте (create skip при flood).
3. Убрать шумные `hashHints` логи после стабилизации.
4. Не гонять Kate API циклами — только точечные fallback.

## Тесты

Скрипты в `device_test/` (Python + uiautomator). Артефакты dump (`*.xml`) в git не кладём.
