package com.reprivacyspace.app.bean

import com.reprivacyspace.app.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ConfigData(
    @Json(name = "enableLog")
    val enableDetailLog: Boolean,
    val hiddenAppList: Set<String>,
    val whitelist: Set<String>,
    val connectedApps: Map<String, Set<String>>,
    val sharedUserIdMap: Map<String, String>?,
    val multiUserConfig: Map<String, Set<Int>>?,
    val blind: Set<String>?,
    /** Volume-Up / Volume-Down / Power sequence launches PrivacySpace (1.3.11 feature). */
    @Json(name = "quickLaunch")
    val quickLaunch: Boolean? = null,
) {
    companion object {
        val EMPTY = ConfigData(
            BuildConfig.DEBUG,
            hiddenAppList = emptySet(),
            whitelist = emptySet(),
            connectedApps = emptyMap(),
            sharedUserIdMap = emptyMap(),
            multiUserConfig = emptyMap(),
            blind = emptySet(),
            quickLaunch = false
        )
    }
}
