package com.bizzarosn.heightmark

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import androidx.annotation.StringRes
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * View host for [StabilityLine], so the layout and [ElevationFragment] keep
 * treating the settling line as an ordinary View with a [setState] call.
 */
class StabilityLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private var readingState by mutableStateOf<ReadingState>(ReadingState.Acquiring)

    // onVisibilityAggregated is the one hook that already folds in GONE,
    // ancestor visibility, and window visibility (screen off / backgrounded)
    private var isVisibleAggregated by mutableStateOf(true)

    fun setState(newState: ReadingState) {
        readingState = newState
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        isVisibleAggregated = isVisible
    }

    @Composable
    override fun Content() {
        StabilityLine(
            state = readingState,
            animate = isVisibleAggregated && ValueAnimator.areAnimatorsEnabled(),
            modifier = Modifier.fillMaxSize()
        )
    }

    companion object {
        // The wave and dormant strokes carry meaning, so they must clear the
        // 3:1 non-text contrast minimum over the day-mode scrim floor;
        // ScrimContrastTest references these constants directly. The glow is
        // a decorative under-stroke beneath the near-opaque core line and is
        // exempt.
        internal const val WAVE_ALPHA = 0.65f
        internal const val CORE_ALPHA = 0.95f
        internal const val GLOW_ALPHA = 0.22f
        internal const val DORMANT_ALPHA = 0.6f
    }
}

/**
 * The settling line: a single kinetic line under the elevation number that
 * shows at a glance how much the reading can be trusted.
 *
 * - Acquiring: a sine wave travels along the full width — searching.
 * - Converging: a bright flat core grows outward from the center while the
 *   wave dies down in the unfilled edges — the line literally flattens as
 *   the average settles.
 * - Stable: a crisp full-width line whose glow breathes slowly.
 * - Dormant: a motionless dotted line — stillness itself signals that the
 *   GPS is off and the number above is frozen.
 *
 * Every state change springs toward its targets, so each transition is a
 * smooth morph. With [animate] false (hidden, or animator scale off on CI
 * emulators and under reduced motion) the line snaps to each state's static
 * frame and requests no frames.
 */
