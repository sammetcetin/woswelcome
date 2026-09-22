version = 2

cloudstream {
    language    = "tr"
    description = "Türk Anime TV - Türkiye'nin Online Anime izleme sitesi."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 0 // Site kapandi (veda/duyuru sayfasi); resmi yeni alan adi bulunana kadar kapali
    tvTypes = listOf("Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=www.turkanime.tv&sz=%size%"
}
