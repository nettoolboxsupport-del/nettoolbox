package de.nettoolbox.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val NetToolboxTypography = Typography()

/**
 * Style for anything that is machine output or an identifier: ping lines, BSSIDs,
 * cell IDs, hex dumps. A fixed-width font keeps column alignment intact and makes
 * transposed digits in an eCI visible at a glance.
 */
val MonospaceTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)
