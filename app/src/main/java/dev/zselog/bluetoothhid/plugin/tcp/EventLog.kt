package dev.zselog.bluetoothhid.plugin.tcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {
    private const val MAX_ENTRIES = 100
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val mutableEntries = MutableStateFlow<List<String>>(emptyList())

    val entries: StateFlow<List<String>> = mutableEntries.asStateFlow()

    fun add(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        mutableEntries.update { entries -> (listOf(line) + entries).take(MAX_ENTRIES) }
    }

    fun clear() {
        mutableEntries.value = emptyList()
    }
}
