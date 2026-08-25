# LionRay VPN 🦁

Android system-wide VPN client powered by **Xray-core** (via `libv2ray.aar` / gomobile).

- VLESS share-link import (+ button → clipboard / QR scan / manual entry)
- Every parameter of an imported key is fully editable & deletable
- TCP ping per server + **Auto Ping** toggle in the toolbar menu
- Real device-wide tunneling through Android `VpnService`

---

## APK

| File | Notes |
|---|---|
| `LionRayVPN-v1.0-release.apk` | Signed release build – install this |
| `app/build/outputs/apk/debug/app-debug.apk` | Debug build |

Signing key: `release.keystore` (alias `lionray`, store/key pass `lionray123`).
**Keep the keystore safe – the same key is required to ship updates.**

## How the system-wide routing works

```
 Android apps (all traffic, IPv4+IPv6, DNS included)
        │
        ▼
 VpnService.Builder  (routes 0.0.0.0/0 + ::/0 into the TUN, MTU 8500,
        │             own package excluded → no loop)
        ▼
 ParcelFileDescriptor (TUN fd)
        │
        ▼
 hev-socks5-tunnel  (libhev-socks5-tunnel.so, tun2socks)   ← LionRayVpnService + HevTunnel
        │
        ▼
 Xray-core SOCKS5 inbound 127.0.0.1:10808 (sniffing http/tls)
        ▼
 routing rules: private IPs → direct, everything else → proxy
        ▼
 VLESS outbound (TLS / REALITY, tcp/ws/grpc/h2/httpupgrade/xhttp/kcp…)
```

Why tun2socks instead of Xray's native "tun" inbound? The core's TUN inbound
queries the system routing table (netlink) at startup, which SELinux denies to
regular apps on Android 10+ (`netlinkrib: permission denied`).
hev-socks5-tunnel is the same proven engine used by v2rayNG and Orbot.
The prebuilt `libhev-socks5-tunnel.so` files live in `app/src/main/jniLibs/`
and are bound through `com.v2ray.ang.service.TProxyService` (the package name
is fixed by the JNI symbols compiled into the .so).

## Project layout

```
app/src/main/java/com/lionray/vpn/
├── LionRayApp.kt              # init store + xray env + notification channel
├── data/
│   ├── Models.kt              # ServerProfile ↔ JSON ↔ vless:// URI
│   ├── VlessParser.kt         # full vless:// link parser
│   └── ProfileStore.kt        # JSON file storage + StateFlow
├── core/
│   ├── XrayBridge.kt          # ONLY file importing libv2ray.aar classes
│   ├── XrayConfigBuilder.kt   # builds complete xray config JSON
│   └── VpnBus.kt              # connection state bus
├── service/LionRayVpnService.kt  # VpnService + foreground notification
├── util/                      # TCP ping engine + QR generator
└── ui/                        # MainActivity / ServerAdapter / EditActivity
```

## Building from source

Requirements: Android Studio (or SDK + JDK 17+), plus one external artifact:

1. Download **libv2ray.aar** from
   <https://github.com/2dust/AndroidLibXrayLite/releases> (v26.6.2 or newer;
   must contain Xray-core ≥ v26.1.23 for the TUN inbound)
   and place it at `app/libs/libv2ray.aar`.
   (Or build it yourself: clone that repo → `gomobile bind -androidapi 24 ...`)
2. Open the `LionRayVPN` folder in Android Studio → Run.
   CLI: `gradle assembleDebug` / `assembleRelease`.

## Notes

- First connect asks for the standard "VPN connection" permission dialog.
- Ping = TCP handshake latency to server address:port (same method most
  VPN clients use); Auto Ping repeats every 15 s while enabled.
- REALITY requires pbk/sid/sni fields – all editable in the editor screen.
- Local SOCKS5 test port `127.0.0.1:10808` is exposed while connected.
