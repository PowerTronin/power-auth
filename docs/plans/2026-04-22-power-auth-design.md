# Power Auth Design

## Goal

Power Auth is a server-side Fabric 1.20.1 mod for servers that need an in-game account password layer and optional Telegram two-factor authentication. It targets practical offline-mode style deployments where the server operator wants basic account protection without forcing every client to install a mod.

## Scope

The MVP provides registration, password login, session restore, player lockdown before authentication, Telegram account linking, and Telegram login codes. Passwords are stored with PBKDF2 and per-user salts. User records live in `power-auth-users.json`; server settings live in `config/power-auth.json`.

## Player Flow

New players use `/register <password> <password>`. Returning players use `/login <password>`. If Telegram 2FA is enabled for the user, the password step sends a six-digit code through the linked Telegram bot, and the player finishes with `/auth <code>`.

Players link Telegram with `/linktg`, then send `/link CODE` to the bot. Linking enables 2FA for that player. `/2fa on` and `/2fa off` are available after linking.

Admin operations are grouped under `/authadmin` and require the configured permission level. The MVP includes account info, password reset by deleting the saved auth record, Telegram reset, stats, and config reload.

## Enforcement

Unauthenticated players are locked at their join position. Fabric player callbacks block block attacks, item use, entity interaction, and block interaction. A `ServerPlayNetworkHandler` mixin blocks chat, movement packets, command execution except auth commands, inventory clicks, creative inventory actions, and player actions.

## Telegram Integration

The first implementation uses Telegram long polling from a daemon thread. This avoids exposing a webhook HTTP server and works on typical rented Minecraft hosting. The bot token is read from config and is never sent to players. Login confirmation uses Telegram inline buttons with one-time callback tokens; `/auth CODE` remains as a fallback.

When `telegram.botUsername` is configured, `/linktg` also prints a Telegram deep link using `/start CODE`; otherwise it falls back to `/link CODE`.

## Future Ideas

- SQLite storage for larger servers.
- Optional Discord 2FA provider with the same internal interface.
- Audit log for successful logins, failed logins, and 2FA changes.
