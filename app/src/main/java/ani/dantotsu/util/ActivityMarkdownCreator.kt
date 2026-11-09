package ani.dantotsu.util

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityMarkdownCreatorBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.AndroidBug5497Workaround
import android.widget.ArrayAdapter
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.MarkwonEditorTextWatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO

class ActivityMarkdownCreator : AppCompatActivity() {
    private lateinit var binding: ActivityMarkdownCreatorBinding
    private lateinit var type: String
    private var text: String = ""
    private var ping: String? = null
    private var parentId: Int = 0
    private var isPreviewMode: Boolean = false

    enum class MarkdownFormat(
        val syntax: String,
        val selectionOffset: Int,
        val imageViewId: Int
    ) {
        BOLD("****", 2, R.id.formatBold),
        ITALIC("**", 1, R.id.formatItalic),
        STRIKETHROUGH("~~~~", 2, R.id.formatStrikethrough),
        SPOILER("~!!~", 2, R.id.formatSpoiler),
        LINK("[Placeholder](%s)", 0, R.id.formatLink),
        IMAGE("img(%s)", 0, R.id.formatImage),
        YOUTUBE("youtube(%s)", 0, R.id.formatYoutube),
        VIDEO("webm(%s)", 0, R.id.formatVideo),
        ORDERED_LIST("1. ", 3, R.id.formatListOrdered),
        UNORDERED_LIST("- ", 2, R.id.formatListUnordered),
        HEADING("# ", 2, R.id.formatTitle),
        CENTERED("~~~~~~", 3, R.id.formatCenter),
        QUOTE("> ", 2, R.id.formatQuote),
        CODE("``", 1, R.id.formatCode),
        UNDERLINE("<u></u>", 4, 0)
    }

    companion object {
        val FORUM_CATEGORIES = listOf(
            1 to "General Discussion",
            2 to "Anime",
            3 to "Manga",
            4 to "Release Discussion",
            5 to "News",
            7 to "Music",
            8 to "Light Novels",
            17 to "Gaming",
            18 to "Visual Novels"
        )
    }

    private class CreatorData(
        val editId: Int,
        val userId: Int,
        val mediaId: Int,
        var selectedCategoryId: Int,
        var isPrivate: Boolean
    )

    private fun extractCreatorData(): CreatorData {
        val initialCategories = intent.getIntegerArrayListExtra("categories")
        return CreatorData(
            editId = intent.getIntExtra("edit", -1),
            userId = intent.getIntExtra("userId", -1),
            mediaId = intent.getIntExtra("mediaId", -1),
            selectedCategoryId = initialCategories?.firstOrNull() ?: 1,
            isPrivate = intent.getBooleanExtra("private", false)
        )
    }

    private fun setupTypeViews(
        data: CreatorData,
        initialSummary: String,
        initialScore: Int,
        initialTitle: String
    ) {
        when (type) {
            "replyActivity" -> if (parentId == -1) {
                toast("Error: No parent ID")
                finish()
            }
            "activity" -> {
                binding.privateCheckbox.visibility = ViewGroup.GONE
            }
            "message" -> {
                if (data.editId == -1) binding.privateCheckbox.visibility = ViewGroup.VISIBLE
            }
            "review" -> setupReviewViews(data, initialSummary, initialScore)
            "thread" -> setupThreadViews(data, initialTitle)
        }
    }

    private fun setupReviewViews(data: CreatorData, initialSummary: String, initialScore: Int) {
        binding.reviewInputsContainer.visibility = ViewGroup.VISIBLE
        binding.privateCheckbox.visibility = ViewGroup.VISIBLE
        if (initialSummary.isNotEmpty()) binding.reviewSummaryEditText.setText(initialSummary)
        if (initialScore > 0) binding.reviewScoreEditText.setText(initialScore.toString())
        binding.privateCheckbox.isChecked = data.isPrivate
        binding.reviewBodyCountText.text = "Review body: ${text.length} / 2200 min characters"
    }

