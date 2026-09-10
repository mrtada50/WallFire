package com.example.netguardlite.data

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val icon: Drawable?,
    val isSystemApp: Boolean
)
