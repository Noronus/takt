# Public access through the NetBird reverse proxy

Friends use the app over HTTPS without joining the VPN. The NetBird reverse proxy terminates TLS and forwards to the Takt container. Takt checks one token per person.

```
phone ──HTTPS──> NetBird proxy (takt.example.de) ──WireGuard──> Unraid :8097 (Takt) ──LAN──> :8098 (MOTIS, never public)
```

## Security layers

| Layer | What it does |
|---|---|
| NetBird proxy | TLS with an automatic certificate (Takt adds HSTS), country allow list, CrowdSec IP reputation, only `/api/` forwarded |
| Takt: tokens | One token per person in `DATA/tokens.txt`, at least 32 characters, revocable within 10 s, no restart |
| Takt: fail closed | A request that came through a proxy (has `X-Real-IP` / `X-Forwarded-For`) is refused when no token is configured. `REQUIRE_TOKEN=true` refuses to start without one |
| Takt: proxy paths | Through the proxy only `/api/*` exists. `/rt/*.pb` (MOTIS realtime feed) and `/icon.png` answer 404 |
| Takt: limits | 10 wrong tokens per IP in 15 min → further wrong tokens get 429 for the rest of the window (a correct token always passes). 120 requests per person per minute. Max 4 parallel journey searches |
| Takt: input | Station ids, coordinates, times, trip ids and search text are validated before they reach MOTIS. Errors never include internal hosts or messages. `/api/v1/status` shows only a summary through the proxy |
| App | Refuses plain `http://` to anything that is not a LAN/VPN address, so a token never travels unencrypted. App backup is disabled, so the token is not copied off the device |

Why the token is checked by Takt and not by the proxy: the proxy's header authentication removes the header it checks before forwarding. Takt would then not know who is calling, and revoking one person would mean editing the proxy.

## Setup

1. Create tokens (yours first, the app needs one too once any token exists):
   ```sh
   ./tokens.sh add me
   ./tokens.sh add anna
   ```
2. In `deploy.env` set `REQUIRE_TOKEN=true`, then run `./deploy.sh`.
3. In the NetBird dashboard, **Reverse Proxy → Add service**:
   - Domain: e.g. `takt.example.de`
   - Target: the Unraid peer, port `8097` (the Takt API port), protocol HTTP
   - Path: `/api/`, **Preserve Full Path** on (Takt expects the `/api` prefix)
   - Authentication: none (Takt does it, see above)
   - Access Control: **Allowed Countries** `DE` (add countries your friends travel to), **CrowdSec** `Enforce` if offered
   - Never add a target for port `8098` (MOTIS has no authentication)
4. Check from outside the VPN (mobile data, or turn NetBird off):
   ```sh
   D=https://takt.example.de
   curl -s -o /dev/null -w '%{http_code}\n' $D/api/v1/status                     # 401
   curl -s -o /dev/null -w '%{http_code}\n' $D/rt/nv.pb                          # 404 (or proxy 404)
   curl -s -H "Authorization: Bearer $TOKEN" $D/api/v1/status                   # {"stations":…,"motis":"ok",…}
   ```
   If `/rt/nv.pb` returns 200, the proxy does not send `X-Real-IP`/`X-Forwarded-For`: stop and fix before sharing.
5. Give each friend the APK, the URL and their own token (Signal or in person, not in a group chat). In the app: **Einstellungen → Adresse** `https://takt.example.de`, **Zugangs-Token**, **Speichern & testen**.

## Revoke

```sh
./tokens.sh remove anna     # effective within 10 s
./tokens.sh list
```

Failed logins are logged as `[auth] abgelehnt: IP …` in the Takt container log.

## What remains

- Anyone with a valid token can use the API until you revoke it. A token leaked by a friend is the main risk.
- More users means more requests to INSA (unofficial API). Results are cached for 45 s per station. Keep the group small.
- The server sees the start/destination coordinates of journey searches. Takt does not log them.
