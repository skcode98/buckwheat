package com.danilkinkin.buckwheat.widget.voice

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.widget.CanvasText
import com.danilkinkin.buckwheat.widget.WidgetReceiver

class VoiceWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

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

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.minimal_widget_preview_background))
            .cornerRadius(48.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .clickable(actionStartActivity(intent)),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                if (stateSet) {
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
                                fontSize = 22.sp,
                            ),
                        )
                    }
                } else {
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

            Box(
                modifier = GlanceModifier
                    .padding(start = 12.dp)
                    .size(48.dp)
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(24.dp)
                    .clickable(actionStartService(Intent(context, VoiceWidgetCommitService::class.java), true)),
            ) {
                val micProvider = ResourcesCompat.getDrawable(
                    context.resources,
                    R.drawable.ic_mic,
                    null,
                )?.let { drawable -> ImageProvider(drawable.toBitmap()) }

                if (micProvider != null) {
                    Image(
                        modifier = GlanceModifier.fillMaxSize().padding(10.dp),
                        provider = micProvider,
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
                        contentDescription = null,
                    )
                }
            }
        }
    }
}
