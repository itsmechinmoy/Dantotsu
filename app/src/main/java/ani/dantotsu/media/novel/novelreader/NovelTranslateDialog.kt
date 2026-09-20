package ani.dantotsu.media.novel.novelreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.databinding.DialogTranslateSheetBinding
import ani.dantotsu.settings.saving.PrefManager
import kotlinx.coroutines.launch

class NovelTranslateDialog : BottomSheetDialogFragment() {

    private var _binding: DialogTranslateSheetBinding? = null
    private val binding get() = _binding!!

    private var originalText: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTranslateSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        originalText = arguments?.getString(ARG_TEXT).orEmpty().trim()
        binding.translateOriginalText.text = originalText

        val langCodes = NovelTextTranslator.languages.keys.filter { it != "none" }.toList()
        val langNames = langCodes.map { NovelTextTranslator.languages[it] ?: it }

        binding.translateLangSpinner.adapter =
            NoPaddingArrayAdapter(requireContext(), R.layout.item_dropdown, langNames)

        val savedLang = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_TRANSLATE_LANG, "en")
        val savedIndex = langCodes.indexOf(savedLang).let { if (it >= 0) it else 0 }
        binding.translateLangSpinner.setSelection(savedIndex)

        binding.translateLangSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedLang = langCodes.getOrNull(position) ?: "en"
                PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_TRANSLATE_LANG, selectedLang)
                doTranslate(selectedLang)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.translateCopyButton.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val textToCopy = binding.translateResultText.text.toString()
            if (textToCopy.isNotBlank()) {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Translated Text", textToCopy)
                clipboard?.setPrimaryClip(clip)
                if (isAdded) Toast.makeText(ctx, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
        }

        binding.translateCloseButton.setOnClickListener {
            dismiss()
        }

        doTranslate(savedLang)
    }

    private fun doTranslate(targetLang: String) {
        if (originalText.isBlank()) {
            binding.translateProgress.visibility = View.GONE
            binding.translateResultText.text = ""
            return
        }

        binding.translateProgress.visibility = View.VISIBLE
        binding.translateResultContainer.visibility = View.INVISIBLE

        lifecycleScope.launch {
            try {
                val result = NovelTextTranslator.translate(originalText, targetLang)
                if (!isAdded || _binding == null) return@launch
                binding.translateProgress.visibility = View.GONE
                binding.translateResultContainer.visibility = View.VISIBLE
                binding.translateResultText.text = result
            } catch (e: Exception) {
                if (!isAdded || _binding == null) return@launch
                binding.translateProgress.visibility = View.GONE
                binding.translateResultContainer.visibility = View.VISIBLE
                binding.translateResultText.text = "Translation failed: ${e.localizedMessage ?: "Unknown error"}"
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TEXT = "arg_text"
        const val TAG = "NovelTranslateDialog"

        fun newInstance(text: String): NovelTranslateDialog {
            val fragment = NovelTranslateDialog()
            fragment.arguments = Bundle().apply {
                putString(ARG_TEXT, text)
            }
            return fragment
        }
    }
}
