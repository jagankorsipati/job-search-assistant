# Household deployment security checklist

This is a future Raspberry Pi release gate, not evidence that deployment has occurred.

- [ ] Terminate validated HTTPS before using the browser flow; keep `SESSION_COOKIE_SECURE=true`.
- [ ] Externalize strong, unique database and bootstrap credentials; never reuse development/test defaults.
- [ ] Bootstrap the first administrator once, then disable bootstrap and remove its process/environment values.
- [ ] Keep PostgreSQL inside the application network with no LAN or public port.
- [ ] Restrict application, database-volume, backup, key, and configuration file permissions.
- [ ] Configure `BASE_RESUME_STORAGE_ROOT` outside the repository, classpath, frontend, and web-served directories; restrict it to the application OS user.
- [ ] Include base résumé storage in encrypted backup/restore drills and treat backups as sensitive personal data.
- [ ] Decide whether malware scanning is required before allowing less-trusted uploads or broader access.
- [ ] Configure trusted reverse proxies explicitly before honoring any forwarded address/header; otherwise retain direct-address behavior.
- [ ] Review host firewall, router rules, Tailscale policy, and confirm there is no port forwarding.
- [ ] Run and document an encrypted backup and restore drill.
- [ ] Establish routine OS, JDK, container, Maven, npm, and advisory review/update work.
- [ ] Confirm audit retention settings and monitor the non-sensitive expired-event query.
- [ ] Benchmark real Argon2id verification on the target Pi under representative concurrent load without changing the committed parameters; record latency, memory pressure, throttling, and temperature. Any parameter change requires security review and regression tests.
- [ ] Restrict access to the household/private overlay. Public exposure requires a separate threat model, edge controls, monitoring, and penetration review.

Windows development does not validate production TLS, reverse-proxy trust, Raspberry Pi performance, firewall policy, backup permissions, or restore success.
