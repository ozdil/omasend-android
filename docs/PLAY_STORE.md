# OmaSend - Google Play Store Listing & Compliance Guide

This document contains all official store metadata, textual assets, and regulatory disclosures required for publishing OmaSend on the Google Play Console.

---

## 1. App Identity & Category

- **App Name**: OmaSend - Local P2P Transfer
- **Package Name**: `io.omarchy.omasend`
- **Default Language**: English (United States) - `en-US`
- **Secondary Language**: Turkish - `tr-TR`
- **Category**: Tools / Productivity
- **Tags / Keywords**: `peer-to-peer`, `file transfer`, `clipboard sync`, `local network`, `lan sharing`, `privacy`, `open source`
- **Target Audience**: Ages 13 and older (General Audience)
- **Contains Ads**: No
- **In-App Purchases**: No
- **Government App**: No
- **Financial / Health Features**: No

---

## 2. Store Descriptions

### English (en-US)

**Short Description (Max 80 characters):**
High-performance local peer-to-peer file transfer and Wayland clipboard sync.

**Full Description (Max 4000 characters):**
OmaSend is a high-performance, privacy-first peer-to-peer file and clipboard sharing application built for local networks. It provides seamless cross-platform parity between Omarchy Linux (and other modern Linux distributions) and Android devices.

OmaSend works within your local area network (LAN). It has no user accounts, remote databases or relay servers: your files and clipboard content travel directly from device to device at full local network speeds. The only outside contact is a periodic STUN request (see "Network use" below), which carries no file or clipboard data.

KEY CAPABILITIES

1. Instant Local Peer Discovery:
Using automated UDP broadcast beacons on port 53317, OmaSend discovers nearby devices without manual IP entry. Devices can also be paired by scanning a QR code with the camera.

2. High-Speed File Streaming:
Transfer photos, documents, archives, and media directly between your Android device and Linux workstations with no compression, up to 10 GiB per file, and real-time progress tracking. QR-paired devices use an end-to-end encrypted QUIC connection (UDP port 53318).

3. Bi-Directional Clipboard Synchronization:
Share text snippets, URLs, code blocks, and tokens seamlessly between your Android clipboard and Linux Wayland desktop environment with a single tap.

4. System Share Sheet Integration:
Send any file or text directly from your favorite Android apps using the native Android system share dialog.

5. Background Daemon & Radar Interface:
Monitor nearby devices and incoming transfers effortlessly with a low-power background service and a cybernetic radar interface.

PRIVACY AND ARCHITECTURE GUARANTEES

- No Cloud Relay: All transfers are direct connections over your local Wi-Fi or Ethernet network.
- Zero Telemetry: OmaSend collects no analytics, no crash metrics, no device identifiers, and no user tracking data.
- Open Protocol Parity: Full wire compatibility with the official OmaSend Linux engine and desktop plugin.
- Strict Permission Discipline: Requests only the network, notification and camera (QR pairing) permissions it needs.

OmaSend is designed for users who demand speed, reliability, and absolute privacy on their local networks.

---

### Turkish (tr-TR)

**Kısa Açıklama (En fazla 80 karakter):**
Yerel ağ üzerinden hızlı P2P dosya transferi ve Wayland pano eşitleme.

**Tam Açıklama (En fazla 4000 karakter):**
OmaSend, yerel ağlar için geliştirilmiş yüksek performanslı ve gizlilik odaklı bir eşler arası (P2P) dosya ve pano paylaşım uygulamasıdır. Omarchy Linux ve modern Linux masaüstü ortamları ile Android cihazlar arasında doğrudan bağlantı sağlar.

OmaSend yerel ağınız (LAN) içinde çalışır. Kullanıcı hesabı, uzak veritabanı veya aktarma sunucusu yoktur: dosyalarınız ve pano içerikleriniz cihazlar arasında doğrudan yerel ağ hızında aktarılır. Dışarıyla tek temas, dosya veya pano verisi taşımayan periyodik bir STUN isteğidir (aşağıda "Ağ kullanımı" bölümüne bakın).

TEMEL ÖZELLİKLER

1. Anında Yerel Eş Keşfi:
53317 portu üzerindeki otomatik UDP yayın sinyalleri sayesinde OmaSend, IP adresi girmeye gerek kalmadan yakındaki cihazları bulur. Cihazlar kamerayla QR kod okutularak da eşleştirilebilir.

2. Yüksek Hızlı Dosya Aktarımı:
Fotoğraf, video, belge ve arşiv dosyalarını Android cihazınız ile Linux bilgisayarınız arasında sıkıştırma olmadan, dosya başına 10 GiB'a kadar ve anlık ilerleme takibiyle aktarın. QR ile eşleşmiş cihazlar uçtan uca şifreli QUIC bağlantısı (UDP 53318 portu) kullanır.

