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

The closed-flow line also carries the average download speed over the whole life of the flow
(`ср. вниз 82КБ/с`, "avg down 82 KB/s"). It exists so that YouTube cache hosts, and servers, can be compared with
numbers.

## Tunnel QUIC statistics

Patch `core-patches/0012-hysteria2-tunnel-stats.patch` lets the core report the state of the QUIC connection of the
tunnel itself: round-trip time (min, latest, smoothed, deviation), packets and bytes sent and received, and lost
packets, with the figures for the last window and the share of lost packets. The app asks for it
(`CommandServer.tunnelStats()`) at its own traffic sample, so there is no timer inside the core and nothing wakes
the phone while the screen is off. The `O` layer then gets one line per Hysteria2 server:

```text
<server tag>: tunnel quic: rtt min=78ms latest=91ms smoothed=85ms dev=9ms; total sent … pkts …, recv … pkts …, lost … pkts; last 5.0s: sent … , recv …, lost N (x.xx% of sent)
```

How it works: the Hysteria client does not expose its QUIC connection, so the patch reads the private field
through reflection inside the connection callback, where the wrapper's lock is held. Any change in the layout of a
new Hysteria version produces an error and the statistics simply disappear; it never panics. The test
`TestQuicConnOfReachesTheTunnelConnectionOfARealClient` starts a real Hysteria server and client in one process and
proves the accessor works against the real library. Use the numbers to compare servers: a path with a high lost
share or a high RTT explains slow flows without guessing.

## Resetting stalled YouTube flows

Owner decision of 2026-11-08: a YouTube connection that is stuck should be cut at once, so that the player opens a
new one instead of waiting for its own long timeout. Patch `core-patches/0013-proxy-flows-close.patch` adds
`CommandServer.closeFlows(ids)`, which closes the listed proxy flows (direct and service flows are never touched).
It is a direct call, like `proxyFlows`: there is no subscription to connections (`I6-05` is respected).

`StalledFlowPolicy` decides which flows are stalled. A flow is reset only if **all** of these hold: it is TCP, its
name is `googlevideo.com` or a subdomain, it is at least 8 seconds old, it has received at most 4 KB, and it has
sent something (the request left). Flows without a name, UDP flows and every other site are left alone. At most
3 flows are reset per snapshot (oldest first) and at most 30 per sliding window of 5 minutes (it was 30 per session until 0.5.44, which ran out after 40 minutes in the 0.5.43 field archive), which is a fuse in case the rule matches
healthy flows by mistake. Each reset writes a line to the `F` layer:

```text
сброс зависшего потока <name> tcp: жил 8.4с, вверх 1КБ, вниз 647Б; плеер откроет новое соединение
```

("reset of a stalled flow … lived 8.4 s, up 1 KB, down 647 B; the player will open a new connection").
The behaviour has a switch, "Сбрасывать зависшие соединения YouTube" (Settings → Advanced, on by default), read at
the start of a session, so a change applies from the next connection. Limits worth knowing: a pre-opened idle
connection that received only a short TLS answer can match the rule and is reset too, which only costs the player
one reconnect; and because snapshots come every ~5 s, the reset happens between 8 and 13 s after the flow was created.

## Speed reference: what is high and what is not

The log grades speeds with `SpeedClass`. The thresholds follow what YouTube video needs (approximate bitrates:
360p about 0.7 Mbit/s = 90 KB/s, 720p 2.5-4 Mbit/s = 300-500 KB/s, 1080p 5-8 Mbit/s = 600-1000 KB/s, 4K from
20 Mbit/s). Playback starts smoothly only when the download is faster than the bitrate; otherwise the spinner turns
while the buffer fills.

| Class (log word) | Per-flow speed | What it means for video |
| --- | --- | --- |
| `быстро` (fast) | from 600 KB/s | 1080p and above with room to spare |
| `норма` (normal) | 200-600 KB/s | 720p plays, 1080p is at the limit |
| `медленно` (slow) | 50-200 KB/s | enough for 360p-480p; start and seeking are slow |
| `очень медленно` (very slow) | below 50 KB/s | not enough even for 360p; the buffer does not fill |

A flow is graded only after it moved at least 64 KB and lived at least 1 s, because the handshake and the ramp-up say
nothing about the path. Closed-flow lines show the average over the whole life of the flow with its class; the top
flows of a window show the speed of that window.

The tunnel line gets a channel grade from the QUIC statistics (`TunnelGrade`): `хороший` (good) when loss is below 1 %
and the smoothed round-trip time below 120 ms, `средний` (medium) for 1-3 % loss or 120-250 ms, `плохой` (bad) from
3 % loss or 250 ms. Real figures from the field archives: a healthy tunnel showed 51-71 ms and 1 lost packet out of
1703 (0.06 %), and carried about 1 MB/s at the start.

