package com.secretchat.app.ui

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

fun formatTimestamp(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        OffsetDateTime.parse(iso).toLocalTime().format(TIME_FORMAT)
    }.getOrDefault("")
}
