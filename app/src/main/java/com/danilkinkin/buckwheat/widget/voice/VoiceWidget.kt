package com.danilkinkin.buckwheat.widget.voice

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceComposable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.ui.colorGood
import com.danilkinkin.buckwheat.ui.colorMax
import com.danilkinkin.buckwheat.ui.colorMin
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.widget.CanvasText
import com.danilkinkin.buckwheat.widget.LocalAccentColor
import com.danilkinkin.buckwheat.widget.LocalContentColor
import com.danilkinkin.buckwheat.widget.WidgetReceiver
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class VoiceFeedbackState { IDLE, LISTENING, PROCESSING, ADDED }

// Writes the transient input-feedback state to every voice widget and re-renders it. Called
// from the mic tap (with the exact glanceId for an instant response) and from the commit
// service (with glanceId = null, updating all placed voice widgets).
internal suspend fun setVoiceFeedbackState(
    context: Context,
    state: VoiceFeedbackState,
    text: String? = null,
    glanceId: GlanceId? = null,
) {
    val ids = if (glanceId != null) {
        listOf(glanceId)
    } else {
        GlanceAppWidgetManager(context).getGlanceIds(VoiceWidget::class.java)
    }

    ids.forEach { id ->
        updateAppWidgetState(
            context = context,
            definition = PreferencesGlanceStateDefinition,
            glanceId = id,
        ) { preferences ->
            preferences.toMutablePreferences().apply {
                this[WidgetReceiver.voiceFeedbackStatePreferenceKey] = state.name
                if (text != null) {
                    this[WidgetReceiver.voiceFeedbackTextPreferenceKey] = text
                }
            }
        }
        VoiceWidget().update(context, id)
    }
}

private val feedbackResetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

// A few seconds after a successful commit the widget returns to the idle mic so the user can
// immediately add another spend. The reset only fires while the state is still ADDED, so it
// never clobbers a session the user already started.
internal fun scheduleFeedbackReset(context: Context, delayMs: Long = 4_000L) {
    feedbackResetScope.launch {
        delay(delayMs)
        GlanceAppWidgetManager(context).getGlanceIds(VoiceWidget::class.java).forEach { id ->
            updateAppWidgetState(
                context = context,
                definition = PreferencesGlanceStateDefinition,
                glanceId = id,
            ) { preferences ->
                val current = runCatching {
                    VoiceFeedbackState.valueOf(
                        preferences[WidgetReceiver.voiceFeedbackStatePreferenceKey]
                            ?: VoiceFeedbackState.IDLE.name
                    )
                }.getOrDefault(VoiceFeedbackState.IDLE)
                if (current === VoiceFeedbackState.ADDED) {
                    preferences.toMutablePreferences().apply {
                        this[WidgetReceiver.voiceFeedbackStatePreferenceKey] =
                            VoiceFeedbackState.IDLE.name
                    }
                } else {
                    preferences
                }
            }
            VoiceWidget().update(context, id)
        }
    }
}

class VoiceWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    // Surface any composition failure to logcat so a device-only "can't show content" is
    // diagnosable instead of silently showing Glance's opaque error layout.
    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable,
    ) {
        android.util.Log.e("VoiceWidget", "composition error, appWidgetId=$appWidgetId", throwable)
        super.onCompositionError(context, glanceId, appWidgetId, throwable)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                VoiceWidgetContent()
            }
        }
    }
}

