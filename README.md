# Power Auth

Fabric 1.20.1 authentication mod with optional Telegram two-factor authentication. The auth logic runs on the server side, but the jar is also client-loadable for local integrated-server testing.

## Features

- `/register <password> <password>` and `/login <password>` for offline-mode style servers.
- PBKDF2 password hashing with per-user salts.
- Login sessions tied to the last remote address for a limited time.
- Player lockdown before authentication: movement, block interaction, item use, entity interaction and drops are blocked.
- Optional Telegram bot 2FA using long polling and inline confirmation buttons.
- `/linktg`, `/2fa on`, `/2fa off`, `/2fa status`, `/auth <code>` account flow.
- Admin commands for account info, password reset, 2FA reset, stats and config reload.
- Login attempt throttling to slow down password and Telegram-code brute force.

## Build

Install JDK 17 and Gradle, then run:

```sh
gradle build
```

The jar will be in `build/libs/`.

## Server Setup

1. Put the built jar into the server `mods/` directory.
2. Start the server once to generate `config/power-auth.json`.
3. Edit `config/power-auth.json`.
4. Restart the server.

Telegram 2FA is disabled by default. To enable it, create a bot through BotFather and set:

```json
{
  "telegram": {
    "enabled": true,
    "botToken": "123456:ABC...",
    "botUsername": "YourBotName",
    "requireForAllUsers": false
  }
}
```

Players link Telegram with `/linktg`, then send the shown code to the bot as `/link CODE`.
If a player sends `/start` or `/help` to the bot, it replies with the configured `telegram.startMessage` instructions.

After a linked player enters `/login <password>`, the bot sends a message like "someone is trying to enter your account" with `Подтвердить` and `Отклонить` buttons. The old `/auth <code>` flow remains available as a fallback if Telegram buttons do not work.

## Commands

Player commands:

- `/register <password> <password>` - register a new account.
- `/login <password>` - log into an existing account.
- `/linktg` - generate a Telegram link code or deep link.
- `/auth <code>` - confirm a Telegram login code.
- `/2fa on` - enable Telegram 2FA after Telegram is linked.
- `/2fa off` - disable Telegram 2FA.
- `/2fa status` - show Telegram binding and 2FA status.

Admin commands require the configured `adminPermissionLevel`, default `3`:

- `/authadmin info <player>` - show account status.
- `/authadmin resetpassword <player>` - delete the saved password record; the player must register again.
- `/authadmin reset2fa <player>` - unlink Telegram and disable 2FA.
- `/authadmin stats` - show basic auth database stats.
- `/authadmin reload` - reload `config/power-auth.json` and restart the Telegram bot if needed.
