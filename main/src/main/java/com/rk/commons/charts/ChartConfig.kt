package com.rk.commons.charts

import android.graphics.Typeface
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import java.text.DecimalFormat

object ChartConfig {
    const val MAX_GRAPH_POINTS = 120

    /** Percent charts (CPU/RAM/GPU/battery level): fixed 0–100 + % suffix. */
    val RangeProvider = CartesianLayerRangeProvider.fixed(maxY = 100.0)
    val YDecimalFormat = DecimalFormat("#.##'%'")
    val StartAxisValueFormatter = CartesianValueFormatter.decimal(YDecimalFormat)
    val MarkerValueFormatter = DefaultCartesianMarker.ValueFormatter.default(YDecimalFormat)

    /** Value charts (net rates KB/s, battery current mA): dynamic range, no suffix. */
    val AutoRangeProvider = CartesianLayerRangeProvider.auto()
    val PlainDecimalFormat = DecimalFormat("#.##")
    val PlainStartAxisValueFormatter = CartesianValueFormatter.decimal(PlainDecimalFormat)
    val PlainMarkerValueFormatter = DefaultCartesianMarker.ValueFormatter.default(PlainDecimalFormat)

    val xValues = List(MAX_GRAPH_POINTS) { it.toDouble() }
}
