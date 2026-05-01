package ani.dantotsu.others

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.updateLayoutParams
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityCrashBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import eu.kanade.tachiyomi.util.system.copyToClipboard
import java.io.File

class CrashActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCrashBinding

    private lateinit var stackTrace: String
    private lateinit var logcat: String

    /** Which content is currently shown — false = stack trace, true = logcat */
    private var showingLogcat = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        stackTrace = intent.getStringExtra("stackTrace") ?: "No stack trace available"
        logcat = intent.getStringExtra("logcat") ?: "No logcat available"

        // Show stack trace by default
        showReport(stackTrace)

        binding.crashReportView.setOnKeyListener(View.OnKeyListener { _, _, _ ->
            true // Blocks input from hardware keyboards.
        })

        binding.copyButton.setOnClickListener {
            val label = if (showingLogcat) "Logcat" else "Crash log"
            copyToClipboard(label, currentContent())
        }

        binding.shareAsTextFileButton.setOnClickListener {
            shareAsTextFile(currentContent(), if (showingLogcat) "logcat.txt" else "crash_log.txt")
        }

        binding.toggleLogcatButton.setOnClickListener {
            showingLogcat = !showingLogcat
            if (showingLogcat) {
                showReport(logcat)
                binding.toggleLogcatButton.text = getString(R.string.show_crash_report)
            } else {
                showReport(stackTrace)
                binding.toggleLogcatButton.text = getString(R.string.show_logcat)
            }
        }
    }

    private fun currentContent() = if (showingLogcat) logcat else stackTrace

    private fun showReport(content: String) {
        binding.crashReportView.setText(content)
        // Scroll to bottom for logcat (most recent lines last), top for stack trace
        if (showingLogcat) {
            binding.crashReportScrollView.post {
                binding.crashReportScrollView.fullScroll(View.FOCUS_DOWN)
            }
        } else {
            binding.crashReportScrollView.scrollTo(0, 0)
        }
    }

    private fun shareAsTextFile(content: String, fileName: String) {
        val file = File(cacheDir, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }
}