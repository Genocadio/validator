package com.gocavgo.validator.util

import android.util.Log
import com.gocavgo.validator.BuildConfig
import java.util.concurrent.ConcurrentHashMap

object Logging {
    @Volatile
    private var globalEnabled: Boolean = BuildConfig.DEBUG

    private val tagEnabledOverrides: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()

    fun setGlobalEnabled(enabled: Boolean) {
        globalEnabled = enabled
    }

    fun setTagEnabled(tag: String, enabled: Boolean) {
        tagEnabledOverrides[tag] = enabled
    }

    fun clearTagOverride(tag: String) {
        tagEnabledOverrides.remove(tag)
    }

    fun clearAllOverrides() {
        tagEnabledOverrides.clear()
    }

    fun isEnabled(tag: String): Boolean {
        val tagEnabled = tagEnabledOverrides[tag]
        val effectiveTagEnabled = tagEnabled ?: true
        return BuildConfig.DEBUG && globalEnabled && effectiveTagEnabled
    }

    // Debug
    fun d(tag: String, message: String) {
        if (isEnabled(tag)) Log.d(tag, message)
    }

    fun d(tag: String, message: String, tr: Throwable?) {
        if (isEnabled(tag)) Log.d(tag, message, tr)
    }

    fun d(tag: String, messageProvider: () -> String) {
        if (isEnabled(tag)) Log.d(tag, messageProvider())
    }

    // Info
    fun i(tag: String, message: String) {
        if (isEnabled(tag)) Log.i(tag, message)
    }

    fun i(tag: String, message: String, tr: Throwable?) {
        if (isEnabled(tag)) Log.i(tag, message, tr)
    }

    fun i(tag: String, messageProvider: () -> String) {
        if (isEnabled(tag)) Log.i(tag, messageProvider())
    }

    // Warn
    fun w(tag: String, message: String) {
        if (isEnabled(tag)) Log.w(tag, message)
    }

    fun w(tag: String, message: String, tr: Throwable?) {
        if (isEnabled(tag)) Log.w(tag, message, tr)
    }

    fun w(tag: String, tr: Throwable) {
        if (isEnabled(tag)) Log.w(tag, tr)
    }

    // Error
    fun e(tag: String, message: String) {
        if (isEnabled(tag)) Log.e(tag, message)
    }

    fun e(tag: String, message: String, tr: Throwable?) {
        if (isEnabled(tag)) Log.e(tag, message, tr)
    }

    fun e(tag: String, messageProvider: () -> String) {
        if (isEnabled(tag)) Log.e(tag, messageProvider())
    }

    // Verbose
    fun v(tag: String, message: String) {
        if (isEnabled(tag)) Log.v(tag, message)
    }

    fun v(tag: String, message: String, tr: Throwable?) {
        if (isEnabled(tag)) Log.v(tag, message, tr)
    }
}



