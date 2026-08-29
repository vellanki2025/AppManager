package com.lucid.auto.appstore.data.local

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.pm.PackageInfoCompat

/**
 * The real implementation of [PackageInspector], backed by [PackageManager].
 *
 * ### How LucidAppStore obtains a local app version
 *
 * 1. `packageManager.getPackageInfo(packageName, 0)` returns a `PackageInfo` for an installed app.
 * 2. `PackageInfo.versionName` is the display string ("1.2.0").
 * 3. `PackageInfoCompat.getLongVersionCode(info)` is the comparison key — it reads
 *    `longVersionCode` on API 28+ and falls back to the deprecated `versionCode` below that.
 * 4. `NameNotFoundException` means "not installed", which is a normal outcome and not an error.
 *
 * ### The API 30+ trap
 *
 * Since Android 11, package visibility is filtered. If a package is not declared in the
 * `<queries>` block of AndroidManifest.xml (or the app holds QUERY_ALL_PACKAGES), then
 * `getPackageInfo` throws `NameNotFoundException` **even for an app that is installed**.
 * The symptom is every row showing "Install" on a device where the apps are present.
 * The manifest declares all three managed packages explicitly.
 */
class PackageManagerInspector(
    private val packageManager: PackageManager,
) : PackageInspector {

    constructor(context: Context) : this(context.packageManager)

    override fun getInstalled(packageName: String): InstalledApp? = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val appInfo: ApplicationInfo? = info.applicationInfo
        InstalledApp(
            packageName = packageName,
            label = appInfo?.loadLabel(packageManager)?.toString(),
            versionName = info.versionName.orEmpty(),
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            isSystemApp = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    } catch (e: PackageManager.NameNotFoundException) {
        // Not installed, or filtered out by package visibility. Either way: no local version.
        null
    } catch (e: RuntimeException) {
        // Defensive: a dead PackageManager binder must not take the list down.
        Log.w(TAG, "Failed to read package info for $packageName", e)
        null
    }

    override fun getIcon(packageName: String): ImageBitmap? = try {
        packageManager.getApplicationIcon(packageName).toImageBitmap()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "Failed to read icon for $packageName", e)
        null
    }

    private fun Drawable.toImageBitmap(): ImageBitmap? {
        (this as? BitmapDrawable)?.bitmap?.let { return it.asImageBitmap() }
        val width = intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_ICON_PX
        val height = intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_ICON_PX
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap.asImageBitmap()
    }

    private companion object {
        const val TAG = "PackageInspector"
        const val DEFAULT_ICON_PX = 96
    }
}
