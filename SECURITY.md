# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.28.x  | :white_check_mark: |
| < 0.28  | :x:                |

Only the latest release is supported. Previous versions are deleted per the project's release policy.

## Reporting a Vulnerability

GHBot-Plugin is an admin-only tool with significant server access (console commands, file editing, world manipulation). Security is critical.

### What to Report

- Authentication bypass (web console token, admin-only dispatch)
- Command injection via tool protocol
- Path traversal in admin/schematic operations
- Privilege escalation (non-OP → OP)
- Denial of service (memory exhaustion, main-thread freeze)
- Data exfiltration (logs, config files, world data)

### How to Report

**Do NOT open a public issue for security vulnerabilities.**

Instead, contact the maintainer directly:
- GitHub: [@SG009](https://github.com/SG009)
- Email: (check GitHub profile)

Include:
1. Description of the vulnerability
2. Steps to reproduce
3. Potential impact
4. Suggested fix (if any)

### Response Timeline

- **Acknowledgment**: Within 48 hours
- **Initial assessment**: Within 1 week
- **Fix timeline**: Critical issues patched within 1 week, others within 1 month
- **Disclosure**: After fix is released (or 90 days, whichever comes first)

### Security Best Practices

When running GHBot-Plugin:

1. **Never share the web console token** (`WEB-########`) — it's printed once in server logs
2. **Use `server.web.local-bypass: false`** (default) unless you're on the server device itself
3. **Audit CONF tokens** — systemic commands (`stop`/`reload`/`op`) require explicit confirmation
4. **Monitor `logs/ghbot.log`** — all admin operations are audit-logged
5. **Restrict file access** — `admin read/set` is confined to `plugins/` and server config files
6. **Keep Paper updated** — GHBot runs with full console trust

### Known Security Features

- **Token auth**: Web console requires `WEB-########` token (minted at startup)
- **Brute-force guard**: 5 wrong logins/min → 10 min lockout
- **Admin-only dispatch**: Non-OP players blocked at command dispatch
- **CONF tokens**: Systemic commands mint tokens, require explicit `confirm <token>`
- **Path confinement**: Schematic imports confined to library dir (no traversal)
- **Bounded uploads**: 25 MB max, rejected DURING read (not after buffering)
- **Session TTL**: 30 min stale, 100 max sessions, evicted every 5 min
- **Audit logging**: All admin operations logged to `logs/admin.log`, `logs/edits.log`, `logs/commands.log`

## Acknowledgments

Security researchers who have responsibly disclosed vulnerabilities will be credited here (with permission).

---

**Last updated**: 2026-09-24