    private fun setupThreadViews(data: CreatorData, initialTitle: String) {
        binding.threadTitleLayout.visibility = ViewGroup.VISIBLE
        binding.threadCategoryLayout.visibility = ViewGroup.VISIBLE
        if (initialTitle.isNotEmpty()) binding.threadTitleEditText.setText(initialTitle)
        val categoryNames = FORUM_CATEGORIES.map { it.second }
        val categoryAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            categoryNames
        )
        binding.threadCategoryAutoComplete.setAdapter(categoryAdapter)
        val defaultCategory = FORUM_CATEGORIES.find { it.first == data.selectedCategoryId }
            ?: FORUM_CATEGORIES.first()
        binding.threadCategoryAutoComplete.setText(defaultCategory.second, false)
        binding.threadCategoryAutoComplete.setOnItemClickListener { _, _, position, _ ->
            data.selectedCategoryId = FORUM_CATEGORIES.getOrNull(position)?.first ?: 1
        }
    }

    private fun validateCreatorInputs(): Boolean {
        if (text.isBlank()) {
            toast(getString(R.string.cannot_be_empty))
            return false
        }
        val summary = binding.reviewSummaryEditText.text.toString().trim()
        val scoreText = binding.reviewScoreEditText.text.toString().trim()
        val titleText = binding.threadTitleEditText.text.toString().trim()

        if (type == "review") {
            if (text.length < 2200) {
                toast(getString(R.string.review_body_min_length_error, text.length))
                return false
            }
            if (summary.length < 20 || summary.length > 120) {
                toast(getString(R.string.review_summary_length_error))
                return false
            }
            val score = scoreText.toIntOrNull()
            if (score == null || score < 0 || score > 100) {
                toast("Please enter a valid score between 0 and 100")
                return false
            }
        } else if (type == "thread") {
            if (titleText.length < 6) {
                toast(getString(R.string.thread_title_length_error))
                return false
            }
        }
        return true
    }

    private suspend fun postActivityMutation(data: CreatorData, isEdit: Boolean): String {
        return if (isEdit) Anilist.mutation.postActivity(text, data.editId) else Anilist.mutation.postActivity(text)
    }

    private suspend fun postReplyActivityMutation(data: CreatorData, isEdit: Boolean): String {
        return if (isEdit) Anilist.mutation.postReply(parentId, text, data.editId) else Anilist.mutation.postReply(parentId, text)
    }

    private suspend fun postMessageMutation(data: CreatorData, isEdit: Boolean): String {
        return if (isEdit) {
            Anilist.mutation.postMessage(data.userId, text, data.editId)
        } else {
            Anilist.mutation.postMessage(data.userId, text, isPrivate = data.isPrivate)
        }
    }

    private suspend fun postReviewMutation(data: CreatorData, isEdit: Boolean): String {
        val score = binding.reviewScoreEditText.text.toString().trim().toIntOrNull() ?: 0
        val summary = binding.reviewSummaryEditText.text.toString().trim()
        val edit = if (isEdit) data.editId else null
        return Anilist.mutation.postReview(
            summary = summary,
            body = text,
            mediaId = data.mediaId,
            score = score,
            edit = edit,
            isPrivate = data.isPrivate
        )
    }

    private suspend fun postThreadMutation(data: CreatorData, isEdit: Boolean): String {
        val titleText = binding.threadTitleEditText.text.toString().trim()
        val mediaCategories = intent.getIntegerArrayListExtra("mediaCategories")
        val edit = if (isEdit) data.editId else null
        return Anilist.mutation.saveThread(
            title = titleText,
            body = text,
            categories = arrayListOf(data.selectedCategoryId),
            mediaCategories = mediaCategories,
            edit = edit
        )
    }

    private suspend fun postThreadCommentMutation(data: CreatorData, isEdit: Boolean): String {
        val parentCommentId = if (parentId != -1) parentId else null
        val threadId = intent.getIntExtra("threadId", -1)
        val edit = if (isEdit) data.editId else null
        return Anilist.mutation.saveThreadComment(
            threadId = threadId,
            comment = text,
            parentCommentId = parentCommentId,
            edit = edit
        )
    }

    private suspend fun updateUserBioMutation(): String {
        val ok = Anilist.mutation.updateUserBio(text)
        return if (ok) "Profile bio updated" else "Failed to update bio"
    }

    private suspend fun executePostMutation(data: CreatorData): String {
        val isEdit = data.editId != -1
        return when (type) {
            "activity" -> postActivityMutation(data, isEdit)
            "review" -> postReviewMutation(data, isEdit)
            "bio" -> updateUserBioMutation()
            "thread" -> postThreadMutation(data, isEdit)
            "threadComment" -> postThreadCommentMutation(data, isEdit)
            "replyActivity" -> postReplyActivityMutation(data, isEdit)
            "message" -> postMessageMutation(data, isEdit)
            else -> "Error: Unknown type"
        }
    }

    private fun showPostConfirmationDialog(data: CreatorData) {
        customAlertDialog().apply {
            setTitle(R.string.warning)
            setMessage(R.string.post_to_anilist_warning)
            setPosButton(R.string.ok) {
                launchIO {
                    val success = executePostMutation(data)
                    val isSuccess = success == getString(R.string.success) ||
                            success == "Success" ||
                            success == "Profile bio updated"
                    withContext(Dispatchers.Main) {
                        toast(success)
                        if (isSuccess) finish()
                    }
                }
            }
            setNeutralButton(R.string.open_rules) {
                openLinkInBrowser("https://anilist.co/forum/thread/14")
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun checkAndLoadBio() {
        if (type == "bio" && text.isBlank()) {
            launchIO {
                val currentBio = Anilist.userid?.let { Anilist.query.getUserRawBio(it) } ?: ""
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (currentBio.isNotBlank() && !isFinishing) {
                        text = currentBio
                        binding.editText.setText(currentBio)
                        binding.editText.setSelection(currentBio.length)
                    }
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityMarkdownCreatorBinding.inflate(layoutInflater)
        binding.markdownCreatorToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.markdownOptionsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }
        setContentView(binding.root)
        AndroidBug5497Workaround.assistActivity(this) {}

        val params = binding.createButton.layoutParams as ViewGroup.MarginLayoutParams
        params.marginEnd = 16 * resources.displayMetrics.density.toInt()
        binding.createButton.layoutParams = params

        if (!intent.hasExtra("type")) {
            toast("Error: No type")
            finish()
            return
        }
        type = intent.getStringExtra("type")!!
        parentId = intent.getIntExtra("parentId", -1)
        val data = extractCreatorData()

        ping = intent.getStringExtra("other")
        text = ping ?: ""
        binding.editText.setText(text)
        binding.editText.addTextChangedListener {
            if (!isPreviewMode) text = it.toString()
            if (type == "review") {
                binding.reviewBodyCountText.text = "Review body: ${it?.length ?: 0} / 2200 min characters"
            }
        }

        setupTypeViews(
            data,
            initialSummary = intent.getStringExtra("summary") ?: "",
            initialScore = intent.getIntExtra("score", 0),
            initialTitle = intent.getStringExtra("title") ?: ""
        )

        binding.privateCheckbox.setOnCheckedChangeListener { _, isChecked ->
            data.isPrivate = isChecked
        }
        checkAndLoadBio()
        previewMarkdown(false)

        binding.markdownCreatorBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.createButton.setOnClickListener {
            if (validateCreatorInputs()) {
                showPostConfirmationDialog(data)
            }
        }

        binding.previewCheckbox.setOnClickListener {
            isPreviewMode = !isPreviewMode
            previewMarkdown(isPreviewMode)
            toast(if (isPreviewMode) "Preview enabled" else "Preview disabled")
        }
        binding.editText.requestFocus()
        setupMarkdownButtons()
    }

    private fun setupMarkdownButtons() {
        MarkdownFormat.entries.forEach { format ->
            if (format.imageViewId != 0) {
                findViewById<ImageView>(format.imageViewId)?.setOnClickListener {
                    applyMarkdownFormat(format)
                }
            }
        }
    }

    private fun applyMarkdownFormat(format: MarkdownFormat) {
        val start = binding.editText.selectionStart
        val end = binding.editText.selectionEnd

        if (start != end) {
            val selectedText = binding.editText.text?.substring(start, end) ?: ""
            val lines = selectedText.split("\n")

            val newText = when (format) {
                MarkdownFormat.UNORDERED_LIST -> {
                    lines.joinToString("\n") { "- $it" }
                }

                MarkdownFormat.ORDERED_LIST -> {
                    lines.mapIndexed { index, line -> "${index + 1}. $line" }.joinToString("\n")
                }

                else -> {
                    if (format.syntax.contains("%s")) {
                        String.format(format.syntax, selectedText)
                    } else {
                        format.syntax.substring(0, format.selectionOffset) +
                                selectedText +
                                format.syntax.substring(format.selectionOffset)
                    }
                }
            }

            binding.editText.text?.replace(start, end, newText)
            binding.editText.setSelection(start + newText.length)
        } else {
            if (format.syntax.contains("%s")) {
                showInputDialog(format, start)
            } else {
                val newText = format.syntax
                binding.editText.text?.insert(start, newText)
                binding.editText.setSelection(start + format.selectionOffset)
            }
        }
    }


    private fun showInputDialog(format: MarkdownFormat, position: Int) {
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            isHintEnabled = true
        }

        val inputEditText = TextInputEditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        inputLayout.addView(inputEditText)

        val container = FrameLayout(this).apply {
            addView(inputLayout)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 0, 0, 0)
        }
        customAlertDialog().apply {
            setTitle("Paste your link here")
            setCustomView(container)
            setPosButton(getString(R.string.ok)) {
                val input = inputEditText.text.toString()
                val formattedText = String.format(format.syntax, input)
                binding.editText.text?.insert(position, formattedText)
                binding.editText.setSelection(position + formattedText.length)
            }
            setNegButton(getString(R.string.cancel))
        }.show()

        inputEditText.requestFocus()
    }

    private fun previewMarkdown(preview: Boolean) {
        val markwon = buildMarkwon(this, false, anilist = true)
        if (preview) {
            binding.editText.isVisible = false
            binding.editText.isEnabled = false
            binding.markdownPreview.isVisible = true
            markwon.setMarkdown(binding.markdownPreview, AniMarkdown.getBasicAniHTML(text))
        } else {
            binding.editText.isVisible = true
            binding.markdownPreview.isVisible = false
            binding.editText.setText(text)
            binding.editText.isEnabled = true
            val markwonEditor = MarkwonEditor.create(markwon)
            binding.editText.addTextChangedListener(
                MarkwonEditorTextWatcher.withProcess(markwonEditor)
            )
        }
    }
}
