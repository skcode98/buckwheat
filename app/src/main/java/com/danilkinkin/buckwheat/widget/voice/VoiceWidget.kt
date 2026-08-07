package com.danilkinkin.buckwheat.widget.voice

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
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
import java.text.NumberFormat
import java.util.Locale
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
    val onSurfaceColor = GlanceTheme.colors.onSurface.getColor(context)
    val onSurfaceVariantColor = GlanceTheme.colors.onSurfaceVariant.getColor(context)

    val series = prefs[WidgetReceiver.voiceChartSeriesPreferenceKey]
        ?.split(",")
        ?.mapNotNull { it.toBigDecimalOrNull() }
        ?: emptyList()
    val dailyBudget = prefs[WidgetReceiver.voiceDailyBudgetPreferenceKey]
        ?.toBigDecimalOrNull()
        ?: BigDecimal.ZERO

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
                        val todayBudget = prefs[WidgetReceiver.todayBudgetPreferenceKey]
                            ?.toBigDecimalOrNull()
                        if (todayBudget != null) {
                            val formatted = runCatching {
                                numberFormat(
                                    context,
                                    todayBudget,
                                    ExtendCurrency.getInstance(
                                        prefs[WidgetReceiver.currencyPreferenceKey] ?: "RUB"
                                    ),
                                )
                            }.getOrDefault(todayBudget.toPlainString())
                            CanvasText(
                                modifier = GlanceModifier.fillMaxWidth(),
                                text = formatted,
                                style = TextStyle(
                                    color = GlanceTheme.colors.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                ),
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        VoiceWidgetChart(
                            series = series,
                            dailyBudget = dailyBudget,
                            barColorStart = colorMin,
                            barColorEnd = colorMax,
                            todayBarColor = primaryColor,
                            zeroBarColor = onSurfaceColor.copy(alpha = 0.18f),
                            goalLineColor = onSurfaceVariantColor.copy(alpha = 0.55f),
                            markerColor = Color.White,
                        )
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            CanvasText(
                                text = context.getString(
                                    R.string.voice_widget_chart_min,
                                    compactAmount(minNonZero(series)),
                                ),
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 9.sp,
                                ),
                            )
                            Spacer(modifier = GlanceModifier.defaultWeight())
                            CanvasText(
                                text = context.getString(
                                    R.string.voice_widget_chart_max,
                                    compactAmount(maxNonZero(series)),
                                ),
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 9.sp,
                                ),
                            )
                        }
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
    VoiceFeedbackState.IDLE -> context.getString(R.string.voice_widget_today_caption)
}

private fun minNonZero(series: List<BigDecimal>): BigDecimal =
    series.filter { it.signum() > 0 }.minOrNull() ?: BigDecimal.ZERO

private fun maxNonZero(series: List<BigDecimal>): BigDecimal =
    series.filter { it.signum() > 0 }.maxOrNull() ?: BigDecimal.ZERO

private fun compactAmount(value: BigDecimal): String {
    val formatter = NumberFormat.getNumberInstance(Locale.getDefault())
    formatter.maximumFractionDigits = if (value.abs() < BigDecimal.ONE) 1 else 0
    return formatter.format(value)
}

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

// Mini bar chart of the last 7 days. Heights are fixed dp values computed at composition
// time (Glance 1.1.1 weights are equal-only), so every bar, the goal-guard line and today's
// commit marker can be positioned precisely.
@Composable
@GlanceComposable
private fun VoiceWidgetChart(
    series: List<BigDecimal>,
    dailyBudget: BigDecimal,
    barColorStart: Color,
    barColorEnd: Color,
    todayBarColor: Color,
    zeroBarColor: Color,
    goalLineColor: Color,
    markerColor: Color,
) {
    if (series.isEmpty()) return

    val chartHeight = 20f
    val maxScale = maxOf(dailyBudget, maxNonZero(series)).coerceAtLeast(BigDecimal.ONE)
    val goalFraction = dailyBudget
        .divide(maxScale, 4, RoundingMode.HALF_EVEN)
        .toFloat()

    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(chartHeight.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            series.forEachIndexed { index, value ->
                val fraction = value
                    .divide(maxScale, 4, RoundingMode.HALF_EVEN)
                    .toFloat()
                val isToday = index == series.lastIndex
                val barHeight = if (fraction > 0f) {
                    (chartHeight * fraction).coerceAtLeast(2f)
                } else {
                    0f
                }

                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (barHeight > 0f) {
                        Box(
                            modifier = GlanceModifier
                                .width(4.dp)
                                .height(barHeight.dp)
                                .background(ColorProvider(if (isToday) todayBarColor else combineColors(barColorStart, barColorEnd, fraction)))
                                .cornerRadius(2.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            if (isToday) {
                                Box(
                                    modifier = GlanceModifier
                                        .size(5.dp)
                                        .background(ColorProvider(markerColor))
                                        .cornerRadius(2.5.dp),
                                ) {}
                            }
                        }
                    } else {
                        Box(
                            modifier = GlanceModifier
                                .width(4.dp)
                                .height(2.dp)
                                .background(ColorProvider(zeroBarColor))
                                .cornerRadius(1.dp),
                        ) {}
                    }
                }
            }
        }

        // Goal-guard line at the daily-budget level, drawn over the bars.
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(chartHeight.dp),
        ) {
            val goalTop = (chartHeight * (1f - goalFraction)).coerceIn(0f, chartHeight - 1f)
            Spacer(modifier = GlanceModifier.height(goalTop.dp))
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ColorProvider(goalLineColor)),
            ) {}
            Spacer(modifier = GlanceModifier.height((chartHeight - goalTop - 1f).coerceAtLeast(0f).dp))
        }
    }
}
