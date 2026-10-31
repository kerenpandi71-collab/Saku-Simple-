package com.saku.dex.ui
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.PopupMenu
import com.saku.dex.data.AppLauncher
import com.saku.dex.model.LauncherApp
internal fun showAppActions(anchor: View, app: LauncherApp) {
    PopupMenu(anchor.context, anchor).apply {
        menu.add("Buka aplikasi").setOnMenuItemClickListener { runCatching { AppLauncher.launch(anchor.context, app) }; true }
        menu.add("Info aplikasi").setOnMenuItemClickListener { openAppInfoAction(anchor.context, app.packageName); true }
        menu.add("Uninstall").setOnMenuItemClickListener { requestAppUninstall(anchor.context, app.packageName); true }
    }.show()
}
internal fun openAppInfoAction(context: Context, packageName: String) {
    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }) }
}
internal fun requestAppUninstall(context: Context, packageName: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DELETE).apply {
        data = Uri.parse("package:$packageName"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }) }
}
