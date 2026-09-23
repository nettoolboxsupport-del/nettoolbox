package de.nettoolbox.core.terminal

import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.nettoolbox.vterm.VtermBridge
import de.nettoolbox.vterm.VtermCell
import de.nettoolbox.vterm.VtermCursor
import kotlinx.coroutines.delay

/**
 * High-performance terminal screen renderer.
 *
 * Renders the whole character grid on a single [Canvas] using direct native canvas
 * drawing and [VtermBridge.snapshot] index arithmetic. Never allocates per-cell
 * composables or objects per frame.
 */
@Composable
fun TerminalCanvas(
    vtermPtr: Long?,
    redrawTrigger: Long,
    modifier: Modifier = Modifier,
    onResize: (rows: Int, cols: Int) -> Unit = { _, _ -> },
    onTap: () -> Unit = {},
    // 11sp on a phone in portrait is the point where a standard 80-column line
    // still fits without wrapping on most devices. Larger looks better in a
    // screenshot and is worse to work in: every wrapped line costs a row.
    fontSizeSp: Float = 11f,
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }

    // Pre-configured paint objects for text and cursor
    val textPaint = remember(fontSizePx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = fontSizePx
        }
    }

    val charWidth = remember(textPaint) {
        textPaint.measureText("M").coerceAtLeast(1f)
    }

    val fontMetrics = remember(textPaint) { textPaint.fontMetrics }
    val charHeight = remember(fontMetrics) {
        (fontMetrics.descent - fontMetrics.ascent + fontMetrics.leading).coerceAtLeast(1f)
    }
    val baselineOffset = remember(fontMetrics) { -fontMetrics.ascent }

    var canvasWidth by remember { mutableFloatStateOf(0f) }
    var canvasHeight by remember { mutableFloatStateOf(0f) }
    var measuredRows by remember { mutableIntStateOf(24) }
    var measuredCols by remember { mutableIntStateOf(80) }

    // Cursor blinking state
    var cursorBlinkState by remember { mutableStateOf(true) }
    LaunchedEffect(vtermPtr) {
        while (true) {
            delay(500)
            cursorBlinkState = !cursorBlinkState
        }
    }

    // Allocate reusable snapshot buffer based on current grid geometry (with safety headroom)
    val snapshotBuffer = remember(measuredRows, measuredCols) {
        IntArray(VtermBridge.snapshotSizeFor(measuredRows, measuredCols).coerceAtLeast(VtermBridge.snapshotSizeFor(60, 120)))
    }

    // Pre-allocated character buffer for row-based text rendering (zero allocations in draw loop)
    val lineCharsBuffer = remember { CharArray(256) }

    val defaultBgColor = remember { Color(0xFF101418) }
    val defaultFgColorInt = remember { 0xFFE0E0E0L.toInt() }
    val defaultBgColorInt = remember { 0xFF101418L.toInt() }
    val cursorColor = remember { Color(0xFF8FCFF3) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(defaultBgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .onSizeChanged { size ->
                canvasWidth = size.width.toFloat()
                canvasHeight = size.height.toFloat()
                if (charWidth > 0 && charHeight > 0) {
                    val cols = (size.width / charWidth).toInt().coerceAtLeast(10)
                    val rows = (size.height / charHeight).toInt().coerceAtLeast(4)
                    if (cols != measuredCols || rows != measuredRows) {
                        measuredCols = cols
                        measuredRows = rows
                        onResize(rows, cols)
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read redrawTrigger so Compose invalidates this Canvas whenever a dirty tick occurs
            @Suppress("UNUSED_VARIABLE")
            val trigger = redrawTrigger

            if (vtermPtr == null || vtermPtr == 0L) {
                Log.w("NetToolboxTerminal", "TerminalCanvas: vtermPtr is null or 0!")
                return@Canvas
            }

            val gridCols = VtermBridge.getCols(vtermPtr).takeIf { it > 0 } ?: measuredCols
            val gridRows = VtermBridge.getRows(vtermPtr).takeIf { it > 0 } ?: measuredRows

            val cellsWritten = VtermBridge.snapshot(vtermPtr, snapshotBuffer) ?: 0
            val cursor: VtermCursor? = VtermBridge.cursor(vtermPtr)

            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas

                for (r in 0 until gridRows) {
                    val rowStartIndex = r * gridCols
                    if (rowStartIndex >= cellsWritten) break

                    val rowY = r * charHeight + baselineOffset
                    var lastVisibleCol = -1

                    // First pass: fill lineCharsBuffer and detect non-space characters
                    for (c in 0 until minOf(gridCols, lineCharsBuffer.size)) {
                        val cellIdx = rowStartIndex + c
                        if (cellIdx >= cellsWritten) {
                            lineCharsBuffer[c] = ' '
                            continue
                        }
                        val cp = VtermCell.codePoint(snapshotBuffer, cellIdx)
                        lineCharsBuffer[c] = if (cp in 32..126) cp.toChar() else if (cp > 126) cp.toChar() else ' '
                        if (cp > 32) {
                            lastVisibleCol = c
                        }

                        // Background rect if cell has custom background
                        val bgRgb = VtermCell.backgroundRgb(snapshotBuffer, cellIdx)
                        if (bgRgb != 0 && bgRgb != defaultBgColorInt) {
                            val bgPaintColor = (0xFF000000L).toInt() or (bgRgb and 0x00FFFFFF)
                            textPaint.color = bgPaintColor
                            textPaint.style = Paint.Style.FILL
                            val cellX = c * charWidth
                            val cellY = r * charHeight
                            nativeCanvas.drawRect(cellX, cellY, cellX + charWidth, cellY + charHeight, textPaint)
                        }
                    }

                    // Second pass: render text spans for this row
                    if (lastVisibleCol >= 0) {
                        var c = 0
                        while (c <= lastVisibleCol) {
                            val cellIdx = rowStartIndex + c
                            var fgRgb = VtermCell.foregroundRgb(snapshotBuffer, cellIdx)
                            if (fgRgb == 0) fgRgb = defaultFgColorInt
                            val isBold = VtermCell.hasAttribute(snapshotBuffer, cellIdx, VtermCell.ATTR_BOLD)
                            val isItalic = VtermCell.hasAttribute(snapshotBuffer, cellIdx, VtermCell.ATTR_ITALIC)

                            val spanStart = c
                            val spanFgColor = (0xFF000000L).toInt() or (fgRgb and 0x00FFFFFF)
                            val spanBold = isBold
                            val spanItalic = isItalic

                            // Extend span while attributes match
                            c++
                            while (c <= lastVisibleCol) {
                                val nextIdx = rowStartIndex + c
                                var nextFg = VtermCell.foregroundRgb(snapshotBuffer, nextIdx)
                                if (nextFg == 0) nextFg = defaultFgColorInt
                                val nextFgColor = (0xFF000000L).toInt() or (nextFg and 0x00FFFFFF)
                                val nextBold = VtermCell.hasAttribute(snapshotBuffer, nextIdx, VtermCell.ATTR_BOLD)
                                val nextItalic = VtermCell.hasAttribute(snapshotBuffer, nextIdx, VtermCell.ATTR_ITALIC)

                                if (nextFgColor == spanFgColor && nextBold == spanBold && nextItalic == spanItalic) {
                                    c++
                                } else {
                                    break
                                }
                            }

                            val spanLength = c - spanStart
                            val startX = spanStart * charWidth

                            textPaint.color = spanFgColor
                            textPaint.isFakeBoldText = spanBold
                            textPaint.textSkewX = if (spanItalic) -0.25f else 0f
                            textPaint.style = Paint.Style.FILL

                            nativeCanvas.drawText(
                                lineCharsBuffer,
                                spanStart,
                                spanLength,
                                startX,
                                rowY,
                                textPaint,
                            )
                        }
                    }
                }

                // Draw cursor
                if (cursor != null && cursor.visible && cursorBlinkState) {
                    val cursorX = cursor.col * charWidth
                    val cursorY = cursor.row * charHeight
                    if (cursor.col in 0 until gridCols && cursor.row in 0 until gridRows) {
                        textPaint.color = 0xAA8FCFF3.toInt()
                        textPaint.style = Paint.Style.FILL
                        nativeCanvas.drawRect(
                            cursorX,
                            cursorY,
                            cursorX + charWidth,
                            cursorY + charHeight,
                            textPaint,
                        )
                    }
                }
            }
        }
    }
}
