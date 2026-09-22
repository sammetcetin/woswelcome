# CloudStream eklenti deposu — yapay zekâ devir notları

> Son güncelleme: 22 Eylül 2026, Europe/Istanbul (2. tur: tam derleme + sağlık + WebteIzle + FilmMakinesi tamamlandı)
> Çalışma dizini: `C:\apps\kekik devam\cloudstream-extensions`  
> Dal: `master`  
> Başlangıç commit'i: `3105eb2`  
> Bu çalışma ağacında commit, push veya staging yapılmadı.

## 0. İkinci turda yapılanlar (22 Eylül 2026, ~19:30 Istanbul)

Önceki devir notlarındaki "önerilen kesin devam sırası" aynen uygulandı:

1. **DiziKorea sonrası tam temiz derleme + dağıtım doğrulaması — TAMAMLANDI.**
   `clean make makePluginsJson ensureJarCompatibility` → `BUILD SUCCESSFUL, 542 task`;
   `validate_distribution.py` → `30 indeks kaydı, 30 paket`.
2. **Sağlık taraması — çalıştırıldı.** Sonuç `23 healthy / 7 blocked / 0 down` ile tabanla birebir uyumluydu.
3. **DNS sinkhole ön kontrolü (`scripts/provider_health.py`) — UYGULANDI.**
   `probe()` artık HTTP isteğinden önce `dns_block_addresses()` çalıştırıyor.
   Sonuç değişmiyor, yalnız yavaş timeout'lar kısalıyor:
   HQPorner 36009 ms → 1 ms, xHamster 20046 ms → 1 ms, FullPorner 1141 ms → 17 ms.
   `except` bloğundaki eski kontrol fallback olarak duruyor.
4. **WebteIzle interceptor kapsamı — GENİŞLETİLDİ, derlendi.**
   `getMainPage`, `load`, `loadLinks` içindeki `app.get` çağrılarına ve
   `iframe` çözümündeki `app.get` çağrısına `interceptor = interceptor` eklendi.
   `dataAlternatif3.asp` ve `dataEmbed.asp` POST isteklerine `interceptor`
   + `referer = data` eklendi. `:WebteIzle:compileDebugKotlin` → `BUILD SUCCESSFUL`
   (`app.post` `interceptor` parametresini destekliyor).
   Runtime Cloudflare challenge çözümü Android cihazda doğrulanmalıdır;
   sağlık tarayıcısının çıplak HTTP düzeyinde `blocked` göstermesi normaldir.
5. **FilmMakinesi — YENİDEN ETKİNLEŞTİRİLDİ (sürüm 8).**
   `status = 1` yapıldı, `settings.gradle.kts` kapalı listesinden çıkarıldı.
   Etkin modül 30 → 31, dağıtım `31 indeks / 31 paket` doğrulandı,
   sağlık raporu yenilendi: **31 sağlayıcı; 23 healthy, 8 blocked, 0 down**.
   FilmMakinesi sağlık sonucu: `healthy` (HTTP 200, 4/15 seçici).
   Ayrıntı aşağıda "17. FilmMakinesi yeniden etkinleştirme" bölümündedir.
6. **Watch2Movies 451 gözlemi:** ikinci sağlık taramasında `blocked / HTTP 451`
   verdi (3/3 tutarlı). Nedeni Cloudflare'in **Birleşik Krallık** bölgesine
   uyguladığı hukuki erişim engelidir; ölçüm ortamının çıkışı o sırada
   Londra (CF-RAY `...-LHR`) görünüyordu. Site down değil; kod değişikliği
   yapılmadı. Ölçüm konumu/çıkışı değişince sonucun normale dönmesi beklenir.

## 17. FilmMakinesi yeniden etkinleştirme (sürüm 8)

Önceki nottaki "HTTP 403 managed challenge" durumu değişmişti:
`https://filmmakinesi.to/` çıplak HTTP istemcisine **HTTP 200 + 76 KB gerçek
film sitesi** döndürüyor. Ancak site komple yeniden tasarlanmış; eski
seçicilerin hiçbiri (`section#film_posts`, `article`, `div.tooltip`, `h6 a`,
`div#film_izle`, `div.player-div`, `?s=`, `/page/`) artık yok.

Doğrulanan yeni yapı ve koda işlenen karşılıkları:

- Liste kartı: `div.film-list a.item` (`data-title`, `img.thumbnail[src]`,
  `div.item-footer div.title`). Ana sayfada slider/tv-section kartları da
  `a.item` taşıdığı için seçici bilerek `div.film-list` ile sınırlandı
  (temiz 24 kart; sınırsız seçici 42 karışık kart veriyordu).
- Ana liste: `/filmler-1/` (sayfa 1), `/filmler-1/sayfa/N/` (sayfa N).
- Tür sayfaları: `/tur/<tur>-fm<k>/film/` kalıbı (ör. `/tur/aksiyon-fmy54y/film/`,
  sayfalama `/tur/.../film/sayfa/N/`). 20 tür `mainPage` listesine eklendi.
