# LoneBot â€” share (subset)

Private mirror voor code-review / AI. **Geen launcher**, geen start-bats, geen accounts/2FA/secrets.

**Bron:** git origin/main (GitHub / committed)  
**Gegenereerd:** 2026-09-30 21:57

## Inhoud

- `api/`, `sdk/`
- `client/` â€” plugin + `com.lonebot.client` (zonder `com.lonebot.launcher`)
- `script-*`, `example-plugin/`, `docs/`
- Gradle root-bestanden

## Niet inbegrepen

- Launcher / Jagex OAuth / 2FA (`com.lonebot.launcher`)
- Start/Restart/Rebuild bats, `update/`, `tools/`, `backups/`, accounts JSON

## Bijwerken

```powershell
# Exact GitHub (origin/main) â†’ lonebot-github-share
.\Sync-AiShare.ps1 -GitRef origin/main -Commit -Push

# Lokale werkmap (inclusief uncommitted) â†’ lonebot-ai-share
.\Sync-AiShare.ps1 -Commit -Push
```
