# Service slots: a dedicated server and switching logic per service (design draft)

Status: IN PROGRESS (phases 1 to 4a in code, behind a default-OFF beta setting; see "Status" at the end). Owner direction of 2026-11-14, written down so the design starts from the owner's words.
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
- DNS stays owned by the gasket: a slot never changes DNS; domain-specific server-name DNS follows the slot's server
  through the existing rules.

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

## Decided pool scope (my reasoning, owner leaned the same way)

The pool is per slot but drawn from the **active profile**: all selectors live in one sing-box config built from one
profile, and mixing servers of several profiles would need a merged config, separate credentials handling and a
second failover plan. A slot may therefore not use servers outside the active profile in the first version; a user
who wants a different subscription for YouTube switches the profile (revisit when profiles can be merged).

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
- Not yet: the slot sheet (pin, pool, apps; tap on a row), Windows parity, phone test.
