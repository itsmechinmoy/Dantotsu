package ani.dantotsu.media.manga.mangareader

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetCurrentReaderSettingsBinding
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.CurrentReaderSettings.Directions
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.getThemeColor
import com.google.android.material.slider.Slider
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import ani.dantotsu.media.manga.mangareader.webgpu.WebGpuManager

import com.google.android.material.bottomsheet.BottomSheetBehavior

class ReaderSettingsDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetCurrentReaderSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onStart() {
        super.onStart()
        // Lock the bottom sheet to expanded so tab switches don't change its height/position
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (bottomSheet != null) {
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCurrentReaderSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MangaReaderActivity
        val settings = activity.defaultSettings

        // Defensive null safety for settings deserialized from older versions
        settings.imageQuality = settings.imageQuality ?: CurrentReaderSettings.ImageQuality.FAST
        settings.layout = settings.layout ?: CurrentReaderSettings.Layouts.CONTINUOUS
        settings.direction = settings.direction ?: CurrentReaderSettings.Directions.TOP_TO_BOTTOM
        settings.dualPageMode = settings.dualPageMode ?: CurrentReaderSettings.DualPageModes.Automatic

        // Close button
        binding.closeReaderSheet.setOnClickListener { dismiss() }

        // Subtitle indicator
        binding.readerSheetSubtitle.text =
            resources.getStringArray(R.array.manga_layouts)[settings.layout.ordinal]

        // --- SEGMENTED TAB SWITCHING ---
        val tabButtons = listOf(
            binding.tabLayoutBtn,
            binding.tabNavBtn,
            binding.tabDisplayBtn,
            binding.tabDataBtn
        )
        val tabContents = listOf(
            binding.tabLayoutContent,
            binding.tabNavContent,
            binding.tabDisplayContent,
            binding.tabDataContent
        )

        val primaryColor = requireContext().getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        val containerColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
        val unselectedColor = ContextCompat.getColor(requireContext(), R.color.grey_60)

        fun selectTab(selectedIdx: Int) {
            tabButtons.forEachIndexed { idx, btn ->
                if (idx == selectedIdx) {
                    btn.setBackgroundResource(R.drawable.badge_bg_rounded)
                    btn.backgroundTintList = ColorStateList.valueOf(containerColor)
                    btn.setTextColor(primaryColor)
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(unselectedColor)
                }
            }
            tabContents.forEachIndexed { idx, layout ->
                layout.visibility = if (idx == selectedIdx) View.VISIBLE else View.GONE
            }
            binding.readerSettingsScrollView.scrollTo(0, 0)
        }

        tabButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener { selectTab(idx) }
        }

        // ================= TAB 1: LAYOUT =================
        val layoutButtons = listOf(
            binding.readerPaged,
            binding.readerContinuousPaged,
            binding.readerContinuous
        )

        binding.readerPadding.isEnabled = settings.layout.ordinal != 0
        fun paddingAvailable(enable: Boolean) {
            binding.readerPadding.isEnabled = enable
        }

        binding.readerLayoutText.text =
            resources.getStringArray(R.array.manga_layouts)[settings.layout.ordinal]
        var selectedLayoutBtn = layoutButtons[settings.layout.ordinal]
        selectedLayoutBtn.alpha = 1f

        layoutButtons.forEachIndexed { index, imageButton ->
            imageButton.setOnClickListener {
                selectedLayoutBtn.alpha = 0.33f
                selectedLayoutBtn = imageButton
                selectedLayoutBtn.alpha = 1f
                settings.layout =
                    CurrentReaderSettings.Layouts[index] ?: CurrentReaderSettings.Layouts.CONTINUOUS
                val layoutText = resources.getStringArray(R.array.manga_layouts)[settings.layout.ordinal]
                binding.readerLayoutText.text = layoutText
                binding.readerSheetSubtitle.text = layoutText
                activity.applySettings()
                paddingAvailable(settings.layout.ordinal != 0)
            }
        }

        binding.readerDirectionText.text =
            resources.getStringArray(R.array.manga_directions)[settings.direction.ordinal]
        binding.readerDirection.rotation = 90f * (settings.direction.ordinal)
        binding.readerDirection.setOnClickListener {
            settings.direction =
                Directions[settings.direction.ordinal + 1] ?: Directions.TOP_TO_BOTTOM
            binding.readerDirectionText.text =
                resources.getStringArray(R.array.manga_directions)[settings.direction.ordinal]
            binding.readerDirection.rotation = 90f * (settings.direction.ordinal)
            activity.applySettings()
        }

