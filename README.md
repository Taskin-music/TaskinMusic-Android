<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" alt="Taşkın Music App Icon" width="180" />

# Taşkın Music (Android)

### Modern, Özelleştirilmiş ve Reklamsız YouTube Music İstemcisi
*Material 3 Tasarımı • Çevrimdışı İndirme • Aile Cihaz Tanıma • Dahili Ses Düzenleme Araçları*

<br/>

[![GitHub Release](https://img.shields.io/github/v/release/Taskin-music/TaskinMusic-Android?style=for-the-badge&color=2ea44f&labelColor=0d1117)](https://github.com/Taskin-music/TaskinMusic-Android/releases)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg?style=for-the-badge&labelColor=0d1117)](https://github.com/Taskin-music/TaskinMusic-Android/blob/main/LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg?style=for-the-badge&labelColor=0d1117&logo=android)](https://www.android.com/)

<br/>

[**İndir (Releases)**](https://github.com/Taskin-music/TaskinMusic-Android/releases) · [**Özellikler**](#özellikler) · [**Derleme**](#uygulamayı-derleme) · [**Lisans & Atıflar**](#lisans-ve-yasal-bildirim)

</div>

---

## 📌 Genel Bakış

**Taşkın Music**, açık kaynaklı [Metrolist](https://github.com/MetrolistGroup/Metrolist) projesi temel alınarak geliştirilmiş, aile üyelerine özel kişiselleştirmeler ve gelişmiş ses düzenleme araçları eklenmiş modern bir Android YouTube Music istemcisidir.

Uygulama; YouTube Music üzerindeki milyonlarca şarkıyı reklamsız dinlemenizi, yüksek kalitede cihazınıza indirmenizi, şarkı sözlerini senkronize takip etmenizi ve cihazınızdaki ses dosyalarını düzenleyebilmenizi sağlar.

---

## ✨ Özellikler

<table>
  <tr>
    <td width="50%" valign="top">

### 🎵 Oynatma & Akış
- **Reklamsız YouTube Music Deneyimi:** Kesintisiz ve reklamsız müzik akışı.
- **Arka Planda Çalma:** Ekran kapalıyken veya başka uygulamalardayken çalma.
- **Çevrimdışı İndirme:** Şarkıları ve albümleri yüksek kalitede önbelleğe alma ve indirme.
- **Sessizlik Atlama & Uyku Zamanlayıcısı:** Şarkı geçişlerinde sessiz kısımları atlama ve otomatik durdurma zamanlayıcısı.
- **Normalizasyon & Ekolayzer:** Ses seviyesi dengeleme, hız/perde ayarı ve ekolayzer.

</td>
    <td width="50%" valign="top">

### 🛠️ Taşkın Music Özel Ses Araçları
- **Şarkı Kırpma (Audio Trimmer):** Parçaların istenilen saniyeler arasını hassas kırpıp zil sesi veya özel kayıt yapabilme.
- **Format Dönüştürücü (Converter):** MP3, M4A, OPUS, WAV ve FLAC arasında doğrudan cihaz üzerinde format dönüştürme.
- **Şarkı Birleştirici (Audio Merger):** Birden fazla parçayı tek bir ses dosyasında birleştirme.
- **Yerel Medya Tarayıcısı:** Cihazdaki yerel ses dosyalarını tarama, albüm kapağı çıkarma ve etiket yönetimi.

</td>
  </tr>
  <tr>
    <td width="50%" valign="top">

### 📱 Aileye Özel Kişiselleştirme
- **Donanımsal Cihaz Tanıma:** Cihaz model koduna göre aile fertlerini (`Ata Berk`, `Nilay`, `Beren`, `Günkut`) otomatik tespit etme.
- **Özel Karşılama Ekranı (Splash):** Aile üyelerine dinamik animasyonlu kişisel karşılama mesajı.
- **Dahili Otomatik Güncelleyici (In-App Updater):** Yeni bir sürüm çıktığında telefon üzerinden doğrudan algılama ve tek dokunuşla güncelleme.

</td>
    <td width="50%" valign="top">

### 🎨 Tasarım & Şarkı Sözleri
- **Material 3 & Dinamik Renkler:** Albüm kapağına ve sistem temasına uyum sağlayan akıcı tasarım.
- **Canlı Senkronize Sözler:** Kelime kelime ve satır satır şarkı sözü takibi (LRCLIB, KuGou).
- **AI Destekli Çeviri:** Yabancı şarkı sözlerini anında Türkçe'ye çevirebilme.
- **Gelişmiş Kitaplık:** Çalma listeleri oluşturma, içe aktarma ve düzenleme.

</td>
  </tr>
</table>

---

## 🚀 Kurulum & Güncellemeler

### 1. Doğrudan İndirme (APK)
1. [Releases](https://github.com/Taskin-music/TaskinMusic-Android/releases) sayfasına gidin.
2. En güncel sürümdeki `universal-foss` APK dosyasını indirin ve telefonunuza yükleyin.

### 2. Uygulama İçi Otomatik Güncelleme
Uygulama, bu GitHub reposunun sürümlerini (`Releases`) belirli aralıklarla otomatik kontrol eder:
* Yeni bir güncelleme yayınlandığında uygulama içinde uyarı kartı görüntülenir.
* USB kablosuna veya manuel dosya aktarımına gerek kalmadan telefon üzerinden doğrudan güncellenebilir.

### 3. Obtainium ile Takip (Tavsiye Edilen)
Ailenin telefonlarına [Obtainium](https://github.com/ImranR98/Obtainium) kurup bu reponun linkini ekleyerek güncellemelerin bildirim olarak düşmesini ve tek tıkla yüklenmesini sağlayabilirsiniz.

---

## 💻 Uygulamayı Derleme

Projeyi Android Studio veya komut satırından derlemek için:

1. **Gereksinimler:**
   - Android Studio (Ladybug / Meerkat veya üzeri)
   - JDK 21+
   - Android SDK (API 35+)

2. **Repoyu Klonlayın:**
   ```bash
   git clone https://github.com/Taskin-music/TaskinMusic-Android.git
   cd TaskinMusic-Android
   ```

3. **APK Derleme (FOSS Debug):**
   ```bash
   ./gradlew :app:assembleFossDebug
   ```
   Derlenen APK konumu:
   `app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk`

---

## ⚖️ Lisans ve Yasal Bildirim

### Açık Kaynak Lisansı (GPL-3.0)
Bu proje **GNU General Public License v3.0 (GPL-3.0)** altında lisanslanmıştır.  
Metin ve koşullar için [LICENSE](LICENSE) dosyasına göz atabilirsiniz.

### Temel Alınan Projeler ve Teşekkürler
**Taşkın Music**, topluluğun değerli açık kaynak projeleri üzerine inşa edilmiştir:
- **[Metrolist](https://github.com/MetrolistGroup/Metrolist):** Mo Agamy ve katkıda bulunanlar tarafından geliştirilen temel YouTube Music istemcisi.
- **[InnerTune](https://github.com/z-huang/InnerTune):** Zion Huang & Malopieds.
- **[OuterTune](https://github.com/DD3Boh/OuterTune):** Davide Garberi & Michael Zh.
- **[Better Lyrics](https://better-lyrics.boidu.dev):** Senkronize şarkı sözü desteği.

### Yasal Uyarı (Disclaimer)
Bu uygulama **Google LLC, YouTube veya Metrolist Group ile resmi olarak bağlantılı değildir, onlar tarafından desteklenmemekte veya onaylanmamaktadır**. Projede adı geçen tüm ticari markalar ve fikri mülkiyet hakları ilgili sahiplerine aittir.

---

<div align="center">
Taşkın ailesi için sevgiyle özelleştirilmiştir ❤️
</div>
