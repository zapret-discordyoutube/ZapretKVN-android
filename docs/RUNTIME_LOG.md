# Detailed runtime log (`runtime-log`)

Owner decisions of 2026-11-08: write a detailed log to disk; drop nothing until the file reaches 100 MB;
discard noise and keep everything useful; keep domains and IP addresses; write quickly and compactly; log
more lines per layer so that errors and the place where playback hangs are found faster; export the
diagnostics as one archive containing all files.

Why. The in-memory diagnostics (`DiagnosticState`) keep 200 lines, at most 48 core lines per batch, and
evict the "ordinary" lines when space runs out. That is enough to understand why a connection did not come
up, but not enough to find the moment where an already working stream (YouTube video) hung. The disk log
writes the whole core stream and all app events in order, with no selection by line count.

The log lines themselves are written in Russian (the code writes them that way); this document quotes them
literally so that `grep` keeps working, and glosses them in English.

## Where it lives

`noBackupFilesDir/runtime-log/log-NNNNNN.zkvn`: outside Android backups, in the app's private folder.
Segments are 4 MB each; the total never exceeds 100 MB (`RuntimeLogStore.DEFAULT_MAX_TOTAL_BYTES`): before a
new segment is opened the oldest one is deleted, so the total including the new segment fits. A segment is
opened when the app starts; after a restart the numbering continues and the previous segments stay (and are
trimmed to the cap at startup).

## Layers

| Letter | Layer | What is written |
| --- | --- | --- |
| `A` | app | app events: start, connection stages with their duration, "Путь" (path) decisions, failures |
| `N` | network | Android network: transport, interface, validated/metered/captive, Private DNS (an identical repeat is not written) |
| `D` | dns | core lines `dns:` and `dns/…`: queries, answers with timing, failures, `context canceled` of the losing branch |
| `R` | routing | core lines `router:`, `route`, `rule…`: app package, rule, outbound |
| `T` | tun | core lines `inbound/tun`: incoming connections and packets |
| `O` | outbound | core lines `outbound/…`, Hysteria, Xray: outgoing connections, drops, `stateless reset` |
| `F` | traffic | traffic sample every ~5 s, flow lines and the borders of "no incoming" windows |
| `C` | core | everything else from the core |

The layer of a core line is decided by `CoreLogLayers.classify` from the body of the line. The level comes
from the core: 0 panic, 1 fatal, 2 error, 3 warn, 4 info, 5 debug, 6 trace. All levels arrive through the
stream regardless of `log.level` in the config: in the core's `log/observable.go` a line is dropped by level
only when there is neither a platform writer nor a subscriber, and the app is always connected as a subscriber.

## Line format

The first line of a segment: `#zkvn-log 1 <start, epoch ms> <number>`. After that, one record per line:

```text
<ms since segment start, base36> <layer letter><level digit> <text>
```

