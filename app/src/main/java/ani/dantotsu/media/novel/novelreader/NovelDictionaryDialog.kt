package ani.dantotsu.media.novel.novelreader

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.DialogDictionarySheetBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder

class NovelDictionaryDialog : BottomSheetDialogFragment() {

    private var _binding: DialogDictionarySheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogDictionarySheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rawWord = arguments?.getString(ARG_WORD).orEmpty().trim()
        val word = rawWord.filter { it.isLetterOrDigit() || it == '-' || it == '\'' }

        binding.dictWordTitle.text = word
        if (word.isBlank()) {
            binding.dictProgress.visibility = View.GONE
            binding.dictError.visibility = View.VISIBLE
            binding.dictError.text = getString(R.string.error)
            return
        }

        lookupWord(word)
    }

    private fun lookupWord(word: String) {
        binding.dictProgress.visibility = View.VISIBLE
        binding.dictError.visibility = View.GONE
        binding.dictContentScroll.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(word.lowercase(), "UTF-8")
                val url = "https://api.dictionaryapi.dev/api/v2/entries/en/$encoded"
                val response = URL(url).readText()
                val json = JSONArray(response)
                if (json.length() == 0) throw Exception("Empty result")

                val firstEntry = json.getJSONObject(0)
                val phonetic = firstEntry.optString("phonetic", "")
                val meanings = firstEntry.optJSONArray("meanings") ?: JSONArray()

                val parsedMeanings = mutableListOf<ParsedMeaning>()
                for (i in 0 until meanings.length()) {
                    val m = meanings.getJSONObject(i)
                    val pos = m.optString("partOfSpeech", "")
                    val defs = m.optJSONArray("definitions") ?: JSONArray()
                    val defPairs = mutableListOf<Pair<String, String?>>()
                    for (j in 0 until defs.length().coerceAtMost(3)) {
                        val d = defs.getJSONObject(j)
                        val defText = d.optString("definition", "")
                        val example = d.optString("example", "").takeIf { it.isNotBlank() }
                        if (defText.isNotBlank()) {
                            defPairs.add(defText to example)
                        }
                    }
                    if (defPairs.isNotEmpty()) {
                        parsedMeanings.add(ParsedMeaning(pos, defPairs))
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    binding.dictProgress.visibility = View.GONE
                    if (phonetic.isNotBlank()) {
                        binding.dictPhonetic.text = phonetic
                        binding.dictPhonetic.visibility = View.VISIBLE
                    } else {
                        binding.dictPhonetic.visibility = View.GONE
                    }

                    if (parsedMeanings.isEmpty()) {
                        binding.dictError.visibility = View.VISIBLE
                        binding.dictError.text = "No definitions available"
                        return@withContext
                    }

                    binding.dictContentScroll.visibility = View.VISIBLE
                    binding.dictMeaningsContainer.removeAllViews()

                    val ctx = requireContext()
                    for (meaning in parsedMeanings) {
                        // Part of speech badge
                        val posView = TextView(ctx).apply {
                            text = meaning.partOfSpeech.replaceFirstChar { it.uppercase() }
                            setTypeface(typeface, Typeface.BOLD_ITALIC)
                            setTextColor(Color.parseColor("#80FFFFFF"))
                            textSize = 13f
                            setPadding(0, 16, 0, 4)
                        }
                        binding.dictMeaningsContainer.addView(posView)

                        meaning.definitions.forEachIndexed { idx, (def, ex) ->
                            val defView = TextView(ctx).apply {
                                text = "${idx + 1}. $def"
                                setTextColor(Color.WHITE)
                                textSize = 14f
                                setPadding(0, 4, 0, 2)
                            }
                            binding.dictMeaningsContainer.addView(defView)

                            if (ex != null) {
                                val exView = TextView(ctx).apply {
                                    text = "   \"$ex\""
                                    setTypeface(typeface, Typeface.ITALIC)
                                    setTextColor(Color.parseColor("#A0FFFFFF"))
                                    textSize = 12f
                                    setPadding(0, 0, 0, 6)
                                }
                                binding.dictMeaningsContainer.addView(exView)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    binding.dictProgress.visibility = View.GONE
                    binding.dictError.visibility = View.VISIBLE
                    binding.dictError.text = "No definition found for \"$word\""
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private data class ParsedMeaning(
        val partOfSpeech: String,
        val definitions: List<Pair<String, String?>>
    )

    companion object {
        const val TAG = "NovelDictionaryDialog"
        private const val ARG_WORD = "arg_word"

        fun newInstance(word: String) = NovelDictionaryDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_WORD, word)
            }
        }
    }
}
