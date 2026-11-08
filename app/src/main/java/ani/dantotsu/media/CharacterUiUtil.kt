package ani.dantotsu.media

import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityCharacterBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.appbar.AppBarLayout

object CharacterUiUtil {
    fun setupCharacterView(
        activity: AppCompatActivity,
        binding: ActivityCharacterBinding,
        listener: AppBarLayout.OnOffsetChangedListener
    ): ImageView {
        ThemeManager(activity).applyTheme()
        activity.setContentView(binding.root)

        initActivity(activity)
        if (PrefManager.getVal(PrefName.ImmersiveMode)) activity.window.statusBarColor =
            ContextCompat.getColor(activity, R.color.transparent)

        val banner =
            if (PrefManager.getVal(PrefName.BannerAnimations)) binding.characterBanner else binding.characterBannerNoKen

        banner.updateLayoutParams { height += statusBarHeight }
        binding.characterClose.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.characterCollapsing.minimumHeight = statusBarHeight
        binding.characterCover.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.characterRecyclerView.updatePadding(bottom = 64f.px + navBarHeight)
        binding.characterTitle.isSelected = true
        binding.characterAppBar.addOnOffsetChangedListener(listener)

        binding.characterClose.setOnClickListener {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        return banner
    }
}
