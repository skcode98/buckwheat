package com.danilkinkin.buckwheat.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.danilkinkin.buckwheat.widget.category.CategoryWidgetReceiver
import com.danilkinkin.buckwheat.widget.extend.ExtendWidgetReceiver
import com.danilkinkin.buckwheat.widget.minimal.MinimalWidgetReceiver
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetReceiver

class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetRefreshScheduler.ACTION_REFRESH) return

        // Same path as Application's onActivityPaused: each widget receiver re-reads its
        // DataStore state and re-renders all placed instances via the custom updateAction.
        WidgetReceiver.requestUpdateData(context, ExtendWidgetReceiver::class.java)
        WidgetReceiver.requestUpdateData(context, MinimalWidgetReceiver::class.java)
        WidgetReceiver.requestUpdateData(context, VoiceWidgetReceiver::class.java)
        WidgetReceiver.requestUpdateData(context, CategoryWidgetReceiver::class.java)

        // setWindow is one-shot: re-arm the next day's refresh.
        WidgetRefreshScheduler.schedule(context)
    }
}
