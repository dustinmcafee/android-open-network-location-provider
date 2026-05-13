# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| 1.x     | ✅        |

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Instead, email the maintainer directly or use
[GitHub private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability).

Include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix if you have one

You can expect an acknowledgement within 72 hours and a fix or mitigation
plan within 14 days for confirmed vulnerabilities.

## Known design constraints

**Apple WPS endpoint is unauthenticated and undocumented.**  
`AppleWpsSource` sends observed Wi-Fi BSSIDs to `gs-loc.apple.com`. This is
the default implementation for development only. A MITM attacker on the
network path could intercept BSSID observations or return false position data.
Replace with a commercially licensed provider (Skyhook, HERE, Combain) before
any production deployment. See [SHIP-BLOCKERS.md](SHIP-BLOCKERS.md).

**Cell-DB download is SHA-256 verified but CDN trust is assumed.**  
The first-boot fetcher verifies the downloaded `cells.db` against a SHA-256
digest shipped in the system image. An attacker who compromises your CDN and
also modifies your system image could serve a malicious database. Use HTTPS
for the CDN URL and consider signing the artifact separately for high-assurance
deployments.

**`/data/misc/nlp-celldb/` is readable by `platform_app`.**  
The cell-tower database is OpenCellID public data — it contains no user-specific
information. The directory is owned by `system:system` and labeled with a custom
SELinux type (`nlp_celldb_data_file`), restricting write access to the fetcher
init service.
