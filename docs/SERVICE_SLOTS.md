# Service slots: a dedicated server and switching logic per service (design draft)

Status: IN PROGRESS (phases 1 to 4b in code, behind a default-OFF beta setting; see "Status" at the end). Owner direction of 2026-11-14, written down so the design starts from the owner's words.
Language: English (owner decision 2026-11-08). Read `docs/ARCHITECTURE.md` and the root `AGENTS.md` section
"Architecture basis: native cores, sing-box is the gasket" first.

## Why

The field work of 2026-11-13/14 showed what one shared server costs: a slow Telegram transfer lowers the measured
speed and moves YouTube off a working server (12:37 UTC, Senge to Tiraru on 0.9 Mbit/s from mostly idle traffic); a dead
YouTube cluster on a node drags every other app through a server switch; and everything leaves through one exit
address, which makes the traffic easy to link and to block. The owner's answer: every service gets its own server and
its own switching logic.

## Owner decisions (2026-11-14)

1. YouTube, Telegram, AI apps and "everything else" are separate **slots**, each its own; any other application can get
   its own slot too. A slot groups **several apps**: every YouTube client (ReVanced, the official app, other builds) is
   one YouTube slot; Telegram is one slot for the official app, Telegram X and every fork.
2. Each slot has its own server, its own protocol and its own switching logic. Example: YouTube over VLESS from one
   server, Telegram over Hysteria2 from another. Every setting is configurable.