Example: `1k2z D5 INFO[0840] [3650259972 91ms] dns: exchanged rr3---sn-x.googlevideo.com. A 142.250.1.5`.
Only `\`, line feed and carriage return are escaped in the text. The time is an offset, so it takes 3-6
characters instead of 13. Compression happens at export (zip, level 3), where the text shrinks several times.

## What is not written (noise)

Only `TRACE connection: … upload closed` and `… download closed` from the core (`LogNoiseFilter.Default`):
two lines per closed connection with no information; the duration is visible in the `outbound` lines and in the
traffic samples. Everything else, including DEBUG and other TRACE lines, is written. The number of discarded
noise lines is counted and appears in the summary (`filtered_noise_lines`).

## "No incoming" windows

`TrafficStallTracker` watches the counters: connections exist, outgoing traffic flows, incoming traffic is
below 8 KB per window for two windows in a row. Then the `F` layer gets
`НЕТ ВХОДЯЩЕГО: … с <start of the first bad window>` ("NO INCOMING: … since …"), and when the flow returns
(more than 64 KB per window) or fades away, `НЕТ ВХОДЯЩЕГО закончилось через N мс` ("… ended after N ms").
This may be a frozen video, or a player pause with a full buffer: the counters cannot tell them apart, so the
event only marks the borders and a person decides (what was on the screen at that time). The count uses the
through-proxy counters when they exist: direct traffic would mask a dead tunnel. The window resolution is
about 5 s; the `D` and `O` core lines around that time show the cause: a lost DNS query, `stateless reset`, a
closed connection.

## How to read

Unpack the archive; the segments are in `runtime-log/`. A layer: `grep -h ' D' log-*.zkvn`. The windows:
`grep -h 'НЕТ ВХОДЯЩЕГО' log-*.zkvn`, then the lines nearby in time: convert the base36 offset to ms
(`int(x, 36)`) and add it to the start time from the segment header. A ready reader for this format lives in
the task journals directory: `/root/.agent-todo/zapret-kvn/tools/read_runtime_log.py`.

## Privacy

- Domains and IP addresses are kept as they are, including the VPN server address and the list of opened sites.
- Secrets are always masked: links with a scheme, keys, tokens, authorization headers, UUIDs, PEM blocks
  (`SecretRedactor.redactInline`). Masking runs on the writer thread, so `log()` never waits.
- The JSON summary `diagnostic.json` still contains no server addresses; its fields
  `privacy.runtime_log_persisted`, `runtime_log_in_archive` and `runtime_log_contains_domains_and_ips` state
  honestly that the log is on disk and in the archive and that it contains domains and IPs.
- The "Diagnostics" screen warns about this before sending. The archive leaves the device only when the user
  presses the button.

## Export

The "Export diagnostics" button creates a single archive `zapret-kvn-diagnostic.zip` in a temporary cache
folder (`cacheDir/diagnostics`, removed at the next start): `diagnostic.json`, `log-format.txt` and
`runtime-log/log-*.zkvn`. The size of the current segment is fixed at the beginning, so the log can keep being
written during the export.

## Robustness

- `log()` never blocks: a queue of 32,768 records, written by a separate thread. An overflow is counted
  (`dropped_lines`) and a line "очередь переполнена, потеряно строк: N" ("queue overflowed, N lines lost") is
  written to the log.
- A disk write failure does not take the VPN down: the lines count as lost, and re-opening the segment is
  retried at most once every 5 s.
- A masking failure never lets a raw line through: "строка не записана" ("line not written") is written
  instead.

## Related core fix

Patch `core-patches/0009-hysteria2-serialize-udp-send.patch`: `hyPacketConn.WriteTo` serializes writes into
the Hysteria2 session. The official client marks `Send` as not thread-safe (a shared `SendBuf`), and the shared
UDP DNS transport writes into one session in parallel (A and AAAA of every name): without a lock two queries
overwrote each other's buffer, one went out twice and the other vanished and waited for a timeout (that was
the "lost" UDP while TCP answered instantly). The test `TestPacketConnSerializesConcurrentSends` fails without
the lock (16 simultaneous entries into `Send`) and passes with it under `-race`. The protocol and the official
client are unchanged: the sing-box glue was fixed.

## Hysteria connection conditions

Patch `core-patches/0010-hysteria2-handshake-log.patch` appends the handshake conditions to the line
`official Hysteria2 connected …`: `udp=` (whether UDP is enabled on the server), `congestion=` (BBR, or Brutal
with the agreed rate), `configured up/down=` (the speeds from the subscription) and `ech=`. It exists to
explain low speed or loss: Brutal at an overstated rate chokes the flow, and `udp=false` sends QUIC nowhere.
Note: the secret redactor masks the value after `ech=`, so it shows as `•••`.

## QUIC blocking

The setting "Блокировать QUIC (UDP 443)" ("Block QUIC (UDP 443)", Settings → Advanced, on by default, owner
decision of 2026-11-08) adds the rule `{"network":"udp","port":443,"action":"reject"}` to the runtime copy,
after the service rules (hijack-dns and others) and before the user rules, in every DNS mode. The profile on
disk does not change. Reason: video went over QUIC inside Hysteria2 datagrams (QUIC inside QUIC) and the player
tried seven `googlevideo.com` hosts in 14 s; rejecting UDP 443 moves apps to TCP at once. It applies at the next
connection. Hysteria's own outer QUIC tunnel is not affected: the server socket is protected and bypasses the
TUN. Result of the field test (0.5.36): blocking QUIC did **not** speed YouTube up, so QUIC is not the cause.

## Flows through the proxy

The counters of all traffic do not show which flow received what. Patch
`core-patches/0011-proxy-flows-snapshot.patch` adds `CommandServer.proxyFlows(closedAfter, limit)` to the core:
a snapshot of the flows through the proxy as one JSON string (active ones, heaviest by incoming bytes first,
and flows closed after the cursor with their final byte counts). The app calls it itself at the moment of its
traffic sample (about every 5 s, only while the screen is on or diagnostics are open), next to
`proxyTraffic()`. **There is no subscription to connections**: invariant `I6-05` ("never enable
`CommandConnections`", checked by `scripts/verify-project.sh`) is respected.

`FlowTracker` compares the totals of a flow with the previous snapshot and writes to the `F` layer:

- `закрыт tcp <name> (<app>, <outbound>): жил 12.5с, вверх 2КБ, вниз 1.0МБ` ("closed … lived 12.5 s, up
  2 KB, down 1.0 MB") for every closed flow;
- `потоки: живых N, за окно вверх …, вниз …; топ по входящему: <name> tcp ↓3.0МБ (жив 8.2с), …` ("flows: N
  alive, in the window up …, down …; top by incoming: …") for every snapshot;
- `ждут ответ (N): <name> tcp жив 40.0с, вверх 4КБ, всего вниз 12КБ` ("waiting for an answer (N): …", level 3)
  for flows older than 10 s that sent data during the window and received nothing. This is a description,
  not a verdict: a flow may be waiting while the player fills its buffer.

This shows which YouTube flows download and how fast, which hang, and how long each one lives.
