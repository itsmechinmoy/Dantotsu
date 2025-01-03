package ani.dantotsu.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityExtensionsBinding
import ani.dantotsu.databinding.DialogRepositoriesBinding
import ani.dantotsu.databinding.ItemRepositoryBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.AndroidBug5497Workaround
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.parsers.ParserTestActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class ExtensionsActivity : AppCompatActivity() {
    lateinit var binding: ActivityExtensionsBinding

    private val animeExtensionManager: AnimeExtensionManager by injectLazy()
    private val mangaExtensionManager: MangaExtensionManager by injectLazy()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityExtensionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        AndroidBug5497Workaround.assistActivity(this) {
            if (it) {
                binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = statusBarHeight
                }
            } else {
                binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = statusBarHeight + navBarHeight
                }
            }
        }

        binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = statusBarHeight + navBarHeight
        }

        binding.testButton.setOnClickListener {
            ContextCompat.startActivity(
                this,
                Intent(this, ParserTestActivity::class.java),
                null
            )
        }

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.offscreenPageLimit = 1

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 6

            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> InstalledAnimeExtensionsFragment()
                    1 -> AnimeExtensionsFragment()
                    2 -> InstalledMangaExtensionsFragment()
                    3 -> MangaExtensionsFragment()
                    4 -> InstalledNovelExtensionsFragment()
                    5 -> NovelExtensionsFragment()
                    else -> AnimeExtensionsFragment()
                }
            }

        }

        val searchView: AutoCompleteTextView = findViewById(R.id.searchViewText)

        tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    searchView.setText("")
                    searchView.clearFocus()
                    tabLayout.clearFocus()
                    if (tab.text?.contains("Installed") == true) binding.languageselect.visibility =
                        View.GONE
                    else binding.languageselect.visibility = View.VISIBLE
                    viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }

                    if (tab.text?.contains("Anime") == true) {
                        generateRepositoryButton(MediaType.ANIME)
                    }
                    if (tab.text?.contains("Manga") == true) {
                        generateRepositoryButton(MediaType.MANGA)
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) {
                    viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    tabLayout.clearFocus()
                }

                override fun onTabReselected(tab: TabLayout.Tab) {
                    viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    // Do nothing
                }
            }
        )

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Installed Anime"
                1 -> "Available Anime"
                2 -> "Installed Manga"
                3 -> "Available Manga"
                4 -> "Installed Novels"
                5 -> "Available Novels"
                else -> null
            }
        }.attach()


        searchView.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val currentFragment =
                    supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
                if (currentFragment is SearchQueryHandler) {
                    currentFragment.updateContentBasedOnQuery(s?.toString()?.trim())
                }
            }
        })

        initActivity(this)
        binding.languageselect.setOnClickListener {
            val languageOptions =
                LanguageMapper.Companion.Language.entries.map { entry ->
                    entry.name.lowercase().replace("_", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }.toTypedArray()
            val listOrder: String = PrefManager.getVal(PrefName.LangSort)
            val index = LanguageMapper.Companion.Language.entries.toTypedArray()
                .indexOfFirst { it.code == listOrder }
            customAlertDialog().apply {
                setTitle("Language")
                singleChoiceItems(languageOptions, index) { selected ->
                    PrefManager.setVal(PrefName.LangSort, LanguageMapper.Companion.Language.entries[selected].code)
                    val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
                    if (currentFragment is SearchQueryHandler) {
                        currentFragment.notifyDataChanged()
                    }
                }
                show()
            }
        }
        binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
    }

    private fun processUserInput(input: String, mediaType: MediaType) {
        val entry = if (input.endsWith("/") || input.endsWith("index.min.json"))
            input.substring(0, input.lastIndexOf("/")) else input
        if (mediaType == MediaType.ANIME) {
            val anime =
                PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).plus(entry)
            PrefManager.setVal(PrefName.AnimeExtensionRepos, anime)
            CoroutineScope(Dispatchers.IO).launch {
                animeExtensionManager.findAvailableExtensions()
            }
        }
        if (mediaType == MediaType.MANGA) {
            val manga =
                PrefManager.getVal<Set<String>>(PrefName.MangaExtensionRepos).plus(entry)
            PrefManager.setVal(PrefName.MangaExtensionRepos, manga)
            CoroutineScope(Dispatchers.IO).launch {
                mangaExtensionManager.findAvailableExtensions()
            }
        }
    }

    private fun getSavedRepositories(repoInventory: ViewGroup, type: MediaType) {
        repoInventory.removeAllViews()
        val prefName: PrefName? = when (type) {
            MediaType.ANIME -> {
                PrefName.AnimeExtensionRepos
            }

            MediaType.MANGA -> {
                PrefName.MangaExtensionRepos
            }

            else -> {
                null
            }
        }
        prefName?.let { repoList ->
            PrefManager.getVal<Set<String>>(repoList).forEach { item ->
                val view = ItemRepositoryBinding.inflate(
                    LayoutInflater.from(repoInventory.context), repoInventory, true
                )
                view.repositoryItem.text = item.removePrefix("https://raw.githubusercontent.com")
                view.repositoryItem.setOnClickListener {
                    customAlertDialog().apply {
                        setTitle(R.string.rem_repository)
                        setMessage(item)
                        setPosButton(R.string.ok) {
                            val repos = PrefManager.getVal<Set<String>>(prefName).minus(item)
                            PrefManager.setVal(prefName, repos)
                            repoInventory.removeView(view.root)
                            CoroutineScope(Dispatchers.IO).launch {
                                when (type) {
                                    MediaType.ANIME -> {
                                        animeExtensionManager.findAvailableExtensions()
                                    }

                                    MediaType.MANGA -> {
                                        mangaExtensionManager.findAvailableExtensions()
                                    }

                                    else -> {}
                                }
                            }
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }
                view.repositoryItem.setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    copyToClipboard(item, true)
                    true
                }
            }
        }
    }

    private fun processEditorAction(editText: EditText, mediaType: MediaType) {
        editText.setOnEditorActionListener { textView, action, keyEvent ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE ||
                (keyEvent?.action == KeyEvent.ACTION_UP
                        && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                return@setOnEditorActionListener if (textView.text.isNullOrBlank()) {
                    false
                } else {
                    processUserInput(textView.text.toString(), mediaType)
                    true
                }
            }
            false
        }
    }

    private fun generateRepositoryButton(type: MediaType) {
        val hintResource: Int? = when (type) {
            MediaType.ANIME -> {
                R.string.anime_add_repository
            }

            MediaType.MANGA -> {
                R.string.manga_add_repository
            }

            else -> {
                null
            }
        }
        hintResource?.let { res ->
            binding.openSettingsButton.setOnClickListener {
                val dialogView = DialogRepositoriesBinding.inflate(
                    LayoutInflater.from(binding.openSettingsButton.context), null, false
                )
                dialogView.repositoryTextBox.hint = getString(res)
                dialogView.repoInventory.apply {
                    getSavedRepositories(this, type)
                }
                processEditorAction(dialogView.repositoryTextBox, type)
                customAlertDialog().apply {
                    setTitle(R.string.edit_repositories)
                    setCustomView(dialogView.root)
                    setPosButton(R.string.add_list) {
                        if (!dialogView.repositoryTextBox.text.isNullOrBlank()) {
                            processUserInput(dialogView.repositoryTextBox.text.toString(), type)
                        }
                    }
                    setNegButton(R.string.close)
                    show()
                }
            }
        }
    }
}

interface SearchQueryHandler {
    fun updateContentBasedOnQuery(query: String?)
    fun notifyDataChanged()
}
