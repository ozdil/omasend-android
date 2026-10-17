# OmaSend - Google Play Store Listing & Compliance Guide

This document contains all official store metadata, textual assets, and regulatory disclosures required for publishing OmaSend on the Google Play Console.

---

## 1. App Identity & Category

- **App Name**: OmaSend for Omarchy
- **Package Name**: `io.omarchy.omasend`
- **Default Language**: English (United States) - `en-US`
- **Secondary Language**: Turkish - `tr-TR`
- **Category**: Tools / Productivity
- **Tags / Keywords**: `peer-to-peer`, `file transfer`, `clipboard sync`, `local network`, `lan sharing`, `privacy`, `open source`, `omarchy`
- **Target Audience**: Ages 13 and older (General Audience)
- **Contains Ads**: No
- **In-App Purchases**: No
- **Government App**: No
- **Financial / Health Features**: No

---

## 2. Store Descriptions

Written for 2.0.8 (OmaID account, internet relay, shared clipboard, mono UI). Do not publish this text with an older build.

### English (en-US)

**App name (max 30):** OmaSend for Omarchy

**Short description (max 80):**
Send files and share your clipboard between Omarchy and Android. Privately.

**Full description (max 4000):**
OmaSend is the file and clipboard sharing app for Omarchy. Send a photo from your phone to your Omarchy desktop, or a file from your desktop to your phone, in a couple of taps.

It is built on two ideas: sharing should be as simple as tapping a name, and nothing you share should be readable by anyone but you.

WHAT YOU CAN DO

- Send files both ways. Photos, videos, documents and archives go straight between your devices, uncompressed, up to 10 GiB per file.
- Share from any app. Pick OmaSend in the Android share menu.
- Share your clipboard. Copy on one of your devices and paste on another. The latest copy wins, and a Quick Settings tile turns it on or off.
- Pair with a QR code. Scan the code on your Omarchy desktop once, and the two devices trust each other from then on.
- Use one OmaID on all your devices. Your OmaID is an anonymous 16-digit number. There is no email, name or password. Add a new device by scanning a QR code, or by typing the number and approving it once on a device you already have.
- Share beyond your Wi-Fi. Devices on the same OmaID reach each other over the internet through an encrypted relay when they are not on the same network.

PRIVACY

- End-to-end encrypted: paired devices talk over QUIC with TLS 1.3. Relays only pass along encrypted traffic.
- Anonymous account: your OmaID number never leaves your devices. The registry stores only a value derived from it and your device list, encrypted and signed on your devices.
- No ads, no analytics, no tracking.
- Only the permissions it needs: network, notifications, and the camera for scanning QR codes.

DESIGNED FOR OMARCHY

OmaSend works with the OmaSend plugin for Omarchy and follows your phone's light or dark theme.

OmaSend is an independent project. It is not made or endorsed by the Omarchy project or Basecamp.

Support: https://omaid.app/support
Privacy: https://omaid.app/privacy

### Türkçe (tr-TR)

**Uygulama adı (en fazla 30):** OmaSend for Omarchy

**Kısa açıklama (en fazla 80):**
Omarchy ile Android arasında dosya gönderin, panoyu paylaşın. Gizli ve sade.

**Tam açıklama (en fazla 4000):**
OmaSend, Omarchy için dosya ve pano paylaşım uygulamasıdır. Telefonunuzdaki bir fotoğrafı Omarchy masaüstünüze, masaüstünüzdeki bir dosyayı telefonunuza birkaç dokunuşla gönderin.

İki fikir üzerine kurulu: paylaşmak bir isme dokunmak kadar kolay olmalı ve paylaştığınız hiçbir şeyi sizden başkası okuyamamalı.

NELER YAPABİLİRSİNİZ

- İki yönde dosya gönderin. Fotoğraf, video, belge ve arşivler cihazlarınız arasında doğrudan, sıkıştırılmadan, dosya başına 10 GiB'a kadar gider.
- Her uygulamadan paylaşın. Android paylaşım menüsünde OmaSend'i seçin.
- Panonuzu paylaşın. Bir cihazınızda kopyalayın, diğerinde yapıştırın. Son kopyalanan geçerlidir; Hızlı Ayarlar kutucuğuyla açıp kapatabilirsiniz.
- QR kodla eşleştirin. Omarchy masaüstünüzdeki kodu bir kez okutun; iki cihaz bundan sonra birbirine güvenir.
- Tüm cihazlarınızda tek OmaID kullanın. OmaID anonim, 16 haneli bir numaradır. E-posta, isim ya da şifre yoktur. Yeni bir cihazı QR kod okutarak ya da numarayı girip mevcut bir cihazınızda bir kez onaylayarak ekleyin.
- Wi-Fi dışında da paylaşın. Aynı OmaID'deki cihazlar aynı ağda değilken internet üzerinden şifreli bir aktarıcıyla birbirine ulaşır.

GİZLİLİK

- Uçtan uca şifreli: eşleşmiş cihazlar TLS 1.3 ile QUIC üzerinden konuşur. Aktarıcılar yalnızca şifreli trafiği iletir.
- Anonim hesap: OmaID numaranız cihazlarınızdan hiç çıkmaz. Kayıt sunucusu yalnızca numaradan türetilmiş bir değeri ve cihazlarınızda şifrelenip imzalanmış cihaz listenizi saklar.
- Reklam, analiz ve izleme yoktur.
- Yalnızca gereken izinler: ağ, bildirimler ve QR kod okutmak için kamera.

OMARCHY İÇİN TASARLANDI

OmaSend, Omarchy için OmaSend eklentisiyle birlikte çalışır ve telefonunuzun açık ya da koyu temasını izler.

OmaSend bağımsız bir projedir. Omarchy projesi veya Basecamp tarafından yapılmamış ya da onaylanmamıştır.

Destek: https://omaid.app/support
Gizlilik: https://omaid.app/privacy

---

## 3. Data Safety (Veri Güvenliği) Questionnaire Answers

Google Play Console'da "Data safety" (Veri güvenliği) adımı için resmi yanıtlar:

1. **Does your app collect or share any of the required user data types?**
   - Answer: **NO** (OmaSend does not collect, store, or share any user data).
2. **Is all of the user data collected by your app encrypted in transit?**
   - Answer: **NOT APPLICABLE** (No data is collected by the developer or transmitted to developer servers).
3. **Do you provide a way for users to request that their data be deleted?**
   - Answer: **NOT APPLICABLE** for collected data. Since 2.0.8 the optional OmaID registry entry holds only end-to-end encrypted data the developer cannot read; removing the last device from an account deletes it.
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

As of 2.0.8:

- **Local network:** discovery on UDP 53317; transfers over legacy HTTP on TCP 53317, or end-to-end encrypted QUIC (TLS 1.3) on UDP 53318 for QR-paired devices.
- **Internet path (only with an OmaID account):** devices on the same account connect through iroh relays when they are not on the same network. Relays forward only encrypted QUIC traffic and see the devices' IP addresses.
- **OmaID registry (`id.omaid.app`, only with an account):** stores an Argon2-derived account id and the device list, encrypted and Ed25519-signed on the devices. The 16-digit number itself is never sent.
- **STUN:** disabled since 2.0.8 (the WAN rendezvous engine no longer runs).
- **Limits:** up to 10 GiB per file.
- Because of the relay and the registry, claims such as "no servers" or "zero cloud contact" must not be used.
