package ani.dantotsu.media.novel.novelreader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetCurrentNovelReaderSettingsBinding
import ani.dantotsu.settings.CurrentNovelReaderSettings
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.saving.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NovelReaderSettingsDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetCurrentNovelReaderSettingsBinding? = null
    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCurrentNovelReaderSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as NovelReaderActivity
        val settings = activity.defaultSettings
        val themeLabels = activity.themes.map { it.name }
        binding.themeSelect.adapter =
            NoPaddingArrayAdapter(activity, R.layout.item_dropdown, themeLabels)
        var initialThemeSet = true
        var themeDebounceJob: Job? = null
        binding.themeSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (initialThemeSet) {
                    initialThemeSet = false
                    return
                }
                val newTheme = themeLabels[position]
                if (newTheme != settings.currentThemeName) {
                    settings.currentThemeName = newTheme
                    themeDebounceJob?.cancel()
                    themeDebounceJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        delay(150)
                        activity.applySettings()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val fontLabels = listOf(
            "Default",
            "Sans-Serif",
            "Serif",
            "Monospace",
            "Poppins",
            "OpenDyslexic",
            "Cursive",
            "AccessibleDfA",
            "IA Writer Duospace"
        )
        binding.fontSelect.adapter =
            NoPaddingArrayAdapter(activity, R.layout.item_dropdown, fontLabels)
        val currentFont = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_FONT_FAMILY, "Default")
        val fontIndex = fontLabels.indexOf(currentFont).coerceAtLeast(0)
        binding.fontSelect.setSelection(fontIndex, false)

        var fontDebounceJob: Job? = null
        binding.fontSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val newFont = fontLabels.getOrNull(position) ?: return
                val savedFont = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_FONT_FAMILY, "Default")
                if (newFont != savedFont) {
                    PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_FONT_FAMILY, newFont)
                    fontDebounceJob?.cancel()
                    fontDebounceJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        delay(100)
                        activity.applySettings()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Font Size
        val currentFontSize = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_FONT_SIZE_PX, 100)
        binding.fontSize.setText(currentFontSize.toString())
        binding.fontSize.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.fontSize.text.toString().toIntOrNull() ?: 100
                val clamped = value.coerceIn(50, 300)
                PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_FONT_SIZE_PX, clamped)
                binding.fontSize.setText(clamped.toString())
                activity.applySettings()
            }
        }
        binding.incrementFontSize.setOnClickListener {
            val value = binding.fontSize.text.toString().toIntOrNull() ?: 100
            val newValue = (value + 5).coerceAtMost(300)
            PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_FONT_SIZE_PX, newValue)
            binding.fontSize.setText(newValue.toString())
            activity.applySettings()
        }
        binding.decrementFontSize.setOnClickListener {
            val value = binding.fontSize.text.toString().toIntOrNull() ?: 100
            val newValue = (value - 5).coerceAtLeast(50)
            PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_FONT_SIZE_PX, newValue)
            binding.fontSize.setText(newValue.toString())
            activity.applySettings()
        }

        binding.useOledTheme.isChecked = settings.useOledTheme
        binding.useOledTheme.setOnCheckedChangeListener { _, isChecked ->
            settings.useOledTheme = isChecked
            activity.applySettings()
        }

        val isBoldFont = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_BOLD_FONT, false)
        binding.boldFont.isChecked = isBoldFont
        binding.boldFont.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_BOLD_FONT, isChecked)
            activity.applySettings()
        }
        val layoutList = listOf(
            binding.paged,
            binding.continuous
        )

        binding.layoutText.text = settings.layout.string
        var selected = layoutList[settings.layout.ordinal]
        selected.alpha = 1f

        layoutList.forEachIndexed { index, imageButton ->
            imageButton.setOnClickListener {
                selected.alpha = 0.33f
                selected = imageButton
                selected.alpha = 1f
                settings.layout = CurrentNovelReaderSettings.Layouts[index]
                    ?: CurrentNovelReaderSettings.Layouts.PAGED
                binding.layoutText.text = settings.layout.string
                activity.applySettings()
            }
        }

        val dualList = listOf(
            binding.dualNo,
            binding.dualAuto,
            binding.dualForce
        )

        binding.dualPageText.text = settings.dualPageMode.toString()
        var selectedDual = dualList[settings.dualPageMode.ordinal]
        selectedDual.alpha = 1f

        dualList.forEachIndexed { index, imageButton ->
            imageButton.setOnClickListener {
                selectedDual.alpha = 0.33f
                selectedDual = imageButton
                selectedDual.alpha = 1f
                settings.dualPageMode = CurrentReaderSettings.DualPageModes[index]
                    ?: CurrentReaderSettings.DualPageModes.Automatic
                binding.dualPageText.text = settings.dualPageMode.toString()
                activity.applySettings()
            }
        }

        binding.lineHeight.setText(settings.lineHeight.toString())
        binding.lineHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.lineHeight.text.toString().toFloatOrNull() ?: 1.4f
                settings.lineHeight = value
                binding.lineHeight.setText(value.toString())
                activity.applySettings()
            }
        }

        binding.incrementLineHeight.setOnClickListener {
            val value = binding.lineHeight.text.toString().toFloatOrNull() ?: 1.4f
            val newValue = Math.round((value + 0.1f) * 10f) / 10f
            settings.lineHeight = newValue
            binding.lineHeight.setText(settings.lineHeight.toString())
            activity.applySettings()
        }

        binding.decrementLineHeight.setOnClickListener {
            val value = binding.lineHeight.text.toString().toFloatOrNull() ?: 1.4f
            val newValue = (Math.round((value - 0.1f) * 10f) / 10f).coerceAtLeast(0.5f)
            settings.lineHeight = newValue
            binding.lineHeight.setText(settings.lineHeight.toString())
            activity.applySettings()
        }

        binding.margin.setText(settings.margin.toString())
        binding.margin.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.margin.text.toString().toFloatOrNull() ?: 0.06f
                settings.margin = value
                binding.margin.setText(value.toString())
                activity.applySettings()
            }
        }

        binding.incrementMargin.setOnClickListener {
            val value = binding.margin.text.toString().toFloatOrNull() ?: 0.06f
            val step = if (value < 0.4f) 0.01f else 0.1f
            val factor = if (value < 0.4f) 100f else 10f
            val newValue = Math.round((value + step) * factor) / factor
            settings.margin = newValue
            binding.margin.setText(settings.margin.toString())
            activity.applySettings()
        }

        binding.decrementMargin.setOnClickListener {
            val value = binding.margin.text.toString().toFloatOrNull() ?: 0.06f
            val step = if (value <= 0.4f) 0.01f else 0.1f
            val factor = if (value <= 0.4f) 100f else 10f
            val newValue = (Math.round((value - step) * factor) / factor).coerceAtLeast(0.01f)
            settings.margin = newValue
            binding.margin.setText(settings.margin.toString())
            activity.applySettings()
        }

        binding.maxInlineSize.setText(settings.maxInlineSize.toString())
        binding.maxInlineSize.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.maxInlineSize.text.toString().toIntOrNull() ?: 720
                settings.maxInlineSize = value
                binding.maxInlineSize.setText(value.toString())
                activity.applySettings()
            }
        }

        binding.incrementMaxInlineSize.setOnClickListener {
            val value = binding.maxInlineSize.text.toString().toIntOrNull() ?: 720
            settings.maxInlineSize = value + 10
            binding.maxInlineSize.setText(settings.maxInlineSize.toString())
            activity.applySettings()
        }

        binding.decrementMaxInlineSize.setOnClickListener {
            val value = binding.maxInlineSize.text.toString().toIntOrNull() ?: 720
            settings.maxInlineSize = value - 10
            binding.maxInlineSize.setText(settings.maxInlineSize.toString())
            activity.applySettings()
        }

        binding.maxBlockSize.setText(settings.maxBlockSize.toString())
        binding.maxBlockSize.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.maxBlockSize.text.toString().toIntOrNull() ?: 720
                settings.maxBlockSize = value
                binding.maxBlockSize.setText(value.toString())
                activity.applySettings()
            }

        }
        binding.incrementMaxBlockSize.setOnClickListener {
            val value = binding.maxBlockSize.text.toString().toIntOrNull() ?: 720
            settings.maxBlockSize = value + 10
            binding.maxBlockSize.setText(settings.maxBlockSize.toString())
            activity.applySettings()
        }

        binding.decrementMaxBlockSize.setOnClickListener {
            val value = binding.maxBlockSize.text.toString().toIntOrNull() ?: 720
            settings.maxBlockSize = value - 10
            binding.maxBlockSize.setText(settings.maxBlockSize.toString())
            activity.applySettings()
        }

        binding.useDarkTheme.isChecked = settings.useDarkTheme
        binding.useDarkTheme.setOnCheckedChangeListener { _, isChecked ->
            settings.useDarkTheme = isChecked
            activity.applySettings()
        }

        binding.keepScreenOn.isChecked = settings.keepScreenOn
        binding.keepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            settings.keepScreenOn = isChecked
            activity.applySettings()
        }

        binding.volumeButton.isChecked = settings.volumeButtons
        binding.volumeButton.setOnCheckedChangeListener { _, isChecked ->
            settings.volumeButtons = isChecked
            activity.applySettings()
        }

        val autoScrollEnabled = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_AUTO_SCROLL, false)
        binding.autoScrollSwitch.isChecked = autoScrollEnabled
        binding.autoScrollSwitch.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_AUTO_SCROLL, isChecked)
            if (isChecked && settings.layout == CurrentNovelReaderSettings.Layouts.PAGED) {
                settings.layout = CurrentNovelReaderSettings.Layouts.SCROLLED
                binding.layoutText.text = settings.layout.string
                selected.alpha = 0.33f
                selected = binding.continuous
                selected.alpha = 1f
            }
            activity.applySettings()
        }

        val autoScrollSpeed = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_AUTO_SCROLL_SPEED, 3f).toFloat()
        binding.autoScrollSpeedSlider.value = autoScrollSpeed.coerceIn(0.5f, 10f)
        binding.autoScrollSpeedText.text = "${autoScrollSpeed}x"
        binding.autoScrollSpeedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_AUTO_SCROLL_SPEED, value)
                binding.autoScrollSpeedText.text = "${value}x"
                activity.autoScroll.speed = value
            }
        }
    }


    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }


    companion object {
        fun newInstance() = NovelReaderSettingsDialogFragment()
        const val TAG = "NovelReaderSettingsDialogFragment"
    }
}
