# LionRay VPN 🦁

Android system-wide VPN client powered by **Xray-core**.

Multi-protocol support (VLESS / Trojan / Shadowsocks), subscription management, TLS fragmentation DPI bypass, per-app bypass, ad blocker, and built-in auto-update system.

---

## Features

### Core VPN
- **Full-device tunnel** via Android `VpnService` + `hev-socks5-tunnel` (tun2socks)
- **Multi-protocol**: VLESS, Trojan, Shadowsocks (AEAD)
- **Transports**: TCP, WebSocket, gRPC, H2, HTTPupgrade, XHTTP, SplitHTTP, KCP, QUIC
- **Security**: TLS, REALITY (pbk/sid/sni/fingerprint)
- **TLS Fragmentation**: ClientHello fragmentation to bypass DPI SNI-based blocking

### Server Management
- **Import**: Clipboard, QR code scan, QR from gallery, manual entry
- **Subscription**: Sing-box JSON, Clash/Clash.Meta YAML, plain text vless:// links, base64 auto-detect
- **Auto-update subscriptions**: Background refresh every 12 hours
- **TCP Ping**: Per-server latency test + Auto Ping toggle
- **Auto Select**: Sort by ping speed

### VoIP & Calls
- **Telegram group call**: XUDP mux tunnel for voice/video media (mt.me domain routing)
- **Viber calls**: viber.com + viber-cdn.net routing
- **Messenger calls**: edge-mqtt.facebook.com routing
- **VoIP VPN toggle**: On = tunnel UDP through proxy, Off = direct UDP

### Stability
- **Auto-reconnect**: Detects WiFi ↔ SIM changes, debounced restart with exponential backoff
- **Auto-failover**: Concurrent ping probe of all servers, traffic-aware watchdog, automatic switch to fastest reachable server
- **Network watcher**: Real-time connection state monitoring

### Privacy & Security
- **Ad blocker**: DNS-level domain blocking with live counter
- **Per-app VPN bypass**: Choose which apps bypass the tunnel
- **Custom bypass domains**: Route specific domains direct (bypass Cloudflare blocks)
- **Exit IP detection**: Dual-provider GeoIP lookup with country flag
- **No logs**: All data stored locally, no telemetry

### UI
- **Multi-language**: English + Myanmar (Burmese) with first-launch picker
- **Dark mode support**
- **Quick Settings tile**: Connect/disconnect from notification shade
- **Battery optimization**: One-time keep-alive prompt
- **Server sharing**: Copy URI, QR code, or share link

### Update System
- **In-app update (Android 10+)**: GitHub releases → download → system installer
- **Core binary update (Android 9 and below)**: Direct binary replacement
- **CI/CD**: GitHub Actions workflow for automated APK builds

---

## Architecture

```
Android apps (all traffic, IPv4+IPv6, DNS)
        │
        ▼
VpnService.Builder  (routes 0.0.0.0/0 + ::/0 into TUN, MTU 8500)
        │
        ▼
ParcelFileDescriptor (TUN fd)
        │
        ▼
hev-socks5-tunnel  (tun2socks, in-process)
        │
        ▼
Xray-core SOCKS5 inbound 127.0.0.1:10808 (sniffing http/tls)
        │
        ▼
Routing rules:
  ├─ Telegram/Viber/Messenger UDP → proxy (VoIP)
  ├─ Private IPs → direct
  ├─ Bypass domains → direct
  ├─ Ad domains → block
  └─ Everything else → proxy
        │
        ▼
VLESS outbound (TLS / REALITY, ws/grpc/h2/tcp/kcp…)
```

Why tun2socks instead of Xray's native "tun" inbound? The core's TUN inbound
queries the system routing table (netlink) at startup, which SELinux denies to
regular apps on Android 10+ (`netlinkrib: permission denied`).
hev-socks5-tunnel is the same proven engine used by v2rayNG and Orbot.

---

## Project Layout