@Composable
internal fun StabilityLine(
    state: ReadingState,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val presentation = presentationFor(state)
    val spec: AnimationSpec<Float> = if (animate) {
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
            visibilityThreshold = EPSILON
        )
    } else {
        snap()
    }
    val amplitude = animateFloatAsState(presentation.amplitude, spec, label = "amplitude")
    val core = animateFloatAsState(presentation.core, spec, label = "core")
    val dormantMix = animateFloatAsState(presentation.dormantMix, spec, label = "dormantMix")

    val pump by remember(animate) {
        derivedStateOf {
            when {
                !animate -> FramePump.None
                amplitude.value > EPSILON && core.value < 1f - EPSILON -> FramePump.Full
                core.value > EPSILON -> FramePump.Slow
                else -> FramePump.None
            }
        }
    }
    var frameMillis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pump) {
        when (pump) {
            FramePump.Full -> while (true) withFrameMillis { frameMillis = it }
            FramePump.Slow -> while (true) {
                withFrameMillis { frameMillis = it }
                delay(SLOW_FRAME_INTERVAL_MS)
            }
            FramePump.None -> Unit
        }
    }

    val description = stringResource(R.string.stability_line_a11y)
    val spokenState = stringResource(presentation.spokenRes)
    val wavePath = remember { Path() }

    // The polite live region turns a stateDescription change into a TalkBack
    // announcement. Semantics report only changed values, so the per-step
    // Converging updates do not re-announce the same phrase.
    Canvas(
        modifier.semantics {
            contentDescription = description
            stateDescription = spokenState
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        val strokeWidth = STROKE_WIDTH.toPx()
        val glowWidth = GLOW_WIDTH.toPx()
        val width = size.width
        val centerY = size.height / 2f
        val inset = glowWidth / 2f
        val active = 1f - dormantMix.value

        if (dormantMix.value > EPSILON) {
            drawLine(
                color = Color.White,
                start = Offset(inset, centerY),
                end = Offset(width - inset, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx())
                ),
                alpha = StabilityLineView.DORMANT_ALPHA * dormantMix.value
            )
        }

        if (active > EPSILON) {
            val centerX = width / 2f
            val coreHalf = core.value * (centerX - inset)

            if (amplitude.value > EPSILON && core.value < 1f - EPSILON) {
                wavePath.buildWave(
                    startX = inset,
                    endX = width - inset,
                    centerY = centerY,
                    amplitudePx = MAX_AMPLITUDE.toPx() * amplitude.value,
                    wavelengthPx = WAVELENGTH.toPx(),
                    stepPx = WAVE_STEP.toPx(),
                    phase = (frameMillis % WAVE_PERIOD_MS).toFloat() / WAVE_PERIOD_MS
                )
                clipRect(
                    left = centerX - coreHalf,
                    right = centerX + coreHalf,
                    clipOp = ClipOp.Difference
                ) {
                    drawPath(
                        wavePath,
                        Color.White,
                        alpha = StabilityLineView.WAVE_ALPHA * active,
                        style = Stroke(strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            if (coreHalf > strokeWidth / 2f) {
                val start = Offset(centerX - coreHalf, centerY)
                val end = Offset(centerX + coreHalf, centerY)
                val breath = 0.875f + 0.125f *
                    sin(2f * PI.toFloat() * (frameMillis % BREATH_PERIOD_MS) / BREATH_PERIOD_MS)
                // Fake glow: a wide translucent under-stroke, tinted glacier so
                // the settled state reads as cold light rather than plain white
                drawLine(
                    GLOW_COLOR, start, end, glowWidth, StrokeCap.Round,
                    alpha = StabilityLineView.GLOW_ALPHA * breath * active
                )
                drawLine(
                    Color.White, start, end, strokeWidth, StrokeCap.Round,
                    alpha = StabilityLineView.CORE_ALPHA * active
                )
            }
        }
    }
}

/**
 * How often [StabilityLine] needs a new frame. Acquiring's traveling wave and
 * Converging's wave edges need every vsync. Stable's breathing glow has a 4 s
 * period, so ~20 fps is ample. Dormant needs none. Springs toward a new
 * target schedule their own frames.
 */
private enum class FramePump { None, Slow, Full }

/** Everything the line derives from a [ReadingState], in one place. */
private data class StatePresentation(
    val amplitude: Float,
    val core: Float,
    val dormantMix: Float,
    @param:StringRes val spokenRes: Int
)

private fun presentationFor(state: ReadingState): StatePresentation = when (state) {
    ReadingState.Acquiring -> StatePresentation(
        amplitude = 1f, core = 0f, dormantMix = 0f,
        spokenRes = R.string.stability_acquiring
    )
    is ReadingState.Converging -> {
        val p = state.visualProgress
        StatePresentation(
            amplitude = 1f - p, core = p, dormantMix = 0f,
            spokenRes = R.string.stability_converging
        )
    }
    ReadingState.Stable -> StatePresentation(
        amplitude = 0f, core = 1f, dormantMix = 0f,
        spokenRes = R.string.stability_stable
    )
    ReadingState.Dormant -> StatePresentation(
        amplitude = 0f, core = 0f, dormantMix = 1f,
        spokenRes = R.string.stability_dormant
    )
}

// Envelope pins both ends so the line reads as a plucked string leveling out
private fun Path.buildWave(
    startX: Float,
    endX: Float,
    centerY: Float,
    amplitudePx: Float,
    wavelengthPx: Float,
    stepPx: Float,
    phase: Float
) {
    val span = endX - startX
    fun waveY(x: Float): Float {
        val envelope = sin(PI.toFloat() * (x - startX) / span)
        return centerY + amplitudePx * envelope * sin(2f * PI.toFloat() * (x / wavelengthPx - phase))
    }
    rewind()
    var x = startX
    moveTo(x, waveY(x))
    while (x < endX) {
        x = (x + stepPx).coerceAtMost(endX)
        lineTo(x, waveY(x))
    }
}

private const val EPSILON = 0.005f
private const val WAVE_PERIOD_MS = 1_400L
private const val BREATH_PERIOD_MS = 4_000L
// The settled state draws a slow breathing glow, which needs no more than ten
// frames a second. At 50 ms it repainted 20 times a second for as long as the
// screen was up, with the reading perfectly still.
private const val SLOW_FRAME_INTERVAL_MS = 100L
/** Glacier tint for the settled glow; same value as hm_tertiary_container. */
private val GLOW_COLOR = Color(0xFFBCEBF2)
private val STROKE_WIDTH = 3.dp
private val GLOW_WIDTH = 9.dp
private val MAX_AMPLITUDE = 6.dp
private val WAVELENGTH = 66.dp
private val WAVE_STEP = 3.dp
private val DASH_ON = 3.dp
private val DASH_OFF = 6.dp

private class ReadingStatePreviews : PreviewParameterProvider<ReadingState> {
    override val values = sequenceOf(
        ReadingState.Acquiring,
        ReadingState.Converging(progress = 0.3f),
        ReadingState.Stable,
        ReadingState.Dormant
    )
}

@Preview(widthDp = 200, heightDp = 24, showBackground = true, backgroundColor = 0xFF37474F)
@Composable
private fun StabilityLinePreview(
    @PreviewParameter(ReadingStatePreviews::class) state: ReadingState
) {
    StabilityLine(state, animate = true)
}
