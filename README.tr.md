# Android İçin OmaSend

<p align="center">
  <img src="assets/feature_graphic_1024x500.png" alt="OmaSend Android Banner" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/ozdil/omasend-android/releases"><img src="https://img.shields.io/badge/S%C3%BCr%C3%BCm-v1.7.1-38BDF8?style=for-the-badge&logo=android" alt="Sürüm v1.7.1" /></a>
  <a href="https://play.google.com/apps/testing/io.omarchy.omasend"><img src="https://img.shields.io/badge/Google%20Play-Kapal%C4%B1%20Beta-34A853?style=for-the-badge&logo=googleplay&logoColor=white" alt="Google Play Kapalı Beta" /></a>
  <a href="https://groups.google.com/g/omasend-testers"><img src="https://img.shields.io/badge/Google%20Grubu-Test%20Ekibi-4285F4?style=for-the-badge&logo=googlegroups&logoColor=white" alt="Google Grubuna Katıl" /></a>
  <a href="https://github.com/ozdil/omarchy-omasend"><img src="https://img.shields.io/badge/Omarchy%20Linux-Masa%C3%BCst%C3%BC%20Eklentisi-00ADD8?style=for-the-badge&logo=archlinux&logoColor=white" alt="Omarchy Linux Eklentisi" /></a>
  <a href="https://buymeacoffee.com/ozdil"><img src="https://img.shields.io/badge/Kahve_Ismarla-Destek-FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black" alt="Kahve Ismarla" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/Lisans-MIT-blue?style=for-the-badge" alt="MIT Lisansı" /></a>
</p>

> **Android, Web PWA ve Omarchy Linux arasında yüksek performanslı, gizlilik odaklı yerel P2P dosya aktarımı, sistem paylaşım menüsü entegrasyonu, 16 haneli Luhn OmaID eşleşmesi ve gerçek zamanlı Wayland pano köprüsü.**

---

## Mimari Evrim: OmaSend V2 Mobil Mühendisliği

Android için OmaSend, Omarchy Linux masaüstü arka plan motoru ve Web PWA istemcisiyle tam uyum içinde geliştirilmiştir:

- **16 Haneli Luhn Mod 10 OmaID Eşleşmesi:** Sıfır hesap, sıfır sunucu; RFC 2104 HMAC-SHA256 Blinded Rendezvous konuları ve RFC 5869 HKDF-SHA256 simetrik anahtar türetimi ile güvenli eşleşme.
- **AMOLED Zen Radar:** Karmaşık teknik veriler yerine temiz durum rozetleri (`[E2EE SECURE]`, `[LAN DIRECT]`, `[OMAID P2P]`) ve dokunsal titreşim geri bildirimi.
- **Evrensel Çok Formatlı Pano Kasası:** Metin ve görseller için gerçek zamanlı çift yönlü pano senkronizasyonu ve otomatik WebP mikro önizleme önbelleği.
- **Hassas Pano Kalkanı:** Android 13+ `ClipDescription.EXTRA_IS_SENSITIVE` ve parola yöneticisi etiketlerini otomatik olarak tanır; hassas şifrelerin istem dışı aktarılmasını engeller.
- **Sıfır Kopyalı ByteBuffers (`ASharedMemory` Mimarisi):** NDK katmanında optimize edilmiş bellek yönetimi ile çok gigabaytlık aktarımlarda Garbage Collection duraksamalarını tamamen ortadan kaldırır.

---

## Resmi Google Play Kapalı Beta

Android için OmaSend, doğrulanmış SHA-256 uygulama imzası ve **Play Protect** güvenlik taramasıyla resmi olarak **Google Play** üzerinden dağıtılmaktadır.

### Kurulum ve Test Ekibine Katılma (3 Basit Adım):