- Arama: eski `?s=` 404 veriyor; yenisi `GET /arama/?s=<url-encoded>` (24 sonuç doğrulandı).
- Detay: başlık `h1.title` (`" izle"` sonrası kesilir), yıl
  `h1.title span.date a`, poster `div.cover img.cover-img`, açıklama
  `div.info-description p`, tür `div.type a[href*='/tur/']`, süre `div.time`,
  puan `div.stars[data-star]`, oyuncular `a.cast` (`div.cast-name` +
  `img.cast-img`), fragman `iframe[data-src*='youtube.com/embed']`
  (`src` değil `data-src`; `src` fallback olarak okunur).
- Player: `div.video-parts a[data-video_url]` butonları
  ("Altyazılı Close" → `closeload.filmmakinesi.to/video/embed/...`,
  "Altyazılı Rapid" → `rapid.filmmakinesi.to/embed-...`).
  YouTube fragman butonu `loadLinks` içinde atlanır.
- CloseLoad çözümü: sayfa `var m2j = uc90("...".split("#"))` + jwplayer
  `sources: [{file: m2j}]` kullanıyor. `uc90` (dizi-splice, Caesar, base64,
  permütasyon, XOR) Python'da prototiplenip doğrulandı, sonra
  `CloseLoadExtractor.kt` içine Kotlin portu olarak yazıldı
  (`uc90Decode`; `atob` adımları Latin-1 üzerinden taşınır).
  Altyazılar sayfadaki `tracks: [{...}]` JSON'undan alınır (Türkçe dahil).
- Canlı uçtan uca doğrulama (The Fin 2025):
  `m2j` → `https://srv9.cdnimages2722.shop/hls/.../txt/master.txt` →
  HTTP 200, `application/vnd.apple.mpegurl`, geçerli `#EXTM3U` playlist.
- `CloseLoad.mainUrl` `closeload.filmmakinesi.de` → `.to` güncellendi.
- Rapid (`rapid.filmmakinesi.to`) `loadExtractor` mekanizmasına bırakıldı;
  kayıtlı extractor tanımazsa atlanır, CloseLoad birincil kaynaktır.
  İstenirse ayrı Rapid extractor sonradan eklenebilir.

Kalan küçük iş (opsiyonel): önerilen-benzer filmler bölümü `load` yanıtına
eklenmedi; istenirse detay sayfasındaki ilgili kart alanından alınabilir.

## 1. Kullanıcının hedefi

Çalışma sırası ve kapsamı şöyledir:

1. Eski ve kişisel ad/marka izlerini kaynak koddan, paket adlarından, klasörlerden, yazar alanlarından ve dağıtım metadatasından temizlemek.
2. Derleme altyapısını güncel ve tekrar üretilebilir hale getirmek.
3. `.cs3` üretimini ve `plugins.json` dağıtımını düzeltmek.
4. Sağlayıcıları HTTP, HTML seçicileri ve mümkün olduğunda gerçek oynatıcı/akış düzeyinde tek tek sınamak.
5. Önce DNS/NS, 404, 5xx veya terk edilmiş alan adı sorunu olan sağlayıcıları; sonra seçicisi ve oynatıcısı bozulanları onarmak.

Kullanıcı burada durulmasını ve başka bir yapay zekânın devam edebilmesi için tüm durumun bu dosyaya aktarılmasını istedi. Bu nedenle aşağıdaki “kalan işler” yapılmadı.

## 2. Çok önemli çalışma ağacı uyarısı

Namespace taşıması nedeniyle `git status`, eski Kotlin yollarını silinmiş (`D`) ve yeni `com/cloudstream/extensions` yollarını izlenmeyen (`??`) olarak gösteriyor. Bunlar ayrı, ilgisiz kullanıcı değişiklikleri değil; aynı taşımanın iki yüzüdür.

- `git reset --hard`, `git checkout --`, `git clean` veya toplu silme çalıştırma.
- Yeni namespace altındaki izlenmeyen dosyaları kesinlikle temizleme.
- Değişiklikleri incelemek için `git diff --no-index` gerekebilir; normal `git diff` izlenmeyen yeni dosyaların içeriğini göstermez.
- Taşımanın rename olarak görünmesi ancak ileride `git add -A` sonrasında mümkün olacaktır. Kullanıcı henüz commit istemedi.
- Çalışma ağacı büyük ve beklenen biçimde kirli. İlgisiz değişiklik varsayarak geri alma yapma.

## 3. Yerel ortam

- Windows 11 / PowerShell
- JDK: Temurin 17.0.19
- Gradle wrapper: 8.13
- Android SDK yolu `local.properties` içinde ayarlı; değeri belgeye yazılmadı.
- Toplam Gradle modülü: 42
- Etkin modül: 30
- Dağıtımdan kapalı modül: 12
- Kotlin kaynak dosyası: yaklaşık 156

