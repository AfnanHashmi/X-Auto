package com.clipcut.ui.theme

import androidx.compose.ui.graphics.Color

// Dark first: every screen in this app is used either at night or over a black video frame, so the
// dark scheme is the real one and the light scheme is a courtesy.
val Accent = Color(0xFF6E9BFF)
val AccentDark = Color(0xFF1B49B8)
val OnAccent = Color(0xFF00133B)

val Ink = Color(0xFF0E1013) // app background
val Surface1 = Color(0xFF16191F) // cards
val Surface2 = Color(0xFF1F242C) // raised rows, chips
val Outline = Color(0xFF39414C)

val TextHigh = Color(0xFFE8EBF0)
val TextLow = Color(0xFF9AA4B2)

val Danger = Color(0xFFFF6B6B)
val DangerContainer = Color(0xFF3A1416)
val OnDangerContainer = Color(0xFFFFD5D5)

val Good = Color(0xFF5BD6A2)

// Light scheme: plain, readable, no attempt at matching the dark one's mood.
val LightAccent = Color(0xFF17489E)
val LightBackground = Color(0xFFF7F8FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE7EAF0)
val LightText = Color(0xFF11151B)
