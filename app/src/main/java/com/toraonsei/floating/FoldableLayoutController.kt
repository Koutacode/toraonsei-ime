package com.toraonsei.floating

import android.content.Context
import android.graphics.Rect
import android.view.WindowManager
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FoldableLayoutController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFoldingChanged: (FoldingFeature?, Rect) -> Unit
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            runCatching {
                val tracker = WindowInfoTracker.getOrCreate(context)
                tracker.windowLayoutInfo(context).collectLatest { info ->
                    val folding = info.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull()
                    val bounds = runCatching {
                        WindowMetricsCalculator.getOrCreate()
                            .computeCurrentWindowMetrics(context).bounds
                    }.getOrDefault(Rect())
                    onFoldingChanged(folding, bounds)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        fun isBubbleOverHinge(
            params: WindowManager.LayoutParams,
            bubbleSize: Int,
            windowBounds: Rect,
            folding: FoldingFeature
        ): Boolean {
            val rightGravity = (params.gravity and android.view.Gravity.END) == android.view.Gravity.END
            val bottomGravity = (params.gravity and android.view.Gravity.BOTTOM) == android.view.Gravity.BOTTOM
            val left = if (rightGravity) windowBounds.right - params.x - bubbleSize else params.x
            val top = if (bottomGravity) windowBounds.bottom - params.y - bubbleSize else params.y
            val bubbleRect = Rect(left, top, left + bubbleSize, top + bubbleSize)
            return Rect.intersects(bubbleRect, folding.bounds)
        }
    }
}