Kontrol komutları:

```powershell
java -version
.\gradlew.bat --version
git status --short
git diff --check
```

## 4. Tamamlanan genel temizlik ve tarafsızlaştırma

### Namespace ve metadata

- Bütün modüllerde Kotlin paketi `com.cloudstream.extensions` oldu.
- Kaynak klasörleri `src/main/kotlin/com/cloudstream/extensions` altına taşındı.
- Android namespace kökte `com.cloudstream.extensions` olarak ayarlandı.
- CloudStream `authors` alanı boş liste oldu.
- Sabit kullanıcı/depo bağı kaldırıldı. Yerel varsayılan `OWNER/REPOSITORY`; CI sırasında `GITHUB_REPOSITORY`, yerelde istenirse `-Pcloudstream.repository=owner/repo` kullanılıyor.
- `repo.json` tarafsız ad/açıklama ve `OWNER/REPOSITORY` yer tutucusu kullanıyor.
- Depo README'si tarafsız bir derleme/dağıtım/sağlık belgesine dönüştürüldü.
- Kişisel bağış dosyası kaldırıldı: `.github/FUNDING.yml`.
- Eski, silinmiş bir denetim betiğine ve kişisel alanlara bağlı workflow kaldırıldı: `.github/workflows/Kontrol.yml`.
- Eski `_config.yml`, `MiBox.md`, `KONTROL.py` ve modüllerdeki `bakalim.py` yardımcıları kaldırıldı.
- Kök ve kaynaklarda yapılan son marka/ad taramasında `.git`, `.gradle` ve `build` hariç eşleşme kalmamıştı. Bu devir dosyası geliştirici notudur; nihai taramada belge de ayrıca değerlendirilmelidir.

### Gradle/Android altyapısı

Kök `build.gradle.kts` şu hale getirildi:

- Android Gradle Plugin: `8.13.2`
- Kotlin Gradle Plugin: `2.3.0`
- Gradle wrapper: `8.13`
- `compileSdk` ve `targetSdk`: 35
- `minSdk`: 21
- Java/Kotlin bytecode hedefi: JVM 11
- Jackson Kotlin ve databind: Android/CloudStream uyumluluğu için `2.13.1` sürümüne sabitlendi.
- CloudStream Gradle eklentisi JitPack snapshot üzerinden kullanılıyor.
- Kök `clean` görevi yalnız etkin projeleri değil, `build.gradle.kts` içeren tüm modüllerin `build` klasörlerini siliyor. Bu değişiklik, daha önce kapatılmış modüllerin eski `.cs3` dosyalarının yeni dağıtıma karışmasını önlüyor.

Önemli dosyalar:

- `build.gradle.kts`
- `gradle/wrapper/gradle-wrapper.properties`
- `settings.gradle.kts`
- `repo.json`
- `README.md`

## 5. `.cs3` dağıtım altyapısı

### Derleme workflow'u

`.github/workflows/Derleyici.yml` modernize edildi:

- Kaynak dalı `src`, dağıtım dalı `builds` klasörüne ayrı checkout edilir.
- JDK 17 ve Android SDK kurulur.
- `make`, `makePluginsJson`, `ensureJarCompatibility` çalışır.
- Eski `.cs3`, `.jar`, `plugins.json`, `repo.json` çıktıları dağıtım dalından temizlenir.
- Yalnız güncel modül `.cs3` dosyaları kopyalanır.
- `repo.json`, çalışan fork'un `GITHUB_REPOSITORY` değeriyle yeniden yazılır.
- Dağıtım doğrulanmadan `builds` dalına push yapılmaz.
- Değişiklik yoksa boş commit üretilmez.

### Yeni yardımcı betikler

`scripts/configure_repository.py`

- `repo.json` içindeki `pluginLists` URL'sini çalışan `owner/repo` değerine bağlar.
- Depo kimliğini regex ile doğrular.

`scripts/validate_distribution.py`

- `plugins.json` dolu ve liste mi kontrol eder.
- Zorunlu indeks alanlarını kontrol eder.
- Tekrarlanan `internalName` ve paket URL'sini reddeder.
- İndeks URL'sindeki dosya adının `<internalName>.cs3` olmasını şart koşar.
- Boyut ve SHA-256 hash doğrular.
- `.cs3` ZIP bütünlüğünü sınar.
- Paket içinde `manifest.json` ve `classes.dex` arar.
- Manifest adı/sürümü ile indeks adı/sürümünü karşılaştırır.
- İndekste olmayan fazladan `.cs3` paketini hata sayar.

Son başarılı dağıtım doğrulaması:

```text
Dağıtım doğrulandı: 30 indeks kaydı, 30 paket
```

