# OmaSend for Android

<p align="center">
  <img src="assets/feature_graphic_1024x500.png" alt="OmaSend Android Banner" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/ozdil/omasend-android/releases"><img src="https://img.shields.io/badge/Release-v2.0.9-38BDF8?style=for-the-badge&logo=android" alt="Release v2.0.9" /></a>
  <a href="https://play.google.com/apps/testing/io.omarchy.omasend"><img src="https://img.shields.io/badge/Google%20Play-Closed%20Beta-34A853?style=for-the-badge&logo=googleplay&logoColor=white" alt="Google Play Closed Beta" /></a>
  <a href="https://groups.google.com/g/omasend-testers"><img src="https://img.shields.io/badge/Google%20Group-Join%20Testers-4285F4?style=for-the-badge&logo=googlegroups&logoColor=white" alt="Join Google Group" /></a>
  <a href="https://github.com/ozdil/omarchy-omasend"><img src="https://img.shields.io/badge/Omarchy%20Linux-Desktop%20Plugin-00ADD8?style=for-the-badge&logo=archlinux&logoColor=white" alt="Omarchy Linux Desktop Plugin" /></a>
  <a href="https://buymeacoffee.com/ozdil"><img src="https://img.shields.io/badge/Buy_Me_A_Coffee-Support-FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black" alt="Buy Me A Coffee" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="MIT License" /></a>
</p>

> **OmaSend for Omarchy: send files and clipboard between your Android phone, Omarchy Linux and the browser. Pair once with a QR code and everything between your devices is end-to-end encrypted. An optional anonymous OmaID connects your own devices over the internet too. No sign-up, no e-mail, no tracking.**

---

## What's new in 2.0.9 (versionCode 109)

**New**
- **OmaID account.** One anonymous 16-digit number for all of your devices, Mullvad style: no e-mail, no name, no phone number. The number never leaves the phone; the registry at `id.omaid.app` only stores an Argon2id-derived id and an end-to-end encrypted, signed device list. Add a device with the number and a one-time approval (both screens show the same 6-digit code), or scan a join QR (`https://omaid.app/j#…`).
- **Over the internet.** With an OmaID your own devices connect through encrypted iroh relays when they are not on the same network, and switch to a direct connection when they can. Transfers over 4 GiB wait for a direct connection.
- **Shared clipboard, last copy wins.** Your latest copy reaches your own devices silently; copies older than 2 minutes, or older than a newer one, are dropped. Quick Settings tile **Clipboard**.
- **One ink.** Monochrome OmaSend logo and UI that follow the phone's light/dark theme and Material You tones; tap a device to send.

**Bug fixes**
- Phone to PC sends no longer fail with "Permission denied (os error 13)".
- "From gallery" pairs with a pairing QR saved as an image.
- A refused transfer shows the refusal instead of waiting it out.
- The Quick Settings tile label is no longer cut off; "1 device" instead of "1 devices"; About text is current.

Full history: [desktop CHANGELOG](https://github.com/ozdil/omarchy-omasend/blob/next/CHANGELOG.md).

---

## Official Google Play Closed Beta

OmaSend for Android is distributed officially via **Google Play** with verified SHA-256 application signing and **Play Protect** security scanning.

### How to Install & Join the Beta (3 Simple Steps):

1. **Join the Tester Community (Google Group):**  
   [Join OmaSend Testers Google Group](https://groups.google.com/g/omasend-testers)  
   *(Click "Join group" with the same Google account you use on your Android phone).*

2. **Opt-in to the Testing Program (Google Play Web):**  
   [Opt-in on Google Play](https://play.google.com/apps/testing/io.omarchy.omasend)  
   *(Click "Become a tester" / "Test kullanıcısı ol").*

3. **Install from Google Play Store:**  
   [Download OmaSend on Google Play](https://play.google.com/store/apps/details?id=io.omarchy.omasend)  
   *(Open the link directly on your Android device to install via Play Store).*

---

## Features & Capabilities

- **Tap a device, send:** share photos, videos, documents and archives from any app through the system share sheet, or pick them in OmaSend.
- **QR pairing with per-device keys:** each phone has its own Ed25519 key, wrapped by the Android Keystore. Paired devices talk over QUIC + TLS 1.3 (the shared Rust `omasend-core`), and files are verified with BLAKE3 and resume where they stopped.
- **My devices and other people's devices:** devices you pair as your own accept transfers automatically and share the clipboard; anyone else's always needs your approval.
- **OmaID:** optional anonymous account that keeps your device list in sync and connects your devices over the internet (see above).
- **Clipboard:** text from your own devices, newest copy wins; clipboard items marked sensitive by Android 13+ (password managers) are never shared.
- **Nearby devices:** unpaired devices on the same network still work over the legacy HTTP path (port `53317`), always with approval. That path is not encrypted; use it on networks you trust.

---

## Security

- **End-to-end encryption between paired devices:** QUIC/TLS 1.3 bound to each device's key; there is no silent fallback to plain HTTP for paired devices.
- **No personal data:** no sign-up, no analytics. The OmaID registry never sees your number, names or files; relays only carry encrypted traffic.
- **Verified builds:** every Gradle dependency is pinned to a SHA-256 checksum in `gradle/verification-metadata.xml`.

---

## Omarchy Linux Desktop & Web Client

- **Desktop Plugin Repository:** [ozdil/omarchy-omasend](https://github.com/ozdil/omarchy-omasend)
- **Web Client Repository:** [ozdil/omasend-web](https://github.com/ozdil/omasend-web)
- **Engine Protocol:** Compatible with the OmaSend desktop engine (`omasend-engine`) 2.0.9: QUIC on UDP `53318`, legacy HTTP on `53317`.

---

## Build from Source

```bash
# Clone the repository
git clone https://github.com/ozdil/omasend-android.git
cd omasend-android

# Build Debug APK
./gradlew assembleDebug

# Build Release App Bundle (AAB)
./gradlew bundleRelease

# Install directly to connected phone via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## License

This project is licensed under the **MIT License**. See [LICENSE](LICENSE) for details.
