package ani.dantotsu.media.manga.mangareader.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.databinding.DialogOcrTranslationBinding
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrTranslationBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogOcrTranslationBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogOcrTranslationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bitmap = currentBitmap
        if (bitmap == null || bitmap.isRecycled) {
            if (isAdded) Toast.makeText(context, "No page image available for translation", Toast.LENGTH_SHORT).show()
            dismiss()
            return
        }

        lifecycleScope.launch {
            var detectedText = ""
            try {
                // 1. Recognize text using ML Kit on-device recognizer
                detectedText = recognizeText(bitmap)

                if (!isAdded) return@launch

                if (detectedText.isBlank()) {
                    binding.ocrProgressBar.visibility = View.GONE
                    binding.ocrStatusText.visibility = View.GONE
                    binding.ocrResultsContainer.visibility = View.VISIBLE
                    binding.ocrDetectedText.text = getString(R.string.no_text_detected)
                    binding.ocrTranslatedText.text = getString(R.string.no_text_detected)
                    return@launch
                }

                // 2. Check if text is already Latin/English (all ASCII ≤ 127)
                //    If so, skip translation — just show the detected text directly.
                val isLikelyLatin = detectedText.all { it.code <= 127 }

                val translatedText = if (isLikelyLatin) {
                    detectedText  // Don't translate English → English
                } else {
                    binding.ocrStatusText.text = "Translating text..."
                    translateText(detectedText)
                }

                if (!isAdded) return@launch

                // 3. Update UI
                binding.ocrProgressBar.visibility = View.GONE
                binding.ocrStatusText.visibility = View.GONE
                binding.ocrResultsContainer.visibility = View.VISIBLE
                binding.ocrDetectedText.text = detectedText
                binding.ocrTranslatedText.text = translatedText

                binding.ocrCopyButton.setOnClickListener {
                    val ctx = context ?: return@setOnClickListener
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText("Manga Translation", translatedText)
                    clipboard?.setPrimaryClip(clip)
                    if (isAdded) Toast.makeText(ctx, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                }

                binding.ocrCloseButton.setOnClickListener {
                    dismiss()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (!isAdded) return@launch
                // Don't dismiss — show whatever we detected so user can still copy it
                binding.ocrProgressBar.visibility = View.GONE
                binding.ocrStatusText.visibility = View.GONE
                binding.ocrResultsContainer.visibility = View.VISIBLE
                binding.ocrDetectedText.text = detectedText.ifBlank { getString(R.string.no_text_detected) }
                binding.ocrTranslatedText.text = "Translation failed: ${e.localizedMessage ?: "Network error"}"
                binding.ocrCopyButton.setOnClickListener {
                    val ctx = context ?: return@setOnClickListener
                    val textToCopy = detectedText.ifBlank { return@setOnClickListener }
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Manga OCR", textToCopy))
                    if (isAdded) Toast.makeText(ctx, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                }
                binding.ocrCloseButton.setOnClickListener { dismiss() }
            }
        }
    }

    private suspend fun processWithRecognizer(
        recognizer: TextRecognizer,
        image: InputImage
    ): String = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                continuation.resume(visionText.text)
            }
            .addOnFailureListener { e ->
                continuation.resumeWithException(e)
            }
    }

    private suspend fun recognizeText(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val isCopy = safeBitmap !== bitmap
        try {
            val image = InputImage.fromBitmap(safeBitmap, 0)
            val ctx = context?.applicationContext

            // 1. Attempt Japanese text recognition first
            var text = ""
            try {
                val jpRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

                if (ctx != null) {
                    try {
                        val moduleInstallClient = ModuleInstall.getClient(ctx)
                        val isAvailable = suspendCancellableCoroutine<Boolean> { cont ->
                            moduleInstallClient.areModulesAvailable(jpRecognizer)
                                .addOnSuccessListener { response ->
                                    cont.resume(response.areModulesAvailable())
                                }
                                .addOnFailureListener {
                                    cont.resume(false)
                                }
                        }

                        if (!isAvailable) {
                            withContext(Dispatchers.Main) {
                                if (isAdded) binding.ocrStatusText.text = "Downloading Japanese OCR model..."
                            }
                            suspendCancellableCoroutine<Boolean> { cont ->
                                val listener = InstallStatusListener { statusUpdate ->
                                    when (statusUpdate.installState) {
                                        ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOADING -> {
                                            val info = statusUpdate.progressInfo
                                            if (info != null && info.totalBytesToDownload > 0) {
                                                val percent = ((info.bytesDownloaded * 100) / info.totalBytesToDownload).toInt()
                                                lifecycleScope.launch(Dispatchers.Main) {
                                                    if (isAdded) binding.ocrStatusText.text = "Downloading Japanese OCR model ($percent%)..."
                                                }
                                            }
                                        }
                                        ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                                            lifecycleScope.launch(Dispatchers.Main) {
                                                if (isAdded) binding.ocrStatusText.text = "Initializing recognizer..."
                                            }
                                        }
                                    }
                                }
                                val request = ModuleInstallRequest.newBuilder()
                                    .addApi(jpRecognizer)
                                    .setListener(listener)
                                    .build()

                                moduleInstallClient.installModules(request)
                                    .addOnSuccessListener {
                                        cont.resume(true)
                                    }
                                    .addOnFailureListener {
                                        cont.resume(false)
                                    }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isAdded) binding.ocrStatusText.text = "Recognizing text..."
                }
                text = processWithRecognizer(jpRecognizer, image)
            } catch (e: Exception) {
                e.printStackTrace()
                text = ""
            }

            // 2. Fallback to default Latin recognizer if Japanese produces blank text or fails
            if (text.isBlank()) {
                withContext(Dispatchers.Main) {
                    if (isAdded) binding.ocrStatusText.text = "Recognizing text (Latin fallback)..."
                }
                text = try {
                    val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    processWithRecognizer(latinRecognizer, image)
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
            }

            text
        } finally {
            if (isCopy) {
                safeBitmap.recycle()
            }
        }
    }

    /**
     * Translate [text] to English using a fallback chain:
     *   1. Google Translate (client=dict-chrome-ex, unauthenticated & unblocked)
     *   2. MyMemory         (free, 5 000 chars/day, with detected language)
     */
    private suspend fun translateText(text: String): String = withContext(Dispatchers.IO) {
        translateWithGoogle(text)
            ?: translateWithMyMemory(text)
            ?: "Translation unavailable — all engines failed or are rate-limited. Please try again later."
    }

    private fun detectLanguage(text: String): String {
        return when {
            text.any { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' } -> "ko"
            text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' } -> "ja"
            text.any { it in '\u4E00'..'\u9FFF' } -> "zh"
            else -> "ja"
        }
    }

    /** Returns null on rate-limit or any error, so the caller can fall through. */
    private fun translateWithGoogle(text: String): String? = try {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val response = httpGet(
            "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl=auto&tl=en&dt=t&q=$encoded"
        )
        // Google returns a /sorry page when rate-limited instead of JSON
        if (response.contains("google.com/sorry") || !response.trimStart().startsWith("[")) return null
        val firstArray = JSONArray(response).getJSONArray(0)
        val result = StringBuilder()
        for (i in 0 until firstArray.length()) result.append(firstArray.getJSONArray(i).getString(0))
        result.toString().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /** MyMemory free tier — 5 000 chars/day, no API key required. */
    private fun translateWithMyMemory(text: String): String? = try {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val sourceLang = detectLanguage(text)
        val response = httpGet(
            "https://api.mymemory.translated.net/get?q=$encoded&langpair=$sourceLang|en"
        )
        val json = JSONObject(response)
        val status = json.optInt("responseStatus", 0)
        if (status != 200) return null
        json.getJSONObject("responseData")
            .getString("translatedText")
            .takeIf { it.isNotBlank() && !it.equals(text, ignoreCase = true) }
    } catch (_: Exception) { null }

    /** Shared GET helper — throws on non-2xx or network error. */
    private fun httpGet(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        return connection.inputStream.bufferedReader().use { it.readText() }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap = null
    }

    companion object {
        private var currentBitmap: Bitmap? = null

        fun newInstance(bitmap: Bitmap): OcrTranslationBottomSheet {
            currentBitmap = bitmap
            return OcrTranslationBottomSheet()
        }
    }
}
