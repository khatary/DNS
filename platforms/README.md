# IR_Khatary DNS — Windows + iOS

Cross-platform work begins alongside the existing Android app without changing Android behavior.

## Windows 10/11
Run PowerShell as Administrator and execute:
powershell -ExecutionPolicy Bypass -File .\desktop\windows\IR-Khatary-DNS.ps1

The initial utility applies a selected public DNS resolver to an active network adapter or requests a reset to DHCP. It reports errors instead of pretending to connect. This is a system DNS setting, not a VPN tunnel.

## iOS
See [iOS setup and limitations](ios/README.md). iOS requires a DNS configuration profile for system DNS or a signed app with suitable Apple capabilities. A signed installable app requires Xcode/macOS and Apple signing assets.

## Important limitations
- Public DNS is not a VPN and does not encrypt all device traffic.
- DNS changes do not guarantee lower game ping/FPS and may not bypass blocking.
- This repository does not host a custom IR_Khatary DNS resolver.
- Test on real Windows and iOS devices before calling either platform release-ready.