How to read the two together. A good channel with a very slow flow means the problem is not the tunnel: it is the
server or cache node on the other end (or the app). In the 0.5.38 archive the same Hipge tunnel (good channel)
carried one YouTube cache node at 120-160 KB/s and two others at 4-7 KB/s in the same minute.

### How to speed up further

1. Choose the server by YouTube performance, not by ping: the log summary (below) shows the average speed per
   server and per cache node. A server whose YouTube average is `медленно` or worse is the wrong server for video even
   if its channel grade is good.
2. Let the app cut bad flows (the reset of stalled and trickling flows): the player reconnects at once and often lands
   on a better cache node. The longer the app runs, the earlier it cuts nodes it has seen slow.
3. Keep QUIC blocked only if it helps on your server; the field test on 0.5.36 showed it was not the cause.
4. Compare servers with the numbers in the archive (`speed-history.txt` and the `история …` lines) instead of by feel.

## Speed history ("the more you use the app, the smarter it gets")

Owner decision of 2026-11-08: keep the history, store it compactly, show it in the logs. `SpeedHistory` keeps one
record per pair (server, target) instead of one per flow: flows, graded flows, bytes and time, a running average speed
(EWMA, weight 0.3 for the newest flow), the number of slow and fast flows, the number of resets and the last day seen.
The target of a YouTube video flow is the cache node (`yt:sn-1giz7n7l`, the `sn-…` part of the host name
`rr2---sn-1giz7n7l.googlevideo.com`), for other names the last two labels of the domain, for flows without a name the
/24 network of the address.

- **Nothing is deleted.** When there are more than 4,000 records, the least informative ones (fewest flows, longest
  unseen) are merged into the record `*другие` ("other") of the same server, so the totals survive.
- **Compact file:** `noBackupFilesDir/speed-history.txt`, one tab-separated line per record, written atomically and at
  most once a minute. A damaged file is ignored and rebuilt. The file is also put into the diagnostics archive.
- **Shown in the log:** at the start of every session the `F` layer gets `история скоростей: …` and, per server,
  `история <server>: YouTube ср. …/с (class), оценённых потоков N, медленных M, быстрых K, сбросов R; медленные узлы: …;
  быстрые узлы: …`.
- **It changes behaviour:** a cache node that was slow before for this server (at least 3 graded flows, running average
  below 50 KB/s, at least 70 % slow flows) is "known slow", and a trickling flow to it is cut after one window from
  6 s of age instead of two windows from 10 s.

## Trickling flows

Besides flows that received almost nothing, the policy cuts flows that keep sending more than they receive. Rule:
`googlevideo.com`, TCP, at least 10 s old, under 3 MB received in total, and in two consecutive windows at least 8 KB
sent with no more received than sent. Healthy video downloads are the opposite (a lot received, little sent); a player
pause sends nothing. Reason: in the 0.5.38 archive the flows to `74.125.162.225` and `74.125.11.74` trickled at 4-7 KB/s
for 25 s and one of them sent 203 KB while receiving 81 KB, so the old rule (at most 4 KB received) never fired.

## Recognizing a slow video stream fast, and moving to a faster server

Owner decisions of 2026-11-08: recognize a slow video stream at once, "even faster", and redirect to a faster
server, as an option. The design has four parts, each with one job.

**1. Faster sampling.** The coordinator runs a loop that asks the core for the flow snapshot (`proxyFlows`) every
4 s while the screen is on, and every 1.5 s while a `googlevideo.com` flow exists. With the screen off there is no
loop at all (I6-03). The window line in the log is still written about every 4 s, so the log does not grow.

**2. The detector (`SlowVideoDetector`, pure, tested on the numbers of the field archives).** A *slow video flow* is a
TCP flow to `googlevideo.com` (not `redirector.`), at least 4 s old, that has received from 16 KB to 256 KB, has sent at
least 3 KB, and runs below 40 KB/s in the last window. A healthy flow takes 550-600 KB in its first 3 s, so it is never
slow; an idle pre-connected flow has received less than 16 KB, so it is not a video flow yet. *Starving* means: there is
a slow video flow, no healthy flow (from 120 KB/s) beside it, the total video speed of the window is below 60 KB/s, and
this holds in 2 consecutive samples (about 3 s at the fast rate). The log writes `ВИДЕО ГОЛОДАЕТ: …` when it starts and
`видео снова идёт …` when it ends. In the 0.5.39 archive the stuck flow `74.125.11.70` would be recognized about 6-8 s
after it started, instead of never.

**3. Names for the flows.** The detector and the history need the host name, but the flow record often has none (the
player connects to an address from an earlier answer). `DnsAddressBook` learns `address -> googlevideo name` from the
`dns: exchanged A …` lines of the core itself and fills the name in (entries expire after an hour, at most 2,000).