Bu doğrulama DiziKorea'nın son onarımından önceki tam paket setinde yapıldı. DiziKorea sonrasında yeniden tam derleme ve yeniden staging gereklidir.

## 6. Sağlık tarama altyapısı

Yeni betik: `scripts/provider_health.py`

Özellikleri:

- `settings.gradle.kts` içindeki kapalı modülleri atlayarak etkin sağlayıcıları keşfeder.
- Kotlin kaynaklarından `mainUrl`, ilk ana sayfa URL'si, `healthProbeUrl`, temel header map'i ve `.select(...)` CSS seçicilerini çıkarır.
- Eşzamanlı HTTP taraması yapar.
- HTML, JSON, M3U, boş/bozuk yanıt ve Cloudflare/bot challenge sinyallerini ayırır.
- Yönlendirmeyi ve origin değişimini raporlar.
- HTML etiket/sınıf/id envanterinden hafif seçici eşleştirmesi yapar.
- Python TLS istemcisi reddedilirse `curl` fallback kullanır. Bu, SineWix API'sinde gereklidir.
- Türkiye DNS engel/sinkhole adreslerini ayrı `dns-block` olarak tanır:
  - `195.175.254.2`
  - `2a01:358:4014:a00::3`
  - loopback (`127.0.0.0/8`, `::1`)
- Normal sayfa içinde CAPTCHA bileşeni bulunmasını challenge sanan yanlış pozitif düzeltildi: genel `captcha` işareti challenge listesinden çıkarıldı.
- HTTP `5xx`, `404`, `410` sınıflandırması challenge sınıflandırmasından önce çalışacak hale getirildi.
- JSON ve Markdown rapor üretir.

CI workflow'u: `.github/workflows/Saglik.yml`

- Her pazartesi zamanlanmış çalışır ve elle tetiklenebilir.
- Python 3.12 kullanır.
- Markdown raporu job summary'ye, iki raporu 14 günlük artifact'e koyar.

Yerel komut:

```powershell
python -m py_compile scripts\provider_health.py
python scripts\provider_health.py `
  --repo . `
  --timeout 18 `
  --workers 8 `
  --json build\reports\provider-health.json `
  --markdown build\reports\provider-health.md
```

Son rapor dosyaları:

- `build/reports/provider-health.md`
- `build/reports/provider-health.json`

Son sonuç: **30 sağlayıcı; 23 healthy, 7 blocked, 0 down**.

## 7. Etkin modüllerin son sağlık durumu

### Sağlıklı (23)

`AnimeciX`, `BelgeselX`, `CanliTV`, `CizgiMax`, `DiziBox`, `DiziKorea`, `DiziMom`, `DiziYou`, `Dizilla`, `FilmModu`, `FullHDFilmizlesene`, `HDFilmCehennemi`, `IzleAI`, `JetFilmizle`, `KultFilmler`, `RareFilmm`, `SetFilmIzle`, `SezonlukDizi`, `SineWix`, `SinemaCX`, `TurkAnime`, `Watch2Movies`, `YouTube`.

### Erişim engelli (7)

| Modül | Sonuç | Yorum |
|---|---|---|
| FullPorner | DNS sinkhole | Alan adı güncel; yerel DNS sansürü, 404/NS arızası değil. |
| HQPorner | DNS sinkhole | Alan adı güncel; yerel DNS sansürü. |
| PornHub | DNS sinkhole | Alan adı güncel; yerel DNS sansürü. |
| SpankBang | DNS sinkhole | Alan adı güncel ve 2026 sertifikası/WHOIS kaydı var; yerel DNS sansürü. |
| UncutMaza | DNS sinkhole | `.cc` güncel olarak görünmektedir; yerel DNS sansürü. |
| xHamster | `::1` | Yerel DNS/hosts engeli. |
| WebteIzle | HTTP 403 challenge | Güncel alan adı Cloudflare challenge döndürüyor. |

Bu altı DNS-engelli yetişkin sağlayıcı için rastgele TLD değiştirilmemelidir. Arama ve 2026 tarihli DNS/WHOIS kaynakları mevcut alan adlarının güncel olduğunu destekliyor. Uygulama tarafında erişim; kullanıcının DNS/VPN ortamına bağlıdır.

### Tarayıcı performans notu

Bazı sinkhole alanlarında `urllib` zaman aşımından sonra DNS sınıflandırması yapıldığı için tarama 18–36 saniye uzayabiliyor. Sonraki yapay zekâ güvenli bir iyileştirme olarak HTTP isteğinden **önce** `dns_block_addresses()` çalıştırabilir. Bu yalnız performans değişikliği olmalı; public DoH ile engeli otomatik aşmak sağlık sonucunu yanlış biçimde “healthy” yapabilir.

## 8. Tek tek tamamlanan sağlayıcı onarımları

### FullHDFilmizlesene — sürüm 6

- Alan adı `https://www.fullhdfilmizlesene.now` olarak doğrulandı.
- Güncel kategori, sayfalama, kart ve detay seçicileri uyarlandı.
- RapidVid alan adı `https://rapidvid.org` oldu.
- RapidVid'in `_p8` gömülü verisi çözülerek güncel M3U8 ve altyazı kaynakları çıkarılıyor.
- Modül derlendi ve tam derlemeye dahil edildi.

