# CloudStream Türkçe Eklentileri

CloudStream için Türkçe içerik sağlayıcılarından oluşan çok modüllü eklenti deposu.

## Kısakod ile kurulum

CloudStream → Ayarlar → Eklentiler → Depo ekle alanına şunu yapıştır:

```text
https://cutt.ly/woswelcome
```

## Yerel derleme

Android SDK yolu `local.properties` içinde tanımlandıktan sonra:

```powershell
.\gradlew.bat clean make makePluginsJson ensureJarCompatibility
```

Yerel indeks URL'lerini kendi GitHub deponuza göre üretmek için:

```powershell
.\gradlew.bat make makePluginsJson -Pcloudstream.repository=OWNER/REPOSITORY
```

Üretilen paketler her modülün `build` klasöründe, eklenti indeksi ise
`build/plugins.json` altında bulunur.

## Dağıtım

`CloudStream Derleyici` GitHub Actions iş akışı `master` dalındaki eklentileri
derler, paketleri doğrular ve `builds` dalına gönderir. İş akışı depo kimliğini
`GITHUB_REPOSITORY` ortam değişkeninden aldığı için fork üzerinde ek ayar istemez.

## Sağlık kontrolü

```powershell
python scripts\provider_health.py `
  --json build\reports\provider-health.json `
  --markdown build\reports\provider-health.md
```

Tarama HTTP erişimini, yanıt türünü, yönlendirmeleri, bot korumasını ve kaynak
kodda kullanılan temel CSS seçicilerini denetler. Bu kontrol tam oynatma testi
değildir; başarılı görünen sağlayıcıların akış çıkarımı ayrıca sınanmalıdır.

## Lisans

Bu proje [GNU General Public License v3.0](LICENSE) kapsamında dağıtılır.
