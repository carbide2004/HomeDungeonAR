package com.memepatrol.core

data class TerminalStatus(
    val calibrated: Boolean = false,
    val currentRoom: String = "Unknown"
)
