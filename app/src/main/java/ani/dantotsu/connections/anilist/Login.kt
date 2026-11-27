package ani.dantotsu.connections.anilist

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.logError
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.startMainActivity
import ani.dantotsu.themes.ThemeManager

class Login : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        val data: Uri? = intent?.data
        try {
            val rawQuery = data?.encodedFragment ?: data?.encodedQuery
            val parsedToken = rawQuery
                ?.let { Uri.parse("https://localhost?$it").getQueryParameter("access_token") }
                ?.takeIf { it.isNotBlank() }
                ?: Regex("""(?<=access_token=)[^&]+""").find(data?.toString().orEmpty())?.value
            if (!parsedToken.isNullOrBlank()) {
                Anilist.saveToken(parsedToken)
            }
        } catch (e: Exception) {
            logError(e)
        }
        startMainActivity(this)
        finish()
    }
}