        val dualList = listOf(
            binding.readerDualNo,
            binding.readerDualAuto,
            binding.readerDualForce
        )

        binding.readerDualPageText.text = settings.dualPageMode.toString()
        var selectedDual = dualList[settings.dualPageMode.ordinal]
        selectedDual.alpha = 1f

        dualList.forEachIndexed { index, imageButton ->
            imageButton.setOnClickListener {
                selectedDual.alpha = 0.33f
                selectedDual = imageButton
                selectedDual.alpha = 1f
                settings.dualPageMode = CurrentReaderSettings.DualPageModes[index]
                    ?: CurrentReaderSettings.DualPageModes.Automatic
                binding.readerDualPageText.text = settings.dualPageMode.toString()
                activity.applySettings()
            }
        }

        // Continuous Side Padding Slider
        binding.readerContinuousPaddingSlider.value = settings.continuousSidePadding.toFloat()
        binding.readerContinuousPaddingText.text = "${settings.continuousSidePadding}%"
        binding.readerContinuousPaddingSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val paddingVal = value.toInt()
                settings.continuousSidePadding = paddingVal
                binding.readerContinuousPaddingText.text = "${paddingVal}%"
                PrefManager.setVal(PrefName.ContinuousSidePadding, paddingVal)
                activity.applySidePadding(paddingVal)
            }
        }

        binding.readerPadding.isChecked = settings.padding
        binding.readerPadding.setOnCheckedChangeListener { _, isChecked ->
            settings.padding = isChecked
            activity.applySettings()
        }

        binding.readerCropBorders.isChecked = settings.cropBorders
        binding.readerCropBorders.setOnCheckedChangeListener { _, isChecked ->
            settings.cropBorders = isChecked
            activity.applySettings()
        }

        binding.readerWrapImage.isChecked = settings.wrapImages
        binding.readerWrapImage.setOnCheckedChangeListener { _, isChecked ->
            settings.wrapImages = isChecked
            activity.applySettings()
        }

        // ================= TAB 2: NAVIGATION =================
        binding.readerOneHandZoom.isChecked = settings.oneHandZoom
        binding.readerOneHandZoom.setOnCheckedChangeListener { _, isChecked ->
            settings.oneHandZoom = isChecked
            PrefManager.setVal(PrefName.OneHandZoom, isChecked)
            activity.applyOneHandZoom(isChecked)
        }

        binding.readerAutoScroll.isChecked = settings.autoScroll
        binding.readerAutoScroll.setOnCheckedChangeListener { _, isChecked ->
            settings.autoScroll = isChecked
            activity.updateAutoScrollState(isChecked)
        }

        binding.readerAutoScrollSpeedSlider.value = settings.autoScrollSpeed.toFloat()
        binding.readerAutoScrollSpeedText.text = "${settings.autoScrollSpeed}x"
        binding.readerAutoScrollSpeedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.autoScrollSpeed = value
                binding.readerAutoScrollSpeedText.text = "${value.toInt()}x"
                PrefManager.setVal(PrefName.AutoScrollSpeed, value)
                activity.updateAutoScrollSpeed(value)
            }
        }

        binding.readerOverscroll.isChecked = settings.overScrollMode
        binding.readerOverscroll.setOnCheckedChangeListener { _, isChecked ->
            settings.overScrollMode = isChecked
            activity.applySettings()
        }

        binding.readerVolumeButton.isChecked = settings.volumeButtons
        binding.readerVolumeButton.setOnCheckedChangeListener { _, isChecked ->
            settings.volumeButtons = isChecked
            activity.applySettings()
        }

        binding.readerLongClickImage.isChecked = settings.longClickImage
        binding.readerLongClickImage.setOnCheckedChangeListener { _, isChecked ->
            settings.longClickImage = isChecked
            activity.applySettings()
        }

        // ================= TAB 3: DISPLAY =================
        // Image Quality chips
        val currentQuality = settings.imageQuality ?: CurrentReaderSettings.ImageQuality.FAST
        when (currentQuality) {
            CurrentReaderSettings.ImageQuality.FAST     -> binding.readerImageQualityFast.isChecked = true
            CurrentReaderSettings.ImageQuality.BALANCED -> binding.readerImageQualityBalanced.isChecked = true
            CurrentReaderSettings.ImageQuality.LANCZOS  -> binding.readerImageQualityLanczos.isChecked = true
        }
        binding.readerImageQualityGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val quality = when (checkedIds.firstOrNull()) {
                R.id.readerImageQualityBalanced -> CurrentReaderSettings.ImageQuality.BALANCED
                R.id.readerImageQualityLanczos  -> CurrentReaderSettings.ImageQuality.LANCZOS
                else                            -> CurrentReaderSettings.ImageQuality.FAST
            }
            settings.imageQuality = quality
            PrefManager.setVal(PrefName.ImageQuality, quality.ordinal)
            activity.applySettings()
        }

        // Background Color Chips (0: Auto, 1: Black, 2: Gray, 3: White)
        when (settings.backgroundColor) {
            0 -> binding.readerBgAuto.isChecked = true
            1 -> binding.readerBgBlack.isChecked = true
            2 -> binding.readerBgGray.isChecked = true
            3 -> binding.readerBgWhite.isChecked = true
            else -> binding.readerBgAuto.isChecked = true
        }

        binding.readerBgAuto.setOnClickListener {
            settings.backgroundColor = 0
            PrefManager.setVal(PrefName.ReaderBackgroundColor, 0)
            activity.applyBackgroundColor(0)
        }
        binding.readerBgBlack.setOnClickListener {
            settings.backgroundColor = 1
            PrefManager.setVal(PrefName.ReaderBackgroundColor, 1)
            activity.applyBackgroundColor(1)
        }
        binding.readerBgGray.setOnClickListener {
            settings.backgroundColor = 2
            PrefManager.setVal(PrefName.ReaderBackgroundColor, 2)
            activity.applyBackgroundColor(2)
        }
        binding.readerBgWhite.setOnClickListener {
            settings.backgroundColor = 3
            PrefManager.setVal(PrefName.ReaderBackgroundColor, 3)
            activity.applyBackgroundColor(3)
        }

        // Orientation Lock Chips (0: Free, 1: Portrait, 2: Landscape)
        when (settings.defaultRotation) {
            0 -> binding.readerRotationFree.isChecked = true
            1 -> binding.readerRotationPortrait.isChecked = true
            2 -> binding.readerRotationLandscape.isChecked = true
            else -> binding.readerRotationFree.isChecked = true
        }

        binding.readerRotationFree.setOnClickListener {
            settings.defaultRotation = 0
            PrefManager.setVal(PrefName.DefaultRotation, 0)
            activity.applyOrientationLock(0)
        }
        binding.readerRotationPortrait.setOnClickListener {
            settings.defaultRotation = 1
            PrefManager.setVal(PrefName.DefaultRotation, 1)
            activity.applyOrientationLock(1)
        }
        binding.readerRotationLandscape.setOnClickListener {
            settings.defaultRotation = 2
            PrefManager.setVal(PrefName.DefaultRotation, 2)
            activity.applyOrientationLock(2)
        }

        binding.readerEInkFlash.isChecked = settings.eInkFlash
        binding.readerEInkFlash.setOnCheckedChangeListener { _, isChecked ->
            settings.eInkFlash = isChecked
            PrefManager.setVal(PrefName.EInkFlashPageChange, isChecked)
            activity.saveCurrentSettings()
        }

        binding.readerTrueColors.isChecked = settings.trueColors
        binding.readerTrueColors.setOnCheckedChangeListener { _, isChecked ->
            settings.trueColors = isChecked
            activity.applySettings()
        }

        val isWebGpuSupported = WebGpuManager.isSupported(requireContext())
        if (!isWebGpuSupported) {
            binding.readerWebGpuRenderer.isEnabled = false
            binding.readerWebGpuRenderer.isChecked = false
            binding.readerWebGpuRendererDesc.text = getString(R.string.use_webgpu_renderer_info) + " (Unsupported on this device/OS)"
        } else {
            binding.readerWebGpuRenderer.isChecked = settings.webGpuRenderer
            binding.readerWebGpuRenderer.setOnCheckedChangeListener { _, isChecked ->
                settings.webGpuRenderer = isChecked
                PrefManager.setVal(PrefName.WebGpuRenderer, isChecked)
                activity.saveCurrentSettings()
                activity.applySettings()
            }
        }

        binding.readerImageRotation.isChecked = settings.rotation
        binding.readerImageRotation.setOnCheckedChangeListener { _, isChecked ->
            settings.rotation = isChecked
            activity.applySettings()
        }

        binding.readerKeepScreenOn.isChecked = settings.keepScreenOn
        binding.readerKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            settings.keepScreenOn = isChecked
            activity.applySettings()
        }

        binding.readerHideScrollBar.isChecked = settings.hideScrollBar
        binding.readerHideScrollBar.setOnCheckedChangeListener { _, isChecked ->
            settings.hideScrollBar = isChecked
            activity.applySettings()
        }

        binding.readerHidePageNumbers.isChecked = settings.hidePageNumbers
        binding.readerHidePageNumbers.setOnCheckedChangeListener { _, isChecked ->
            settings.hidePageNumbers = isChecked
            activity.applySettings()
        }

        binding.readerHorizontalScrollBar.isChecked = settings.horizontalScrollBar
        binding.readerHorizontalScrollBar.setOnCheckedChangeListener { _, isChecked ->
            settings.horizontalScrollBar = isChecked
            activity.applySettings()
        }

        // ================= TAB 4: DATA & PRELOAD =================
        binding.readerPreloadSlider.value = settings.preloadAmount.toFloat()
        binding.readerPreloadText.text = "${settings.preloadAmount} Pages"
        binding.readerPreloadSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val amount = value.toInt()
                settings.preloadAmount = amount
                binding.readerPreloadText.text = "${amount} Pages"
                PrefManager.setVal(PrefName.PagePreloadAmount, amount)
                activity.updatePreloadAmount(amount)
            }
        }

        binding.readerAlwaysShowChapterTransition.isChecked = settings.alwaysShowChapterTransition
        binding.readerAlwaysShowChapterTransition.setOnCheckedChangeListener { _, isChecked ->
            settings.alwaysShowChapterTransition = isChecked
            PrefManager.setVal(PrefName.AlwaysShowChapterTransition, isChecked)
            activity.saveCurrentSettings()
        }

        // Data Saver Modes
        val dataSaverModes = listOf(
            binding.dataSaverNone,
            binding.dataSaverBandwidthHero,
            binding.dataSaverWsrvNl
        )
        val dataSaverModeNames = arrayOf(
            getString(R.string.disabled),
            getString(R.string.bandwidth_hero),
            getString(R.string.wsrv_nl)
        )

        binding.dataSaverModeText.text = dataSaverModeNames[settings.dataSaverMode]
        dataSaverModes[settings.dataSaverMode].alpha = 1f

        binding.dataSaverQualitySlider.value = settings.dataSaverImageQuality.toFloat()
        binding.dataSaverIgnoreJpeg.isChecked = settings.dataSaverIgnoreJpeg
        binding.dataSaverIgnoreGif.isChecked = settings.dataSaverIgnoreGif
        binding.dataSaverImageFormat.isChecked = settings.dataSaverImageFormatJpeg

        dataSaverModes.forEachIndexed { index, button ->
            button.setOnClickListener {
                dataSaverModes.forEach { it.alpha = 0.33f }
                button.alpha = 1f
                settings.dataSaverMode = index
                binding.dataSaverModeText.text = dataSaverModeNames[index]
                PrefManager.setVal(PrefName.DataSaverMode, index)
                try { Injekt.get<MangaCache>().clearBitmaps() } catch (_: Exception) {}
                activity.applySettings()
            }
        }

        binding.dataSaverQualitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.dataSaverQualityLabel.text = "${getString(R.string.data_saver_quality)} (${value.toInt()}%)"
            }
        }

        binding.dataSaverQualitySlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val q = slider.value.toInt()
                settings.dataSaverImageQuality = q
                PrefManager.setVal(PrefName.DataSaverImageQuality, q)
                binding.dataSaverQualityLabel.text = "${getString(R.string.data_saver_quality)} ($q%)"
                try { Injekt.get<MangaCache>().clearBitmaps() } catch (_: Exception) {}
                activity.applySettings()
            }
        })

        binding.dataSaverIgnoreJpeg.setOnCheckedChangeListener { _, isChecked ->
            settings.dataSaverIgnoreJpeg = isChecked
            PrefManager.setVal(PrefName.DataSaverIgnoreJpeg, isChecked)
            try { Injekt.get<MangaCache>().clearBitmaps() } catch (_: Exception) {}
            activity.applySettings()
        }

        binding.dataSaverIgnoreGif.setOnCheckedChangeListener { _, isChecked ->
            settings.dataSaverIgnoreGif = isChecked
            PrefManager.setVal(PrefName.DataSaverIgnoreGif, isChecked)
            try { Injekt.get<MangaCache>().clearBitmaps() } catch (_: Exception) {}
            activity.applySettings()
        }

        binding.dataSaverImageFormat.setOnCheckedChangeListener { _, isChecked ->
            settings.dataSaverImageFormatJpeg = isChecked
            PrefManager.setVal(PrefName.DataSaverImageFormatJpeg, isChecked)
            try { Injekt.get<MangaCache>().clearBitmaps() } catch (_: Exception) {}
            activity.applySettings()
        }
    }

    override fun onDestroyView() {
        (activity as? MangaReaderActivity)?.saveCurrentSettings()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = ReaderSettingsDialogFragment()
    }
}
