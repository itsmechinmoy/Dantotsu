package ani.dantotsu.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetGoogleFontsBinding
import ani.dantotsu.snackString
import ani.dantotsu.themes.CustomFontManager
import ani.dantotsu.toPx
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class GoogleFontsBottomSheet(
    private val onFontDownloaded: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetGoogleFontsBinding? = null
    private val binding get() = _binding!!
    private var busy = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetGoogleFontsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderFontList("")

        binding.googleFontSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderFontList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun renderFontList(rawQuery: String) {
        val ctx = context ?: return
        val q = rawQuery.trim()
        val matches = if (q.isEmpty()) {
            CustomFontManager.popularGoogleFonts
        } else {
            CustomFontManager.popularGoogleFonts.filter { it.contains(q, ignoreCase = true) }
        }
        val activeName = CustomFontManager.displayNameForActiveFont()

        binding.googleFontListContainer.removeAllViews()
        if (q.isNotBlank() && matches.none { it.equals(q, ignoreCase = true) }) {
            binding.googleFontListContainer.addView(
                buildFontOptionButton(
                    family = q,
                    label = getString(R.string.download_named_font, q),
                    ctx = ctx
                )
            )
        }
        matches.forEach { family ->
            val label = if (family == activeName) "$family ✓" else family
            binding.googleFontListContainer.addView(buildFontOptionButton(family, label, ctx))
        }
    }

    private fun buildFontOptionButton(family: String, label: String, ctx: android.content.Context): View {
        return MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label
            isAllCaps = false
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.toPx }
            setOnClickListener { downloadAndApply(family) }
        }
    }

    private fun downloadAndApply(family: String) {
        if (busy) return
        val ctx = context ?: return
        busy = true
        binding.googleFontProgress.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val file = CustomFontManager.downloadGoogleFont(ctx, family)
            val b = _binding
            busy = false
            b?.googleFontProgress?.isVisible = false
            if (file != null && CustomFontManager.activateCustomFont(file)) {
                snackString(getString(R.string.font_applied, family))
                onFontDownloaded?.invoke()
                if (isAdded) dismiss()
            } else {
                snackString(getString(R.string.google_font_error))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
