package com.awd.teledrive.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        addLog("DEBUG", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        android.util.Log.e(tag, message, throwable)
        addLog("ERROR", tag, message + (throwable?.let { "\n${it.stackTraceToString()}" } ?: ""))
    }

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        addLog("INFO", tag, message)
    }

    private fun addLog(level: String, tag: String, message: String) {
        // SimpleDateFormat is not thread-safe — guard every use.
        val time = synchronized(dateFormat) { dateFormat.format(Date()) }
        val logEntry = "[$time] $level/$tag: $message"
        // Atomic read-modify-write: the old read-then-assign raced when two
        // threads logged at once and silently dropped entries.
        _logs.update { current -> (listOf(logEntry) + current).take(500) }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