**4. The switch (`YoutubeSwitchEngine`), optional.** Setting "Менять сервер при медленном YouTube" (Settings → Advanced,
**off by default**: a switch can change the exit country). When it is on and the verdict is *starving*:

1. the current server is marked "bad for YouTube" for 30 minutes;
2. candidates are taken from the same profile group: valid, not in maintenance, no failed ping, not marked, and not
   with a bad history; first servers with a good YouTube average from the speed history (best first), then servers
   without history by ping; at most 2 per episode;
3. the shared `ServerSwitcher` makes a trial switch (nothing saved), the old video flows are closed so the player
   reconnects through the new server, and the detector is reset;
4. the new server is verified **on live video**, not with a download of a foreign file: a healthy video flow within
   15 s commits the choice and announces it; starving again marks that server too and tries the next candidate;
   silence (the video never started, for example the user left) is inconclusive and the previous server comes back;
5. if every candidate is slow, the original server comes back.

**5. Dead cache clusters (`DeadVideoClusterTracker`, 0.5.44).** The field archive of 0.5.43 (2026-11-13) showed a stall
the detector above cannot see: the player opened flows to cache nodes of ONE cluster (`sn-8ph2xajvh`), sent the
TLS ClientHello (about 2 KB) and got **not a single byte** back, again and again. Over 5 days that cluster gave 146
flows, 137 with 0 B, none healthy, on every node. Resetting such flows only makes the player retry the same cluster
sooner, and `SlowVideoDetector` stays silent because a flow with 0 B is not a "slow video flow" (it needs 16-256 KB).
What helps is another exit address: YouTube chooses the cluster by the address the player's request came from, so a
different server usually gets a different cluster (on the phone the same video played through another node within 7 s).
The cluster is the part of the cache node name between `---` and the second dash: `rr2---sn-8ph2xajvh-3a4l` is `sn-8ph2xajvh`.
Both spellings of the name count: the player asks for `rr2---sn-…`, but a flow is named from the DNS answer, which CNAMEs to
the dotted form `rr2.sn-…`. **Field bug (0.5.47 archive, 2026-11-13 23:02-23:06):** the tracker only knew the `---` form, so
three batches of three silent flows (948 B back after 8.1 s) to `sn-8ph2xajvh` were never counted and the switch never ran,
while Chrome through the same node, cluster and cache address (128.75.235.173) received 1-6 MB. Fixed in 0.5.48; the test uses
the exact names and sizes of that archive. Windows `cluster_of` has the same regex and the same fix.

**Field test of 0.5.48 (2026-11-14 10:17-10:22 UTC, phone, Titiru).** The fix worked: the detector fired 14 s after ReVanced
started (`МЁРТВЫЙ КЛАСТЕР YouTube sn-8ph2xajvh`), the app moved to Horologium, and the video pulled 10.9 MB and 5.0 MB there. Then
two defects of the *automation* froze it at 3:13 (the owner reports the freeze was real, not caused by switching apps):
1. **False starvation.** A player with a full buffer stops fetching. Two 5 s windows with 0 B and one 36 KB/s tail flow were
   enough for `ВИДЕО ГОЛОДАЕТ`, 90 s after the first switch the engine probed Tiraru (dead, flows 0 B, timeouts) and left a
   working server. `SlowVideoDetector` now says "not starving" while the video flows received at least 3 MB in the last 30 s
   (`deliveredHoldBytes`, `deliveredWindowMillis`); the 0.5.39 field case (555 KB burst, then a trickle) still counts.
2. **Failover returned to the server with the dead cluster.** The probe candidate timed out, the general failover ranked the
   replacement by ping and chose Titiru, whose cluster was dead 90 s earlier. After a switch away because of a dead cluster
   the old server is now marked (`PathSupervisor.avoidForYoutube`, 10 min): it is offered LAST, never excluded, and the log
   says so (`кластер YouTube мёртв, при автоматической замене сервера он будет последним`).
3. **A dead server was probed twice.** At 10:43:33 and again at 10:44:10 the engine tried Clermany, which had just timed out and
   been marked dead by the failover. The engine's candidates now exclude `PathSupervisor.deadServerIds()` (servers confirmed
   dead in the last 5 min).
4. **Tiraru is not a safe fallback for this cluster either** (0.5.48, second replay 10:41-10:48 UTC): ReVanced got 0 B on two
   clusters there while Titiru and Chrome worked. Open, see the vpnbot router check.