@Composable
@GlanceComposable
fun VoiceWidgetContent() {
    val context = LocalContext.current
    val intent = Intent(context, MainActivity::class.java)

    val prefs = currentState<Preferences>()
    val stateBudget = runCatching {
        WidgetReceiver.StateBudget.valueOf(
            prefs[WidgetReceiver.stateBudgetPreferenceKey]
                ?: WidgetReceiver.StateBudget.NOT_SET.name
        )
    }.getOrDefault(WidgetReceiver.StateBudget.NOT_SET)
    val stateSet =
        stateBudget !== WidgetReceiver.StateBudget.NOT_SET &&
            stateBudget !== WidgetReceiver.StateBudget.END_PERIOD

    val feedbackState = runCatching {
        VoiceFeedbackState.valueOf(
            prefs[WidgetReceiver.voiceFeedbackStatePreferenceKey]
                ?: VoiceFeedbackState.IDLE.name
        )
    }.getOrDefault(VoiceFeedbackState.IDLE)

    val primaryColor = GlanceTheme.colors.primary.getColor(context)
    val onSurfaceVariantColor = GlanceTheme.colors.onSurfaceVariant.getColor(context)

    val series = prefs[WidgetReceiver.voiceChartSeriesPreferenceKey]
        ?.split(",")
        ?.mapNotNull { it.toBigDecimalOrNull() }
        ?: emptyList()
    val dailyBudget = prefs[WidgetReceiver.voiceDailyBudgetPreferenceKey]
        ?.toBigDecimalOrNull()
        ?: BigDecimal.ZERO
    val currency = ExtendCurrency.getInstance(
        prefs[WidgetReceiver.currencyPreferenceKey] ?: "RUB"
    )

    CompositionLocalProvider(
        LocalContentColor provides GlanceTheme.colors.onSurface,
        LocalAccentColor provides GlanceTheme.colors.primary,
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(R.drawable.minimal_widget_preview_background))
                .cornerRadius(48.dp),
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                if (stateSet) {
                    Column(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .clickable(actionStartActivity(intent)),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        CanvasText(
                            modifier = GlanceModifier.fillMaxWidth(),
                            text = captionFor(context, prefs, feedbackState),
                            style = TextStyle(
                                color = if (feedbackState === VoiceFeedbackState.ADDED) {
                                    ColorProvider(colorGood)
                                } else {
                                    GlanceTheme.colors.onSurfaceVariant
                                },
                                fontWeight = FontWeight.Medium,
                                fontSize = 10.sp,
                            ),
                        )
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        val todaySpent = series.lastOrNull() ?: BigDecimal.ZERO
                        val dailyRemaining = (dailyBudget - todaySpent).coerceAtLeast(BigDecimal.ZERO)
                        val remainingFraction = if (dailyBudget.signum() > 0) {
                            dailyRemaining
                                .divide(dailyBudget, 4, RoundingMode.HALF_UP)
                                .toFloat()
                                .coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val remainingPercent = (remainingFraction * 100f).roundToInt()
                        CanvasText(
                            modifier = GlanceModifier.fillMaxWidth(),
                            text = "$remainingPercent%",
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            ),
                        )
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        CanvasText(
                            modifier = GlanceModifier.fillMaxWidth(),
                            text = context.getString(
                                R.string.voice_widget_of_daily,
                                runCatching { numberFormat(context, dailyBudget, currency) }
                                    .getOrDefault(dailyBudget.toPlainString()),
                            ),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontWeight = FontWeight.Medium,
                                fontSize = 9.sp,
                            ),
                        )
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        VoiceWidgetProgressBar(
                            fraction = remainingFraction,
                            fillColor = primaryColor,
                            trackColor = onSurfaceVariantColor.copy(alpha = 0.25f),
                        )
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        VoiceWidgetChart(
                            series = series,
                            dailyBudget = dailyBudget,
                            topColor = colorMax,
                            bottomColor = colorMin,
                            todayColor = primaryColor,
                            goalLineColor = onSurfaceVariantColor.copy(alpha = 0.55f),
                            markerColor = Color.White,
                        )
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        CanvasText(
                            modifier = GlanceModifier.fillMaxWidth(),
                            text = context.getString(
                                R.string.voice_widget_today_summary,
                                runCatching { numberFormat(context, dailyBudget, currency) }
                                    .getOrDefault(dailyBudget.toPlainString()),
                                runCatching { numberFormat(context, todaySpent, currency) }
                                    .getOrDefault(todaySpent.toPlainString()),
                                runCatching { numberFormat(context, dailyRemaining, currency) }
                                    .getOrDefault(dailyRemaining.toPlainString()),
                            ),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontWeight = FontWeight.Medium,
                                fontSize = 9.sp,
                            ),
                        )
                    }
                } else {
                    Column(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .clickable(actionStartActivity(intent)),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        CanvasText(
                            modifier = GlanceModifier.fillMaxWidth(),
                            text = context.resources.getString(
                                if (stateBudget === WidgetReceiver.StateBudget.END_PERIOD) {
                                    R.string.finish_period_title
                                } else {
                                    R.string.budget_not_set
                                }
                            ),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            ),
                        )
                    }
                }

                when (feedbackState) {
                    VoiceFeedbackState.LISTENING,
                    VoiceFeedbackState.PROCESSING,
                    -> ListeningButton(context)
                    VoiceFeedbackState.ADDED -> AddedButton(context)
                    VoiceFeedbackState.IDLE -> MicButton(context)
                }
            }
        }
    }
}