```
app/src/main/java/com/lionray/vpn/
├── LionRayApp.kt                # App init, update checks, notification channels
├── data/
│   ├── Models.kt                # ServerProfile ↔ JSON ↔ vless:// URI
│   ├── VlessParser.kt           # VLESS/Trojan/Shadowsocks link parser
│   ├── ProfileStore.kt          # JSON file storage + StateFlow
│   └── SubStore.kt              # Subscription storage
├── core/
│   ├── XrayBridge.kt            # External Xray-core process bridge
│   ├── XrayConfigBuilder.kt     # Builds complete Xray config JSON
│   ├── HevTunnel.kt             # hev-socks5-tunnel JNI bridge
│   └── VpnBus.kt                # Connection state bus
├── config/
│   └── XrayConfigBuilder.kt     # Config generation with routing rules
├── service/
│   └── LionRayVpnService.kt     # VpnService + auto-reconnect + failover
├── util/
│   ├── ApkUpdater.kt            # GitHub APK self-update (Android 10+)
│   ├── CoreUpdater.kt           # Binary self-update (Android 9-)
│   ├── PingEngine.kt            # TCP ping with semaphore control
│   ├── QrUtil.kt                # QR code generation/reading (ZXing)
│   ├── ExitIpChecker.kt         # Exit IP GeoIP detection
│   ├── GeoResolver.kt           # Offline + online GeoIP resolution
│   ├── SettingsStore.kt         # SharedPreferences wrapper
│   ├── VersionChecker.kt        # Xray-core version check
│   └── SubUpdater.kt            # Subscription fetch + parse
└── ui/
    ├── MainActivity.kt          # Home screen + update dialog
    ├── EditActivity.kt          # Server editor
    ├── SettingsActivity.kt      # Settings + update + about
    ├── SubscriptionsActivity.kt # Subscription manager
    └── AppRulesActivity.kt      # Per-app VPN bypass picker
```

---

## Building from Source

### Requirements
- Android Studio (or SDK + JDK 17+)
- Android SDK Platform 36
- Build Tools 35.0.0+

### Build
```bash
# Set Java home (if using Android Studio JBR)
export JAVA_HOME="/path/to/android-studio/jbr"

# Build release APK
./gradlew assembleRelease

# Or use the batch file (Windows)
.\build.bat "assembleRelease"
```

### Signing
Release APKs are signed with `release.keystore`:
- **Alias**: `lionray`
- **Store/Key password**: `lionray123`

```bash
# Zipalign
zipalign -p -f 4 app-release-unsigned.apk aligned.apk

# Sign
apksigner sign --ks release.keystore --ks-key-alias lionray \
  --ks-pass pass:lionray123 --out LionRayVPN.apk aligned.apk
```

### GitHub Actions CI/CD
Automated builds via `.github/workflows/build.yml`:
1. Go to Actions → "Build & Release APK"
2. Click "Run workflow"
3. Enter `xray_version` (e.g., `26.3.27`) and `app_version` (e.g., `1.1`)
4. Workflow downloads Xray-core, builds APK, signs, and creates GitHub release

---

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Connect to proxy servers |
| `FOREGROUND_SERVICE` | VPN foreground service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ service type |
| `POST_NOTIFICATIONS` | VPN notification (Android 13+) |
| `REQUEST_INSTALL_PACKAGES` | In-app APK update (Android 8+) |
| `QUERY_ALL_PACKAGES` | Per-app VPN bypass picker |
| `RECEIVE_BOOT_COMPLETED` | Auto-restart after reboot |
| `WAKE_LOCK` | Keep alive during calls |
| `ACCESS_NETWORK_STATE` | Network change detection |
| `SYSTEM_ALERT_WINDOW` | Battery optimization prompt |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Disable battery optimization |

---

## Dependencies

| Library | Purpose |
|---|---|
| [Xray-core](https://github.com/XTLS/Xray-core) | Proxy core (external process) |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | TUN → SOCKS5 tunnel |
| [ZXing](https://github.com/journeyapps/zxing-android-embedded) | QR code scan/generate |
| [Material Components](https://github.com/material-components/material-components-android) | UI components |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Async operations |

---

## License

MIT License - see [LICENSE](LICENSE) for details.

### Credits
- **Xray-core** by [XTLS Team](https://github.com/XTLS) - The core proxy engine
- **hev-socks5-tunnel** by [heiher](https://github.com/heiher/hev-socks5-tunnel) - TUN to SOCKS5 tunnel
- **v2rayNG** - Architecture reference for tun2socks approach
- **ZXing** - QR code processing

---

## Disclaimer

This software is provided for educational and personal use only. Users are responsible for compliance with local laws and regulations. The developers are not responsible for any misuse of this software.

---

**Developer**: Sett Min Paing
**GitHub**: [@satminpaing1](https://github.com/satminpaing1)
**Telegram**: [@lionrayvpn](https://t.me/lionrayvpn)
