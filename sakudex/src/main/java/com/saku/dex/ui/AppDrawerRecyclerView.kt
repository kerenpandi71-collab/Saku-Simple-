package com.saku.dex.ui

import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.saku.dex.model.LauncherApp

@Composable
internal fun AppDrawerRecyclerView(
    apps: List<LauncherApp>,
    listMode: Boolean,
    scrollPosition: Int,
    scrollOffset: Int,
    onScrollChanged: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    onAppClick: (LauncherApp) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            RecyclerView(context).apply {
                layoutManager = createLayoutManager(context, listMode)
                adapter = LauncherAppAdapter(onAppClick)
                setHasFixedSize(true)
                clipChildren = true
                clipToPadding = true
                setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
                itemAnimator = null
                setItemViewCacheSize(18)
                overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                isNestedScrollingEnabled = false
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        val manager = recyclerView.layoutManager ?: return
                        val first = when (manager) {
                            is GridLayoutManager -> manager.findFirstVisibleItemPosition()
                            is LinearLayoutManager -> manager.findFirstVisibleItemPosition()
                            else -> RecyclerView.NO_POSITION
                        }
                        onScrollChanged(first.coerceAtLeast(0), manager.findViewByPosition(first)?.top ?: 0)
                    }
                })
                (adapter as LauncherAppAdapter).submitList(apps) {
                    post { restorePosition(scrollPosition, scrollOffset) }
                }
            }
        },
        update = { recyclerView ->
            val adapter = recyclerView.adapter as? LauncherAppAdapter ?: return@AndroidView
            adapter.updateCallback(onAppClick)
            if (adapter.listMode != listMode) {
                recyclerView.layoutManager = createLayoutManager(recyclerView.context, listMode)
                adapter.setListMode(listMode)
                recyclerView.post { recyclerView.restorePosition(scrollPosition, scrollOffset) }
            }
            adapter.submitList(apps)
        }
    )
}

private fun createLayoutManager(context: android.content.Context, listMode: Boolean): RecyclerView.LayoutManager {
    if (listMode) return LinearLayoutManager(context)
    val density = context.resources.displayMetrics.density
    val widthDp = context.resources.displayMetrics.widthPixels / density
    val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = (widthDp / 112f).toInt().coerceIn(2, 7)
    return GridLayoutManager(context, if (isLandscape) 4 else columns)
}

private fun RecyclerView.restorePosition(position: Int, offset: Int) {
    when (val manager = layoutManager) {
        is GridLayoutManager -> manager.scrollToPositionWithOffset(position, offset)
        is LinearLayoutManager -> manager.scrollToPositionWithOffset(position, offset)
    }
}

private class LauncherAppAdapter(
    private var onAppClick: (LauncherApp) -> Unit
) : ListAdapter<LauncherApp, LauncherAppAdapter.Holder>(DIFF) {
    var listMode = false
        private set

    init { setHasStableIds(true) }

    fun updateCallback(click: (LauncherApp) -> Unit) {
        onAppClick = click
    }

    fun setListMode(value: Boolean) {
        if (listMode == value) return
        listMode = value
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int) = getItem(position).uniqueId.hashCode().toLong()
    override fun getItemViewType(position: Int) = if (listMode) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val isList = viewType == 1
        val root = LinearLayout(context).apply {
            orientation = if (isList) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (isList) Gravity.CENTER_VERTICAL else Gravity.CENTER
            setPadding(dp(if (isList) 12 else 5), dp(if (isList) 6 else 8), dp(8), dp(if (isList) 6 else 8))
            layoutParams = RecyclerView.LayoutParams(-1, dp(if (isList) 62 else 104))
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(if (isList) 42 else 48), dp(if (isList) 42 else 48))
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                cornerRadius = dp(20).toFloat()
            }
            clipToOutline = true
            elevation = dp(3).toFloat()
        }
        val label = TextView(context).apply {
            gravity = if (isList) Gravity.CENTER_VERTICAL else Gravity.CENTER
            textSize = 12.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = if (isList) LinearLayout.LayoutParams(0, -1, 1f).apply {
                marginStart = dp(14)
            } else LinearLayout.LayoutParams(-1, dp(22)).apply { topMargin = dp(5) }
        }
        root.addView(icon)
        root.addView(label)
        return Holder(root, icon, label)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), onAppClick)
    }

    class Holder(root: LinearLayout, private val icon: ImageView, private val label: TextView) :
        RecyclerView.ViewHolder(root) {
        private var current: LauncherApp? = null
        fun bind(app: LauncherApp, click: (LauncherApp) -> Unit) {
            current = app
            icon.setImageBitmap(app.icon)
            label.text = if (app.isClone) "${app.name} · Klon" else app.name
            itemView.contentDescription = if (app.isClone) "${app.name}, aplikasi klon" else app.name
            itemView.setOnClickListener { current?.let(click) }
            itemView.setOnLongClickListener {
                current?.let { showAppActions(itemView, it) }
                true
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<LauncherApp>() {
            override fun areItemsTheSame(old: LauncherApp, new: LauncherApp) =
                old.uniqueId == new.uniqueId
            override fun areContentsTheSame(old: LauncherApp, new: LauncherApp) =
                old.name == new.name && old.packageName == new.packageName && old.isClone == new.isClone && old.icon === new.icon
        }
    }
}
