package com.danilkinkin.buckwheat.widget.category

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceComposable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.analytics.categoriesChart.baseColors
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.CATEGORY_CAP_NEAR_PERCENT
import com.danilkinkin.buckwheat.util.HarmonizedColorPalette
import com.danilkinkin.buckwheat.util.harmonizeWithColor
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.toPaletteWithTheme
import com.danilkinkin.buckwheat.widget.CanvasText
import com.danilkinkin.buckwheat.widget.WidgetReceiver
import com.danilkinkin.buckwheat.widget.toColorProvider
import kotlin.math.roundToInt

enum class CategoryWidgetDesign { BATTERY, COMPACT }

// The design a category widget instance should render: its own per-instance override when set
// (non-blank), otherwise the global "Category widget design" setting. Blank is the "follow
// global" marker written by the per-instance configuration flow. Pure so it is trivially
// unit-testable.
fun effectiveCategoryWidgetDesign(overrideName: String?, globalName: String): String =
    overrideName?.takeIf { it.isNotBlank() } ?: globalName

private val batteryAmber = Color(0xFFE6A23C)
private val batteryAmberText = Color(0xFF1A1A1A)

class CategoryWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    companion object {
        val smallMode = DpSize(220.dp, 110.dp)
        val mediumMode = DpSize(220.dp, 160.dp)
        val largeMode = DpSize(220.dp, 240.dp)
        val hugeMode = DpSize(220.dp, 400.dp)

        const val PILL_HEIGHT_DP = 24f
        const val CONTENT_WIDTH_DP = 204f
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(smallMode, mediumMode, largeMode, hugeMode)
    )

    // Surface any composition failure to logcat so a device-only "can't show content" is
    // diagnosable instead of silently showing Glance's opaque error layout.
    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable,
    ) {
        android.util.Log.e("CategoryWidget", "composition error, appWidgetId=$appWidgetId", throwable)
        super.onCompositionError(context, glanceId, appWidgetId, throwable)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                CategoryWidgetContent()
            }
        }
    }
}

