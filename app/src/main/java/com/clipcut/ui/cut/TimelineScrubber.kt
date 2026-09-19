package com.clipcut.ui.cut

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val TRACK_HEIGHT = 6.dp
private val HANDLE_SIZE = 28.dp
private val LANE_GAP = 4.dp
private val SCRUBBER_HEIGHT = HANDLE_SIZE * 2 + LANE_GAP

/**
 * A DVR timeline spanning `[0, durationMs]` -- whatever the server currently reports, never a
 * hardcoded window -- with a highlighted in/out selection and draggable handles.
 *
 * In/out handles render in separate vertical lanes (in above, out below) rather than both on the
 * track's center line. With a short selection against a long DVR window (the common case here --
 * default is the last ~15s of up to a 2h window) the two handles land on nearly the same pixel;
 * sharing one lane makes it ambiguous which handle a touch should grab. Separate lanes make that
 * unambiguous regardless of how close in/out are in time.
 */
@Composable
fun TimelineScrubber(
    durationMs: Long,
    positionMs: Long,
    inPointMs: Long,
    outPointMs: Long,
    hasSelection: Boolean,
    onSeek: (Long) -> Unit,
    onDragInPoint: (Long) -> Unit,
    onDragOutPoint: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (durationMs <= 0L) {
        Text(
            text = "Loading DVR window...",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(SCRUBBER_HEIGHT)) {
        var widthPx by remember { mutableFloatStateOf(0f) }
        val handleSizePx = with(LocalDensity.current) { HANDLE_SIZE.toPx() }
        val laneGapPx = with(LocalDensity.current) { LANE_GAP.toPx() }
        val msToPx = { ms: Long -> if (widthPx == 0f) 0f else (ms.toFloat() / durationMs) * widthPx }
        val pxToMs = { px: Float -> if (widthPx == 0f) 0L else ((px / widthPx) * durationMs).roundToLong().coerceIn(0, durationMs) }

        // detectDragGestures/detectTapGestures run inside a pointerInput block keyed on Unit, which
        // is set up only once -- closures it captures on that first pass are otherwise frozen and
        // never see later recompositions' values. rememberUpdatedState is the documented fix: reads
        // through it always reflect the latest value regardless of when the closure was created.
        val msToPxLive = rememberUpdatedState(msToPx)
        val pxToMsLive = rememberUpdatedState(pxToMs)
        val onSeekLive = rememberUpdatedState(onSeek)
        val onDragInLive = rememberUpdatedState(onDragInPoint)
        val onDragOutLive = rememberUpdatedState(onDragOutPoint)
        val inPointMsLive = rememberUpdatedState(inPointMs)
        val outPointMsLive = rememberUpdatedState(outPointMs)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(HANDLE_SIZE)
                .align(Alignment.TopStart)
                .offset(y = HANDLE_SIZE / 2 + LANE_GAP / 2)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectTapGestures { offset -> onSeekLive.value(pxToMsLive.value(offset.x)) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onSeekLive.value(pxToMsLive.value(change.position.x))
                    }
                },
        ) {
            val trackY = size.height / 2f
            val trackHeightPx = TRACK_HEIGHT.toPx()
            drawRect(
                color = Color.DarkGray,
                topLeft = Offset(0f, trackY - trackHeightPx / 2f),
                size = Size(size.width, trackHeightPx),
            )
            if (hasSelection) {
                val inPx = msToPx(inPointMs)
                val outPx = msToPx(outPointMs)
                drawRect(
                    color = Color(0xFF4FC3F7),
                    topLeft = Offset(inPx, trackY - trackHeightPx / 2f),
                    size = Size((outPx - inPx).coerceAtLeast(0f), trackHeightPx),
                )
            }
            val posPx = msToPx(positionMs)
            drawLine(
                color = Color.White,
                start = Offset(posPx, 0f),
                end = Offset(posPx, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }

        if (hasSelection) DragHandle(
            xPx = msToPx(inPointMs) - handleSizePx / 2f,
            yPx = 0f,
            label = "IN",
            currentPx = { msToPxLive.value(inPointMsLive.value) },
            onDrag = { newPx -> onDragInLive.value(pxToMsLive.value(newPx)) },
        )
        if (hasSelection) DragHandle(
            xPx = msToPx(outPointMs) - handleSizePx / 2f,
            yPx = handleSizePx + laneGapPx,
            label = "OUT",
            currentPx = { msToPxLive.value(outPointMsLive.value) },
            onDrag = { newPx -> onDragOutLive.value(pxToMsLive.value(newPx)) },
        )
    }
}

@Composable
private fun BoxWithConstraintsScope.DragHandle(
    xPx: Float,
    yPx: Float,
    label: String,
    currentPx: () -> Float,
    onDrag: (Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
            .size(HANDLE_SIZE)
            .clip(CircleShape)
            .background(Color(0xFF4FC3F7))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(currentPx() + dragAmount.x)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 8.sp, color = Color.Black)
    }
}
