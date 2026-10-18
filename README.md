# IR_Khatary DNS

Android DNS VPN prototype using Android `VpnService` and configurable IPv4 DNS resolvers.

## DNS resolvers
- `87.248.145.40`
- `193.242.208.98`

## Build
Open this repository in Android Studio or use GitHub Actions (`Build Android APK`). The app requests Android VPN permission before starting. DNS selection alone does not guarantee lower PUBG Mobile ping; network routing and game servers also affect latency.

## Compatibility and status
Minimum SDK is set to Android 4.0 (API 14) in the initial source. Compatibility and DNS forwarding should be tested on real devices before production use. No root access is required.
