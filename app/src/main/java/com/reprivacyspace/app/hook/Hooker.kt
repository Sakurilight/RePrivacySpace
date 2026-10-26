package com.reprivacyspace.app.hook

interface Hooker {
    fun start(classLoader: ClassLoader)

    val Any.packageName: String
        get() = toString().substringAfterLast(" ").substringBefore("/")
}