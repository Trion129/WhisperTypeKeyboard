package me.trion.whispertype.ime

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import me.trion.whispertype.R

internal class EmojiPanelView(
    private val context: Context,
    private val host: FrameLayout,
) {
    private val density = context.resources.displayMetrics.density

    fun renderBrowse(
        catalog: EmojiCatalog,
        activeGroup: String,
        recents: List<EmojiItem>,
        onSearch: () -> Unit,
        onGroup: (String) -> Unit,
        onEmoji: (EmojiItem) -> Unit,
        onTones: (View, List<String>) -> Unit,
    ) {
        host.removeAllViews()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val searchEntry = TextView(context).apply {
            id = R.id.emoji_search_entry
            hint = context.getString(R.string.emoji_search_hint)
            contentDescription = context.getString(R.string.emoji_search_hint)
            setHintTextColor(ContextCompat.getColor(context, R.color.key_text_secondary))
            setTextColor(ContextCompat.getColor(context, R.color.key_text))
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onSearch() }
        }
        val tabs = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabs.addView(tabRow)
        (listOf(RECENTS_GROUP) + catalog.groups).forEach { group ->
            val tab = TextView(context).apply {
                text = if (group == RECENTS_GROUP) "🕒" else group.substringBefore(' ')
                contentDescription = group
                setTextColor(ContextCompat.getColor(context, R.color.key_text))
                textSize = 12f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { onGroup(group) }
            }
            tabRow.addView(tab)
        }

        val items = if (activeGroup == RECENTS_GROUP) recents else catalog.inGroup(activeGroup)
        column.addView(
            searchEntry,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)),
        )
        column.addView(
            tabs,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)),
        )
        column.addView(
            emojiGrid(items, showNoResults = false, onEmoji, onTones),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        attach(column)
    }

    fun renderSearch(
        query: String,
        results: List<EmojiItem>,
        onBack: () -> Unit,
        onClear: () -> Unit,
        onEmoji: (EmojiItem) -> Unit,
        onTones: (View, List<String>) -> Unit,
    ) {
        host.removeAllViews()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backView = TextView(context).apply {
            id = R.id.emoji_search_back
            text = "‹"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.key_text))
            contentDescription = context.getString(R.string.emoji_search_back)
            setOnClickListener { onBack() }
        }
        val queryView = TextView(context).apply {
            id = R.id.emoji_search_query
            text = query
            hint = context.getString(R.string.emoji_search_hint)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(context, R.color.key_text))
            setHintTextColor(ContextCompat.getColor(context, R.color.key_text_secondary))
            setPadding(dp(4), 0, dp(4), 0)
        }
        val clearView = TextView(context).apply {
            id = R.id.emoji_search_clear
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.key_text))
            contentDescription = context.getString(R.string.emoji_search_clear)
            setOnClickListener { onClear() }
        }
        header.addView(backView, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT))
        header.addView(queryView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        header.addView(clearView, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT))

        column.addView(
            header,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)),
        )
        column.addView(
            emojiGrid(results, showNoResults = query.isNotBlank(), onEmoji, onTones),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        attach(column)
    }

    private fun emojiGrid(
        items: List<EmojiItem>,
        showNoResults: Boolean,
        onEmoji: (EmojiItem) -> Unit,
        onTones: (View, List<String>) -> Unit,
    ): ScrollView {
        val grid = LinearLayout(context).apply {
            id = R.id.emoji_result_grid
            orientation = LinearLayout.VERTICAL
        }
        if (items.isEmpty()) {
            if (showNoResults) {
                grid.addView(TextView(context).apply {
                    val message = context.getString(R.string.emoji_search_no_results)
                    text = message
                    contentDescription = message
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, R.color.key_text_secondary))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
            }
        } else {
            var row: LinearLayout? = null
            items.forEachIndexed { index, item ->
                if (index % GRID_COLUMNS == 0) {
                    row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    grid.addView(
                        row,
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)),
                    )
                }
                val cell = TextView(context).apply {
                    text = item.emoji
                    contentDescription = item.name
                    gravity = Gravity.CENTER
                    textSize = 18f
                    setOnClickListener { onEmoji(item) }
                    if (item.toneCapable) {
                        setOnLongClickListener {
                            onTones(this, item.tones)
                            true
                        }
                    }
                }
                row!!.addView(
                    cell,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
            }
        }
        return ScrollView(context).apply { addView(grid) }
    }

    private fun attach(view: View) {
        host.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val GRID_COLUMNS = 8
        const val RECENTS_GROUP = "recents"
    }
}