@Composable
@GlanceComposable
fun CategoryWidgetContent() {
    val size = LocalSize.current
    val context = LocalContext.current
    val intent = Intent(context, MainActivity::class.java)

    val prefs = currentState<Preferences>()
    val pills = parseCategoryWidgetPills(prefs[WidgetReceiver.categoryRowsPreferenceKey])
    val headerRaw = prefs[WidgetReceiver.categoryHeaderPreferenceKey]
    val (headerTotal, headerPercent) = headerRaw?.let {
        val parts = it.split(';', limit = 2)
        (parts.getOrNull(0)?.toBigDecimalOrNull()) to (parts.getOrNull(1)?.toIntOrNull())
    } ?: (null to null)
    val currency = ExtendCurrency.getInstance(prefs[WidgetReceiver.currencyPreferenceKey] ?: "RUB")

    val widgetDesign = runCatching {
        CategoryWidgetDesign.valueOf(
            prefs[WidgetReceiver.categoryDesignPreferenceKey] ?: CategoryWidgetDesign.BATTERY.name
        )
    }.getOrDefault(CategoryWidgetDesign.BATTERY)
    val isCompact = widgetDesign == CategoryWidgetDesign.COMPACT

    val primaryColor = GlanceTheme.colors.primary.getColor(context)
    val contentColor = GlanceTheme.colors.onSurface.getColor(context)
    val secondaryColor = GlanceTheme.colors.onSurfaceVariant.getColor(context)
    val backgroundColor = GlanceTheme.colors.background.getColor(context)
    val isNight = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val paletteColors = baseColors.map { base ->
        toPaletteWithTheme(harmonizeWithColor(base, primaryColor), isNight)
    }
    val restColor = toPaletteWithTheme(harmonizeWithColor(Color(0xFF222222), primaryColor), isNight)

    val maxRows = when (size) {
        CategoryWidget.smallMode -> 2
        CategoryWidget.mediumMode -> 3
        CategoryWidget.largeMode -> 5
        else -> Int.MAX_VALUE
    }
    val visiblePills = pills.take(maxRows)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(backgroundColor)
            .cornerRadius(16.dp)
            .padding(8.dp)
            .clickable(actionStartActivity(intent)),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            CanvasText(
                text = context.getString(R.string.categories_title),
                style = TextStyle(
                    color = contentColor.toColorProvider(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                ),
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            if (headerTotal != null) {
                Column(horizontalAlignment = Alignment.Horizontal.End) {
                    CanvasText(
                        modifier = GlanceModifier.width(84.dp),
                        text = runCatching {
                            numberFormat(context, headerTotal, currency)
                        }.getOrDefault(headerTotal.toPlainString()),
                        style = TextStyle(
                            color = contentColor.toColorProvider(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Right,
                        ),
                    )
                    if (headerPercent != null) {
                        CanvasText(
                            modifier = GlanceModifier.width(84.dp),
                            text = context.getString(
                                R.string.category_widget_budget_percent,
                                headerPercent,
                            ),
                            style = TextStyle(
                                color = secondaryColor.toColorProvider(),
                                fontWeight = FontWeight.Medium,
                                fontSize = 8.sp,
                                textAlign = TextAlign.Right,
                            ),
                        )
                    }
                }
            }
        }

        if (visiblePills.isEmpty()) {
            Spacer(modifier = GlanceModifier.defaultWeight())
            CanvasText(
                modifier = GlanceModifier.fillMaxWidth(),
                text = context.getString(R.string.category_widget_empty),
                style = TextStyle(
                    color = secondaryColor.toColorProvider(),
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
        } else {
            visiblePills.forEach { pill ->
                Spacer(modifier = GlanceModifier.height(4.dp))
                CategoryPill(
                    context = context,
                    pill = pill,
                    paletteColors = paletteColors,
                    restColor = restColor,
                    isCompact = isCompact,
                    currency = currency,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                )
            }
        }
    }
}

// A battery pill like the analytics CategoryBatteryChip: full-width rounded track (the base
// color at low alpha) with a fill layer covering the left `fraction` and the emoji + name +
// amount + percent text rendered in a contrast color over the filled part. Glance 1.1.1 has
// no clip/canvas composables, so the whole pill is drawn into a bitmap at composition time
// (same technique as the voice widget chart/progress bar) and stretched with FillBounds.
@Composable
@GlanceComposable
private fun CategoryPill(
    context: Context,
    pill: CategoryWidgetPill,
    paletteColors: List<HarmonizedColorPalette>,
    restColor: HarmonizedColorPalette,
    isCompact: Boolean,
    currency: ExtendCurrency,
    primaryColor: Color,
    secondaryColor: Color,
) {
    val baseColor = if (pill.isSpecial) {
        restColor.main
    } else {
        paletteColors[pill.colorIndex % paletteColors.size].main
    }
    val percent = pill.percent
    val fillColor = when {
        percent >= 100 -> GlanceTheme.colors.error.getColor(context)
        percent >= CATEGORY_CAP_NEAR_PERCENT -> batteryAmber
        else -> baseColor
    }
    val fillTextColor = when {
        percent >= 100 -> Color.White
        percent >= CATEGORY_CAP_NEAR_PERCENT -> batteryAmberText
        else -> if (pill.isSpecial) {
            restColor.onSurface
        } else {
            paletteColors[pill.colorIndex % paletteColors.size].onSurface
        }
    }
    val amountText = runCatching {
        numberFormat(context, pill.used, currency)
    }.getOrDefault(pill.used.toPlainString())
    val percentText = if (pill.cap != null && !isCompact) "$percent%" else ""

    Image(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(CategoryWidget.PILL_HEIGHT_DP.dp),
        provider = ImageProvider(
            drawCategoryPillBitmap(
                context = context,
                widthDp = CategoryWidget.CONTENT_WIDTH_DP,
                heightDp = CategoryWidget.PILL_HEIGHT_DP,
                emoji = pill.emoji,
                name = pill.name,
                amount = amountText,
                percent = percentText,
                fillFraction = if (isCompact || pill.cap == null) 0f else pill.fraction,
                trackColor = baseColor.copy(alpha = if (isCompact) 0.10f else 0.15f),
                fillColor = fillColor,
                trackTextColor = secondaryColor,
                fillTextColor = fillTextColor,
            )
        ),
        contentScale = ContentScale.FillBounds,
        contentDescription = null,
    )
}

private fun drawCategoryPillBitmap(
    context: Context,
    widthDp: Float,
    heightDp: Float,
    emoji: String,
    name: String,
    amount: String,
    percent: String,
    fillFraction: Float,
    trackColor: Color,
    fillColor: Color,
    trackTextColor: Color,
    fillTextColor: Color,
): Bitmap {
    val density = context.resources.displayMetrics.density
    val width = (widthDp * density).roundToInt().coerceAtLeast(1)
    val height = (heightDp * density).roundToInt().coerceAtLeast(1)
    val radius = height / 2f
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)

    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = trackColor.toArgb()
    }
    canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, trackPaint)

    val fillWidth = width.toFloat() * fillFraction.coerceIn(0f, 1f)

    val contentPaint: (Color) -> TextPaint = { textColor ->
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            isSubpixelText = true
            typeface = Typeface.Builder(context.assets, "font/manrope_variable.ttf")
                .setFontVariationSettings("'wght' 700")
                .build()
            textSize = 10f * density
            color = textColor.toArgb()
        }
    }
    val emojiPaint: (Color) -> Paint = { textColor ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isSubpixelText = true
            textSize = 12f * density
            color = textColor.toArgb()
        }
    }

    val padX = 10f * density
    val emojiGap = 6f * density
    val percentGap = 8f * density
    val nameTailGap = 4f * density

    val emojiWidth = if (emoji.isNotBlank()) {
        emojiPaint(fillColor).measureText(emoji) + emojiGap
    } else {
        0f
    }
    val amountWidth = contentPaint(fillColor).measureText(amount)
    val percentWidth = if (percent.isNotBlank()) {
        contentPaint(fillColor).measureText(percent) + percentGap
    } else {
        0f
    }
    val rightGroupWidth = amountWidth + percentWidth
    val nameMaxWidth = (width - padX - emojiWidth - rightGroupWidth - padX - nameTailGap)
        .coerceAtLeast(0f)
    val displayName = if (nameMaxWidth > 0f) {
        TextUtils.ellipsize(name, contentPaint(fillColor), nameMaxWidth, TextUtils.TruncateAt.END)
            .toString()
    } else {
        ""
    }

    fun drawTextLayer(textColor: Color) {
        val paint = contentPaint(textColor)
        val fm = paint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        var leftX = padX
        if (emoji.isNotBlank()) {
            val ePaint = emojiPaint(textColor)
            val eFm = ePaint.fontMetrics
            val eBaseline = height / 2f - (eFm.ascent + eFm.descent) / 2f
            canvas.drawText(emoji, leftX, eBaseline, ePaint)
            leftX += emojiWidth
        }
        if (displayName.isNotBlank()) {
            canvas.drawText(displayName, leftX, baseline, paint)
        }
        if (rightGroupWidth > 0f) {
            val amountRight = width - padX - percentWidth
            canvas.drawText(amount, amountRight - amountWidth, baseline, paint)
            if (percent.isNotBlank()) {
                canvas.drawText(percent, width - padX, baseline, paint)
            }
        }
    }

    drawTextLayer(trackTextColor)

    if (fillWidth > 1f) {
        canvas.save()
        canvas.clipRect(0f, 0f, fillWidth, height.toFloat())
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor.toArgb()
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, fillPaint)
        drawTextLayer(fillTextColor)
        canvas.restore()
    }

    return bitmap
}