3. Çift Yönlü Pano (Clipboard) Eşitleme:
Metinleri, web adreslerini, kod parçacıklarını ve notları tek dokunuşla Android panonuz ile Linux Wayland masaüstünüz arasında eşitleyin.

4. Sistem Paylaşım Menüsü Entegrasyonu:
Android üzerindeki herhangi bir uygulamadan dosya veya metin paylaşırken doğrudan sistem paylaşım menüsü üzerinden OmaSend'i seçebilirsiniz.

5. Arka Plan Hizmeti ve Radar Arayüzü:
Düşük güç tüketimli arka plan servisi ve modern radar arayüzü ile ağdaki aktif cihazları ve gelen aktarımları kolayca izleyin.

GİZLİLİK VE MİMARİ İLKELER

- Bulut Aktarıcısı Yok: Tüm aktarımlar yerel Wi-Fi veya Ethernet ağınız üzerinden doğrudan bağlantıyla yapılır.
- Sıfır Telemetri: OmaSend hiçbir analiz verisi, çökme raporu, cihaz kimliği veya kullanıcı izleme verisi toplamaz.
- Açık Protokol Uyumluluğu: Resmi OmaSend Linux motoru ve masaüstü eklentisiyle tam protokol uyumluluğu.
- Sıkı İzin Yönetimi: Yalnızca gerekli ağ, bildirim ve kamera (QR eşleştirme) izinlerini talep eder.

OmaSend, yerel ağlarında hız, kararlılık ve mutlak gizlilik isteyen kullanıcılar için tasarlanmıştır.

---

## 3. Data Safety (Veri Güvenliği) Questionnaire Answers

Google Play Console'da "Data safety" (Veri güvenliği) adımı için resmi yanıtlar:

1. **Does your app collect or share any of the required user data types?**
   - Answer: **NO** (OmaSend does not collect, store, or share any user data).
2. **Is all of the user data collected by your app encrypted in transit?**
   - Answer: **NOT APPLICABLE** (No data is collected by the developer or transmitted to developer servers).
3. **Do you provide a way for users to request that their data be deleted?**
   - Answer: **NOT APPLICABLE** (No user accounts or cloud storage exist).
4. **Is your app target audience including children?**
   - Answer: **NO** (Ages 13+).

---

## 4. Permissions & Justifications

Matches `app/src/main/AndroidManifest.xml`. OmaSend declares no Bluetooth or location permissions.

- `android.permission.INTERNET`: Local HTTP/TCP sockets (port 53317) and the QUIC transport (UDP port 53318) for transfers on the local network.
- `android.permission.ACCESS_NETWORK_STATE` & `ACCESS_WIFI_STATE`: Detect Wi-Fi/Ethernet connectivity and pick the LAN address to advertise.
- `android.permission.CHANGE_WIFI_MULTICAST_STATE`: Receive UDP broadcast/multicast discovery beacons on port 53317.
- `android.permission.WAKE_LOCK`: Keep the CPU and Wi-Fi awake while a transfer is streaming.
- `android.permission.FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` & `FOREGROUND_SERVICE_CONNECTED_DEVICE`: The foreground service (`foregroundServiceType="dataSync|connectedDevice"`) keeps the device reachable by nearby devices and receives incoming files while the app is in the background.
- `android.permission.POST_NOTIFICATIONS`: Android 13+ service status, incoming-transfer and transfer-result notifications.
- `android.permission.VIBRATE`: Haptic feedback for sent/received items.
- `android.permission.CAMERA` (camera hardware optional): Scan pairing / OmaID QR codes. Frames are decoded on the device and never stored or sent.
- `android.permission.READ_EXTERNAL_STORAGE` (max SDK 32) & `WRITE_EXTERNAL_STORAGE` (max SDK 28): Save received files to Downloads/OmaSend on older Android versions; newer versions use MediaStore without a permission.

## 5. Network Use (for the Data safety form and the description)

- **LAN only for content:** files and clipboard content are sent only to devices on the local network (legacy HTTP on TCP 53317, or end-to-end encrypted QUIC on UDP 53318 for QR-paired devices). There is no relay or cloud storage.
- **Limits:** up to 10 GiB per file.
- **STUN:** while the app runs, it sends a STUN Binding Request about every 30 seconds to `stun.l.google.com:19302`, `stun1.l.google.com:19302` or `stun.cloudflare.com:3478` to learn its public address. These servers see the device's public IP address; no file, clipboard or identity data is sent. Claims such as "zero cloud contact" must not be used while this is enabled.
