# Connection reports for status.zapret.moe

Owner decisions of 15–19.11.2026 (programme «honest coverage», stage S4). The service's status page
checks its servers from Russian datacenters and home scanners; none of them sees the filter of a
particular home provider. Real connection attempts of Zapret KVN from Russian home networks are that
third source: the page shows a server as reachable or not from homes only when at least 3 installs
on at least 2 networks (ASNs) reported the same within 24 hours.

## What is sent

One report per check of one server of the service's own subscriptions (the reply carries
`zapret-vpn-service`), to `POST https://reports.zapret.moe/v1/reports`, contract
`client_reports/contract.py` of vpnbot, schema 1:

- `server_id` and `product` — the catalogue id from the `zapret-server-ids` header of the subscription
  reply: unpadded base64url of zlib-compressed (RFC 1950) JSON `{"<link fragment>": {"server_id",
  "product"}}`, at most 14 336 chars (vpnbot omits the header rather than truncate it; the app inflates
  at most 512 KB). VLESS product subscriptions name VLESS/Trojan/Shadowsocks lines as product `vless`;
  Hysteria 2 subscriptions `hysteria2`. Relay rows «… через Россию» are not in the header (a report
  would not be about the foreign server). A repeated name (tag «name · 2») uses the entry of its name.
  A server without an entry is never reported, and the id is never guessed from the name.
- `outcome` `connected` / `failed` / `cancelled`, the failed step of the vpnbot ladder
  (resolve → connect → handshake → auth → tunnel → request), a short code (`udp_no_answer`,
  `tls_failed`, `auth_rejected`, `handshake_timeout`, `no_response`, …), elapsed ms, attempt time.
- app name, version and platform; a random 32-hex `install_id` made on the device (not tied to the
  account); `sent_via` `direct` or `tunnel`; the `network_token` described below.

Nothing else: no account, key, address, domain or Wi-Fi name. Other providers' servers are never reported.

## Where the decision lives

- Module `client-reports` (pure Kotlin, JVM tests): the contract and its JSON, the header parser,
  `AttemptClassifier` (core failure code + traffic facts → ladder step; failures that are not about
  the server — local errors, lost Android network, DNS mode with no core evidence — produce no report),
  the disk queue (`ReportQueue`, at most 500 records, a week at most) and `NetworkTokenCache`.
- `app/.../clientreports/ClientReporter.kt`: network and Android glue only.
- The hook is `SessionHealthVerifier.verify`, the single DNS/HTTPS check of the selected server for
  start, fallback and switch; the bootstrap TCP preflight result is attached to the same server
  (`SessionStarter`) so a TCP connect timeout is told from a handshake timeout.

## How it is sent

No prompt and no setting (owner rule). No timers: a flush runs after each new report and on app
start. A batch (≤ 50) goes directly over the non-VPN network first, so the ingest sees the network
itself; if no answer comes, it goes through the tunnel. `GET /v1/network?install_id=…` is asked only
directly; its token (24 h, bound to the install) is kept with the Android network handle it was
obtained on and attached to reports of attempts made on that network, so a report sent later or
through the tunnel still counts for the right network. 202 removes the batch, 400/413 drop it,
429/5xx keep it until the next report.

## Seeing it act

The runtime log (layer `app`) has a `client-report:` line per queued report and per sent batch
(`пакет N (direct) → HTTP 202`). The files are in `noBackupFilesDir/client-reports/`
(`queue.jsonl`, `install-id`, `network-token`) and `subscriptions/server-ids.json`.

## Known limits

- An attempt that fails before the core starts (the server name does not resolve in bootstrap) is not
  reported yet: the `resolve` step is never sent by this version.
- Service slots (YouTube, Telegram, AI) switch servers without a check, so they produce no reports.
