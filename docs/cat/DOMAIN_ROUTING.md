# Domain routing MVP

## User model

Each rule belongs to one tunnel and stores a normalized logical hostname, not an IP-only bypass:

```text
id, tunnelId, domain, matchMode, routeTarget, enabled,
resolvedIpv4/history, resolvedIpv6/history, lastResolvedAt/status,
source, comment
```

`SUFFIX` is the default (`example.com` matches it and subdomains); `EXACT` is explicit. `LOCAL_DIRECT` is active in this MVP. `DEFAULT_TUNNEL`, `SERVER_EGRESS`, and `BLOCK` are present in the durable schema so the model is not trapped in a two-state bypass design. Only the first two are selectable in the current Sites UI.

## Resolution and application

`AndroidDomainResolver` resolves A and AAAA off the UI thread, with a five-second timeout and cancellation. It first uses `ConnectivityManager.activeNetwork.getAllByName()` and falls back to the platform resolver. Results are merged into a bounded history with an `isCurrent` marker: historic answers are visible for diagnosis but are never routed.

Resolution is performed on add, normal tunnel start, manual refresh, imported-rule refresh, underlying-network changes, and after upstream seamless recovery reports a reconnect. If routes changed while a tunnel is active, the existing `Backend.bounceTunnelDevice()` path is used; Cat does not create a parallel VPN lifecycle.

On API 33+, the synchronous route snapshot is applied in `VpnService.createTunInterface()` before `Builder.establish()`:

```text
IPv4 current answer → IpPrefix /32 → excludeRoute()
IPv6 current answer → IpPrefix /128 → excludeRoute()
```

No Android 12-or-older complement-CIDR workaround is implemented. The app still retains upstream `minSdk 26`; domain local-direct routing is deliberately unavailable below API 33.

## UI, share, import/export

Per-tunnel Settings → Sites supports search, add/edit/delete/toggle, exact/suffix,
local-direct/default-tunnel choice, IPv4/IPv6 inspection, last resolution metadata, manual refresh,
and JSON/TXT import/export. Imports preserve logical/user metadata, assign the target tunnel, and
regenerate network-dependent DNS observations.

The manifest registers `ACTION_SEND` + `text/plain`. The activity safely extracts a URL/domain, asks for suffix or exact mode and a tunnel (preferring an active tunnel), then resolves and applies the rule through the same coordinator. Automated parser tests cover Chrome/Firefox-style text payloads; physical browser share UI was not run.

## Important limitation: IP is not a domain

VPN routing is IP-based. A local reverse index maps observed IPs to all saved rules/domains,
including historical and disabled rules. Sites diagnostics shows a shared-IP warning, direct/VPN
decision, the active IP-route owner (if a current direct exclusion exists), the separately matched
hostname rule, all locally known domains for that IP, freshness, changed IPs, and IPv4/IPv6
differences. It only claims what was locally observed; an unknown hostname sharing an IP is never
claimed to belong to a saved domain, and no reverse-IP or passive-DNS data is uploaded or queried.
