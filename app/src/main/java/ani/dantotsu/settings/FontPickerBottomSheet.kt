package ani.dantotsu.settings

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetFontPickerBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.themes.CustomFontManager
import ani.dantotsu.toPx
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.io.File

class FontPickerBottomSheet(
    private val onFontApplied: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetFontPickerBinding? = null
    private val binding get() = _binding!!

    private val openFontDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val imported = CustomFontManager.importFontFromUri(ctx, uri)
            if (imported != null && CustomFontManager.activateCustomFont(imported)) {
                snackString(getString(R.string.font_applied, imported.nameWithoutExtension))
                onFontApplied?.invoke()
                dismiss()
            } else {
                snackString(getString(R.string.custom_font_error))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetFontPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderSavedFonts()

        binding.fontDefaultButton.setOnClickListener {
            CustomFontManager.clearCustomFont()
            PrefManager.setVal(PrefName.UseSystemFont, false)
            onFontApplied?.invoke()
            dismiss()
        }

        binding.fontSystemButton.setOnClickListener {
            CustomFontManager.clearCustomFont()
            PrefManager.setVal(PrefName.UseSystemFont, true)
            onFontApplied?.invoke()
            dismiss()
        }

        binding.fontGoogleButton.setOnClickListener {
            GoogleFontsBottomSheet {
                onFontApplied?.invoke()
                dismiss()
            }.show(parentFragmentManager, "GoogleFontsBottomSheet")
        }

        binding.fontImportButton.setOnClickListener {
            openFontDocument.launch(arrayOf("*/*"))
        }
    }

    private fun renderSavedFonts() {
        val ctx = context ?: return
        val saved = CustomFontManager.listSavedFonts(ctx)
        val activePath = PrefManager.getVal<String>(PrefName.CustomFontPath)

        binding.fontSavedHeader.isVisible = saved.isNotEmpty()
        binding.fontSavedList.removeAllViews()
        saved.forEach { file ->
            binding.fontSavedList.addView(buildSavedFontRow(file, file.absolutePath == activePath))
        }
    }

    private fun buildSavedFontRow(file: File, isSelected: Boolean): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.toPx }
        }

        val selectBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = if (isSelected) "${file.nameWithoutExtension} ✓" else file.nameWithoutExtension
            isAllCaps = false
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (CustomFontManager.activateCustomFont(file)) {
                    snackString(getString(R.string.font_applied, file.nameWithoutExtension))
                    onFontApplied?.invoke()
                    dismiss()
                } else {
                    snackString(getString(R.string.custom_font_error))
                }
            }
        }

        val deleteBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_round_delete_24)
            setBackgroundResource(android.R.color.transparent)
            contentDescription = getString(R.string.delete)
            setPadding(8.toPx, 8.toPx, 8.toPx, 8.toPx)
            setOnClickListener {
                val wasActive = PrefManager.getVal<String>(PrefName.CustomFontPath) == file.absolutePath
                CustomFontManager.deleteSavedFont(file)
                renderSavedFonts()
                if (wasActive) onFontApplied?.invoke()
            }
        }

        row.addView(selectBtn)
        row.addView(deleteBtn)
        return row
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
