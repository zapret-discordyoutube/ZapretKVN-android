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
- UI: Settings -> «Маскировка» (global set, «Применить»; per-server choice for the active profile).

## Verified core facts (pinned versions)

- Xray: mux `concurrency` 0..128 all become 128 (on/off in practice); `xudpProxyUDP443`
  reject|allow|skip; `maxConnections` with `maxConcurrency` is refused; Vision breaks mux connections
  that carry TCP, so with Vision only XUDP is multiplexed.
- amneziawg-go v3.1.20261116: tags `b t r rc rd d ds dz`, no `<c>` (the importer refuses it too);
  `<b>` strips only a lowercase `0x`; `jmin > jmax` is refused by the core.
- sing-box native `spoof` needs SOCK_RAW (root) on Android: not offered.