1. **Test Topluluğuna Katılın (Google Grubu):**  
   [OmaSend Test Ekibi Google Grubuna Katıl](https://groups.google.com/g/omasend-testers)  
   *(Android telefonunuzda kullandığınız Google hesabıyla "Gruba katıl" butonuna tıklayın).*

2. **Test Programını Onaylayın (Google Play Web):**  
   [Google Play Üzerinde Test Kullanıcısı Ol](https://play.google.com/apps/testing/io.omarchy.omasend)  
   *("Test kullanıcısı ol" / "Become a tester" butonuna tıklayın).*

3. **Google Play Store Üzerinden Yükleyin:**  
   [Google Play'de OmaSend İndir](https://play.google.com/store/apps/details?id=io.omarchy.omasend)  
   *(Doğrudan Android telefonunuzda bağlantıyı açarak Play Store üzerinden kurun).*

---

## Yetenekler ve Özellikler

- **Yerel Android Paylaşım Menüsü:** Galeri, Kamera veya Dosyalar uygulamasından fotoğraf, video, ses, belge ve arşivleri tek dokunuşla paylaşın.
- **Canlı Çift Yönlü Pano Köprüsü:** Bilgisayarınızın Wayland masaüstündeki metin veya görselleri anında Android'e aktarın veya telefondaki metinleri döngü korumalı olarak Linux'a iletin.
- **Sıfır Bulut ve Mutlak Gizlilik:** Yalnızca yerel Wi-Fi ve Kör Randevu kanalları üzerinden çalışır. Sıfır telemetri, sıfır harici bulut sunucusu ve sıfır kullanıcı takibi.
- **Anında UDP Keşfi:** Manuel IP adresi yazmaya gerek kalmadan `53317` portu üzerinden otomatik cihaz tespiti.
- **Modern Jetpack Compose Arayüzü:** Material 3, Koyu Akrilik cam teması ve akıcı reaktif durum yönetimi.
- **Uçtan Uca Kriptografik Güvenlik:** Sıfır Güven (Zero-Trust) geçici belirteçler, PIN doğrulaması ve AES-256-GCM / BLAKE3 bütünlük denetimi.

---

## Güvenlik ve Kurumsal Sıfır Güven (Zero-Trust) Standartları

1. **Adli Bilişim Karşıtı Koruma ve Bellek Temizleme (RAM Scrubbing):**  
   Kriptografik anahtarlar, ara aktarım tamponları ve hassas veriler kullanımdan hemen sonra sıfırlanarak (`Zeroize`) bellekten tamamen silinir.

2. **Kuantum Dirençli Kriptografi:**  
   Standart SHA-256'nın yanı sıra akış ve protokol başlık doğrulaması için yüksek performanslı, kuantum dirençli Merkle ağacı kriptografik özet algoritması olan **BLAKE3** kullanılır.

3. **Katı Bağımlılık Doğrulaması:**  
   Tüm Gradle bağımlılıkları `verification-metadata.xml` dosyasındaki SHA-256 özetlerine göre sıkı modda (`strict`) doğrulanır.

---

## Omarchy Linux Masaüstü ve Web PWA Entegrasyonu

- **Masaüstü Eklenti Deposu:** [ozdil/omarchy-omasend](https://github.com/ozdil/omarchy-omasend)
- **Web PWA İstemci Deposu:** [ozdil/omasend-web](https://github.com/ozdil/omasend-web)
- **Motor Protokolü:** OmaSend Linux Motoru (`omasend-engine`) ile `53317` portu üzerinden uyumludur.

---

## Kaynak Koddan Derleme

```bash
# Depoyu klonlayın
git clone https://github.com/ozdil/omasend-android.git
cd omasend-android

# Debug APK derleyin
./gradlew assembleDebug

# Yayınlama Paketi (AAB) derleyin
./gradlew bundleRelease

# Bağlı telefona ADB ile yükleyin
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Lisans

Bu proje **MIT Lisansı** ile lisanslanmıştır. Detaylar için [LICENSE](LICENSE) dosyasına bakın.
