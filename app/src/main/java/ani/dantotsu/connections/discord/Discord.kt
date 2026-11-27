package ani.dantotsu.connections.discord

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebStorage
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWith
import java.io.File

object Discord {

    /**
     * Older builds logged in with the Discord account token. Delete what they stored:
     * the token and profile prefs, the OAuth cache in filesDir/discord and discord.com web storage.
     */
    fun removeLegacyLogin(context: Context) {
        val hadToken = !PrefManager.getVal(PrefName.DiscordToken, null as String?).isNullOrEmpty()
        val dir = File(context.filesDir, "discord")
        LegacyLoginMigration.run(object : LegacyLoginMigration.Store {
            override val hadLegacyLogin = hadToken
            override var migrated: Boolean
                get() = PrefManager.getVal(PrefName.DiscordLoginMigrated)
                set(value) = PrefManager.setVal(PrefName.DiscordLoginMigrated, value)
            override fun enablePresence() = PrefManager.setVal(PrefName.rpcEnabled, true)
        })
        if (!hadToken && !dir.exists()) return
        listOf(PrefName.DiscordToken, PrefName.DiscordId, PrefName.DiscordUserName, PrefName.DiscordAvatar)
            .forEach { PrefManager.removeVal(it) }
        PrefManager.removeCustomVal("discord_activity_token")
        PrefManager.removeCustomVal("DiscordStatus")
        tryWith(true) { dir.deleteRecursively() }
        Handler(Looper.getMainLooper()).post {
            tryWith(true) { WebStorage.getInstance().deleteOrigin("https://discord.com") }
        }
    }

    const val application_Id = "1163925779692912771"
    const val small_Image: String =
        "https://cdn.discordapp.com/emojis/1167344924874784828.gif?size=128&animated=true"
    const val small_Image_AniList: String =
        "https://anilist.co/img/icons/android-chrome-512x512.png"
    const val small_Image_MAL: String =
        "https://cdn.myanimelist.net/img/sp/icon/apple-touch-icon-256.png"
    const val small_Image_Simkl: String =
        "https://eu.simkl.in/img_favicon/v2/favicon-192x192.png"
}
