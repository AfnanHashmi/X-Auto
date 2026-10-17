package com.clipcut.ui.theme

import androidx.compose.ui.graphics.Color

// Dark first: every screen in this app is used either at night or over a black video frame, so the
// dark scheme is the real one and the light scheme is a courtesy.
val Accent = Color(0xFF8FA8FF)
val AccentDark = Color(0xFF26315C)
val OnAccent = Color(0xFF0B1230)

val Ink = Color(0xFF0A0B0F) // app background
val Surface1 = Color(0xFF13151B) // cards
val Surface2 = Color(0xFF1C1F27) // raised rows, chips
val Outline = Color(0xFF2A2E38)
val Hairline = Color(0x1FFFFFFF) // 1dp card borders

val TextHigh = Color(0xFFEDEFF4)
val TextLow = Color(0xFF8E97A6)

val Danger = Color(0xFFFF6B6B)
val DangerContainer = Color(0xFF3A1416)
val OnDangerContainer = Color(0xFFFFD5D5)

val Good = Color(0xFF4FD6A0)
val Gold = Color(0xFFF2C14E) // waiting, dry run, highlights
val OnGold = Color(0xFF2A1F00)

// Light scheme: plain, readable, no attempt at matching the dark one's mood.
val LightAccent = Color(0xFF17489E)
val LightBackground = Color(0xFFF7F8FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE7EAF0)
val LightText = Color(0xFF11151B)
