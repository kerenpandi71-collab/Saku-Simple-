package com.saku.dex.ui

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
            val adapter = LauncherAppAdapter(onAppClick)
            RecyclerView(context).apply {
                layoutManager = createLayoutManager(context, listMode)
                this.adapter = adapter
                setHasFixedSize(true)
                clipChildren = true
                clipToPadding = true
                setPadding(0, (4 * context.resources.displayMetrics.density).toInt(), 0, 0)
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
                        val firstView = manager.findViewByPosition(first)
                        onScrollChanged(first.coerceAtLeast(0), firstView?.top ?: 0)
                    }
                })
                adapter.submitList(apps) {
                    post { restorePosition(listMode, scrollPosition, scrollOffset) }
                }
            }
        },
        update = { recyclerView ->
            val adapter = recyclerView.adapter as? LauncherAppAdapter ?: return@AndroidView
            if (adapter.listMode != listMode) {
                recyclerView.layoutManager = createLayoutManager(recyclerView.context, listMode)
                adapter.setListMode(listMode)
                recyclerView.post { recyclerView.restorePosition(listMode, scrollPosition, scrollOffset) }
            }
            adapter.submitList(apps)
        }
    )
}

private fun createLayoutManager(context: android.content.Context, listMode: Boolean): RecyclerView.LayoutManager {
    if (listMode) return LinearLayoutManager(context)
    val density = context.resources.displayMetrics.density
    val widthDp = context.resources.displayMetrics.widthPixels / density
    val columns = (widthDp / 76f).toInt().coerceIn(2, 8)
    return GridLayoutManager(context, columns)
}

private fun RecyclerView.restorePosition(listMode: Boolean, position: Int, offset: Int) {
    when (val manager = layoutManager) {
        is GridLayoutManager -> manager.scrollToPositionWithOffset(position, offset)
        is LinearLayoutManager -> manager.scrollToPositionWithOffset(position, offset)
    }
}

private class LauncherAppAdapter(
    private val onAppClick: (LauncherApp) -> Unit
) : ListAdapter<LauncherApp, LauncherAppAdapter.AppViewHolder>(DIFF) {

    var listMode: Boolean = false
        private set

    init { setHasStableIds(true) }

    fun setListMode(enabled: Boolean) {
        if (listMode == enabled) return
        listMode = enabled
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long =
        getItem(position).componentName.hashCode().toLong()

    override fun getItemViewType(position: Int): Int = if (listMode) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val isList = viewType == 1

        val container = LinearLayout(context).apply {
            orientation = if (isList) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (isList) Gravity.CENTER_VERTICAL else Gravity.CENTER
            setPadding(dp(if (isList) 12 else 2), dp(5), dp(8), dp(5))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (isList) 60 else 94)
            )
            isFocusable = true
            isClickable = true
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(if (isList) 40 else 48), dp(if (isList) 40 else 48))
            contentDescription = null
        }
        val label = TextView(context).apply {
            gravity = if (isList) Gravity.CENTER_VERTICAL else Gravity.CENTER
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = if (isList) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    .apply { marginStart = dp(14) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22))
                    .apply { topMargin = dp(5) }
            }
        }
        container.addView(icon)
        container.addView(label)
        return AppViewHolder(container, icon, label, onAppClick)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class AppViewHolder(
        itemView: LinearLayout,
        private val icon: ImageView,
        private val label: TextView,
        private val onAppClick: (LauncherApp) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private var boundApp: LauncherApp? = null
        init { itemView.setOnClickListener { boundApp?.let(onAppClick) } }
        fun bind(app: LauncherApp) {
            boundApp = app
            icon.setImageBitmap(app.icon)
            label.text = app.name
            itemView.contentDescription = app.name
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<LauncherApp>() {
            override fun areItemsTheSame(oldItem: LauncherApp, newItem: LauncherApp) =
                oldItem.componentName == newItem.componentName
            override fun areContentsTheSame(oldItem: LauncherApp, newItem: LauncherApp) =
                oldItem.name == newItem.name && oldItem.packageName == newItem.packageName &&
                    oldItem.icon === newItem.icon
        }
    }
}
