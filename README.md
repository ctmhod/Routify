# Routify

Turn any rooted Android phone, even an old one, into a smart router that tunnels traffic through any proxy or VPN protocol, with per-device speed and data limits.

> Status: early planning. No working release yet. See the [issues](../../issues) for the roadmap.

## Planned features

The app has three pages:

1. **Connection**: start a hotspot from Wi-Fi or mobile data. Repeater mode for phones that cannot run Wi-Fi and hotspot at the same time (some Samsung models).
2. **Speed and data**: list connected devices, set a speed limit and a data quota for each one, block a device.
3. **VPN**: add VLESS/VMess servers (link or QR), with custom Host and SNI fields, and route all connected devices through them. Powered by Xray-core.

## Requirements

- Android 10 or newer (API 29+)
- Root access

## Roadmap

- [ ] Phase 0: Project setup (#1)
- [ ] Phase 1: Hotspot and Repeater page (#2)
- [ ] Phase 2: VPN page with Xray (#3)
- [ ] Phase 3: Speed and data limits page (#4)
- [ ] Phase 4: First release (#5)

## Credits and license

Routify is licensed under the [Apache License 2.0](LICENSE).

It is based on [VPN Hotspot](https://github.com/Mygod/VPNHotspot) by Mygod (Apache 2.0) and uses [Xray-core](https://github.com/XTLS/Xray-core) (MPL 2.0). See [NOTICE](NOTICE).
