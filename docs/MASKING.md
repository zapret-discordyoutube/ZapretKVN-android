# Masking («Маскировка»)

Owner request 2026-11-15, plan approved 2026-11-16. Off by default: with the master switch off the
runtime config is byte-for-byte what it was before.

## Who owns what (native cores, sing-box is the gasket)

| Section | Native core | What the app writes |
|---|---|---|
| WireGuard masking | Amnezia (amneziawg-go v3.1) | `jc`, `jmin`, `jmax`, `i1`..`i5` in the endpoint's `amnezia` object |
| Xray shaping | Xray-core | `mux` on the outbound, `xmux` in XHTTP settings, `finalmask.tcp` fragment |
| TLS fragment | sing-box (Trojan, VMess, AnyTLS, HTTP, ShadowTLS) | `tls.fragment`, `tls.record_fragment`, `tls.fragment_fallback_delay` |
| Hysteria2 | Hysteria | nothing: obfs is part of the link |

Only fields the native core already parses. H1-H4/S1-S4 are never touched (they must equal the
server). Junk and I1-I5 are sent by the initiator only, so they work with a plain WireGuard server.

## Code

- `masking/` (pure Kotlin module, JVM tests): model, `MaskingCodec` (JSON shared byte for byte with
  Windows `xray_fluent/masking/codec.py`), `MaskingMerge` (global + per-server override, drops what
  does not fit the core or has an error, with notes), the rules/emitters per core (every limit cites
  the core file), presets, `MaskingForm` (the screen's draft), tester decisions (`FreezeDetector`,
  `CandidateTester`).
- `app/.../masking/`: `MaskingSettingsStore` (DataStore `masking`), `MaskingActions` (write, restart a
  connected VPN with «Смена маскировки»), `MaskingServerKeys` (runtime tag -> `ServerRef.encode()`,
  the same key slot pins use; one runtime-log line per session).
- `RuntimeConfigBuilder` applies `MaskingApplier` to the runtime copy of outbounds and endpoints,
  slot servers included. The stored profile is never edited.
- Core patch `0016-xraycore-masking.patch`: VLESS option `xray_masking` (`mux`, `xmux`, `finalmask`),
  merged by name into the Xray config; `xmux` goes into XHTTP `extra` when the link has one (Xray
  replaces the whole XHTTP config by `extra`); unknown keys are refused.
- UI: Settings -> «Маскировка» (global set, «Применить»; per-server choice for the active profile;
  card «Подобрать»).

## «Подобрать» (the tester)

- Decisions in `masking/tester`: `CandidateTester.candidates` (baseline «Без маскировки», the server's
  own set when it is no preset, then the presets that fit its core), `plan` (2 transfers per candidate,
  jittered 1.5-4 s pauses, one target, no fan-out), `judge` (the control is the run itself: the most
  bytes any transfer carried) and `FreezeDetector` (>= 8 s stall at the same offset on both transfers).
- `app/.../masking/MaskingTrial`: the in-memory candidate the next session start runs with
  (`MaskingSettings.withTrial`: one server only; with masking off nothing else changes). Never stored;
  the session publishes its servers (runtime tag -> key + shape) for the runner.
- `MaskingTuner`: per candidate sets the trial, restarts (reason «Подбор маскировки», trigger
  `masking_tune`), waits for Connected (45 s), checks the primary group still selects the same server,
  runs the transfers, and at the end drops the trial and restarts once more («Подбор маскировки
  завершён»). A VPN the user switched off is never switched back on. «Оставить» is the normal
  per-server write.
- `MaskingTransferProbe`: 256 KB (`Range`) of the speed-check file through the VPN network, fresh
  connection, read timeout 12 s, cap 40 s, byte count sampled every 500 ms; holds the probe lane.
  It measures the inner stream: the outer connection's freeze stops it a few percent of bytes earlier.
- Runtime log: «маскировка: подбор, вариант «…»» at every trial session start.

## Packet tricks (desync, core patch 0017)

- `common/kvndesync` (new Go package): the Xray outbound's protected dialer wraps every TCP socket
  to the server and shapes its first write. `fake` = a decoy ClientHello of the same length with
  `fake_sni`, sent with `fake_ttl` from a page the socket references (vmsplice + splice); after it
  left the host (`tcpi_notsent_bytes`, 50 ms cap) the real bytes are written into the same page and
  the TTL restored, so every retransmission carries the real bytes. `disorder` = first piece at TTL
  1, `split` = two segments, `oob` = one urgent byte. No root, no raw socket.
- Plan: `xray_masking.desync` `{method, split_at, fake_ttl, fake_sni}`, parsed strictly in
  `NewOutbound` (bad plan = the outbound does not start); never part of the Xray config. One log
  line per outbound when the plan is set, one warning per outbound if it could not run (the write
  then goes out plainly).
- Lab (2026-11-16, real router hop to 10.10.10.14:443, `TestLab`): fake TTL 1, disorder and split
  keep the TLS handshake intact; the decoy at TTL 64 breaks it (nginx: unrecognized name), which
  proves it is on the wire; OOB times out on this path (hypothesis: URG cleared on the way), so OOB
  is not in presets or the tester and the form warns.
- App: `Desync` in `XrayShaping` (codec shared with Windows), limits = `kvndesync.Parse`, presets
  «Обманка, TTL 3/5», «Беспорядок пакетов»; tester candidates on TCP-stream VLESS. The right TTL is
  per network (an Android app cannot read the SYN-ACK TTL): «Подобрать» tries 3 and 5.
- Windows runs the same document: disorder/split by the same engine in its sing-box front
  (`windows/core-patches/kvn-desync.patch`, route option `kvn_desync`), the decoy by sing-box
  `tls_spoof` over WinDivert (wrong TCP sequence instead of a low TTL, so `fakeTtl` is unused
  there), OOB not offered. Details: `windows/docs/MASKING.md`.

## Verified core facts (pinned versions)

- Xray: mux `concurrency` 0..128 all become 128 (on/off in practice); `xudpProxyUDP443`
  reject|allow|skip; `maxConnections` with `maxConcurrency` is refused; Vision breaks mux connections
  that carry TCP, so with Vision only XUDP is multiplexed.
- amneziawg-go v3.1.20261116: tags `b t r rc rd d ds dz`, no `<c>` (the importer refuses it too);
  `<b>` strips only a lowercase `0x`; `jmin > jmax` is refused by the core.
- sing-box native `spoof` needs SOCK_RAW (root) on Android: not offered.
