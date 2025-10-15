package ani.dantotsu.connections.github

import ani.dantotsu.client
import ani.dantotsu.settings.Developer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class Contributors {

    suspend fun getContributors(): Array<Developer> {
        val developers = mutableListOf<Developer>()

        try {
            val res =
                client.get("https://git.rebelonion.dev/rebelonion/Dantotsu/activity/contributors/data")
            val json = Json { ignoreUnknownKeys = true }
            val usersMap: Map<String, UserData> = json.decodeFromString(res.text)

            usersMap.values.filterNot { it.login == null  || it.login.isEmpty() }.forEach { user ->
                if (user.name == "SunglassJerry") return@forEach

                val role = when (user.name) {
                    "rebelonion" -> "Owner & Maintainer"
                    "sneazy-ibo" -> "Contributor & Comment Moderator"
                    "WaiWhat" -> "Icon Designer"
                    "itsmechinmoy" -> "Discord & Telegram Admin/Helper, Comment Moderator & Translator"
                    else -> "Contributor"
                }

                developers.add(
                    Developer(
                        name = user.name ?: "" ,
                        pfp = "https://git.rebelonion.dev${user.pfp}",
                        role = role,
                        url = "https://git.rebelonion.dev${user.url}"
                    )
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
            developers.add(
                Developer(
                    "Git repo is down",
                    "https://cdn-icons-png.flaticon.com/512/561/561127.png",
                    "Try again later",
                    ""
                )
            )
        }

        developers.addAll(
            listOf(
                Developer(
                    "MarshMeadow",
                    "https://avatars.githubusercontent.com/u/88599122?v=4",
                    "Beta Icon Designer & Website Maintainer",
                    "https://github.com/MarshMeadow?tab=repositories"
                ),
                Developer(
                    "Zaxx69",
                    "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6342562-kxE8m4i7KUMK.png",
                    "Telegram Admin",
                    "https://anilist.co/user/6342562"
                ),
                Developer(
                    "Arif Alam",
                    "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6011177-2n994qtayiR9.jpg",
                    "Discord & Comment Moderator",
                    "https://anilist.co/user/6011177"
                ),
                Developer(
                    "SunglassJeery",
                    "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b5804776-FEKfP5wbz2xv.png",
                    "Head Discord & Comment Moderator",
                    "https://anilist.co/user/5804776"
                ),
                Developer(
                    "Excited",
                    "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6131921-toSoGWmKbRA1.png",
                    "Comment Moderator",
                    "https://anilist.co/user/6131921"
                ),
                Developer(
                    "Gurjshan",
                    "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6363228-rWQ3Pl3WuxzL.png",
                    "Comment Moderator",
                    "https://anilist.co/user/6363228"
                ),
                Developer(
                    "NekoMimi",
                    "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6244220-HOpImMGMQAxW.jpg",
                    "Comment Moderator",
                    "https://anilist.co/user/6244220"
                ),
                Developer(
                    "Ziadsenior",
                    "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6049773-8cjYeUOFUguv.jpg",
                    "Comment Moderator and Arabic Translator",
                    "https://anilist.co/user/6049773"
                ),
                Developer(
                    "Dawnusedyeet",
                    "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6237399-RHFvRHriXjwS.png",
                    "Contributor",
                    "https://anilist.co/user/Dawnusedyeet/"
                ),
                Developer(
                    "hastsu",
                    "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6183359-9os7zUhYdF64.jpg",
                    "Comment Moderator and Arabic Translator",
                    "https://anilist.co/user/6183359"
                )
            )
        )

        return developers.toTypedArray()
    }

    @Serializable
    data class UserData(
        val name: String?,
        val login: String?,
        @SerialName("avatar_link")
        val pfp: String?,
        @SerialName("home_link")
        val url: String?,
        @SerialName("total_commits")
        val commits: Int?,
    )
}