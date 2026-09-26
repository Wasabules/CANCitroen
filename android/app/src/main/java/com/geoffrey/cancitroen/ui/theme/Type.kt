package com.geoffrey.cancitroen.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    // Hero (RPM/vitesse en grand)
    displayLarge = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold),
    // Titres de sections
    titleLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp),
    titleMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    // KV
    bodyLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.8.sp),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp),
)
