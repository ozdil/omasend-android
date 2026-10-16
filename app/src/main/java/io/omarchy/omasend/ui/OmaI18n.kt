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
        // ---- general
        "accept" to mapOf("tr" to "Kabul et", "en" to "Accept"),
        "cancel" to mapOf("tr" to "İptal", "en" to "Cancel"),
        "close" to mapOf("tr" to "Kapat", "en" to "Close"),
        "copied" to mapOf("tr" to "Kopyalandı", "en" to "Copied"),
        "copy" to mapOf("tr" to "Kopyala", "en" to "Copy"),
        "decline" to mapOf("tr" to "Reddet", "en" to "Decline"),
        "save" to mapOf("tr" to "Kaydet", "en" to "Save"),
        "back" to mapOf("tr" to "Geri", "en" to "Back"),
        "share" to mapOf("tr" to "Paylaş", "en" to "Share"),

        // ---- home
        "home_visible" to mapOf("tr" to "Görünür · %s", "en" to "Visible · %s"),
        "home_hidden" to mapOf("tr" to "Gizli · %s", "en" to "Hidden · %s"),
        "section_my_devices" to mapOf("tr" to "Cihazlarım", "en" to "My devices"),
        "section_nearby" to mapOf("tr" to "Yakındakiler", "en" to "Nearby"),
        "section_recent" to mapOf("tr" to "Son alınanlar", "en" to "Recently received"),
        "send" to mapOf("tr" to "Gönder", "en" to "Send"),
        "settings" to mapOf("tr" to "Ayarlar", "en" to "Settings"),
        "add_device" to mapOf("tr" to "Cihaz ekle", "en" to "Add device"),
        "add_device_empty_title" to mapOf("tr" to "İlk cihazını ekle", "en" to "Add your first device"),
        "add_device_empty_body" to mapOf("tr" to "Bilgisayarındaki OmaSend panelinde QR simgesine tıkla ve buradan tara. Eşleşen cihazlar arasında her şey uçtan uca şifrelenir.", "en" to "Click the QR icon in the OmaSend panel on your computer and scan it here. Everything between paired devices is end-to-end encrypted."),
        "nearby_empty" to mapOf("tr" to "Aynı ağda başka OmaSend görünmüyor.", "en" to "No other OmaSend on this network."),
        "nearby_hidden" to mapOf("tr" to "Görünürlük kapalı. Yakındaki cihazlar aranmıyor.", "en" to "Visibility is off. Not looking for nearby devices."),
        "turn_on" to mapOf("tr" to "Aç", "en" to "Turn on"),
        "status_paired_online" to mapOf("tr" to "Çevrimiçi · şifreli", "en" to "Online · encrypted"),
        "status_paired_offline" to mapOf("tr" to "Görünmüyor", "en" to "Not seen"),
        "status_nearby" to mapOf("tr" to "Şifresiz · onay ister", "en" to "Unencrypted · needs approval"),
        "status_same_omaid" to mapOf("tr" to "OmaID'n · eşleştirilmedi", "en" to "Your OmaID · not paired"),
        "badge_own" to mapOf("tr" to "Senin", "en" to "Yours"),

        // ---- device sheet
        "send_files" to mapOf("tr" to "Dosya gönder", "en" to "Send files"),
        "send_media" to mapOf("tr" to "Fotoğraf veya video gönder", "en" to "Send photos or videos"),
        "send_clipboard" to mapOf("tr" to "Panoyu gönder", "en" to "Send clipboard"),
        "pair_for_encryption" to mapOf("tr" to "Şifreli bağlantı için eşleştir", "en" to "Pair for an encrypted connection"),
        "device_id" to mapOf("tr" to "Cihaz kimliği", "en" to "Device ID"),
        "sheet_paired_note" to mapOf("tr" to "Uçtan uca şifreli. Kendi cihazlarından gelenler otomatik kabul edilir.", "en" to "End-to-end encrypted. Transfers from your own devices are accepted automatically."),
        "sheet_nearby_note" to mapOf("tr" to "Bu cihazla aktarım şifresizdir ve karşı tarafın onayını bekler.", "en" to "Transfers with this device are not encrypted and need the receiver's approval."),
        "unpair" to mapOf("tr" to "Eşleşmeyi kaldır", "en" to "Remove pairing"),
        "unpair_confirm" to mapOf("tr" to "'%s' ile eşleşme kaldırılsın mı? Tekrar aktarım için yeniden QR ile eşleştirmen gerekir.", "en" to "Remove the pairing with '%s'? You will need to pair again by QR to transfer."),

        // ---- add device / pairing
        "scan_qr" to mapOf("tr" to "QR kodu tara", "en" to "Scan QR code"),
        "show_my_qr" to mapOf("tr" to "QR kodumu göster", "en" to "Show my QR code"),
        "connect_by_ip" to mapOf("tr" to "IP adresiyle bağlan", "en" to "Connect by IP address"),
        "pair_intro" to mapOf("tr" to "Bilgisayarda: OmaSend panelindeki QR simgesine tıkla, sonra burada tara. İki telefon arasında: birinde QR kodunu göster, diğeriyle tara.", "en" to "On a computer: click the QR icon in the OmaSend panel, then scan it here. Between two phones: show the QR code on one and scan it with the other."),
        "qr_validity" to mapOf("tr" to "5 dakika geçerli, tek kullanımlık.", "en" to "Valid for 5 minutes, single use."),
        "own_device_toggle" to mapOf("tr" to "Bu cihaz benim", "en" to "This is my device"),
        "own_device_on" to mapOf("tr" to "Aktarımlar otomatik kabul edilir, pano eşitlenir.", "en" to "Transfers are accepted automatically and the clipboard syncs."),
        "own_device_off" to mapOf("tr" to "Başkasının cihazı: her aktarım onay ister.", "en" to "Someone else's device: every transfer needs approval."),
        "pair_failed" to mapOf("tr" to "Eşleşme başarısız: %s", "en" to "Pairing failed: %s"),
        "paired_with" to mapOf("tr" to "Eşleşti: %s", "en" to "Paired with %s"),
        "legacy_omaid_added" to mapOf("tr" to "Eski sürüm OmaID kodu eklendi. Şifreli aktarım için cihazı yeni QR koduyla eşleştir.", "en" to "Legacy OmaID code added. Pair with the new QR code for encrypted transfers."),
        "ip_address" to mapOf("tr" to "IP adresi (ör. 192.168.1.50)", "en" to "IP address (e.g. 192.168.1.50)"),
        "port" to mapOf("tr" to "Port", "en" to "Port"),
        "connect" to mapOf("tr" to "Bağlan", "en" to "Connect"),

        // ---- choose target
        "choose_target" to mapOf("tr" to "Nereye gönderilsin?", "en" to "Send to which device?"),
        "no_targets" to mapOf("tr" to "Henüz cihaz yok. Önce bir cihaz ekle ya da aynı ağdaki OmaSend'in açık olduğundan emin ol.", "en" to "No devices yet. Add a device first, or make sure OmaSend is running on the same network."),

        // ---- transfers
        "incoming" to mapOf("tr" to "Gelen aktarım", "en" to "Incoming transfer"),
        "incoming_title" to mapOf("tr" to "Dosya isteği", "en" to "File request"),
        "incoming_request" to mapOf("tr" to "'%s' sana dosya göndermek istiyor.", "en" to "'%s' wants to send you files."),
        "incoming_encrypted" to mapOf("tr" to "Uçtan uca şifreli · eşleşmiş cihaz", "en" to "End-to-end encrypted · paired device"),
        "incoming_unencrypted" to mapOf("tr" to "Şifresiz · eşleştirilmemiş cihaz", "en" to "Not encrypted · unpaired device"),
        "transfer_accepted" to mapOf("tr" to "Kabul edildi, alınıyor…", "en" to "Accepted, receiving…"),
        "transfer_declined" to mapOf("tr" to "Aktarım reddedildi", "en" to "Transfer declined"),
        "file_received" to mapOf("tr" to "'%s' alındı (%s) · İndirilenler/OmaSend", "en" to "Received '%s' (%s) · Downloads/OmaSend"),
        "clipboard_received" to mapOf("tr" to "%s panosunu gönderdi", "en" to "Clipboard received from %s"),
        "connecting_to" to mapOf("tr" to "%s cihazına bağlanılıyor…", "en" to "Connecting to %s…"),
        "waiting_for" to mapOf("tr" to "%s onayı bekleniyor…", "en" to "Waiting for %s to accept…"),
        "sending" to mapOf("tr" to "Gönderiliyor", "en" to "Sending"),
        "receiving" to mapOf("tr" to "Alınıyor", "en" to "Receiving"),
        "cancelled" to mapOf("tr" to "Aktarım iptal edildi.", "en" to "Transfer cancelled."),
        "n_files" to mapOf("tr" to "%d dosya", "en" to "%d files"),
        "n_files_sent" to mapOf("tr" to "%d dosya gönderildi", "en" to "%d files sent"),
        "file_sent" to mapOf("tr" to "'%s' gönderildi", "en" to "'%s' sent"),
        "sent_encrypted" to mapOf("tr" to "%s → %s gönderildi (şifreli)", "en" to "%s sent to %s (encrypted)"),
        "clipboard_sent" to mapOf("tr" to "Pano '%s' cihazına gönderildi", "en" to "Clipboard sent to '%s'"),
        "clipboard_failed" to mapOf("tr" to "Pano gönderilemedi: %s", "en" to "Clipboard not sent: %s"),
        "toast_clipboard_empty" to mapOf("tr" to "Pano boş. Önce bir metin kopyala.", "en" to "Clipboard is empty. Copy some text first."),
        "err_transfer_failed" to mapOf("tr" to "Aktarım başarısız.", "en" to "Transfer failed."),
        "err_request_failed" to mapOf("tr" to "Aktarım isteği gönderilemedi.", "en" to "Could not send the transfer request."),
        "err_rejected" to mapOf("tr" to "Aktarım reddedildi.", "en" to "Transfer was declined."),
        "err_upload_failed" to mapOf("tr" to "Yükleme başarısız.", "en" to "Upload failed."),
        "err_open_stream" to mapOf("tr" to "'%s' okunamadı.", "en" to "Could not read '%s'."),
        "err_open_file" to mapOf("tr" to "'%s' açılamadı.", "en" to "Could not open '%s'."),
        "err_share_file" to mapOf("tr" to "'%s' paylaşılamadı.", "en" to "Could not share '%s'."),
        "err_declined" to mapOf("tr" to "'%s' aktarımı reddetti.", "en" to "'%s' declined the transfer."),
        "err_not_paired_remote" to mapOf("tr" to "'%s' bu cihazı tanımıyor. Eşleşmeyi kaldırıp yeniden QR ile eşleştir.", "en" to "'%s' does not recognize this device. Remove the pairing and pair again by QR."),
        "err_unreachable_encrypted" to mapOf("tr" to "'%s' şifreli bağlantıyla erişilemiyor. OmaSend'in orada açık olduğundan ve güvenlik duvarının UDP %d'e izin verdiğinden emin ol.", "en" to "'%s' is not reachable over the encrypted connection. Make sure OmaSend is running there and the firewall allows UDP %d."),
        "err_core_not_running" to mapOf("tr" to "Şifreli aktarım motoru başlatılamadı. Uygulamayı yeniden başlatmayı dene.", "en" to "The encrypted transport could not start. Try restarting the app."),

        // ---- settings: OmaID
        "omaid" to mapOf("tr" to "OmaID", "en" to "OmaID"),
        "omaid_body" to mapOf("tr" to "Hesap numaran. E-posta yok, şifre yok, hiçbir yere gönderilmez. Yeni bir cihazını eklemek için bu numarayı o cihazda \"OmaID ile bağla\"ya gir. İki cihaz da aynı ağda ve OmaSend açık olmalı.", "en" to "Your account number. No email, no password, never sent anywhere. To add another device of yours, enter this number there under \"Link with OmaID\". Both devices need OmaSend open on the same network."),
        "omaid_link" to mapOf("tr" to "OmaID ile bağla", "en" to "Link with OmaID"),
        "omaid_link_body" to mapOf("tr" to "Diğer cihazındaki 16 haneli OmaID'yi gir. Aynı numarayı kullanan yakındaki cihazlar senin cihazın olarak eklenir.", "en" to "Enter the 16-digit OmaID from your other device. Nearby devices that use this number are added as your devices."),
        "omaid_link_go" to mapOf("tr" to "Bağla", "en" to "Link"),
        "omaid_linking" to mapOf("tr" to "Yakındaki cihazlar aranıyor…", "en" to "Looking for nearby devices…"),
        "omaid_link_none" to mapOf("tr" to "Bu numarayı kullanan yakın bir cihaz bulunamadı. Diğer cihazda OmaSend açık ve görünür olsun, aynı ağda olun.", "en" to "No nearby device uses this number. Keep OmaSend open and visible on the other device, on the same network."),
        "omaid_linked" to mapOf("tr" to "Bağlandı: %s", "en" to "Linked: %s"),
        "omaid_new_confirm" to mapOf("tr" to "Bu cihaz yeni bir hesap numarası alır. Zaten bağlı cihazlar bağlı kalır; yeni cihazları yeni numarayla bağlarsın.", "en" to "This device gets a new account number. Devices already linked stay linked; new devices link with the new number."),
        "try_again" to mapOf("tr" to "Tekrar dene", "en" to "Try again"),
        "omaid_show" to mapOf("tr" to "Numarayı göster", "en" to "Show number"),
        "omaid_hide" to mapOf("tr" to "Numarayı gizle", "en" to "Hide number"),
        "omaid_invalid" to mapOf("tr" to "Geçerli bir OmaID değil.", "en" to "Not a valid OmaID."),
        "omaid_new" to mapOf("tr" to "Yeni numara oluştur", "en" to "Create a new number"),

        // ---- settings: rest
        "this_device" to mapOf("tr" to "Bu cihaz", "en" to "This device"),
        "device_name" to mapOf("tr" to "Cihaz adı", "en" to "Device name"),
        "device_name_body" to mapOf("tr" to "Yakındaki cihazlarda bu ad görünür.", "en" to "Nearby devices see this name."),
        "preferences" to mapOf("tr" to "Tercihler", "en" to "Preferences"),
        "visibility" to mapOf("tr" to "Görünürlük", "en" to "Visibility"),
        "visibility_body" to mapOf("tr" to "Yakındaki cihazları ara ve onlara görün.", "en" to "Look for nearby devices and be visible to them."),
        "sounds" to mapOf("tr" to "Ses efektleri", "en" to "Sound effects"),
        "sounds_body" to mapOf("tr" to "Gönderirken ve alırken çal.", "en" to "Play when sending and receiving."),
        "web_portal" to mapOf("tr" to "Web portalı", "en" to "Web portal"),
        "web_portal_body" to mapOf("tr" to "Aynı ağdaki bir tarayıcı bu kodu okutarak bağlanabilir. Bağlantı şifresizdir.", "en" to "A browser on the same network can scan this code to connect. The connection is not encrypted."),
        "about" to mapOf("tr" to "Hakkında", "en" to "About"),
        "about_body" to mapOf("tr" to "Omarchy Linux ve Android arasında yerel ağda dosya ve pano aktarımı. QR ile eşleştirdiğin cihazlar arasında her şey uçtan uca şifrelenir (QUIC · TLS 1.3). Eşleştirilmemiş cihazlarla aktarım şifresizdir ve karşı tarafın onayını bekler.", "en" to "File and clipboard transfer between Omarchy Linux and Android on your local network. Everything between devices you paired by QR is end-to-end encrypted (QUIC · TLS 1.3). Transfers with unpaired devices are not encrypted and need the receiver's approval."),
        "version" to mapOf("tr" to "Sürüm", "en" to "Version"),
        "source_code" to mapOf("tr" to "Kaynak kodu", "en" to "Source code"),
        "support" to mapOf("tr" to "Destek ol", "en" to "Support the project"),
        "license" to mapOf("tr" to "MIT lisansı · ücretsiz ve açık kaynak", "en" to "MIT license · free and open source"),

        // ---- share sheet
        "share_title" to mapOf("tr" to "OmaSend ile gönder", "en" to "Send with OmaSend"),
        "system_share" to mapOf("tr" to "Başka uygulamayla", "en" to "Other apps"),
        "text_content" to mapOf("tr" to "Metin", "en" to "Text"),
        "searching_devices" to mapOf("tr" to "Cihazlar aranıyor…", "en" to "Looking for devices…"),
    )
}
