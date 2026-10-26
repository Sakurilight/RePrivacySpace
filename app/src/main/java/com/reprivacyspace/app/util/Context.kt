package com.reprivacyspace.app.util

import android.annotation.StringRes
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast


val Context.sp: SharedPreferences
    get() {
        return getSharedPreferences("config", Context.MODE_PRIVATE)
    }

var SharedPreferences.hasReadNotice: Boolean
    get() {
        return getBoolean("readNotice", false)
    }
    set(value) {
        edit().putBoolean("readNotice", value).apply()
    }

var SharedPreferences.hasReadNotice2: Boolean
    get() {
        return getBoolean("readNotice2", false)
    }
    set(value) {
        edit().putBoolean("readNotice2", value).apply()
    }

var SharedPreferences.iconCellCountLandscape: Int
    get() {
        return getInt("iconCellCountLandscape", 8)
    }
    set(value) {
        edit().putInt("iconCellCountLandscape", value).apply()
    }

var SharedPreferences.iconPaddingLandscape: Float
    get() {
        return getFloat("iconPaddingLandscape", 0.5f)
    }
    set(value) {
        edit().putFloat("iconPaddingLandscape", value).apply()
    }

var SharedPreferences.iconCellCountPortrait: Int
    get() {
        return getInt("iconCellPortrait", 4)
    }
    set(value) {
        edit().putInt("iconCellPortrait", value).apply()
    }

var SharedPreferences.iconPaddingPortrait: Float
    get() {
        return getFloat("iconPaddingPortrait", 0.5f)
    }
    set(value) {
        edit().putFloat("iconPaddingPortrait", value).apply()
    }

fun Context.showToast(@StringRes textRes: Int) {
    showToast(getString(textRes))
}

fun Context.showToast(text: String) {
    Toast.makeText(
        this,
        String.format(text),
        Toast.LENGTH_SHORT
    ).show()
}

fun Context.openUrl(url: String) {
    val uri = Uri.parse(url)
    val intent = Intent(Intent.ACTION_VIEW, uri)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

/**
 * Launches an installed package from the module UI.
 *
 * `getLaunchIntentForPackage()` is the normal path, but on Android 15+ it can return null
 * when the package-manager visibility filter (ours, HMA-OSS', or the ROM's) hides the
 * launcher activity from the query.  Two fallbacks keep the icons usable: an explicit
 * MAIN/LAUNCHER component, then a root `am start` for ROMs that refuse both.
 */
fun Context.launchApp(packageName: String): Boolean {
    android.util.Log.i("PrivacySpace", "launchApp($packageName) begin")
    val pm = packageManager

    // Preferred: let system_server start an explicit component.  This is the same code path
    // as the Volume+/Volume-/Power quick launch, which is not subject to the app-side
    // package visibility filter that can make getLaunchIntentForPackage() return null.
    try {
        if (ConfigClient(this).launchApp(packageName)) {
            android.util.Log.i("PrivacySpace", "launched $packageName via system_server")
            return true
        }
        android.util.Log.w("PrivacySpace", "system_server launch of $packageName refused")
    } catch (t: Throwable) {
        android.util.Log.e("PrivacySpace", "system_server launch of $packageName failed", t)
    }

    val launchIntent = pm.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
        android.util.Log.i(
            "PrivacySpace",
            "launchIntent($packageName) = ${launchIntent.component}"
        )
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startActivitySafely(launchIntent, packageName)) return true
    } else {
        android.util.Log.w("PrivacySpace", "getLaunchIntentForPackage($packageName) == null")
    }

    // Client-side explicit component, resolved through MAIN/LAUNCHER.
    val activityInfo = runCatching {
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName),
            0
        ).firstOrNull()?.activityInfo
    }.getOrNull()
    if (activityInfo != null) {
        android.util.Log.i(
            "PrivacySpace",
            "explicit($packageName) = ${activityInfo.name}"
        )
        val explicit = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(activityInfo.packageName, activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startActivitySafely(explicit, packageName)) return true
    }

    // Last resort: root shell, for ROMs where even system_server refuses.
    val activity = activityInfo?.name
    return try {
        val command = if (activity != null) {
            "am start -n $packageName/$activity"
        } else {
            "am start -a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER -p $packageName"
        }
        Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
    } catch (t: Throwable) {
        android.util.Log.e("PrivacySpace", "root launch of $packageName failed", t)
        false
    }
}

private fun Context.startActivitySafely(intent: Intent, packageName: String): Boolean {
    return try {
        startActivity(intent)
        true
    } catch (t: Throwable) {
        android.util.Log.e("PrivacySpace", "startActivity($packageName) failed", t)
        false
    }
}

/** Component of the launcher alias declared in AndroidManifest.xml. */
private fun Context.launcherAlias(): ComponentName {
    return ComponentName(packageName, "$packageName.LauncherAlias")
}

/** True unless the launcher alias has been explicitly disabled. */
fun Context.isLauncherIconVisible(): Boolean {
    return try {
        packageManager.getComponentEnabledSetting(launcherAlias()) !=
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (t: Throwable) {
        true
    }
}

/**
 * Shows or hides the launcher icon.  MainActivity itself stays enabled, so quick launch,
 * LSPosed and `adb shell am start -n com.reprivacyspace.app/.ui.main.MainActivity` keep working
 * while the icon is hidden.
 */
fun Context.setLauncherIconVisible(visible: Boolean): Boolean {
    return try {
        packageManager.setComponentEnabledSetting(
            launcherAlias(),
            if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        true
    } catch (t: Throwable) {
        android.util.Log.e("PrivacySpace", "could not change launcher icon state", t)
        false
    }
}
