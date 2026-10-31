package com.saku.dex.ui

import android.content.Context
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
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.saku.dex.model.LauncherApp

@Composable
internal fun AppDrawerRecyclerView(
    apps: List<LauncherApp>,
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
                layoutManager = GridLayoutManager(context, 3)
                this.adapter = adapter
                setHasFixedSize(true)
                itemAnimator = null
                setItemViewCacheSize(18)
                overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
                isNestedScrollingEnabled = false
                addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
                    val widthDp = (right - left) / context.resources.displayMetrics.density
                    val columns = (widthDp / 76f).toInt().coerceIn(2, 8)
                    (layoutManager as? GridLayoutManager)?.spanCount = columns
                }
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        val manager = recyclerView.layoutManager as? GridLayoutManager ?: return
                        val first = manager.findFirstVisibleItemPosition()
                        val firstView = manager.findViewByPosition(first)
                        onScrollChanged(first.coerceAtLeast(0), firstView?.top ?: 0)
                    }
                })
                adapter.submitList(apps) {
                    post {
                        (layoutManager as? GridLayoutManager)
                            ?.scrollToPositionWithOffset(scrollPosition, scrollOffset)
                    }
                }
            }
        },
        update = { recyclerView ->
            (recyclerView.adapter as? LauncherAppAdapter)?.submitList(apps)
        }
    )
}

private class LauncherAppAdapter(
    private val onAppClick: (LauncherApp) -> Unit
) : ListAdapter<LauncherApp, LauncherAppAdapter.AppViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        getItem(position).componentName.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(5), dp(2), dp(5))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(94)
            )
            isFocusable = true
            isClickable = true
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            contentDescription = null
        }
        val label = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(22)
            ).apply { topMargin = dp(5) }
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

        init {
            itemView.setOnClickListener { boundApp?.let(onAppClick) }
        }

        fun bind(app: LauncherApp) {
            boundApp = app
            icon.setImageBitmap(app.icon)
            label.text = app.name
            itemView.contentDescription = app.name
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<LauncherApp>() {
            override fun areItemsTheSame(oldItem: LauncherApp, newItem: LauncherApp): Boolean =
                oldItem.componentName == newItem.componentName

            override fun areContentsTheSame(oldItem: LauncherApp, newItem: LauncherApp): Boolean =
                oldItem.name == newItem.name &&
                    oldItem.packageName == newItem.packageName &&
                    oldItem.icon === newItem.icon
        }
    }
}
