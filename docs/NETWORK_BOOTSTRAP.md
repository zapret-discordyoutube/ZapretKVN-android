# network-bootstrap

A narrow Android library module for the bootstrap that happens before the TUN is created. It owns only:

- choosing the current non-VPN network;
- reconciling `NetworkCapabilities` and `LinkProperties`;
- a short stabilization of the snapshot and retries when the network identity, the validation/Private DNS state or the list of DNS servers really changes;
- the system `DnsResolver` / `Network.getAllByName()`;
- stable support codes and a safe technical reason.

The module knows nothing about profiles, Compose, `libbox`, the VPN allowlist, runtime JSON or the LKG cache. Those decisions stay in `app`. It also does not decide what counts as a usable network: the wait accepts a predicate, because during a Wi-Fi ↔ cellular transition Android publishes a candidate long before it is good enough for bootstrap. The maturity criterion and the recovery policy live in `app`.

| Code | Meaning |
|---|---|
| `NET-101` | Android did not provide an underlying network |
| `NET-102` | The network changed during bootstrap and did not stabilize |
| `DNS-101` | DNS timeout |
| `DNS-102` | Name not found / NXDOMAIN |
| `DNS-103` | DNS refused / REFUSED |
| `DNS-104` | Successful answer without addresses |
| `DNS-105` | System error of the Android resolver (`DnsException`/errno) |
| `DNS-106` | Other DNS RCODE |

The codes `NET-110`, `DNS-110`, `VPN-1xx` and `VPN-2xx` belong to `app`: they describe the captive portal, strict Private DNS, the connection deadline and the checks on top of a ready TUN.

`NET-101` and `NET-102` no longer mean the end of the session: `app` treats them as transient and reconnects by itself in a bounded series of attempts. The other codes stay terminal. The text of an error may be refined, but the meaning of a published code does not change.
