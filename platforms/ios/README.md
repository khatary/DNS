# IR_Khatary DNS for iOS (initial setup)

iOS does not let an ordinary app silently replace system DNS. System-wide DNS configuration requires a DNS profile or an Apple-approved Network Extension.

## Resolver choices
- Cloudflare Public DNS: 1.1.1.1 and 1.0.0.1; DNS-over-HTTPS endpoint: https://cloudflare-dns.com/dns-query
- Google Public DNS: 8.8.8.8 and 8.8.4.4; DNS-over-HTTPS endpoint: https://dns.google/dns-query

These are third-party public resolvers, not ChatGPT/OpenAI servers. Availability and latency vary by network and country.

## Install/remove a DNS profile
Install only a profile you trust and review its server details before approving it in iOS Settings → General → VPN & Device Management. To undo it, return to VPN & Device Management and remove the profile.

## Build status
This is documentation, not an installable iOS app or profile. A signed iOS app requires Xcode on macOS and Apple signing/provisioning. A VPN-style DNS tunnel additionally requires the appropriate Network Extension capability, user approval, and device testing.

DNS configuration is not a VPN, does not encrypt all device traffic, and cannot guarantee lower ping or bypass network restrictions.
