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

    /** Translated text with `%s` / `%d` placeholders filled in (in the user's locale). */
    fun f(key: String, vararg args: Any?): String = String.format(Locale.getDefault(), t(key), *args)

    private val dict: Map<String, Map<String, String>> = mapOf(
        "about" to mapOf("tr" to "Hakkında", "en" to "About"),
        "about_body" to mapOf("tr" to "Omarchy Linux ve Android arasında yerel ağda dosya ve pano aktarımı. QR kodla eşleştirdiğin cihazlar arasında her şey uçtan uca şifrelenir (QUIC · TLS 1.3, cihaz anahtarları). Eşleştirilmemiş cihazlarla aktarım şifresizdir ve karşı tarafın onayını bekler.", "en" to "File and clipboard transfer between Omarchy Linux and Android on your local network. Everything between devices you paired by QR code is end-to-end encrypted (QUIC · TLS 1.3, device keys). Transfers with unpaired devices are not encrypted and need the receiver's approval."),
        "about_nearby_row" to mapOf("tr" to "Eşleşmemiş cihazlar", "en" to "Unpaired devices"),
        "about_paired_row" to mapOf("tr" to "Eşleşmiş cihazlar", "en" to "Paired devices"),
        "accept" to mapOf("tr" to "Kabul Et", "en" to "Accept"),
        "add_ip" to mapOf("tr" to "IP ekle", "en" to "Add IP"),
        "badge_own" to mapOf("tr" to "SENİN", "en" to "YOURS"),
        "badge_paired" to mapOf("tr" to "EŞLEŞMİŞ", "en" to "PAIRED"),
        "cancel" to mapOf("tr" to "İptal", "en" to "Cancel"),
        "cancelled" to mapOf("tr" to "Aktarım iptal edildi.", "en" to "Transfer cancelled."),
        "choose_target" to mapOf("tr" to "Nereye gönderilsin?", "en" to "Send to which device?"),
        "clipboard_failed" to mapOf("tr" to "Pano gönderilemedi: %s", "en" to "Clipboard not sent: %s"),
        "clipboard_sent" to mapOf("tr" to "Pano '%s' cihazına gönderildi", "en" to "Clipboard sent to '%s'"),
        "close" to mapOf("tr" to "Kapat", "en" to "Close"),
        "decline" to mapOf("tr" to "Reddet", "en" to "Decline"),
        "device_id" to mapOf("tr" to "Cihaz kimliği", "en" to "Device ID"),
        "encrypted" to mapOf("tr" to "şifreli", "en" to "encrypted"),
        "err_core_not_running" to mapOf("tr" to "Şifreli aktarım motoru başlatılamadı. Uygulamayı yeniden başlatmayı dene.", "en" to "The encrypted transport could not start. Try restarting the app."),
        "err_declined" to mapOf("tr" to "'%s' aktarımı reddetti.", "en" to "'%s' declined the transfer."),
        "err_not_paired_remote" to mapOf("tr" to "'%s' bu cihazı tanımıyor. Eşleşmeyi kaldırıp yeniden QR ile eşleştir.", "en" to "'%s' does not recognize this device. Remove the pairing and pair again by QR."),
        "err_open_file" to mapOf("tr" to "'%s' açılamadı.", "en" to "Could not open '%s'."),
        "err_transfer_failed" to mapOf("tr" to "Aktarım başarısız.", "en" to "Transfer failed."),
        "err_unreachable_encrypted" to mapOf("tr" to "'%s' şifreli bağlantıyla erişilemiyor. OmaSend'in orada açık olduğundan ve güvenlik duvarının UDP %d'e izin verdiğinden emin ol.", "en" to "'%s' is not reachable over the encrypted connection. Make sure OmaSend is running there and the firewall allows UDP %d."),
        "gallery" to mapOf("tr" to "Galeri", "en" to "Gallery"),
        "incoming" to mapOf("tr" to "Gelen aktarım", "en" to "Incoming transfer"),
        "incoming_encrypted" to mapOf("tr" to "Uçtan uca şifreli · eşleşmiş cihaz", "en" to "End-to-end encrypted · paired device"),
        "incoming_request" to mapOf("tr" to "'%s' sana dosya göndermek istiyor:", "en" to "'%s' wants to send you files:"),
        "incoming_unencrypted" to mapOf("tr" to "Şifresiz · eşleştirilmemiş cihaz", "en" to "Not encrypted · unpaired device"),
        "legacy_omaid_added" to mapOf("tr" to "Eski sürüm OmaID kodu eklendi. Şifreli aktarım için cihazı yeni QR koduyla eşleştir.", "en" to "Legacy OmaID code added. Pair with the new QR code for encrypted transfers."),
        "n_files" to mapOf("tr" to "%d dosya", "en" to "%d files"),
        "nearby_empty" to mapOf("tr" to "Yakında başka OmaSend bulunamadı.", "en" to "No other OmaSend found nearby."),
        "nearby_footnote" to mapOf("tr" to "Eşleştirilmemiş cihazlarla aktarım şifresizdir ve karşı tarafın onayını bekler. Yalnızca güvendiğin ağlarda kullan.", "en" to "Transfers with unpaired devices are not encrypted and need the receiver's approval. Use them only on networks you trust."),
        "needs_approval" to mapOf("tr" to "şifresiz · onaylı", "en" to "unencrypted · needs approval"),
        "no_targets" to mapOf("tr" to "Henüz cihaz yok. Önce bir cihaz eşleştir ya da aynı ağdaki OmaSend'in açık olduğundan emin ol.", "en" to "No devices yet. Pair a device first, or make sure OmaSend is running on the same network."),
        "offline" to mapOf("tr" to "görünmüyor", "en" to "not seen"),
        "online" to mapOf("tr" to "çevrimiçi", "en" to "online"),
        "own_device_off" to mapOf("tr" to "Başkasının cihazı: her aktarım onay ister.", "en" to "Someone else's device: every transfer needs approval."),
        "own_device_on" to mapOf("tr" to "Aktarımlar otomatik kabul edilir, pano otomatik eşitlenir.", "en" to "Transfers are accepted automatically and the clipboard syncs."),
        "own_device_toggle" to mapOf("tr" to "Bu cihaz benim", "en" to "This is my device"),
        "pair_device" to mapOf("tr" to "Cihaz eşleştir", "en" to "Pair a device"),
        "pair_failed" to mapOf("tr" to "Eşleşme başarısız: %s", "en" to "Pairing failed: %s"),
        "pair_hint_body" to mapOf("tr" to "Masaüstünde OmaSend panelindeki QR simgesine tıkla ve buradan tara. Eşleştirdiğin cihazlar arasında her şey uçtan uca şifrelenir; kendi cihazlarından gelenler otomatik kabul edilir.", "en" to "Click the QR icon in the OmaSend panel on your desktop and scan it here. Everything between paired devices is end-to-end encrypted; transfers from your own devices are accepted automatically."),
        "pair_hint_title" to mapOf("tr" to "Henüz eşleşmiş cihaz yok", "en" to "No paired devices yet"),
        "pair_intro" to mapOf("tr" to "Masaüstünde: OmaSend panelindeki QR simgesine tıkla, sonra burada tara. İki telefon arasında: birinde 'QR kodumu göster'i aç, diğeriyle tara.", "en" to "On a desktop: click the QR icon in the OmaSend panel, then scan it here. Between two phones: open 'Show my QR code' on one and scan it with the other."),
        "paired_footnote" to mapOf("tr" to "Uçtan uca şifreli. Kendi cihazlarından gelenler otomatik kabul edilir; panon yalnızca kendi cihazlarınla eşitlenir.", "en" to "End-to-end encrypted. Transfers from your own devices are accepted automatically; your clipboard only syncs with your own devices."),
        "paired_with" to mapOf("tr" to "Eşleşti: %s", "en" to "Paired with %s"),
        "qr_validity" to mapOf("tr" to "5 dakika geçerli, tek kullanımlık. Diğer cihazda OmaSend ile tara.", "en" to "Valid for 5 minutes, single use. Scan it with OmaSend on the other device."),
        "refresh" to mapOf("tr" to "Yenile", "en" to "Refresh"),
        "scan_paused" to mapOf("tr" to "Tarama kapalı.", "en" to "Scanning is off."),
        "scan_qr" to mapOf("tr" to "QR kodu tara", "en" to "Scan QR code"),
        "searching_devices" to mapOf("tr" to "Cihazlar aranıyor…", "en" to "Looking for devices…"),
        "section_my_devices" to mapOf("tr" to "CİHAZLARIM", "en" to "MY DEVICES"),
        "section_nearby" to mapOf("tr" to "YAKINDAKİLER", "en" to "NEARBY"),
        "send" to mapOf("tr" to "GÖNDER", "en" to "SEND"),
        "send_clipboard" to mapOf("tr" to "Panoyu gönder", "en" to "Send clipboard"),
        "sent_encrypted" to mapOf("tr" to "%s → %s gönderildi (şifreli)", "en" to "%s sent to %s (encrypted)"),
        "share_title" to mapOf("tr" to "OmaSend ile paylaş", "en" to "Share with OmaSend"),
        "show_my_qr" to mapOf("tr" to "QR kodumu göster", "en" to "Show my QR code"),
        "start_scan" to mapOf("tr" to "Başlat", "en" to "Start"),
        "system_share" to mapOf("tr" to "Sistem", "en" to "System"),
        "text_content" to mapOf("tr" to "Metin", "en" to "Text"),
        "toast_clipboard_empty" to mapOf("tr" to "Pano boş. Önce bir metin kopyala.", "en" to "Clipboard is empty. Copy some text first."),
        "unpair" to mapOf("tr" to "Eşleşmeyi kaldır", "en" to "Remove pairing"),
        "unpair_confirm" to mapOf("tr" to "'%s' ile eşleşme kaldırılsın mı? Tekrar aktarım için yeniden QR ile eşleştirmen gerekir.", "en" to "Remove the pairing with '%s'? You will need to pair again by QR to transfer."),
        "web_portal" to mapOf("tr" to "Web portalı (QR)", "en" to "Web portal (QR)"),
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
        "dock_send_file" to mapOf(
            "tr" to "Dosya Gönder", "en" to "Send File", "de" to "Datei senden", "fr" to "Envoyer fichier",
            "es" to "Enviar archivo", "it" to "Invia file", "pt" to "Enviar arquivo", "ru" to "Файл",
            "ar" to "إرسال ملف", "zh" to "发送文件", "ja" to "ファイル送信", "ko" to "파일 보내기",
            "nl" to "Bestand", "pl" to "Wyślij plik", "uk" to "Надіслати файл", "az" to "Fayl Göndər",
            "kk" to "Файл жіберу", "uz" to "Fayl yuborish", "fa" to "ارسال فایل", "hi" to "फ़ाइल भेजें", "id" to "Kirim File"
        ),
    )
}