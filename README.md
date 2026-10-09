# VK Music

Android-клиент музыки VK. UI и фоновое воспроизведение — по образцу проекта Youtube_Voice.

Репозиторий: https://github.com/Andry495/vk_music

## Возможности (сейчас)

- Вход через WebView (OAuth Implicit Flow), без регистрации приложения на id.vk.com
- Сохранение сессии (token + cookies) в DataStore
- Доступ к музыке через веб-бэкенд `al_audio.php` (OAuth `audio.*` у VK отвечает error 3)
- Список «Моя музыка» (если в аккаунте есть треки)

## Сборка

1. Скопируйте `local.properties.example` → `local.properties` и укажите `sdk.dir`.
2. VK ID credentials опциональны (кнопка OneTap появляется только если заданы).
3. Сборка:

```bat
gradlew.bat :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Документация

- [docs/AUTH.md](docs/AUTH.md) — авторизация
- [docs/TODO.md](docs/TODO.md) — отложенные задачи

## Требования

- Android Studio / JDK 17
- minSdk 26, targetSdk 35
