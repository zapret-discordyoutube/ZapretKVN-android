# Service slots: a dedicated server and switching logic per service (design draft)

Status: DRAFT, no code yet. Owner direction of 2026-11-14, written down so the design starts from the owner's words.
Language: English (owner decision 2026-11-08). Read `docs/ARCHITECTURE.md` and the root `AGENTS.md` section
"Architecture basis: native cores, sing-box is the gasket" first.

## Why

The field work of 2026-11-13/14 showed what one shared server costs: a slow Telegram transfer lowers the measured
speed and moves YouTube off a working server (12:37 UTC, Senge to Tiraru on 0.9 Mbit/s from mostly idle traffic); a dead
YouTube cluster on a node drags every other app through a server switch; and everything leaves through one exit
address, which makes the traffic easy to link and to block. The owner's answer: every service gets its own server and
its own switching logic.

## Owner decisions (2026-11-14)

1. YouTube, Telegram and "everything else" are separate **slots**; any other application can get its own slot too.
2. Each slot has its own server, its own protocol and its own switching logic. Example: YouTube over VLESS from one
   server, Telegram over Hysteria2 from another. Every setting is configurable.
3. sing-box does the routing under the hood (several selectors, several server slots, several protocols).
4. A slot picks its server **automatically from a pool of the profile's servers, and the user can pin one**.
5. Rotation, first step: **on failure only** (no time-based or per-session rotation yet).
6. Home screen gets the slot cards **and** the «Переключать при низкой скорости» toggle.
7. Android first, then Windows.

## Model

A slot is a pure value in a tested module (no Android types):

| Field | Meaning |
|---|---|
| `id`, `title` | stable id (`youtube`, `telegram`, `default`, `app:<package>`), name on the card |
| `matchers` | domains (shared always-proxy list for YouTube), package names, later IP/rule-set |
| `pool` | server ids the slot may use (default: every server of the profile) |
| `pin` | optional server id; overrides automatic choice |
| `switching` | switch policy: `OnFailure` now; room for `LowSpeed`, `Rotate` later |
| `avoid` | server ids another slot is using, so slots do not share a server while the pool allows |

Per-slot logic is a policy object, not a flag inside one global engine. The existing engines
(`SlowServerSwitchEngine`, `YoutubeSwitchEngine`, `PathSupervisor`) become the policies of the `default` and
`youtube` slots; the throughput monitor and the video detector read only the flows that belong to their slot, so a
Telegram transfer can no longer starve or "heal" YouTube.

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

## Open questions for the owner

- Which slots exist out of the box: YouTube, Telegram, Everything else only, or also AI (ChatGPT, Claude, Gemini)?
- Is the pool defined per profile or per slot, when a subscription has servers in several countries?
- Should a slot ever be allowed to use `direct` (for example a Russian-only app)?
