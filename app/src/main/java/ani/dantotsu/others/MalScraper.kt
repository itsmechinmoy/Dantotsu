package ani.dantotsu.others

import ani.dantotsu.client
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.media.Media
import ani.dantotsu.util.Logger
import kotlinx.coroutines.withTimeout

object MalScraper {
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    suspend fun loadMedia(media: Media) {
        val malId = media.idMAL ?: return

        // 1. Try scraping MyAnimeList directly
        try {
            withTimeout(5000) {
                if (media.anime != null) {
                    val res = client.get("https://myanimelist.net/anime/$malId", headers).document
                    val a = res.select(".title-english").text()
                    media.nameMAL = if (a != "") a else res.select(".title-name").text()
                    media.typeMAL =
                        if (res.select("div.spaceit_pad > a").isNotEmpty()) {
                            res.select("div.spaceit_pad > a")[0].text()
                        } else null

                    fun parseSongs(desktopSelector: String, mobileHeaderClass: String): ArrayList<String> {
                        val songs = arrayListOf<String>()
                        var rows = res.select("$desktopSelector table tr")
                        if (rows.isEmpty()) rows = res.select("$desktopSelector tr")
                        if (rows.isEmpty()) rows = res.select("#songs span.$mobileHeaderClass ~ div.di-b table tr")
                        if (rows.isEmpty()) rows = res.select("#songs span.$mobileHeaderClass ~ table tr")

                        rows.forEach {
                            val text = it.text().trim()
                            if (text.isNotBlank() &&
                                !text.contains("Help improve our database") &&
                                !text.contains("No opening themes have been added", ignoreCase = true) &&
                                !text.contains("No ending themes have been added", ignoreCase = true)
                            ) {
                                songs.add(text)
                            }
                        }
                        return songs
                    }

                    val ops = parseSongs(".opnening", "theme-songs-op")
                    if (ops.isNotEmpty()) {
                        media.anime.op = ops
                    }

                    val eds = parseSongs(".ending", "theme-songs-ed")
                    if (eds.isNotEmpty()) {
                        media.anime.ed = eds
                    }
                } else {
                    val res = client.get("https://myanimelist.net/manga/$malId", headers).document
                    val b = res.select(".title-english").text()
                    val a = res.select(".h1-title").text().removeSuffix(b)
                    media.nameMAL = a
                    media.typeMAL =
                        if (res.select("div.spaceit_pad > a").isNotEmpty()) {
                            res.select("div.spaceit_pad > a")[0].text()
                        } else null
                }
            }
        } catch (e: Exception) {
            Logger.log("MalScraper: Direct scrape failed for malId $malId: ${e.message}")
        }

        // 2. Fallback to Jikan API (which uses multi-mirror fallback: Jikan, midnightignite, tenrai)
        try {
            if (media.anime != null && (media.anime.op.isEmpty() && media.anime.ed.isEmpty())) {
                val jikanData = MAL.jikan.getAnimeById(malId)
                if (jikanData != null) {
                    if (media.nameMAL.isNullOrBlank()) {
                        media.nameMAL = jikanData.titleEnglish ?: jikanData.title
                    }
                    if (media.typeMAL.isNullOrBlank()) {
                        media.typeMAL = jikanData.type
                    }
                    jikanData.theme?.openings?.let { ops ->
                        if (ops.isNotEmpty()) media.anime.op = ArrayList(ops)
                    }
                    jikanData.theme?.endings?.let { eds ->
                        if (eds.isNotEmpty()) media.anime.ed = ArrayList(eds)
                    }
                }
            } else if (media.manga != null && (media.nameMAL.isNullOrBlank() || media.typeMAL.isNullOrBlank())) {
                val jikanData = MAL.jikan.getMangaById(malId)
                if (jikanData != null) {
                    if (media.nameMAL.isNullOrBlank()) {
                        media.nameMAL = jikanData.titleEnglish ?: jikanData.title
                    }
                    if (media.typeMAL.isNullOrBlank()) {
                        media.typeMAL = jikanData.type
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log("MalScraper: Jikan fallback failed for malId $malId: ${e.message}")
        }
    }
}