### BelgeselX — sürüm 5

- Güncel kart, detay, bölüm ve oynatıcı kaynak yapısına uyarlandı.
- Ana sayfa sağlık kontrolünde HTTP 200 ve seçici eşleşmesi var.

### CizgiMax — sürüm 6

- Güncel rota, kart, arama, detay ve bölüm yapısı uyarlandı.
- Sayfadaki `var servers = JSON.parse(atob(...))` sunucu listesi çözülüyor.
- Dzen gömüsü tau-video MP4 bağlantısına çözümleniyor.
- AnimeciX caption yapısı destekleniyor.
- Sibnet fallback korunuyor.
- Modül derleme testi geçti.

### KultFilmler — sürüm 8

- Güncel `.mcard` / `.dcard` liste yapısı ve detay metadatası uyarlandı.
- `.ep` bölüm yapısı ve `#kf-srcdata` JSON kaynak listesi ayrıştırılıyor.
- Yeni `VidPapiExtractor` eklendi.
- Oynatıcı POST sözleşmesi:

```text
POST https://vidpapi.xyz/player/index.php?data=<id>&do=getVideo
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
X-Requested-With: XMLHttpRequest
form: hash=<id>, r=<sayfa-referer>
```

- `securedLink`, yoksa `videoSource` kullanılıyor.
- Gerçek örnek M3U8 HTTP 200 döndürdü.
- Modül derleme testi geçti.

### DiziKorea — sürüm 5 (en son yapılan değişiklik)

Eski alan adı Cloudflare 526 veriyordu. Güncel site `https://dizikorea3.com` üzerinde HTTP 200 ve tam içerik döndürüyor.

Yapılanlar:

- `mainUrl = "https://dizikorea3.com"`
- Sağlık hedefi: `/kore-dizileri-izle-dq1`
- Güncel listeler:
  - `/kore-dizileri-izle-dq1`
  - `/cin-dizileri`
  - `/japon-dizileri`
  - `/tayland-dizileri`
  - `/tayvan-dizileri`
  - `/filipin-dizileri`
  - `/filmler`
- Sayfalama: ilk sayfa doğrudan rota, sonrası `/sayfa/<n>`.
- Kart seçicisi: `a.poster-card`
- Başlık: `.poster-card-title`
- Poster: `.poster-card-image img`
- Film/dizi türü URL'deki `/film/` ve `/dizi/` ile ayrılıyor.
- Arama artık eski POST uç noktası değil:

```text
GET https://dizikorea3.com/ara?q=<url-encoded-query>
X-Requested-With: XMLHttpRequest
Referer: https://dizikorea3.com/
```

- Arama JSON sözleşmesi: `{ success, items[] }`; item alanları `title`, `poster`, `year`, `type`, `url`.
- Dizi detay seçicileri:
  - başlık `.series-title`
  - poster `.series-hero-poster img`
  - metadata `.series-meta`
  - açıklama `.series-about-preview .series-about-text`
  - tür `.series-meta a[href*='/tur/']`
  - fragman `.btn-trailer[data-trailer]`
  - oyuncu `.series-cast-grid a.cast-card`
  - bölüm `.episode-list[data-season] a.episode-item`
- Film detay seçicileri:
  - başlık `.watch-title`
  - yıl `.watch-ep-date`
  - metadata `.watch-meta-row`
  - poster `img.sidebar-poster`
- Oynatıcı seçicisi: `.player-source iframe[data-src]` ve `iframe[src]`.
- Yeni `DiziKoreaPlayerExtractor` eklendi ve plugin'e kaydedildi.
- Kullanılmayan eski VideoSeyred extractor dosyası kaldırıldı.

Canlı oynatıcı doğrulaması:

```text
Örnek bölüm:
https://dizikorea3.com/dizi/a-love-other-than-yours-izle-dq/sezon-1/bolum-1

Kaynaklar:
https://playerdkorea.xyz/video/ad720f99a3b6ea189d9c352217bd3028
https://vidmoly.org/embed-0e0ure53ar7o.html
https://bysedikamoum.com/e/eihgkkr59lb8
```

Özel player POST sözleşmesi:

```text
POST https://playerdkorea.xyz/player/index.php?data=<id>&do=getVideo
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
X-Requested-With: XMLHttpRequest
form: hash=<id>, r=<DiziKorea bölüm/film URL'si>
```

Yanıt `securedLink` ve `videoSource` içeriyor. Gerçek `securedLink` HTTP 200, `application/x-mpegurl` ve geçerli master playlist döndürdü.

