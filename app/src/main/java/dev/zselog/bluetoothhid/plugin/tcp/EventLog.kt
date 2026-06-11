package dev.zselog.bluetoothhid.plugin.tcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object EventLog {
    private const val MAX_ENTRIES = 100

    // add() is called concurrently (receivers, service, IO coroutines) — DateTimeFormatter is
    // thread-safe, unlike the SimpleDateFormat it replaced.
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val mutableEntries = MutableStateFlow<List<String>>(emptyList())

    val entries: StateFlow<List<String>> = mutableEntries.asStateFlow()

    fun add(message: String) {
        val line = "${LocalTime.now().format(timeFormat)}  $message"
        mutableEntries.update { entries -> (listOf(line) + entries).take(MAX_ENTRIES) }
    }

    fun clear() {
        mutableEntries.value = emptyList()
    }
}
