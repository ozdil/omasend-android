package io.omarchy.omasend.ui

import java.util.Locale

object OmaI18n {
    private val supportedLangs = setOf(
        "tr", "en", "de", "fr", "es", "it", "pt", "ru", "ar", "zh",
        "ja", "ko", "nl", "pl", "uk", "az", "kk", "uz", "fa", "hi", "id"
    )

    fun currentLang(): String {
        val lang = Locale.getDefault().language.lowercase(Locale.ROOT)
        return if (lang in supportedLangs) lang else "en"
    }

    fun t(key: String): String {
        val lang = currentLang()
        val entry = dict[key] ?: return key
        return entry[lang] ?: entry["en"] ?: entry["tr"] ?: key
    }

    private val dict: Map<String, Map<String, String>> = mapOf(
        "status_active" to mapOf(
            "tr" to "AKTİF", "en" to "ACTIVE", "de" to "AKTIV", "fr" to "ACTIF",
            "es" to "ACTIVO", "it" to "ATTIVO", "pt" to "ATIVO", "ru" to "АКТИВЕН",
            "ar" to "نشط", "zh" to "已启用", "ja" to "有効", "ko" to "활성",
            "nl" to "ACTIEF", "pl" to "AKTYWNY", "uk" to "АКТИВНИЙ", "az" to "AKTİV",
            "kk" to "БЕЛСЕНДІ", "uz" to "FAOL", "fa" to "فعال", "hi" to "सक्रिय", "id" to "AKTIF"
        ),
        "status_off" to mapOf(
            "tr" to "KAPALI", "en" to "OFF", "de" to "AUS", "fr" to "INACTIF",
            "es" to "APAGADO", "it" to "SPENTO", "pt" to "DESATIVADO", "ru" to "ВЫКЛ",
            "ar" to "مغلق", "zh" to "已关闭", "ja" to "オフ", "ko" to "꺼짐",
            "nl" to "UIT", "pl" to "WYŁ.", "uk" to "ВИМК.", "az" to "BAĞLI",
            "kk" to "ӨШІРУЛІ", "uz" to "O'CHIQ", "fa" to "خاموش", "hi" to "बंद", "id" to "MATI"
        ),
        "toast_refreshed" to mapOf(
            "tr" to "Ağ eşleri ve OmaID bağlantıları güncellendi",
            "en" to "Network peers and OmaID connections refreshed",
            "de" to "Netzwerk-Peers und OmaID-Verbindungen aktualisiert",
            "fr" to "Pairs réseau et connexions OmaID actualisés",
            "es" to "Pares de red y conexiones OmaID actualizados",
            "it" to "Peer di rete e connessioni OmaID aggiornati",
            "pt" to "Pares de rede e conexões OmaID atualizados",
            "ru" to "Узлы сети и подключения OmaID обновлены",
            "ar" to "تم تحديث أقران الشبكة واتصالات OmaID",
            "zh" to "网络节点与 OmaID 连接已刷新",
            "ja" to "ネットワークピアとOmaID接続を更新しました",
            "ko" to "네트워크 피어 및 OmaID 연결이 새로고침되었습니다",
            "nl" to "Netwerkpeers en OmaID-verbindingen vernieuwd",
            "pl" to "Odświeżono urządzenia w sieci i połączenia OmaID",
            "uk" to "Вузли мережі та підключення OmaID оновлено",
            "az" to "Şəbəkə cihazları və OmaID bağlantıları yeniləndi",
            "kk" to "Желі құрылғылары мен OmaID қосылымдары жаңартылды",
            "uz" to "Tarmoq qurilmalari va OmaID ulanishlari yangilandi",
            "fa" to "همتاهای شبکه و اتصالات OmaID بازخوانی شدند",
            "hi" to "नेटवर्क पीयर्स और OmaID कनेक्शन रीफ़्रेश किए गए",
            "id" to "Rekan jaringan dan koneksi OmaID diperbarui"
        ),
        "toast_omaid_copied" to mapOf(
            "tr" to "OmaID panoya kopyalandı",
            "en" to "OmaID copied to clipboard",
            "de" to "OmaID in Zwischenablage kopiert",
            "fr" to "OmaID copié dans le presse-papiers",
            "es" to "OmaID copiado al portapapeles",
            "it" to "OmaID copiato negli appunti",
            "pt" to "OmaID copiado para a área de transferência",
            "ru" to "OmaID скопирован в буфер обмена",
            "ar" to "تم نسخ OmaID إلى الحافظة",
            "zh" to "OmaID 已复制到剪贴板",
            "ja" to "OmaIDをクリップボードにコピーしました",
            "ko" to "OmaID가 클립보드에 복사되었습니다",
            "nl" to "OmaID gekopieerd naar klembord",
            "pl" to "OmaID skopiowano do schowka",
            "uk" to "OmaID скопійовано до буфера обміну",
            "az" to "OmaID mübadilə buferinə kopyalandı",
            "kk" to "OmaID алмасу буферіне көшірілді",
            "uz" to "OmaID xotiraga nusxalandi",
            "fa" to "شناسه OmaID در کلیپ‌بورد کپی شد",
            "hi" to "OmaID क्लिपबोर्ड पर कॉपी किया गया",
            "id" to "OmaID disalin ke papan klip"
        ),
        "section_my_devices" to mapOf(
            "tr" to "KENDİ CİHAZLARIM (AYNI OMAID • ZERO-CLICK)",
            "en" to "MY DEVICES (SAME OMAID • ZERO-CLICK)",
            "de" to "MEINE GERÄTE (GLEICHE OMAID • ZERO-CLICK)",
            "fr" to "MES APPAREILS (MÊME OMAID • ZERO-CLICK)",
            "es" to "MIS DISPOSITIVOS (MISMO OMAID • ZERO-CLICK)",
            "it" to "I MIEI DISPOSITIVI (STESSO OMAID • ZERO-CLICK)",
            "pt" to "MEUS DISPOSITIVOS (MESMO OMAID • ZERO-CLICK)",
            "ru" to "МОИ УСТРОЙСТВА (ТОТ ЖЕ OMAID • ZERO-CLICK)",
            "ar" to "أجهزتي (نفس OMAID • بدون نقر)",
            "zh" to "我的设备 (相同 OMAID • 零点击)",
            "ja" to "マイデバイス (同一 OMAID • ゼロクリック)",
            "ko" to "내 기기 (동일 OMAID • 제로클릭)",
            "nl" to "MIJN APPARATEN (ZELFDE OMAID • ZERO-CLICK)",
            "pl" to "MOJE URZĄDZENIA (TEN SAM OMAID • ZERO-CLICK)",
            "uk" to "МОЇ ПРИСТРОЇ (ТОЙ САМИЙ OMAID • ZERO-CLICK)",
            "az" to "ÖZ CİHAZLARIM (EYNİ OMAID • ZERO-CLICK)",
            "kk" to "МЕНІҢ ҚҰРЫЛҒЫЛАРЫМ (БІРДЕЙ OMAID • ZERO-CLICK)",
            "uz" to "MENING QURILMALARIM (BIR XIL OMAID • ZERO-CLICK)",
            "fa" to "دستگاه‌های من (همین OMAID • بدون کلیک)",
            "hi" to "मेरे डिवाइस (समान OMAID • ज़ीरो-क्लिक)",
            "id" to "PERANGKAT SAYA (OMAID SAMA • ZERO-CLICK)"
        ),
        "section_other_devices" to mapOf(
            "tr" to "ÇEVREDEKİ CİHAZLAR & FARKLI OMAID",
            "en" to "NEARBY DEVICES & OTHER OMAID",
            "de" to "GERÄTE IN DER NÄHE & ANDERE OMAID",
            "fr" to "APPAREILS À PROXIMITÉ & AUTRE OMAID",
            "es" to "DISPOSITIVOS CERCANOS Y OTRO OMAID",
            "it" to "DISPOSITIVI VICINI E ALTRO OMAID",
            "pt" to "DISPOSITIVOS PRÓXIMOS E OUTRO OMAID",
            "ru" to "УСТРОЙСТВА РЯДОМ И ДРУГОЙ OMAID",
            "ar" to "الأجهزة القريبة و OMAID آخر",
            "zh" to "附近设备与其他 OMAID",
            "ja" to "周辺のデバイス・別のOmaID",
            "ko" to "주변 기기 및 다른 OmaID",
            "nl" to "APPARATEN IN DE BUURT & ANDERE OMAID",
            "pl" to "URZĄDZENIA W POBLIŻU I INNY OMAID",
            "uk" to "ПРИСТРОЇ ПОРУЧ ТА ІНШИЙ OMAID",
            "az" to "ƏTRAFDAKI CİHAZLAR VƏ FƏRQLİ OMAID",
            "kk" to "МАҢАЙДАҒЫ ҚҰРЫЛҒЫЛАР ЖӘНЕ БАСҚА OMAID",
            "uz" to "ATROFDAGI QURILMALAR VA BOSHQA OMAID",
            "fa" to "دستگاه‌های اطراف و OMAID دیگر",
            "hi" to "आस-पास के डिवाइस और अन्य OMAID",
            "id" to "PERANGKAT SEKITAR & OMAID LAIN"
        ),
        "btn_send_other_omaid" to mapOf(
            "tr" to "Farklı OmaID'ye Gönder",
            "en" to "Send to Another OmaID",
            "de" to "An andere OmaID senden",
            "fr" to "Envoyer à un autre OmaID",
            "es" to "Enviar a otro OmaID",
            "it" to "Invia a un altro OmaID",
            "pt" to "Enviar para outro OmaID",
            "ru" to "Отправить на другой OmaID",
            "ar" to "إرسال إلى OmaID آخر",
            "zh" to "发送到其他 OmaID",
            "ja" to "別のOmaIDに送信",
            "ko" to "다른 OmaID로 보내기",
            "nl" to "Stuur naar andere OmaID",
            "pl" to "Wyślij na inny OmaID",
            "uk" to "Надіслати на інший OmaID",
            "az" to "Fərqli OmaID-yə Göndər",
            "kk" to "Басқа OmaID-ге жіберу",
            "uz" to "Boshqa OmaID-ga yuborish",
            "fa" to "ارسال به OmaID دیگر",
            "hi" to "अन्य OmaID पर भेजें",
            "id" to "Kirim ke OmaID Lain"
        ),
        "dock_send_file" to mapOf(
            "tr" to "Dosya Gönder", "en" to "Send File", "de" to "Datei senden", "fr" to "Envoyer fichier",
            "es" to "Enviar archivo", "it" to "Invia file", "pt" to "Enviar arquivo", "ru" to "Файл",
            "ar" to "إرسال ملف", "zh" to "发送文件", "ja" to "ファイル送信", "ko" to "파일 보내기",
            "nl" to "Bestand", "pl" to "Wyślij plik", "uk" to "Надіслати файл", "az" to "Fayl Göndər",
            "kk" to "Файл жіберу", "uz" to "Fayl yuborish", "fa" to "ارسال فایل", "hi" to "फ़ाइल भेजें", "id" to "Kirim File"
        ),
        "dock_send_media" to mapOf(
            "tr" to "Galeri / Medya", "en" to "Gallery / Media", "de" to "Galerie / Medien", "fr" to "Galerie / Média",
            "es" to "Galería / Media", "it" to "Galleria / Media", "pt" to "Galeria / Mídia", "ru" to "Галерея",
            "ar" to "المعرض / وسائط", "zh" to "相册 / 媒体", "ja" to "メディア送信", "ko" to "갤러리 / 미디어",
            "nl" to "Galerij / Media", "pl" to "Galeria / Media", "uk" to "Галерея / Медіа", "az" to "Qalereya / Media",
            "kk" to "Галерея / Медиа", "uz" to "Galereya / Media", "fa" to "گالری / رسانه", "hi" to "गैलरी / मीडिया", "id" to "Galeri / Media"
        )
    )
}