Derleme testi:

```powershell
.\gradlew.bat :DiziKorea:compileDebugKotlin --no-daemon --console=plain
```

Sonuç: `BUILD SUCCESSFUL`.

## 9. DiziBox ve RareFilmm yanlış pozitif düzeltmesi

Önceki sağlık taramasında her ikisi de sayfada normal CAPTCHA widget metni bulunduğu için `blocked` sayılıyordu. Canlı gövde denetimi:

- DiziBox: HTTP 200, yaklaşık 112 KB gerçek içerik.
- RareFilmm: HTTP 200, yaklaşık 12 KB gerçek içerik.

Genel `captcha` işareti challenge listesinden çıkarılınca ikisi de doğru biçimde `healthy` oldu. WebteIzle ise gerçek `Just a moment...` sayfası ve 403 döndürdüğü için `blocked` kalıyor.

## 10. WebteIzle — sıradaki etkin sağlayıcı işi

Durum:

- `https://webteizle.info` güncel alan adıdır.
- 2026 tarihli sertifika ve Cloudflare DNS kayıtları var.
- Doğrudan HTTP istemcisi 403 ve `Just a moment...` challenge döndürüyor.
- Mevcut `WebteIzle.kt` içinde `CloudflareKiller` ve özel interceptor zaten var, fakat interceptor yalnız `search()` isteğine bağlanmış.
- `getMainPage`, `load`, `loadLinks` ve AJAX POST isteklerinin çoğu interceptor kullanmıyor.

Önerilen sonraki değişiklik:

1. `getMainPage`, `load`, `loadLinks` içindeki site GET isteklerine `interceptor = interceptor` ekle.
2. `/ajax/dataAlternatif3.asp` ve `/ajax/dataEmbed.asp` POST isteklerinde API izin veriyorsa aynı interceptor'ı kullan.
3. Cookie/challenge oturumunun istekler arasında taşındığını Android/CloudStream ortamında doğrula.
4. `dataAlternatif3.asp` → `dataEmbed.asp` → VidMoly/Dzen/diğer iframe zincirini canlı film üzerinde test et.
5. Sağlık tarayıcısının çıplak HTTP düzeyinde yine blocked göstermesi beklenebilir; bu, uygulama içi `CloudflareKiller` başarısını ölçmez.

Henüz WebteIzle kodunda bu turda değişiklik yapılmadı.

> 2. tur notu: interceptor kapsamı genişletildi ve derlendi (bkz. bölüm 0,
> madde 4). Kalan 3–5. maddeler Android runtime doğrulaması gerektirir.

## 11. Dağıtımdan kapalı 11 modül (FilmMakinesi 22 Eylül 2026'da yeniden açıldı)

`settings.gradle.kts` içindeki liste (FilmMakinesi 2. turda çıkarıldı):

```text
__Temel
DiziPal
FullHDFilm
GolgeTV
InatBox
KoreanTurk
NetflixMirror
OxAx
RecTV
SuperFilmGeldi
UgurFilm
```

Hepsinin `build.gradle.kts` durumu `status = 0` olarak ayarlı. `__Temel` yalnız şablondur ve dağıtıma açılmamalıdır.

22 Eylül 2026 canlı ön incelemesi:

| Modül | Mevcut/denenen uç | Gözlem | Devam kararı |
|---|---|---|---|
| DiziPal | `https://dizipal950.com` | Bağlantı zaman aşımı. | Güncel resmi giriş alanı bulunmadan açma. |
| FilmMakinesi | `https://filmmakinesi.to` | ~~HTTP 403 gerçek Cloudflare managed challenge~~ → 2. turda HTTP 200 + yeniden tasarım tespit edildi, **yeniden etkinleştirildi (sürüm 8, bkz. bölüm 17)**. | Açık; Rapid extractor opsiyonel. |
| FilmMakinesi eski alternatif | `https://filmmakinesi.de` | Yerel erişim engeli sayfası ve sertifika/SNI sorunu. | `.de`ye geri dönme. |
| FullHDFilm | `https://fullhdfilm.site` | Sertifika/SNI sorunu; yerel erişim engeli gövdesi. | Güncel alan adı bulunmalı, sonra baştan seçici/oynatıcı testi. |
| GolgeTV | `panel.cloudgolge.shop` | DNS çözümlenmiyor. | Yeni APK/API uç noktası olmadan açma. |
| InatBox | özel uygulama/API yapısı | Standart tek `mainUrl` taraması yok; eski mobil uygulama protokolü. | Güncel APK/protokol incelemesi gerektirir. |
| KoreanTurk | `.com` / `.net` | `.com` güvenilmeyen sertifika; `.net` Cloudflare 522 origin timeout. | Origin düzelmeden açma. |
| NetflixMirror | `iosmirror.cc` | DNS çözümlenmiyor. | Güncel mirror ve doğrulama protokolü bulunmalı. |
| OxAx | `example.invalid` | Bilinçli güvenli yer tutucu; doğrulanabilir eski API bulunamadığı için gerçek olmayan domaine çekildi. | Doğrulanmış API olmadan kesinlikle açma. |
| RecTV | `b.prectv38.sbs` | Bağlantı zaman aşımı. | Güncel APK içinden API base URL ve `swKey` yeniden çıkarılmalı. |
| SuperFilmGeldi | `superfilmgeldi.me` | Yerel BTK erişim engeli gövdesi. | Güncel resmi alan adı bulunmalı. |
| UgurFilm | `ugurfilm8.com` | HTTP 200 ama içerik film sitesi değil, “domain for sale” parking sayfası. | 200'e aldanma; güncel alan adı bulunmadan açma. |

