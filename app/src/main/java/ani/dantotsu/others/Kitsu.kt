package ani.dantotsu.others

import ani.dantotsu.FileUrl
import ani.dantotsu.client
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder

object Kitsu {

    suspend fun getKitsuEpisodesDetails(media: Media): Map<String, Episode>? {
        Logger.log("Kitsu : title=${media.mainName()}")
        return try {
            tryWithSuspend {
                // 1. Try GraphQL Method (Primary Priority)
                var returnedEpisodes: Map<String, Episode>? = null
                try {
                    val query =
                        """
                        query {
                          lookupMapping(externalId: ${media.id}, externalSite: ANILIST_ANIME) {
                            __typename
                            ... on Anime {
                              id
                              episodes(first: 2000) {
                                nodes {
                                  number
                                  titles {
                                    canonical
                                  }
                                  description(locales: ["en", "en-us"])
                                  thumbnail {
                                    original {
                                      url
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }""".trimIndent()

                    val headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                    )
                    
                    val graphqlRes = client.post(
                        "https://kitsu.io/api/graphql",
                        headers,
                        data = mapOf("query" to query)
                    ).parsed<KitsuGraphQLResponse>()
                    
                    if (graphqlRes.data?.lookupMapping != null) {
                        Logger.log("Kitsu : Used GraphQL Method (1st Priority)")
                        val mapping = graphqlRes.data.lookupMapping
                        media.idKitsu = mapping.id
                        val nodes = mapping.episodes?.nodes
                        if (!nodes.isNullOrEmpty()) {
                            returnedEpisodes = nodes.mapNotNull { ep ->
                                val num = ep?.number?.toString() ?: return@mapNotNull null
                                num to Episode(
                                    number = num,
                                    title = ep.titles?.canonical,
                                    desc = ep.description?.en ?: ep.description?.enUs,
                                    thumb = FileUrl[ep.thumbnail?.original?.url]
                                )
                            }.toMap()
                        }
                    }
                } catch (e: Exception) {
                    Logger.log("Kitsu GraphQL failed: ${e.message}")
                }

                if (!returnedEpisodes.isNullOrEmpty()) {
                    return@tryWithSuspend returnedEpisodes
                }

                // 2. Fallback to REST API Method (Secondary Priority)
                // Search for Anime by Title
                val title = URLEncoder.encode(media.mainName(), "utf-8")
                val searchUrl = "https://kitsu.io/api/edge/anime?filter[text]=$title&page[limit]=1"
                val searchRes = client.get(searchUrl).parsed<KitsuAnimeSearch>()
                
                val animeId = searchRes.data?.firstOrNull()?.id ?: return@tryWithSuspend null
                media.idKitsu = animeId
                
                Logger.log("Kitsu : Used REST API Method (2nd Priority)")

                // Fetch Episodes with Pagination
                val allEpisodes = mutableMapOf<String, Episode>()
                var offset = 0
                val limit = 20
                
                while (true) {
                    val episodesUrl = "https://kitsu.io/api/edge/anime/$animeId/episodes?page[limit]=$limit&page[offset]=$offset&sort=number"
                    val episodesRes = client.get(episodesUrl).parsed<KitsuEpisodes>()
                    
                    val pageEpisodes = episodesRes.data?.associate { ep ->
                        val num = ep.attributes?.number?.toString() ?: return@associate null to null
                        val epNum = if (num.endsWith(".0")) num.substringBefore(".") else num
                        epNum to Episode(
                            number = epNum,
                            title = ep.attributes.canonicalTitle,
                            desc = (ep.attributes.synopsis ?: ep.attributes.description)?.replace(
                                Regex("\\(Source:.*\\)"),
                                ""
                            )?.trim(),
                            thumb = FileUrl[ep.attributes.thumbnail?.original],
                            extra = mapOf(
                                "season" to ep.attributes.seasonNumber.toString(),
                                "airDate" to ep.attributes.airdate.toString(),
                                "length" to ep.attributes.length.toString()
                            )
                        )
                    }?.filterKeys { it != null }?.mapKeys { it.key!! }?.filterValues { it != null }?.mapValues { it.value!! }
                    
                    if (pageEpisodes != null) {
                        allEpisodes.putAll(pageEpisodes)
                    }

                    if (episodesRes.links?.next == null || pageEpisodes.isNullOrEmpty()) {
                        break
                    }
                    offset += limit
                }
                
                allEpisodes
            }
        } catch (e: Exception) {
            null
        }
    }

    @Serializable
    data class KitsuGraphQLResponse(
        val data: GraphQLData? = null
    )

    @Serializable
    data class GraphQLData(
        val lookupMapping: LookupMapping? = null
    )

    @Serializable
    data class LookupMapping(
        val id: String? = null,
        val episodes: GraphQLEpisodes? = null
    )

    @Serializable
    data class GraphQLEpisodes(
        val nodes: List<GraphQLNode?>? = null
    )

    @Serializable
    data class GraphQLNode(
        val number: Int? = null,
        val titles: GraphQLTitles? = null,
        val description: GraphQLDescription? = null,
        val thumbnail: GraphQLThumbnail? = null
    )

    @Serializable
    data class GraphQLDescription(
        val en: String? = null,
        @SerialName("en-us") val enUs: String? = null
    )

    @Serializable
    data class GraphQLThumbnail(
        val original: GraphQLOriginal? = null
    )

    @Serializable
    data class GraphQLOriginal(
        val url: String? = null
    )

    @Serializable
    data class GraphQLTitles(
        val canonical: String? = null
    )

    @Serializable
    data class KitsuAnimeSearch(
        val data: List<AnimeData>? = null
    )

    @Serializable
    data class AnimeData(
        val id: String? = null,
        val type: String? = null
    )

    @Serializable
    data class KitsuEpisodes(
        val data: List<EpisodeData>? = null,
        val meta: Meta? = null,
        val links: Links? = null
    )

    @Serializable
    data class EpisodeData(
        val id: String? = null,
        val type: String? = null,
        val attributes: EpisodeAttributes? = null
    )

    @Serializable
    data class EpisodeAttributes(
        val synopsis: String? = null,
        val description: String? = null,
        val canonicalTitle: String? = null,
        val seasonNumber: Int? = null,
        val number: Int? = null,
        val airdate: String? = null,
        val length: Int? = null,
        val thumbnail: EpisodeThumbnail? = null
    )

    @Serializable
    data class EpisodeThumbnail(
        val original: String? = null
    )

    @Serializable
    data class Meta(
        val count: Int? = null
    )

    @Serializable
    data class Links(
        val first: String? = null,
        val next: String? = null,
        val last: String? = null
    )
}