5. **Silence is detected faster (0.5.51, vpnbot node measurement 2026-11-14).** The node side showed that cluster `sn-8ph2xajvh`
   accepts TCP and then sends NOTHING for 13+ s to a TLS ClientHello, while a healthy cache answers in 0.1-0.3 s. A googlevideo flow
   that sent a request and got ZERO server bytes is now reset after 3 s (`StalledFlowPolicy.silentMinAgeMillis`, was 8 s), and the
   dead-cluster tracker counts it as silent after 2.5 s (`silentMinLivedMillis`, was 5 s), so the verdict comes after about 4-5 s
   instead of 14 s. Flows with a few bytes (a started handshake) keep the old limits. The cap (30 resets per 5 min) is unchanged.
   Windows `DeadVideoClusterTracker` has the same rule (`silent_min_age_sec`).

**Saving the archive without a messenger (0.5.50, owner 2026-11-14).** On Android 14+ the share window of «Экспортировать
диагностику» has a custom action «Сохранить в Загрузки»: it copies the same zip to `Download/zapret-kvn-diagnostic-<UTC time>.zip`
through MediaStore (no permission, only on that tap). A developer with adb pulls the exact name (`adb pull` of the file whose
name starts with `zapret-kvn-diagnostic-`); nobody has to forward the zip. The archive is the same one that goes to a
messenger, with domains and IP addresses in it.

Not changed: the "slow YouTube" probe itself may still try a server whose YouTube average is poor; ReVanced stalls with real
cache addresses as well (see the 0.5.47 archive), the cause of that is still open.
The tracker reads the **closed** flows of the snapshot (so it works with or without the reset option) and counts a flow
as *silent* when it is TCP, sent something, received at most 4 KB, and lived at least 5 s (an instant reset is a refusal,
not a silent node). A cluster is **dead** after 3 different silent flows inside 90 s with no healthy flow (64 KB or more) of
that cluster in between; a healthy flow, a new session or a server switch clears the count. The verdict stays active
while the player keeps retrying (90 s after the last silent flow) and then fades. `redirector.googlevideo.com` and names
without an `sn-` label belong to no cluster. The verdict travels in `SlowVideoDetector.Verdict.deadClusters`; the
coordinator treats it like *starving* and starts the same switch episode, so the same gate (cooldown, limit, busy),
candidates and live-video verification apply. When the option "Менять сервер при медленном YouTube" is off, the log gets one
line per episode saying that the cluster is dead and the server is not changed. Log lines (layer `F`, level 3 and 4):

```text
МЁРТВЫЙ КЛАСТЕР YouTube sn-8ph2xajvh: 3 потока(ов) без единого байта ответа за 12с на <server>; сброс потоков не помогает, плеер получает тот же кластер (другой сервер получает другой)
кластер YouTube sn-8ph2xajvh снова отвечает (был мёртв 2м10с)
```

**Field result of 0.5.44 and the 0.5.45 change (2026-11-13).** On the phone the detector fired 6 ms after the third silent
flow and the engine switched to another server, but it rolled back after 15 s ("видео не пошло") three times: the player
that was already running held the URLs of the dead cluster (a media URL is signed and names its cluster in `mn=`; a cache
answers 400 for another cluster's host name and 302 for a backup cluster, measured on 2026-11-13, so the app cannot remap
host names), opened no video flow through the new server, and `verifyVideo` returned `Silent`, which the engine reads as
"the user left". A new `/player` request is needed, which only a restarted video makes. Since 0.5.45 an episode that began
with a **dead-cluster** verdict runs with `keepOnSilent`: when the video stays silent the candidate server is **kept** (committed),
the announcement and notification say "Перезапустите видео", and the timeline line ends with "выбран <server>, перезапустите
видео". The restarted video gets a cluster for the new exit address; if that server is slow too, the normal starving path
switches again (cooldown 90 s, 3 switches per 10 min). A starving candidate is still skipped, a refused commit still restores
the original server, and an episode that began with plain starving keeps the old rule (silence restores). Off switch: the same
option "Менять сервер при медленном YouTube".

The timeline line of the switch names the reason: `Переключение при медленном YouTube (кластер sn-8ph2xajvh не отвечает): выбран <server>.`
A switch does not always help: if the new server is handed a dead cluster too, the next candidate is tried within the
same limits. The tests (`DeadVideoClusterTrackerTest`, `RuntimeLogFlowsTest`) use the numbers of the 0.5.43 archive.

Guards: 90 s of cooldown after an episode, at most 3 switches in 10 minutes, no episode while another switching
operation or the lifecycle is busy. This engine is separate from the "smart check": it is not blocked by the 30 minutes
after a manual server choice (the user turned this option on on purpose) and it does not use the generic download probe.
The tests use a fake host and cover: commit, second candidate after a starving first one, all candidates slow, silence,
a refused commit, a failed trial, no candidate, candidate filtering and ordering, the gate and the slow marks.