Önerilen kapalı modül önceliği (FilmMakinesi 2. turda açıldı, listeden düştü):

1. FullHDFilm ve SuperFilmGeldi — yeni alan adı bulunursa WordPress seçicileri yeniden kurulabilir.
3. DiziPal ve RecTV — güncel uygulama/site uç noktası araştırması gerekir.
4. KoreanTurk — origin 522 düzeliyor mu zaman içinde tekrar kontrol et.
5. GolgeTV, NetflixMirror, InatBox — APK veya protokol tersine incelemesi gerektirir.
6. OxAx — doğrulanmış servis olmadan dokunma.
7. UgurFilm — mevcut domain terk edilmiş; yeni resmi domain yoksa kapalı bırak.

## 12. Derleme ve doğrulama geçmişi

DiziKorea son değişikliğinden **önce** şu tam komut başarılı oldu:

```powershell
.\gradlew.bat clean make makePluginsJson ensureJarCompatibility --no-daemon --console=plain
```

Sonuç:

```text
BUILD SUCCESSFUL
542 actionable tasks
```

Aynı paket seti staging sonrası `30 indeks / 30 paket` olarak doğrulandı.

2. turda (DiziKorea sonrası bekleyen doğrulama + FilmMakinesi yeniden
etkinleştirme sonrası) tam derleme iki kez daha çalıştı:

```text
BUILD SUCCESSFUL in 1m 33s / 542 actionable tasks → 30 indeks / 30 paket
BUILD SUCCESSFUL in 1m 31s / 560 actionable tasks → 31 indeks / 31 paket (FilmMakinesi dahil)
```

Ardından paketleri boş bir geçici klasöre kopyalayıp doğrula. Örnek:

```powershell
$kontrol = Join-Path $env:TEMP ("cloudstream-dist-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $kontrol | Out-Null
Get-ChildItem -Path . -Recurse -Filter *.cs3 |
  Where-Object { $_.Directory.Name -eq 'build' } |
  Copy-Item -Destination $kontrol
python scripts\validate_distribution.py `
  --plugins-json build\plugins.json `
  --packages-dir $kontrol
```

Beklenen sonuç yine `30 indeks kaydı, 30 paket` olmalıdır.
(2. tur notu: bu doğrulama yapıldı; FilmMakinesi sonrası taban `31/31` oldu.)

Son `git diff --check` çalıştırmasında içerik hatası yoktu; yalnız Windows LF→CRLF uyarıları görüldü.

## 13. Yeni yapay zekâ için önerilen kesin devam sırası (2. tur sonrası güncel)

1. Bu dosyayı ve `settings.gradle.kts`, kök `build.gradle.kts`, `README.md`, üç `scripts/*.py` dosyasını oku.
2. `git status --short` al; namespace taşımasını geri alma.
3. ~~DiziKorea sonrası tam temiz derleme ve 30/30~~ — yapıldı (taban artık 31/31).
   Rutin doğrulama: `clean make makePluginsJson ensureJarCompatibility` + `validate_distribution.py`.
