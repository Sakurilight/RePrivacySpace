package com.reprivacyspace.app.hook.impl

import com.reprivacyspace.app.constant.ConfigConstant
import com.reprivacyspace.app.hook.HookMain
import com.reprivacyspace.app.util.XLog

/**
 * The pure decision function: may [callingPackageName] see [targetPackageName]?
 *
 * This runs on the package-manager hot path, so it deliberately touches nothing but the
 * in-memory config snapshot.  The previous implementation resolved shared uids through
 * ServiceManager from inside the filter, which both broke on Android 15+ (the binder object
 * is no longer a PackageManagerService) and risked re-entering the package manager lock.
 * Shared-user information is already merged into the config by [HookMain.updateConfigData].
 */
object HookChecker {

    @JvmStatic
    fun shouldIntercept(
        classLoader: ClassLoader,
        userId: Int,
        targetPackageName: String,
        callingPackageName: String
    ): Boolean {
        if (callingPackageName == targetPackageName) {
            return false
        }
        // The module's own UI must always be able to enumerate and launch hidden apps,
        // even if the user managed to blacklist it by accident.
        if (callingPackageName == com.reprivacyspace.app.BuildConfig.APPLICATION_ID) {
            return false
        }

        val configData = HookMain.configData
        val hiddenApps = configData.hiddenAppList
        val blindApps = configData.blind

        val isHidden = hiddenApps.contains(targetPackageName)
        val callerIsBlind = blindApps?.contains(callingPackageName) == true
        if (!isHidden && !callerIsBlind) {
            return false
        }

        val userWhitelist = configData.whitelist
        val connectedAppsInfoMap = configData.connectedApps
        val multiUserConfig = configData.multiUserConfig ?: emptyMap()
        val defaultWhitelist = ConfigConstant.defaultWhitelist
        val defaultBlindWhitelist = ConfigConstant.defaultBlindWhitelist

        // "Blind" apps must not see anything except the framework defaults and connected apps.
        if (callerIsBlind
            && !defaultBlindWhitelist.contains(targetPackageName)
            && connectedAppsInfoMap[callingPackageName]?.contains(targetPackageName) != true
            && connectedAppsInfoMap[targetPackageName]?.contains(callingPackageName) != true
        ) {
            XLog.d("$callingPackageName was prevented from reading ${targetPackageName} (blind).")
            return true
        }

        if (isHidden && !defaultWhitelist.contains(callingPackageName)) {
            val appMultiUserConfig = multiUserConfig[targetPackageName]
            // User's custom whitelist and 'connected apps'
            if (!userWhitelist.contains(callingPackageName)
                && connectedAppsInfoMap[callingPackageName]?.contains(targetPackageName) != true
                && connectedAppsInfoMap[targetPackageName]?.contains(callingPackageName) != true
                && (appMultiUserConfig.isNullOrEmpty() || appMultiUserConfig.contains(userId))
            ) {
                XLog.d("$callingPackageName was prevented from reading ${targetPackageName}.")
                return true
            } else {
                XLog.d("$callingPackageName read ${targetPackageName}.")
            }
        }
        return false
    }
}
