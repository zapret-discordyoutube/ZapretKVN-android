# Connection reports for status.zapret.moe

Owner decisions of 15–19.11.2026 (programme «honest coverage», stage S4). The service's status page
checks its servers from Russian datacenters and home scanners; none of them sees the filter of a
particular home provider. Real connection attempts of Zapret KVN from Russian home networks are that
third source: the page shows a server as reachable or not from homes only when at least 3 installs
on at least 2 networks (ASNs) reported the same within 24 hours.

## What is sent

One report per check of one server of the service's own subscriptions (the reply carries
`zapret-vpn-service`), to `POST https://reports.zapret.moe/v1/reports`, contract
`client_reports/contract.py` of vpnbot, schema 3 since 2026-11-19:

- `server_id` and `product` — the catalogue id from the `zapret-server-ids` header of the subscription
  reply: unpadded base64url of zlib-compressed (RFC 1950) JSON `{"<link fragment>": {"server_id",
  "product"}}`, at most 14 336 chars (vpnbot omits the header rather than truncate it; the app inflates
  at most 512 KB). VLESS product subscriptions name VLESS/Trojan/Shadowsocks lines as product `vless`;
  Hysteria 2 subscriptions `hysteria2`. Relay rows «… через Россию» are not in the header (a report
  would not be about the foreign server). A repeated name (tag «name · 2») uses the entry of its name.
  A server without an entry is reported as `foreign` (see below), and the id is never guessed from the name.
- `outcome` `connected` / `failed` / `cancelled`, elapsed ms, attempt time.
- Schema 3 (vpnbot «schema 3 live» 2026-11-19 21:37 UTC; owner: analytics live in vpnbot, the apps are open
  source): facts, never a verdict. `carrier` (`tcp`/`udp`, `ReportChecks.carrierOf`: UDP for Hysteria 2, TUIC,
  WireGuard/AWG, mKCP and QUIC transports, XHTTP whose ALPN is only `h3`); `core_code` (the core's own code in the
  shape `^(TARGET|LOCAL)_[A-Z0-9_]{1,48}$`, or `NETWORK_LOST`); `checks` {probe: {`r`, `ms`[, `b`][, `same`]}}:
  every probe that ran, by its wire name, with `answered` / `timeout` / `reset` (RST or EOF after the connection
  was up) / `refused` (RST to SYN) / `unknown` / `nxdomain` (DNS), its time and, for a flow probe, bytes. vpnbot
  judges and answers each batch with `verdicts[i]` {`detail`, `level`, `confidence`, `text`} in batch order
  (`ClientReportJson.parseReply`). A queue line written by an older version still goes out as schema 2.
- app name, version and platform; a random 32-hex `install_id` made on the device (not tied to the
  account); `sent_via` `direct` or `tunnel`; the `network_token` described below.

No account, key, domain or Wi-Fi name.

Schema 2 (owner decisions 2026-11-19, live at the receiver since 16:07 UTC) adds, all optional and
written only when known (a bad value drops out instead of losing the report, `AttemptDetails.cleaned`):

- `server_ip` (the address the start connected to, or the one the path check probed) and `port`;
  `transport` (`tcp`, `ws`, `grpc`, `xhttp`, `quic`, `udp`, …) and `security` (`reality`, `tls`, `none`);
- `network_type` (`wifi`, `cellular`, `ethernet`, `other`) of the non-VPN network;
- `settings`: `mode` (always `tun`), `dns` (`automatic`, `android`, `secure`, `fromjson`), `masking` (`on`/`off` by the master switch), `quic_blocked`, `ipv6` (`proxyIpv4Only` off);
- `versions`: `os` (Android release), `xray`, `hysteria`, `singbox`, `amnezia` from `core.properties`, shortened by `ReportVersions.short` to the contract's 32 characters.

The receiver stores the sender's IP and its /24 (IPv4) or /48 (IPv6) for `sent_via: direct` reports
only; tunnel reports store neither. Everything is kept 90 days.