4. Sağlık taramasını yeniden çalıştır; güncel taban `23 healthy / 8 blocked / 0 down`
   (31 modül; Watch2Movies 451'i ölçüm ortamının UK çıkışıysa geçici say).
5. ~~DNS sinkhole ön kontrolü~~ — uygulandı (`probe()` başında).
6. WebteIzle: interceptor genişletildi + derlendi; kalan iş yalnız Android
   runtime challenge/oynatıcı doğrulamasıdır (bölüm 10, maddeler 3–5).
7. ~~FilmMakinesi~~ — yeniden etkinleştirildi (sürüm 8, bölüm 17).
   Sıradaki kapalı-modül adayı: FullHDFilm / SuperFilmGeldi (yeni alan adı araştırması).
8. Her sağlayıcı için yalnız ana sayfayı değil şu zinciri sınamaya çalış:
   - liste HTTP + en az bir kart
   - arama + en az bir sonuç
   - detay metadata
   - dizi ise bölüm listesi
   - iframe/player çözümü
   - son M3U8/MP4 için HEAD/GET 200
9. Bir modülü ancak tüm zincir makul ölçüde çalışınca `status = 1` yap ve `settings.gradle.kts` kapalı listesinden çıkar.
10. Her yeniden etkinleştirmede sürümü artır, modül derlemesini, tam dağıtım doğrulamasını ve sağlık raporunu yenile.
11. Kullanıcı istemeden commit/push yapma.

## 14. Sağlayıcı onarırken kullanılacak kontrol kalıbı

Alan adı çok değişken olduğu için şu sırayı koru:

1. Resmi Linktree/sosyal profil veya güncel sertifika/DNS kaydıyla alan adını doğrula.
2. `curl -L -A "Mozilla/5.0"` ile status, final URL, content type ve gövde boyunu ölç.
3. 200 yanıtın parking/erişim-engeli/challenge gövdesi olmadığını başlık ve içerikle doğrula.
4. Canlı listeden bir kart URL'si çıkar.
5. Kart ve detay HTML'sini gerçek seçicilerle eşleştir.
6. Player butonu/iframe/API çağrılarını tarayıcı JavaScript'inden veya network sözleşmesinden çıkar.
7. API isteğinde gerekiyorsa `Referer`, `Origin`, `X-Requested-With`, form alanları ve cookie'leri birebir taşı.
8. Son akış URL'sini doğrudan HTTP 200 ve MIME türüyle kontrol et.
9. Sonra kodu değiştir ve derle.

## 15. Doğrulama için kullanılmış güncel kaynaklar

- FilmMakinesi resmi bağlantı sayfası: <https://linktr.ee/filmakinesi>
- FullHDFilmizlesene: <https://www.fullhdfilmizlesene.now/>
- JetFilmizle kullanım koşulları: <https://jetfilmizle.now/kullanim-kosullari>
- SetFilm gizlilik sayfası: <https://www.setfilmizle.ltd/gizlilik-politikasi/>
- KoreanTurk `.net`: <https://www.koreanturk.net/>
- Selcukflix film sayfası: <https://selcukflix.com/film-izle>
- DiziMom yardım sayfası: <https://www.dizimom.beer/yardim/>
- DiziYou hakkında: <https://www.diziyou.one/hakkimizda/>
- SineWix resmi APK deposu: <https://github.com/Sinewix/sinewixapk>
- SineWix resmi bağlantı sayfası: <https://linktr.ee/sinewix>
- KultFilmler ana sayfa: <https://kultfilmler.net/>
- KultFilmler örnek film: <https://kultfilmler.net/faize-hucum/>
- KultFilmler örnek dizi: <https://kultfilmler.net/dizi/black-bird-izle/>
- DiziBox alan adı kontrolü: <https://scanner.pcrisk.com/scan-results/dizibox.live>
- RareFilmm alan adı kontrolü: <https://www.ipaddress.com/website/rarefilmm.com/>
- WebteIzle Cloudflare alan bilgisi: <https://radar.cloudflare.com/domains/domain/webteizle.info>
- DiziKorea yönlendirme geçmişi: <https://builtwith.com/ru/redirects/dizikorea3.com>
- Karşılaştırma için güncel derlenmiş topluluk deposu: <https://github.com/CennetCehennem/Cloudstream-Turkce-Eklentiler>

## 16. Son durumun kısa özeti (2. tur sonrası güncel)

- Genel temizlik ve tarafsız namespace taşıması tamamlandı.
- Güncel Gradle/Android altyapısı çalışıyor.
- `.cs3` üretimi ve dağıtım doğrulaması çalışıyor (`31 indeks / 31 paket`).
- 31 etkin modülden 23'ü HTTP/seçici düzeyinde sağlıklı.
- 6 modül yerel DNS engelli fakat alan adları güncel.
- WebteIzle güncel fakat gerçek Cloudflare 403 altında (interceptor kapsamı genişletildi, runtime doğrulama Android'de yapılacak).
- Watch2Movies ölçüm ortamının UK çıkışından Cloudflare 451 alıyor; site down değil, kod değişikliği yapılmadı.
- 0 etkin sağlayıcı 404/5xx/down durumunda.
- FullHDFilmizlesene, BelgeselX, CizgiMax, KultFilmler, DiziKorea ve FilmMakinesi kapsamlı biçimde onarıldı.
- FilmMakinesi'nin arama, liste, detay ve CloseLoad M3U8 oynatıcısı canlı olarak doğrulandı (sürüm 8).
- 11 eski/kanıtlanmamış modül güvenli biçimde dağıtımdan kapalı.
- Sağlık tarayıcısı DNS ön kontrolüyle hızlandırıldı (36 sn → ~1 ms).
- Sonraki işler: WebteIzle Android runtime doğrulaması; FullHDFilm/SuperFilmGeldi alan adı araştırması; Rapid (`rapid.filmmakinesi.to`) extractor'ı (opsiyonel).
