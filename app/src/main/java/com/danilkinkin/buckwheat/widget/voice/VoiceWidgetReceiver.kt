package com.danilkinkin.buckwheat.widget.voice

import android.content.Context
import com.danilkinkin.buckwheat.widget.WidgetReceiver
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class VoiceWidgetReceiver : WidgetReceiver() {
    companion object {
        fun requestUpdateData(context: Context) {
            requestUpdateData(context, VoiceWidgetReceiver::class.java)
        }
    }

    override val glanceAppWidget = VoiceWidget()
}