Foreign servers (not from the service's subscriptions) get the path check on screen, and their failure is
reported by itself (owner 2026-11-19: «send everything we possibly can automatically, even without the
button», own servers in full, address included): `foreign` {`host`, `protocol`, `name`} replaces
`server_id`/`product` (exactly one of the two, or the receiver refuses), with the server's `server_ip` and
`port`. The name loses Unicode control/format characters and is cut to 64 code points (flags survive);
the host is lower-cased, an IPv6 zone is never sent. The receiver keeps them in its own table, never in
our servers' verdict or ranking.

`report_id` (vpnbot «report_id live», 2026-11-19): 32 lowercase hex characters from the system's secure random
source, made once when the attempt is recorded and kept in the device queue (key `r`), so every re-send of the
same attempt carries the same id and vpnbot stores the first copy only. Queue lines written before carry none and
go out without it. The 202 reply also says how many were `repeated`; a 400 for a re-send older than 7 days drops it.

`via` (vpnbot, 2026-11-19): a «… через Россию» line's header entry is `{"relay_of": "<target id>", "product",
"via": "<10 hex>"}` with no `server_id`, so a parser that does not know relay lines (0.6.39 / 0.8.94 and older)
skips it instead of counting the attempt against the target. A server's own line stays `{"server_id", "product"}`;
any other shape (`server_id` together with `via` or `relay_of`, a relay without a readable key) is skipped. The
report of a relay attempt carries `server_id` = the `relay_of` value and top-level `via` (queue key `w`), never with
`foreign`; vpnbot keeps such attempts out of the target's own verdict and shows them as a separate path. For about
an hour on 2026-11-19 vpnbot sent the interim shape `{"server_id", "product", "via"}`, which the older apps stored
as the target itself, so the stored map got a format marker (the file is now `subscriptions/server-ids-2.json`, the old one is deleted): a map without it is dropped once and the
backfill below learns it again from the header. The network value of `/v1/network` became v2 the same evening (it signs the
address the ingest saw); v1 values are no longer placed in any network, so the cache file is `network-token-2` and
an updated app asks a fresh one.

Catalogue ids (`zapret-server-ids`) come only with a reply of the service subscription. A subscription fetched
before the header existed (2026-11-19 13:07 UTC) keeps no ids, and then no report can name its servers: the
owner's own PC, with subscription auto-update off, sent none. So once per run the app checks each service
subscription without ids once, only for the header; the server list is not changed (Windows: a check-only
fetch, `_learn_missing_server_ids`; Android: `ServerIdBackfill` at the first connection of the process, no timer; the body is dropped). Log line:
`[client-report] … has no server ids: one check for the header` / `client-report: подписка сервиса без id серверов`.

## Where the decision lives

- Module `client-reports` (pure Kotlin, JVM tests): the contract and its JSON, the header parser,
  `ReportChecks` (probe names and limits, carrier, core code shape), `AttemptOutcome` (the attempt as a fact),
  the reply's verdicts, the disk queue (`ReportQueue`, at most 500 records, a week at most; one line per
  `report_id`) and `NetworkTokenCache`. No rule and no verdict text: vpnbot judges.
- `app/.../clientreports/ClientReporter.kt`: network and Android glue only.
- The hook is `SessionHealthVerifier.verify`, the single DNS/HTTPS check of the selected server for
  start, fallback and switch; the bootstrap TCP preflight (part of every start, `ProxyBootstrapper.reach`)
  records the first address's result and time, sent as `tcp_preflight` when the path was not checked.

## How it is sent

No prompt and no setting (owner rule). No timers: a flush runs after each new report and on app
start. A batch (≤ 50) goes directly over the non-VPN network first, so the ingest sees the network
itself; if no answer comes, it goes through the tunnel. `GET /v1/network?install_id=…` is asked only
directly; its token (24 h, bound to the install) is kept with the Android network handle it was
obtained on and attached to reports of attempts made on that network, so a report sent later or
through the tunnel still counts for the right network. 202 removes the batch, 400/413 drop it,
429/5xx keep it until the next report.

## Ingest names and small requests (owner 2026-11-19)

`reports.zapret.moe` alone is usually cut in Russia: the whole zone `zapret.moe` is filtered by name (SNI).
The ingest is therefore also served under random names of the zapret-links pool (service `reports`, a bridge
in CT 204 to the same ingest; the visitor goes on in `CF-Connecting-IP`, so the ingest still sees the
sender's network). `IngestHosts` (module `client-reports`) keeps a fixed seed (the two pool names of the
release day, which zapret-links never removes, and `reports.zapret.moe` last) and learns more from the shared
feed: `services.reports.hosts` of the zapret-links entry pages (script.google.com `?format=json` and the GitHub
gist, both hard to block). A name is accepted only under `domains` of the same document. The feed is asked on
a send, never by a timer: when the names are older than a day, or when none answered (at most once an hour);
`client-reports/ingest-hosts` keeps them.

Each send tries up to 4 names per path (directly, then through the tunnel), in a shuffled order that
alternates domains, with names silent in the last 30 minutes at the end. One request is one batch on its own
new connection (`Connection: close`, fixed-length body) of at most 8 KB (`IngestHosts.MAX_BODY_BYTES`): the
TSPU freezes a TCP connection to part of the foreign networks after ~25 packets both ways (~16 KB), counted
per connection, and a TLS handshake plus 8 KB and the answer stay near 15 packets. A long queue goes as
several small requests. The log line names the domain and the size:
`пакет 3, 1180 Б (direct, osjcpjzipyb.click) → HTTP 202`.

## Path check after a failure (owner 2026-11-19)

The owner asked three times that the check stay quiet on the wire: «always do it randomly so TSPU doesn't notice,
and not make unnecessary requests to the servers»; «make sure there are no unnecessary checks that could trigger
DPI»; «check as rarely as possible and only if it's really necessary». Offered a design with no probe outside the
tunnel at all, he chose to keep these rare minimal probes («if it happens very rarely then let's keep it … it
won't be possible to correlate any pattern from it»; no QUIC probe: «quic is often blocked in Russia»). So
(`app/.../clientreports/BlockProbe.kt`, sockets of the non-VPN network; the same ladder as Windows):

- No hand-made packet goes on the wire. TLS probes use Android's own TLS (`SSLSocketFactory.getDefault()`), the
  ClientHello of ordinary apps, never one that could become this app's fingerprint; a certificate (trusted or not)
  or an alert from the server is an answer. There is no QUIC probe.
- No probe when the core's code already proves the server answered (`TARGET_AUTH_REJECTED`, `_CONNECTION_REFUSED`,
  `_CONNECTION_CLOSED`, `_OBFS_REJECTED`, `_TLS_*`, `_PIN_MISMATCH`, `_DESTINATION_UNREACHABLE`), for `LOCAL_*`
  and `NETWORK_LOST`, and never for a server that works.
- Rarely: one check per server (its name and its IP) a day, one per install in ten minutes
  (`ClientReporter.PATH_CHECK_PER_SERVER_MILLIS`, `PATH_CHECK_PER_INSTALL_MILLIS`); vpnbot sums every install.
- Random gaps of 150–700 ms between probes (`BlockProbe.gap`, the one allowed `delay` of the file in
  `scripts/verify-project.sh`); neutral names drawn from a pool on each run.

The ladder, each step only while it can still change the answer:

1. `control_front`: TCP to our pool front. Silent → `control_neutral` (TCP and TLS to a neutral Russian name:
   ya.ru, vk.com, www.gosuslugi.ru, ozon.ru, www.wildberries.ru) and stop: the server is never touched.
2. The address the start itself used; without one `dns_doh` (our resolver, encrypted), and `dns_system` (the
   server's name in plaintext) only when DoH did not answer.
3. TCP lines: `tcp_target`; silent and the port is not 443 → `tcp_443`; answered and TLS → `tls_real`; no answer
   → `tls_neutral`. The real name is never sent to our front.
4. UDP lines: no probe of the server; the core's own handshake already was one.

Inside the tunnel anything is invisible to DPI as a separate probe:

- `flow` after a success on a TCP line, at most once per server in 6 hours: up to 64 KB of the speed-test file
  (`fra.download.datapacket.com`, not Russian) through the VPN network; `b` is the bytes before a stall.
- Tunnel up, no data (the HTTPS check through the server failed, `trafficFailed`): `NoDataProbe` sends HEAD to a
  connectivity-check name (`generate_204`) by the literal address our DoH gave (`tunnel_a`, no DNS in the tunnel)
  and by name (`tunnel_b`, DNS through the tunnel) over the VPN network, and the same two over the non-VPN network
  (`direct_a`/`direct_b`). Only while the same session still serves the same server (`stillServing`, passed by
  `SessionHealthVerifier`): after a failover the tunnel would test another server. No probe of the server's path
  then: the tunnel proved it.

## Completion: the failure at once, the checks after (vpnbot «completion live» 2026-11-19 22:16 UTC)

A failure that will be checked goes at once as schema 3 with `complete: false` and what the core knows, so the
panel sees it without waiting and the attempt is placed in the network it was made from. After the check the same
report goes whole: the same `report_id`, `attempted_at` and server, `complete: true`, with the checks; vpnbot
replaces the waiting copy. An unsent first copy gives way to its completion in the queue. A failure with no check
goes once, without `complete`.

## The notice shows vpnbot's verdict

The line under the error is vpnbot's `text` for this report, as is (the app keeps no rule and no verdict text): a
preliminary one after the first copy, the final one after the completion. While the check runs it says
«Проверяем, где закрыт путь до сервера…»; until an answer arrives «Разбор сбоя появится, когда отчёт дойдёт до
сервиса.» (`ClientReporter.WAITING_TEXT`).

## Every failure is sent by itself; «Отправить отчёт» (owner 2026-11-19)

Every failed attempt is written and sent without asking (owner 2026-11-19, after a failure on his screen never
reached the panel): a failure that is not about the server goes with its raw `LOCAL_*` code or `NETWORK_LOST`
(the app's own start codes such as `CORE-…` have no contract shape and are left out), which vpnbot keeps out of
the server verdict and ranking. Servers of the user's own go as `foreign` (see «What is sent»).

The button «Отправить отчёт» next to the error stays for a user who wants to send it himself: it sends the queue
at once (the report is normally already written; it never writes a second one). The note under it says «Отчёт
отправлен» or «Отчёт в очереди, отправим при первой связи».

## Seeing it act

The runtime log (layer `app`) has a `client-report:` line per queued report and per sent batch
(`пакет N (direct) → HTTP 202`), the first copy and the completion (`… failed TARGET_…, проб 4, дополнение`), one
per path check (`проверка пути → control_front=answered/41мс tcp_target=timeout/4000мс …`, no addresses), vpnbot's
verdict (`вердикт sni_blocked (sni, proven)`), per flow measure and per button press. The files are in `noBackupFilesDir/client-reports/`
(`queue.jsonl`, `install-id`, `network-token`) and `subscriptions/server-ids.json`.

## Test runs send nothing (2026-11-19)

Device tests connect to local test servers, and every failure, even on a server that is not ours, is reported by
itself, so a test run would fill the production store with test rows (Windows 0.8.97 gate did: «A-works»,
«B-udp-lost»). The test runner `ZapretTestRunner` (androidTest) sets the JVM property
`zapret.kvn.client_reports=off` in its `onCreate`, before `Application.onCreate` and the lazy `AppContainer`;
`ClientReporter` then writes nothing to the queue, asks no token and sends nothing, while the notice and the path
check still work. The runtime log says `client-report: выключены (zapret.kvn.client_reports=off)`. Rule:
`ClientReportSwitch`, test `ClientReportSwitchTest`.

## Known limits

- An attempt that fails before the server check (bootstrap, core start) goes through
  `ClientReporter.onStartFailure` from `VpnRuntime.handleStartFailure`: it is always reported, with the
  path check's verdict when it names the server (for example `resolve`/`dns_nxdomain`), otherwise as `local_*`.
- A check cut by the start budget (`StartupBudgetExhausted`) is a failure with no traffic, not `cancelled`.
- Service slots (YouTube, Telegram, AI) switch servers without a check, so they produce no reports.
