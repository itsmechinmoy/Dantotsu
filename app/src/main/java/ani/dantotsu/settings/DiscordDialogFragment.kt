package ani.dantotsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.databinding.BottomSheetDiscordRpcBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.bumptech.glide.Glide

class DiscordDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetDiscordRpcBinding? = null
    private val binding get() = _binding!!

    private var isMangaTabSelected = false
    private var isLoadingSettings = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDiscordRpcBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup MAL login requirement
        val malEnabled = MAL.token != null
        binding.radioMal.isEnabled = malEnabled
        if (!malEnabled) {
            binding.radioMal.text = "${getString(R.string.discord_mal_mode)} (Login Required)"
        }

        // Listeners for Controls
        binding.switchShowIcon.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            if (isMangaTabSelected) {
                PrefManager.setVal(PrefName.DiscordRPCShowIconManga, isChecked)
            } else {
                PrefManager.setVal(PrefName.DiscordRPCShowIconAnime, isChecked)
            }
            updatePreview()
        }

        binding.radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                binding.radioNothing.id -> "nothing"
                binding.radioDantotsu.id -> "dantotsu"
                binding.radioAnilist.id -> "anilist"
                binding.radioMal.id -> "mal"
                else -> "dantotsu"
            }
            if (isMangaTabSelected) {
                PrefManager.setVal(PrefName.DiscordRPCModeManga, mode)
            } else {
                PrefManager.setVal(PrefName.DiscordRPCModeAnime, mode)
            }
            updatePreview()
        }

        // Setup Tabs
        binding.mediaTypeTabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isMangaTabSelected = checkedId == binding.btnTabManga.id
                loadSettingsForCurrentTab()
                updatePreview()
            }
        }

        // Initialize with Anime tab
        binding.mediaTypeTabGroup.check(binding.btnTabAnime.id)
        isMangaTabSelected = false
        loadSettingsForCurrentTab()
        updatePreview()
    }

    private fun loadSettingsForCurrentTab() {
        isLoadingSettings = true

        val mode = if (isMangaTabSelected) {
            PrefManager.getVal(PrefName.DiscordRPCModeManga, "dantotsu")
        } else {
            PrefManager.getVal(PrefName.DiscordRPCModeAnime, "dantotsu")
        }

        when (mode) {
            "nothing" -> binding.radioNothing.isChecked = true
            "dantotsu" -> binding.radioDantotsu.isChecked = true
            "anilist" -> binding.radioAnilist.isChecked = true
            "mal" -> binding.radioMal.isChecked = true
            else -> binding.radioDantotsu.isChecked = true
        }

        val showIcon = if (isMangaTabSelected) {
            PrefManager.getVal(PrefName.DiscordRPCShowIconManga, true)
        } else {
            PrefManager.getVal(PrefName.DiscordRPCShowIconAnime, true)
        }
        binding.switchShowIcon.isChecked = showIcon

        isLoadingSettings = false
    }

    private fun updatePreview() {
        val mode = if (isMangaTabSelected) PrefManager.getVal(PrefName.DiscordRPCModeManga, "dantotsu")
                   else PrefManager.getVal(PrefName.DiscordRPCModeAnime, "dantotsu")
        val useIcon = if (isMangaTabSelected) PrefManager.getVal(PrefName.DiscordRPCShowIconManga, true)
                      else PrefManager.getVal(PrefName.DiscordRPCShowIconAnime, true)

        // Mock data based on tab
        if (isMangaTabSelected) {
            binding.previewActivityName.text = "Reading One Piece"
            binding.previewDetails.text = "Chapter 1100"
            binding.previewState.text = "Chapter : 1100/??"
        } else {
            binding.previewActivityName.text = "Watching One-Punch Man Season 3"
            binding.previewDetails.text = "Episode 1: Strategy Meeting"
            binding.previewState.text = "Episode : 1/??"
        }

        // Large Image
        Glide.with(this).load(R.mipmap.ic_launcher).into(binding.previewLargeImage)

        // Small Icon
        if (useIcon && mode != "nothing") {
            binding.previewSmallImage.visibility = View.VISIBLE
            val iconUrl = when (mode) {
                "anilist" -> Discord.small_Image_AniList
                "mal" -> Discord.small_Image_MAL
                else -> Discord.small_Image
            }
            Glide.with(this).load(iconUrl).into(binding.previewSmallImage)
        } else {
            binding.previewSmallImage.visibility = View.GONE
        }

        // Buttons
        if (mode == "nothing") {
            binding.previewButton1.visibility = View.GONE
            binding.previewButton2.visibility = View.GONE
        } else {
            binding.previewButton1.visibility = View.VISIBLE
            binding.previewButton2.visibility = View.VISIBLE

            binding.previewButton1.text = when (mode) {
                "mal" -> "VIEW ON MYANIMELIST"
                else -> "VIEW ON ANILIST"
            }

            binding.previewButton2.text = when (mode) {
                "dantotsu" -> "DANTOTSU PROFILE"
                else -> "VIEW PROFILE"
            }
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}