3. sing-box does the routing under the hood (several selectors, several server slots, several protocols).
4. A slot picks its server **automatically from a pool of the profile's servers, and the user can pin one**.
5. Rotation, first step: **on failure only** (no time-based or per-session rotation yet).
6. Home screen gets the slot cards **and** the «Переключать при низкой скорости» toggle.
7. Android first, then Windows.
8. Out of the box: **YouTube, Telegram, AI (ChatGPT, Claude, Gemini and related), Everything else**.
9. The pool belongs to the **slot** (owner's reading: a pool of servers formed for a slot, for specific apps).
10. An app that must go straight out (for example one that only works from a Russian address) is **not in any slot and
    is not shown among the slots**: it passes through as it does today (outside the VPN app scope, or through the
    existing RU-direct rules). Direct is the fall-through, not a slot.

## Model

A slot is a pure value in a tested module (no Android types):

| Field | Meaning |
|---|---|
| `id`, `title` | stable id (`youtube`, `telegram`, `default`, `app:<package>`), name on the card |
| `matchers` | domains (shared always-proxy list for YouTube), a **list of package names** (all clients of the service), IP ranges (Telegram datacentres, so an unknown fork is still caught), later rule-sets |
| `pool` | server ids the slot may use; **per slot**, taken from the active profile's servers (default: all of them), editable on the slot sheet |
| `pin` | optional server id; overrides automatic choice |
| `switching` | switch policy: `OnFailure` now; room for `LowSpeed`, `Rotate` later |
| `avoid` | server ids another slot is using, so slots do not share a server while the pool allows |

Per-slot logic is a policy object, not a flag inside one global engine. The existing engines
(`SlowServerSwitchEngine`, `YoutubeSwitchEngine`, `PathSupervisor`) become the policies of the `default` and
`youtube` slots; the throughput monitor and the video detector read only the flows that belong to their slot, so a
Telegram transfer can no longer starve or "heal" YouTube.

## Which traffic belongs to which slot

Two levels, so "direct" never becomes a slot:

1. **VPN scope** (existing per-app picker, owner 2026-11-14: "direct is the default route for all apps unless they are
   selected to go through the VPN"): an app outside the include list never enters the TUN and goes out directly, as it
   does today. Selecting an app in the picker is what puts it in a slot (a named one, or Everything else). RU-direct rules still apply inside the tunnel. These apps are not shown among the slots.
2. **Slot rules** for traffic that is in scope: first match wins in this order: AI, YouTube, Telegram (package list,
   then domains, then IP ranges), user-defined app slots before the built-in ones; whatever matches nothing goes to
   **Everything else** (`zapret-proxy`, the profile's main selector).

Matching packages: a built-in catalogue per slot (YouTube: `app.morphe.android.youtube`, `com.google.android.youtube`,
other ReVanced/Vanced builds; Telegram: `org.telegram.messenger`, `org.telegram.messenger.web`, `org.thunderdog.challegram`,
well-known forks, plus any app the user adds). The user adds or removes a package on the slot sheet. A package is in
one slot only; adding it to another moves it.

Owner rule (2026-11-14): an app that is in the VPN scope and in a slot has **no Direct choice**. To send it
directly, remove it from the VPN picker ("via VPN" menu); it then leaves its slot and every slot list, and goes out
directly. So the slot sheet offers only: move to another slot, or remove from the VPN. No `direct` outbound is ever
a slot member.

## Mapping to sing-box (the gasket)

- Today there is one managed `selector` with the stable tag `zapret-proxy`, hot-switched with
  `CommandClient.SelectOutbound` without recreating TUN/core (`docs/ARCHITECTURE.md`).
- A slot adds one more managed selector with its own tag (`zapret-youtube`, `zapret-telegram`,
  `zapret-app-<n>`), whose members are the pool's outbounds. All selectors point at the same server outbounds, so a
  server is defined once and a protocol keeps its native core: VLESS to Xray-core, Hysteria2 to Hysteria, the rest to
  sing-box (architecture basis). Different protocols per slot are therefore free.
- Routing: `route.rules` send slot matchers (`domain_suffix`, `package_name`) to the slot's selector; `final` keeps
  `zapret-proxy`. Rule order stays: hijack-dns, sniff/health, QUIC reject, forced always-proxy domains, **slot rules**,
  user rules, RU-direct, final. Slot rules sit before RU-direct so a Russian address does not take YouTube or
  Telegram out of the tunnel.
- `interrupt_exist_connections` is per selector, so switching the YouTube slot cuts only YouTube connections.
- DNS stays owned by the gasket, but a slot's names follow the slot's server (0.6.2). The node DNS of the main selector
  (`zapret-secure-dns`, queries to the sentinel 8.8.8.8 through `zapret-proxy`) picks the cache node (a googlevideo
  cluster) for the main node's network. With separate servers the video leaves through another node, and an address
  chosen for the first network can be unreachable from the second: field 2026-11-14 17:03, `connect error: timeout:
  no recent network activity` to the cache address of cluster `sn-8ph2xajvh-n8vs` through `zapret-slot-youtube` while
  other clusters ran at 245 KB/s on the same server, and the address answers from other hosts. Each slot therefore gets
  its own node DNS (`zapret-slot-dns-<id>`, a UDP and TCP race with `detour` = the slot selector) and DNS rules in the
  router's order (apps, then names) before the main node DNS rules, with `disable_cache` so that an answer from the
  previous node cannot survive a slot move. Only in modes that use the node DNS (Automatic, Secure, system DNS via the
  node); the profile's own DNS ("From JSON") is untouched. `SlotRoutesTest` pins servers, rules, order and idempotence;
  `RuntimeConfigNativeInstrumentedTest.slotConfigPassesPinnedLibbox` runs the config through the pinned core on a device.

## Home screen

Under the current server card: one card per slot (YouTube, Telegram, Everything else, plus slots the user added),
each showing the server, the mode («Авто» or «Закреплён») and a state dot. A tap opens the slot sheet: pin a server,
choose automatic, edit the pool. «Переключать при низкой скорости» moves to Home as a switch under the cards.
Texts shown to users are Russian.

## Phases

1. Pure module `ServiceSlots` + tests on real numbers: model, pool/pin/avoid assignment, failure reassignment.
2. Config builder: managed selectors and slot route rules (extends `RuntimeConfigBuilder`, rule order test,
   `check-core-patches` unaffected). Switch: setting «Раздельные серверы» default OFF, so nothing changes until chosen.
3. Runtime: per-slot switching through `ServerSwitchCoordinator`; flow attribution per slot (package, domain) for the
   detectors; log line per slot decision; timeline entries.
4. Home UI: cards, slot sheet, moved toggle.
5. Field test on the phone with the pinned-server procedure; archive analysis; then Windows parity
   (process name and domain routing, same pure policy ideas).

## Risks and checks

- Per-app scope: an app outside the VPN include list never reaches a slot; the slot sheet must say so.
- Sharing a server across slots when the pool is small: `avoid` is soft, never leaves a slot without a server.
- Profile with one server: slots collapse to that server; cards stay but say it.
- Selector cache reconciliation (`SelectorCacheReconciliation`) must cover every slot selector.
- Failover plan (`FailoverPlanner`) and the latency probes must treat slot selectors as groups, like `zapret-proxy`.
- A wedged ReVanced player (field 2026-11-14) is not fixed by any server choice; it stays a separate item and is
  measured a second time first (owner answer).
- No hidden cost: no new timers; slot logic runs on the existing flow sampling while the screen is on (I6-03).

## Pool scope: servers of ALL profiles (owner decision 2026-11-14, replaces the first version)

The first version drew a slot's pool from the active profile only. The owner decided that a slot may use every server
the user has, from any profile or subscription. How it works (code in `slots/`):

- `ServerRef(profileId, tag)` (in `core:model`) is a server's identity across profiles; its stored form is
  `<profile id>|<tag>`. Pins are stored in that form, so a pin on «Paris» of one profile never applies to «Paris» of
  another; an older pin without the profile id means the active profile.
- `ForeignServers.collect` picks the plain servers (`SLOT_SERVER_TYPES`, no `detour`, the same transport and TLS rules
  as the active profile) of the managed groups of the other profiles. A server whose protocol and address the active
  profile already has joins once, as the active one. At most 64 foreign servers, so the core does not start hundreds
  of outbounds. A profile that cannot be read is skipped; it never stops a start.
- A foreign server's runtime tag is `<tag>@<first 8 digits of its profile id>`: it cannot collide with the active
  profile's tags, and the log, the speed history and the failover keep servers of different profiles apart.
- `RuntimeConfigOptions.extraOutbounds` appends the foreign servers that some slot actually uses to the runtime
  outbounds, before the DNS name registry is filled (their names are server names). They are never members of the main
  selector `zapret-proxy`, never shown on the «Серверы» tab, never written to a stored profile, and a rebuild does not
  double them. Slots use selectors, so there are still no probes that could expose a server.
- A single-server active profile can now get slots when other profiles add servers; a profile without the managed
  selector (raw JSON) still gets none. The log line at the start says how many servers came from other profiles.
- Addresses of the other profiles' servers are registered with the redactor like the active profile's.

## The «Сервисы» tab (owner decision 2026-11-14)

A bottom tab, third after «Серверы» (bar: Главная, Серверы, Сервисы, Маршруты, Настройки, О нас). It replaces the bottom
sheet of Home. A switch «Раздельные серверы (бета)» on top; with it on, one compact row per service (YouTube, Telegram,
ИИ-приложения): the service, where it leaves now (and the profile when it is not the active one), and the mode
(«Авто», «Закреплён», «Закреплённый сервер не отвечал»). A tap expands the row in place: «Автоматически», then the
servers grouped by profile (the active profile first, profiles collapsed unless they hold the current or pinned
server, a search field above 8 servers, the whole list searched while typing). A pick writes the profile-qualified pin
and applies at once in a running session. «Остальные приложения» is only a note: their server is chosen on «Серверы».
The list is built from the stored profiles, so it works with the VPN off too. Home keeps a summary card (each service
and its server, a tap opens the tab) and the «Переключать при низкой скорости» switch. Presenter: `buildServicesUi`
(pure, tested), screen: `ServicesScreen` (module `feature:servers`), adapter: `ServicesRoute` in `:app`.

## Settled by the agent (owner: "settle the remaining questions, then implement", 2026-11-14)

- **AI slot catalogue:** ChatGPT, Claude, Gemini, the Google app (Gemini runs inside it), Perplexity, Copilot, DeepSeek,
  Grok: the AI group of the app picker. Domains: the AI service names listed in `SlotCatalog.aiDomains`.
- **YouTube slot:** every YouTube client of the picker catalogue, YouTube Music included (ReVanced, Vanced, Morphe,
  official). Other Google apps stay in Everything else. YouTube Kids is not in the picker catalogue yet; add it to the
  catalogue and the slot together when a device with it is available.
- **Telegram forks:** matched by package list, by package prefix (`org.zastogram.messenger.*`) and by datacentre IP
  range, so an unknown fork is still caught. Offering them in the picker as "Telegram" is a later UI item.
- **Slots and profiles:** open on purpose ("we will work it out as we go"). The first version keeps one active profile;
  every slot pool is a subset of its servers.

## Status

- Phase 1 done: `slots/ServiceSlots.kt` (router, CIDR matching, catalogue, server assigner, `SlotPlanner`) with
  `ServiceSlotsTest`.
- Phase 2 done (config only): setting «Раздельные серверы (бета)» (Settings → Advanced, default OFF, enabling restarts
  the VPN). When ON and the profile is managed with at least two servers, `SessionStarter` builds a `SlotPlan`
  (`slots=` line in the runtime log) and `RuntimeConfigBuilder` adds one selector per slot (`zapret-slot-<id>`, members =
  the pool, `interrupt_exist_connections`) and slot route rules after the QUIC reject and before the forced-proxy rule
  and user rules (apps, then names, then addresses). The main selector and the `default` slot are unchanged: the
  default slot is the main selector and keeps the server the user selected. `SlotRoutesTest` pins the rule order,
  the repair of broken pools, no-slots = old config byte for byte, and that the stored profile is never edited.
- Phase 3a done (failover on failure, not released): `slots/SlotFailover.kt` (`SlotFailoverPolicy`, pure, tested).
  The main supervisor confirms a hint with a probe through the tunnel, which always leaves through the main server, so
  it cannot judge a slot's own server. A slot server is judged by repetition instead: 3 switchable failures of the same
  server within 60 s (first guess, to tune on the phone). Then the server is marked dead for 5 minutes, the assigner
  runs again with it unusable, and only the slots on it move (the `default` slot never moves here; servers the main
  supervisor confirmed dead are avoided too). At most 3 moves per 15 minutes, stale lines for 2 s after a move are
  ignored. `ServerSwitcher.moveSlot` sends `SelectOutbound` to the slot selector under the session's switch lock;
  nothing is written to the profile (a slot's server lives in the session). Timeline lines: `отказов за минуту ...,
  слоты уходят`, `Слот X: теперь через Y`. Also: slot selectors are hidden from the server list of the screen, and
  `ServerNameRegistry.useForSlots` lets the names of slot servers fall back to system DNS like the selected server
  (before, only one server could).
- Phase 3b done (detectors per slot, not released):
  - **Video detector and the YouTube episode.** The detector already reads only YouTube flows (googlevideo), so it was
    never confused by other apps; what was wrong with slots on is the action: the episode switched the main selector,
    while YouTube leaves through `zapret-slot-youtube`. With a YouTube slot the same `YoutubeSwitchEngine` now runs on
    `YoutubeSlotSwitchHost`, which moves the slot's own selector (`ServerSwitcher.moveSlot`), takes candidates from the
    slot's pool (`SlotCandidates`: dead servers and servers other slots or the main selector use are left out while a
    candidate remains) and writes nothing to the profile. Timeline: `Слот YouTube, переключение при медленном видео`.
  - **Throughput monitor.** It reads one global downlink counter, so a slow Telegram transfer could look like a slow
    main server. With slots on, `SlotTraffic` assigns each flow of the existing direct snapshot (no new core calls,
    I6-05) to a slot by the config's own rule (package, then name, then address) and the monitor subtracts what the
    other slots carried (`SlotTraffic.own`). Both approximations err the safe way: bytes not counted yet raise the
    default rate (no false "slow"), bytes counted early make the counter step back, which the detector treats as a
    reset. The probe that confirms slowness already goes through the main server, which is the right server to judge.
  - Not tuned on the phone yet; the numbers of 3a and these rules are first guesses.
- Phase 4a done (Home, not released): the runtime publishes `VpnController.slotStates` (slot, title, server; set when
  the session activates and after every move, cleared with the groups); `slotCards` (pure, tested) turns them into rows
  with Russian titles, the server name (masked like the server card when addresses are hidden), a flag and the mode
  («Авто»), then one row for the main server («Выбран вами»). Home gets the card «Серверы по сервисам» and, under it,
  the switch «Переключать при низкой скорости» (the same setting as in Settings, which keeps its switch too). Without
  slots the card holds only the switch. The wiki guide `premium/android.md` must show the new Home card at release.
- Phase 4b done (slot sheet, pins; not released): a tap on a slot row opens a bottom sheet with «Автоматически» and
  every server of the profile (marks: «сейчас» = the slot leaves through it, radio = pinned). Pins are stored as one JSON
  object in the settings (`slot_pins`, `SlotPins`, tags hold emoji and punctuation) and apply without a VPN restart:
  `VpnRuntime` posts the pins to the coordinator, `SlotFailoverPolicy.setPins` returns the moves (a pinned slot goes to
  its server at once, also off a server marked dead: an explicit choice beats the app's guess; unpinning moves
  nothing; a pin outside the pool or the profile is ignored), and the session starts pinned slots on their servers
  (`SlotCatalog.withPins`). A pinned server that stops answering is still left by the failover (the pin stays recorded,
  the row shows the real server). The main server is not pinned here: it is chosen on the server card.
- Field test on the phone (Pixel 9 Pro XL, Android 0.5.52, beta ON, 2026-11-14 16:36 to 16:44 UTC, profile of 32
  servers with Hysteria2 and VLESS mixed): the session started with `слоты: ai=Clermany; youtube=Rusge; telegram=Senge`
  and the main server Horologium, all four different. Traffic followed the rules: the Telegram fork (package prefix
  `org.zastogram.messenger.`) left through Senge, ReVanced through the YouTube slot, Chrome through the main server.
  A pin from the sheet moved YouTube to 4virtual at once with the VPN staying up (`Закреплено вручную`, `Слот YouTube:
  теперь через 4virtual`). On 4virtual the video hit a dead YouTube cluster (`sn-ojnpo5`); the YouTube episode moved
  only the YouTube slot to Tiraru, the restarted video played, the other slots did not move. No spurious slot moves in
  about six minutes of mixed traffic. Found and fixed: the row kept saying «Закреплён» after the app had left a pinned
  server that did not work for YouTube; it now says «Закреплённый не отвечал». Not exercised yet: failover on repeated
  connection failures of a slot server (unit tested only) and the AI slot.
- Not yet (phase 4c): the pool per slot and the apps of a slot on the sheet (move an app to another slot, remove it from
  the VPN), user-defined slots. Then Windows parity, phone test.

## A dead slot server seen through its own DNS (Android 0.6.6, field case 2026-11-14)

Phone test of 0.6.5 with the YouTube slot pinned to a Hysteria2 server: the server stopped answering (QUIC handshake
`timeout: no recent network activity`), the home feed stayed in skeleton state for 100 s, and the slot never moved.
Reason: the slot's DNS (`zapret-slot-dns-youtube`) leaves through the slot's server, so every YouTube lookup hung for 10 s,
but the Hysteria client retries its handshake quietly (one `connect error` line, then nothing), so the repetition counter
of `SlotFailoverPolicy` saw one hint instead of three. The main supervisor's probe goes through the main server and
reported "false alarm".

Change: a timeout of a slot's own DNS (`dns/fallback[zapret-slot-dns-<id>]: resolve failed ... timeout|deadline|stateless
reset`, parsed by `CoreLogHints.slotDnsFailures`) is a failure hint for the server the slot sits on
(`SlotFailoverPolicy.onSlotDnsFailure`). At most one hint per slot per 5 s, so a burst of queued lookups is one failure and
a move needs three separate failures (about 10 s of a dead path). Lookups started before a move keep timing out up to the resolver deadline (10 s) after it, so the slot's DNS failures are ignored for
`DNS_STALE_MILLIS` (12 s) after any move of that slot (0.6.8). Honest note: this guard was added after a run in which YouTube
left the dead server and 11 s later its replacement Rusge; the next run (0.6.8, 2026-11-14 21:19) showed that Rusge's slot DNS
really failed (failures kept arriving for lookups started after the move, until 21:20:09), so that second move was probably
correct and the guard only costs up to 12 s of detection. A server whose connection works but whose DNS to the node's resolver
does not (Rusge here) is exactly what the DNS signal is for. Everything else is unchanged: a pin that does not answer
is left until the server is back, moves are rate limited, the default slot never moves, and the timeline shows
`... отказов за минуту (DNS слота), слоты уходят: ...`. Tests: `SlotFailoverTest` (burst counts once, default and unknown
slots ignored) and `CoreLogHintsTest` (real lines).

## A failed server is not where a slot starts (Android 0.6.9, owner decision 2026-11-14)

With the YouTube slot pinned to a dead server, every connect started on it and the video played only about 45 s later
(the slot then left it by itself, see the section above). Now `SlotPlanner.plan(..., unusable)` does not start a slot on a
server whose last saved result is a fresh failure («не отвечает», `hint().failed`, 10 minutes), a pinned server included:
the pin stays set and the server stays a pool member, so the slot goes there again once the failure is old. The main
server is never excluded. A server is recorded as «не отвечает» when a manual pick is refused, when the relay test says so,
and when a slot leaves it because its connections failed (`SlotRuntimeHost.recordUnreachable`); a failure seen only in the
slot's DNS is not recorded, because the server may work for ordinary traffic (Rusge on 2026-11-14), so the Servers tab does
not call it dead. Tests: `ServiceSlotsTest` (failed pin, main server), `SlotFailoverTest`.

### The same rule for a replacement (Android 0.6.12)

Field case 2026-11-14 (0.6.11, long-video test): the YouTube slot started on Clermany, which timed out, and the failover
moved it to 4virtual, a server marked «не отвечает» four minutes earlier; 17 s later it left that one too and the video
started only on the third server (about 47 s after the tap). `SlotFailoverPolicy` takes the same failure marks
(`recentlyFailed`) and looks for a replacement among the unmarked servers first. When every other candidate is marked the
old search runs, so a slot never stays on the server that just failed because of a mark. The main server is never
excluded by a mark. Tests: `SlotFailoverTest`.

### A slot remembers the servers that did not work for it (Android 0.6.13, owner decision 2026-11-15)

Field case 2026-11-15 (0.6.12): the YouTube slot started on the dead pin 4virtual and hopped 4virtual → Clermany →
Rusge → Senge; both hops through Clermany and Rusge waited out a dead slot DNS, the slot settled 46 s after the tap, and
the player gave up with «Видео недоступно» before that. Clermany and Rusge had failed the slot's DNS in three sessions in
a row from the same phone, but nothing remembered it: the «не отвечает» mark lasts 10 minutes and a failure seen only in
the slot's DNS is not recorded as a ping result on purpose (the server may work for ordinary traffic).

- **Slot mark** (`SlotServerMarks`, file `slot-server-marks.json`, `SlotMarkRules`): every time a slot leaves a server for
  any reason (DNS of the slot, failed connections, rejected credentials) the server is marked for one hour. The mark is
  NOT the «не отвечает» row, the Servers tab does not show it and the main selector never reads it: a server can be good
  for ordinary traffic and dead for a slot, because the slot's DNS goes through that server alone. A good relay result
  newer than the mark overrules it (the server proved itself since).
- **Where it acts** (`slotServerHealth`): a slot does not start on a marked server and a failover does not land on one
  (a pin on it included: the pin stays set and the server stays a pool member, the slot goes there again when the mark is
  gone); the main server is never excluded. When every other candidate is marked the old search runs.
- **Proof of life** (`SlotServer.alive`): a server with a good relay result younger than six hours goes first among equally
  used candidates. It is only a tie-break after «least used»: a slot does not share a server just because it is known
  good, so the slots stay on separate servers when separate servers exist.
- **Log:** the `слоты:` line at connect ends with «обойдено серверов с недавним отказом: N» when something was skipped;
  every move still logs its reason.

Tests: `SlotServerMarksTest` (expiry, size cap, encoding, file round trip), `SlotServerHealthTest` (failed, alive,
mark, overruled mark, foreign profile), `ServiceSlotsTest` (proof of life never beats separation), `SlotFailoverTest`.

### A pin cannot trap the video, and a dead rescue server does not end the rescue (Android 0.6.14, owner decision 2026-11-15)

Field case 2026-11-15 (0.6.13, phone, all three slots pinned): the YouTube slot sat on the pin 4virtual, which answers
fast (rtt 5-12 ms) but stops delivering googlevideo data after about 400 KB per connection; the player froze at 0:58.
The slow-video episode found it in 10 s, but its only trial went to Clermany (a server that fails the slot's DNS), the
slot's own failover left Clermany at once, the episode read the silence as "the viewer left", restored the pin and ended.
The owner's rule: even a user who knows nothing about pins must not be able to block YouTube for themselves; the app
works it out alone.

- **Marks count for the rescue too** (`SlotCoordinator.switchContext`): candidates of the episode leave out the servers
  with a slot mark (soft, like «dead» and «used by another slot»: only while another candidate remains).
- **A dead trial server is a failed candidate** (`deadTrialPlace`): when the episode hears silence after a trial move and
  the slot's own failover has already left the trial server, the candidate counts as «тоже медленный» and the episode goes
  on to the next candidate from where the slot sits now. Silence with the slot still on the trial server is read as
  before (the viewer left).
- **A server that starved the video is remembered** (`SlotSwitchContext.markLeft`): when the episode moves the slot off a
  server for a server that plays, the old one gets the one-hour slot mark, a pin on it included. The next connect and the
  next episode do not go back to it; the pin stays set and takes effect again when the mark is gone.
- **Log:** `Пробный сервер … не отвечает: слот ушёл с него сам, берём следующий.`; the mark shows as the usual slot move.

Tests: `DeadTrialTest`, `YoutubeSlowSwitchTest` (Starving candidate then Healthy one).

## Review fixes of 2026-11-14 (five read-only audits, every claim re-read in the code)

Behaviour that changed, all behind the existing beta switch «Раздельные серверы»:

- A cancelled YouTube episode (a manual server choice, a path probe) puts the server or slot back that the trial had
  moved; before, the selector could stay on an unverified candidate. A failed return keeps the slot's recorded place on
  the trial server (the command did not go through), so later decisions start from where the core really is.
- Slot move commands carry a number per slot; an older command that was overtaken does nothing, and a late failure of
  an older command no longer sends the slot back over a newer move (`SlotFailoverPolicy.moveFailed` acts only while the
  slot still sits where the command sent it).
- A server is marked dead only when some slot can leave it; moving one slot keeps the failure count of a server another
  slot still uses.
- A slot server that rejects our credentials or certificate (`SECURITY_FAILURES`) is left at the first hint, not the
  third: repetition cannot make it more certain. The main selector still ends the session in that case.
- DNS: the user's own DNS rules stand in front of the generated catch-all (they were unreachable behind it); names that
  always go through the VPN (`ForcedProxyDomains`) resolve through the node also when `route.final` is direct; a runtime
  copy fed back to the builder does not double slot selectors or slot route rules.
- A queued restart without a generation (read from a UI state a few milliseconds old) is ignored while the VPN is
  stopping (`RestartGate`); a server switch writes its selection into the profile as it is after the verification, not
  into the snapshot taken before it (a subscription refresh in between is no longer undone).

Read and left as they are: the dead-cluster tracker treats any flow with 64 KB or more as proof the cluster answers
(slow flows are the slow-video detector's job); `SlotTraffic` re-counts a flow only after it was absent for 40
snapshots; the routing editor orders managed rules by action by design; a player paused for more than 30 s with one
idle tail flow can still look starving (it needs the media state to tell the difference).

## Code layout (since the refactor of 2026-11-14)

All slot code lives under `app/src/main/java/io/github/zapretkvn/android/slots/`, one file per concept, no behaviour
change (the old `ServiceSlots.kt` and the slot parts of `ServerSwitchCoordinator` were split, `config/SlotRoutes.kt` moved in):

| Place | What |
|---|---|
| `slots/` (pure Kotlin, no Android) | `SlotIds`, `SlotDefinition`, `SlotCatalog` (composes the services), `ServiceSlotRouter` + `Cidr` (which slot a connection belongs to), `SlotServerAssigner` (pin, keep, least used), `SlotPlanner` (the plan a session starts with), `SlotFailover` (failure policy), `SlotTraffic`, `SlotCandidates`, `SlotPins`, `SlotRoutes` (selectors, route rules and per-slot DNS as JSON for the config builder) |
| `slots/youtube/` | `YoutubeSlot` (clients and names of YouTube) and `YoutubeSlotSwitchHost` (the "slow YouTube" episode that moves the slot's selector) |
| `slots/telegram/` | `TelegramSlot` (clients, forks, names, datacentre ranges) |
| `slots/ai/` | `AiSlot` (assistant apps and names) |
| `slots/runtime/` | `SlotCoordinator` (state of the slots in a running session, failover, pins, Home cards, the doorway of the YouTube episode) and `SlotRuntimeHost` (what it needs from the runtime actor) |

A new service is one folder with one definition object plus one line in `SlotCatalog`. `ServerSwitchCoordinator` only
creates the `SlotCoordinator` and forwards the core's hints, the pins and the session activation to it.