private fun captionFor(
    context: Context,
    prefs: Preferences,
    feedbackState: VoiceFeedbackState,
): String = when (feedbackState) {
    VoiceFeedbackState.LISTENING -> context.getString(R.string.voice_listening)
    VoiceFeedbackState.PROCESSING -> context.getString(R.string.voice_processing)
    VoiceFeedbackState.ADDED -> prefs[WidgetReceiver.voiceFeedbackTextPreferenceKey]
        ?: context.getString(R.string.voice_widget_result_title)
    VoiceFeedbackState.IDLE -> context.getString(R.string.voice_widget_left_today)
}

private fun minNonZero(series: List<BigDecimal>): BigDecimal =
    series.filter { it.signum() > 0 }.minOrNull() ?: BigDecimal.ZERO

private fun maxNonZero(series: List<BigDecimal>): BigDecimal =
    series.filter { it.signum() > 0 }.maxOrNull() ?: BigDecimal.ZERO

private fun drawableProvider(context: Context, resId: Int): ImageProvider? =
    ResourcesCompat.getDrawable(context.resources, resId, null)?.let { drawable ->
        ImageProvider(drawable.toBitmap())
    }

@Composable
@GlanceComposable
private fun MicButton(context: Context) {
    Box(
        modifier = GlanceModifier
            .padding(start = 8.dp)
            .size(44.dp)
            .background(GlanceTheme.colors.primary)
            .cornerRadius(22.dp)
            .clickable(actionRunCallback<VoiceWidgetMicCallback>()),
        contentAlignment = Alignment.Center,
    ) {
        val provider = drawableProvider(context, R.drawable.ic_mic)
        if (provider != null) {
            Image(
                modifier = GlanceModifier.size(24.dp),
                provider = provider,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
                contentDescription = null,
            )
        }
    }
}

