package tachiyomi.domain.source.anime.model

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video

@Suppress("OverridingDeprecatedMember")
class StubAnimeSource(
    override val id: Long,
    override val name: String,
) : AnimeSource {

    private val isInvalid: Boolean = name.isBlank()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime =
        throw AnimeSourceNotInstalledException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        throw AnimeSourceNotInstalledException()

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> =
        throw AnimeSourceNotInstalledException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        throw  AnimeSourceNotInstalledException()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> =
        throw AnimeSourceNotInstalledException()

    override fun toString(): String =
        if (!isInvalid) "$name " else id.toString()

    companion object {
        fun from(source: AnimeSource): StubAnimeSource {
            return StubAnimeSource(id = source.id, name = source.name)
        }
    }
}
class AnimeSourceNotInstalledException : Exception()
