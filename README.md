# OmaSend for Android

<p align="center">
  <img src="assets/feature_graphic_1024x500.png" alt="OmaSend Android Banner" width="100%" />
</p>

<p align="center">
  <a href="https://play.google.com/apps/testing/io.omarchy.omasend"><img src="https://img.shields.io/badge/Google%20Play-Closed%20Beta-34A853?style=for-the-badge&logo=googleplay&logoColor=white" alt="Google Play Closed Beta" /></a>
  <a href="https://groups.google.com/g/omasend-testers"><img src="https://img.shields.io/badge/Google%20Group-Join%20Testers-4285F4?style=for-the-badge&logo=googlegroups&logoColor=white" alt="Join Google Group" /></a>
  <a href="https://github.com/ozdil/omarchy-omasend"><img src="https://img.shields.io/badge/Omarchy%20Linux-Desktop%20Plugin-00ADD8?style=for-the-badge&logo=archlinux&logoColor=white" alt="Omarchy Linux Desktop Plugin" /></a>
  <a href="https://buymeacoffee.com/ozdil"><img src="https://img.shields.io/badge/Buy_Me_A_Coffee-Support-FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black" alt="Buy Me A Coffee" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="MIT License" /></a>
</p>

> **High-performance, privacy-first local peer-to-peer file transfer, system share-sheet integration, and real-time Wayland clipboard bridge between Android and Omarchy Linux.**

---

## Architectural Evolution: OmaSend V2 Mobile Engineering

OmaSend for Android is engineered in lockstep with the Omarchy Linux desktop daemon, incorporating architectural insights and simulation results from the **Mimo, Codex, Claude Code, and Jev** architectural council:

- **AMOLED Zen Radar:** Technical IP/port clutter has been removed, replaced with clean status indicators ("Aktarıma Hazır • E2EE Güvenli", "Yerel Wi-Fi P2P") and tactile feedback.
- **Kalman-Filtered BLE Proximity & Directional Haptics:** Ham RSSI signal jitter is smoothed via a 5-sample Kalman filter, coupled with orientation sensors and haptic compositions (`PRIMITIVE_THUD` and `PRIMITIVE_QUICK_RISE`) to provide physical confirmation of completed transfers without looking at the screen.
- **Sensitive Clipboard Shield:** Android 13+ `ClipDescription.EXTRA_IS_SENSITIVE` and password manager tags are automatically detected to prevent private credentials from broadcasting over the local network, with a 45-second auto-wipe TTL.
- **Zero-Copy ByteBuffers (`ASharedMemory` Architecture):** Memory allocations are optimized at the NDK layer, eliminating Dalvik/ART Garbage Collection spikes during multi-gigabyte transfers.

---

## Official Google Play Closed Beta (Testing Program)

OmaSend for Android is distributed officially via **Google Play** with verified SHA-256 application signing and **Play Protect** security scanning.

### How to Install & Join the Beta (3 Simple Steps):

1. **Join the Tester Community (Google Group):**  
   👉 **[Join OmaSend Testers Google Group](https://groups.google.com/g/omasend-testers)**  
   *(Click "Join group" with the same Google account you use on your Android phone).*

2. **Opt-in to the Testing Program (Google Play Web):**  
   👉 **[Opt-in on Google Play](https://play.google.com/apps/testing/io.omarchy.omasend)**  
   *(Click "Become a tester" / "Test kullanıcısı ol").*

3. **Install from Google Play Store:**  
   👉 **[Download OmaSend on Google Play](https://play.google.com/store/apps/details?id=io.omarchy.omasend)**  
   *(Open the link directly on your Android device to install via Play Store).*

> **Troubleshooting Tip:** If you see *"Item not found"* or *"App not available"*, verify that you have joined the [Google Group](https://groups.google.com/g/omasend-testers) using the **exact same Google account** that is logged into your phone's Google Play Store.

---

## Screenshots

<p align="center">
  <img src="assets/screenshot_phone_1.jpg" width="45%" alt="OmaSend Android Transfer Radar" />
  &nbsp;&nbsp;
  <img src="assets/screenshot_phone_2.jpg" width="45%" alt="OmaSend Android Sharing" />
</p>

---

## Features & Capabilities

- **Native Android Share Sheet Integration:** Share photos, videos, audio, documents, and archives directly from Gallery, WhatsApp, Camera, or Files with 1 tap.
- **Live Bi-Directional Clipboard Bridge:** Instantly copy text or code from your PC's Wayland desktop clipboard into Android, or push Android text directly to your Linux desktop clipboard with instant notifications and echo-loop prevention.
- **Zero Cloud & Absolute Privacy:** Operates strictly over local Wi-Fi / Ethernet sockets. Zero telemetry, zero external cloud servers, zero analytics, and zero tracking.
- **Instant UDP Discovery:** Automatic device discovery on port `53317` without tedious manual IP typing or Bluetooth pairing friction.
- **Modern AMOLED Jetpack Compose UI:** Built with Material 3, Cyber Dark AMOLED aesthetic, and fluid reactive state management.
- **End-to-End Cryptographic Security:** Zero-Trust ephemeral tokens, PIN authentication, and SHA-256/BLAKE3 integrity verification.

---

## Security and Enterprise Zero-Trust Architecture

OmaSend for Android elevates local file transfer beyond standard consumer tools:

1. **Anti-Forensics & RAM Zeroization (Memory Scrubbing):**
   - Cryptographic keys, in-flight transit buffers, and sensitive data are scrubbed from RAM immediately after use via `try...finally` blocks using explicit zero-fill byte manipulation (`StorageUtils.wipeMemory(buffer.fill(0))`).
   - Standard tools rely on automated Garbage Collection where keys can remain in RAM indefinitely.

2. **Quantum-Resilient Cryptography:**
   - Integrates **BLAKE3**, a high-performance, quantum-resilient Merkle tree cryptographic hashing algorithm for stream and protocol header validation, alongside standard SHA-256.

3. **Strict Dependency Verification:**
   - Every single Gradle dependency is cryptographically pinned and verified against SHA-256 checksums in `verification-metadata.xml` in strict mode (`org.gradle.dependency.verification=strict`), preventing supply-chain attacks.

4. **Technology Stack & Memory Safety:**
   - Built natively in **Kotlin** and Jetpack Compose allowing deep OS integration, explicit byte-level memory zeroization, and native Android lifecycle and memory management controls.

---

## Omarchy Linux Desktop Integration

OmaSend for Android is designed to pair seamlessly with the official Omarchy Linux desktop plugin:

- **Desktop Plugin Repository:** [ozdil/omarchy-omasend](https://github.com/ozdil/omarchy-omasend)
- **Engine Protocol:** Compatible with OmaSend Linux Engine (`omasend-engine`) on port `53317`.

---

## Build from Source

```bash
# Clone the repository
git clone https://github.com/ozdil/omasend-android.git
cd omasend-android

# Build Debug APK
./gradlew assembleDebug

# Install directly to connected phone via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Support & Sponsorship

If you find OmaSend useful and want to support independent development:

<a href="https://buymeacoffee.com/ozdil" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 50px !important;width: 180px !important;" ></a>

---

## License

This project is licensed under the **MIT License**. See [LICENSE](LICENSE) for details.
