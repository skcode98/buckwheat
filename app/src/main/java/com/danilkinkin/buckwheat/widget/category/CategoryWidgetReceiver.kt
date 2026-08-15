package com.danilkinkin.buckwheat.widget.category

import android.content.Context
import com.danilkinkin.buckwheat.widget.WidgetReceiver
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CategoryWidgetReceiver : WidgetReceiver() {
    companion object {
        fun requestUpdateData(context: Context) {
            requestUpdateData(context, CategoryWidgetReceiver::class.java)
        }
    }

    override val glanceAppWidget = CategoryWidget()
}
