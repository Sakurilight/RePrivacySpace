package com.reprivacyspace.app.util

import com.reprivacyspace.app.bean.ConfigData
import com.reprivacyspace.app.bean.SystemUserInfo
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

object JsonHelper {
    private val moshi = Moshi.Builder().build()

    fun configAdapter(): JsonAdapter<ConfigData> {
        return moshi.adapter(ConfigData::class.java)
    }

    fun systemUserInfoListAdapter(): JsonAdapter<List<SystemUserInfo>> {
        return moshi.adapter(
            Types.newParameterizedType(
                List::class.java,
                SystemUserInfo::class.java
            )
        )
    }
}