// Listening/processing: a soft glow ring around the primary circle with an equalizer glyph.
// Glance 1.1.1 has no animations, so the "pulse" is a static ring.
@Composable
@GlanceComposable
private fun ListeningButton(context: Context) {
    Box(
        modifier = GlanceModifier
            .padding(start = 8.dp)
            .size(44.dp)
            .background(ColorProvider(GlanceTheme.colors.primary.getColor(context).copy(alpha = 0.25f)))
            .cornerRadius(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .size(32.dp)
                .background(GlanceTheme.colors.primary)
                .cornerRadius(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val provider = drawableProvider(context, R.drawable.ic_equalizer)
            if (provider != null) {
                Image(
                    modifier = GlanceModifier.size(18.dp),
                    provider = provider,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
                    contentDescription = null,
                )
            }
        }
    }
}

// Successful commit: green circle with a white check. The amount shown in the caption comes
// from the commit service, so it reflects exactly what was just added. Tapping it starts a
// fresh voice input so the user can add another spend immediately.
@Composable
@GlanceComposable
private fun AddedButton(context: Context) {
    Box(
        modifier = GlanceModifier
            .padding(start = 8.dp)
            .size(44.dp)
            .background(ColorProvider(colorGood))
            .cornerRadius(22.dp)
            .clickable(actionRunCallback<VoiceWidgetMicCallback>()),
        contentAlignment = Alignment.Center,
    ) {
        val provider = drawableProvider(context, R.drawable.ic_apply)
        if (provider != null) {
            Image(
                modifier = GlanceModifier.size(26.dp),
                provider = provider,
                colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                contentDescription = null,
            )
        }
    }
}

// Smooth filled-area curve in the style of the app's analytics SpendsChart (the min/max spend
// chart backdrop): cubic-bezier area with a vertical colorMax→colorMin gradient, a dashed
// goal-guard line at the daily-budget level and a "today" marker on the last point. Glance
// 1.1.1 has no path/canvas composables, so the chart is drawn into a bitmap at composition
// time (same technique as CanvasText) and stretched with ContentScale.FillBounds.
@Composable
@GlanceComposable
private fun VoiceWidgetChart(
    series: List<BigDecimal>,
    dailyBudget: BigDecimal,
    topColor: Color,
    bottomColor: Color,
    todayColor: Color,
    goalLineColor: Color,
    markerColor: Color,
) {
    if (series.isEmpty()) return

    val context = LocalContext.current
    val chartHeight = 26f
    val designWidth = 280f

    Image(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(chartHeight.dp),
        provider = ImageProvider(
            drawChartBitmap(
                context = context,
                designWidthDp = designWidth,
                chartHeightDp = chartHeight,
                series = series,
                dailyBudget = dailyBudget,
                topColor = topColor,
                bottomColor = bottomColor,
                todayColor = todayColor,
                goalLineColor = goalLineColor,
                markerColor = markerColor,
            )
        ),
        contentScale = ContentScale.FillBounds,
        contentDescription = null,
    )
}

// Thin rounded progress bar showing how much of today's daily budget is still left. Drawn
// into a bitmap (Glance 1.1.1 `defaultWeight` takes no weight argument) and stretched with
// ContentScale.FillBounds, like the chart.
@Composable
@GlanceComposable
private fun VoiceWidgetProgressBar(
    fraction: Float,
    fillColor: Color,
    trackColor: Color,
) {
    val context = LocalContext.current
    Image(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(3.dp),
        provider = ImageProvider(
            drawProgressBarBitmap(
                context = context,
                widthDp = 280f,
                heightDp = 3f,
                fraction = fraction,
                fillColor = fillColor,
                trackColor = trackColor,
            )
        ),
        contentScale = ContentScale.FillBounds,
        contentDescription = null,
    )
}

private fun drawProgressBarBitmap(
    context: Context,
    widthDp: Float,
    heightDp: Float,
    fraction: Float,
    fillColor: Color,
    trackColor: Color,
): Bitmap {
    val density = context.resources.displayMetrics.density
    val width = (widthDp * density).roundToInt().coerceAtLeast(1)
    val height = (heightDp * density).roundToInt().coerceAtLeast(1)
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    val radius = height / 2f

    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = trackColor.toArgb()
    }
    canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, trackPaint)

    val fillWidth = width.toFloat() * fraction.coerceIn(0f, 1f)
    if (fillWidth > 0f) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor.toArgb()
        }
        canvas.drawRoundRect(0f, 0f, fillWidth, height.toFloat(), radius, radius, fillPaint)
    }

    return bitmap
}

