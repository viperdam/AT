package com.viperdam.kidsprayer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    // Large corner radius for cards and dialogs
    extraLarge = RoundedCornerShape(28.dp),
    // Medium corner radius for buttons and medium-sized components
    large = RoundedCornerShape(16.dp),
    // Smaller corner radius for chips and small components
    medium = RoundedCornerShape(12.dp),
    // Subtle corner radius for small elements
    small = RoundedCornerShape(8.dp),
    // Extra small corner radius for tiny elements
    extraSmall = RoundedCornerShape(4.dp)
)