# Авторизация VK Music

Основной путь — **WebView Implicit Flow** (как cookie-вход в Youtube_Voice).  
Регистрация приложения на [id.vk.com](https://id.vk.com) **не нужна**.

## Путь WebView (основной)

1. Кнопка «Войти через VK» открывает `VkLoginActivity` (WebView).
2. Загружается `https://oauth.vk.com/authorize` с публичным `client_id` (Kate или Official), `scope=audio,offline`, `response_type=token`.
3. Пользователь входит и подтверждает доступ (в WebView; при необходимости система может открыть установленное приложение VK).
4. Редирект на `https://oauth.vk.com/blank.html#access_token=…&user_id=…`.
5. Токен + User-Agent сохраняются в DataStore, выполняется probe `audio.get?count=1`.

Никакой ручной вставки токена и никакого вашего `client_id` из кабинета разработчика.

## Путь C — логин/пароль (запасной)

Если WebView-токен не даёт `audio`, форма логин/пароль + 2FA (`VkClientAuth`).

## Путь A — VK ID (опционально)

Если в `local.properties` заданы реальные `VKIDClientID` / `VKIDClientSecret`, на экране появится OneTap.  
Для музыки обычно недостаточно — scope `audio` новым приложениям часто не выдают.

## Хранение сессии

`SessionStore` (DataStore `vk_session`): token, userId, source (`WebView` / `Client` / `VkId`), userAgent, expires.

## Риски

- Публичные client_id и WebView-OAuth могут ограничить или изменить на стороне VK.
- Соблюдайте правила платформы; сценарий для личного клиента.