private fun drawChartBitmap(
    context: Context,
    designWidthDp: Float,
    chartHeightDp: Float,
    series: List<BigDecimal>,
    dailyBudget: BigDecimal,
    topColor: Color,
    bottomColor: Color,
    todayColor: Color,
    goalLineColor: Color,
    markerColor: Color,
): Bitmap {
    val density = context.resources.displayMetrics.density
    val width = (designWidthDp * density).roundToInt().coerceAtLeast(1)
    val height = (chartHeightDp * density).roundToInt().coerceAtLeast(1)
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)

    val isNight = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val maxScale = maxOf(dailyBudget, maxNonZero(series)).coerceAtLeast(BigDecimal.ONE)
    val fractions = series.map { fraction ->
        fraction.divide(maxScale, 4, RoundingMode.HALF_EVEN).toFloat().coerceIn(0f, 1f)
    }
    val lastIndex = fractions.size - 1

    val padLeft = 2f * density
    val padRight = 2f * density
    val padTop = 1f * density
    val innerWidth = (width - padLeft - padRight).coerceAtLeast(1f)
    val innerHeight = (height - padTop).coerceAtLeast(1f)

    fun xAt(index: Int): Float =
        if (lastIndex <= 0) padLeft + innerWidth / 2f
        else padLeft + innerWidth * index / lastIndex

    fun yAt(fraction: Float): Float = padTop + innerHeight * (1f - fraction)

    val area = Path()
    var lastY = 0f
    fractions.forEachIndexed { index, fraction ->
        val x = xAt(index)
        val y = yAt(fraction)
        if (index == 0) {
            area.moveTo(x, y)
        } else {
            val controlX = padLeft + innerWidth * (index - 0.5f) / lastIndex
            area.cubicTo(controlX, lastY, controlX, y, x, y)
        }
        lastY = y
    }

    val baselineY = yAt(0f)
    area.lineTo(xAt(lastIndex), baselineY)
    area.lineTo(xAt(0), baselineY)
    area.close()

    val midColor = combineColors(topColor, bottomColor, 0.5f)
    val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(
                topColor.copy(alpha = if (isNight) 0.55f else 0.35f).toArgb(),
                midColor.copy(alpha = if (isNight) 0.38f else 0.20f).toArgb(),
                bottomColor.copy(alpha = if (isNight) 0.20f else 0.08f).toArgb(),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        style = Paint.Style.FILL
    }
    canvas.drawPath(area, areaPaint)

    val goalFraction = dailyBudget
        .divide(maxScale, 4, RoundingMode.HALF_EVEN)
        .toFloat()
        .coerceIn(0f, 1f)
    val goalY = yAt(goalFraction).coerceIn(padTop, height - 1f)
    val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = goalLineColor.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = (1f * density).coerceAtLeast(1f)
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    canvas.drawLine(padLeft, goalY, width - padRight, goalY, goalPaint)

    // Min/max range markers (past days only — today has its own marker below), drawn as a
    // colored ring with a white center: max in the top color, min in the bottom color.
    fun drawRangeMarker(index: Int, rangeColor: Color) {
        val x = xAt(index)
        val y = yAt(fractions[index])
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = rangeColor.toArgb()
        }
        canvas.drawCircle(x, y, 3.5f * density, ring)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = markerColor.toArgb()
        }
        canvas.drawCircle(x, y, 2f * density, dot)
    }

    val pastIndices = series.dropLast(1)
        .mapIndexedNotNull { index, value -> index.takeIf { value.signum() > 0 } }
    if (pastIndices.isNotEmpty()) {
        drawRangeMarker(pastIndices.maxBy { series[it] }, topColor)
        drawRangeMarker(pastIndices.minBy { series[it] }, bottomColor)
    }

    val markerX = xAt(lastIndex)
    val markerY = yAt(fractions.last())

    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = todayColor.copy(alpha = 0.2f).toArgb()
    }
    canvas.drawCircle(markerX, markerY, 8f * density, haloPaint)

    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = todayColor.toArgb()
    }
    canvas.drawCircle(markerX, markerY, 4.5f * density, ringPaint)

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = markerColor.toArgb()
    }
    canvas.drawCircle(markerX, markerY, 3f * density, dotPaint)

    return bitmap
}
