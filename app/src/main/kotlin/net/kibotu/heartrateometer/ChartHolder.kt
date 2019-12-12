package net.kibotu.heartrateometer

import android.graphics.Color
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet


class ChartHolder(private val chart: LineChart) {

    init {
        run {
            chart.setBackgroundColor(Color.WHITE)
            chart.getDescription().setEnabled(false)

            val l = chart.legend
            l.isEnabled = false
            chart.setDrawGridBackground(false)
        }

        val xAxis: XAxis
        run {
            xAxis = chart.getXAxis()
            xAxis.setDrawLabels(false)
            xAxis.setDrawGridLines(false)
            xAxis.setDrawAxisLine(false)
            xAxis.axisMinimum = 0F
            xAxis.axisMaximum = 300F
        }

        val yAxis: YAxis
        run {
            yAxis = chart.getAxisLeft()
            yAxis.setDrawLabels(false)
            yAxis.setDrawGridLines(false)
            yAxis.setDrawAxisLine(false)

            chart.getAxisRight().setEnabled(false)
        }
    }

    fun setRedAvgData(data: List<Pair<Float, Float>>) {

        val values = data.map {
            BarEntry(it.first, it.second)
        }

        // axis range
        val yAxis = chart.axisLeft
        yAxis.axisMinimum = (data.filter { it.second != 0F }.minBy { it.second }?.let{Math.max(it.second, 30000F)} ?: 33000F) - 500
        yAxis.axisMaximum = (data.filter { it.second != 0F }.maxBy { it.second }?.let{Math.min(it.second, 40000F)} ?: 37000F) + 500

        if (chart.data != null && chart.data.dataSetCount > 0) {
            val set = chart.data.getDataSetByIndex(0) as LineDataSet
            updateSetWithValues(set, values)
        } else {
            val redValuesSet = createRedValuesSet(values)

            val peakSet = chart.data?.getDataSetByLabel(
                    PEAK_VALUES,
                    false
            )

            updateSets(redValuesSet, peakSet)
        }
    }

    fun setPeakData(data: List<Pair<Float, Float>>) {

        val values = data.map {
            BarEntry(it.first, it.second)
        }

        if (chart.data != null && chart.data.dataSetCount > 1) {
            val set = chart.data.getDataSetByIndex(1) as LineDataSet
            updateSetWithValues(set, values)
        } else {
            val peakSet = createPeakSet(values)

            val redAvgSet = chart.data?.getDataSetByLabel(
                RED_AVG_VALUES,
                false
            )

            updateSets(redAvgSet, peakSet)
        }
    }

    fun updateSets(redAvgSet: ILineDataSet?, peakSet: ILineDataSet?) {
        val sets = ArrayList<ILineDataSet>()

        redAvgSet?.also {
            sets.add(redAvgSet)
        }
        peakSet?.also {
            sets.add(peakSet)
        }

        val data = LineData(sets)

        chart.setData(data)
    }

    fun updateSetWithValues(set: LineDataSet, values: List<BarEntry>) {
        set.values = values
        set.setDrawValues(false)
        chart.data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    fun createRedValuesSet(values: List<BarEntry>): LineDataSet {
        val redValuesSet = LineDataSet(values, RED_AVG_VALUES)

        redValuesSet.setDrawIcons(false)

        redValuesSet.color = Color.BLACK
        redValuesSet.setDrawCircles(false)
        redValuesSet.lineWidth = 1f
        redValuesSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        return redValuesSet
    }

    fun createPeakSet(values: List<BarEntry>): LineDataSet {
        val peakSet = LineDataSet(values, PEAK_VALUES)
        peakSet.setDrawIcons(false)

        peakSet.color = Color.BLUE
        peakSet.setCircleColor(Color.BLUE)

        peakSet.lineWidth = 1f
        return peakSet
    }

    companion object {
        const val RED_AVG_VALUES = "red_average_values"
        const val PEAK_VALUES = "peak_values"
    }

}