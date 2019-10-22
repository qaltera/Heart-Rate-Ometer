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
            // // Chart Style // //

            // background color
            chart.setBackgroundColor(Color.WHITE)

            // disable description text
            chart.getDescription().setEnabled(false)

            // enable touch gestures
            chart.setTouchEnabled(true)

            // set listeners
            //chart.setOnChartValueSelectedListener(this)
            chart.setDrawGridBackground(false)

            // create marker to display box when values are selected
            //val mv = MyMarkerView(this, R.layout.custom_marker_view)

            // Set the marker to the chart
            //mv.setChartView(chart)
            //chart.setMarker(mv)

            // enable scaling and dragging
            chart.setDragEnabled(true)
            chart.setScaleEnabled(true)
            // chart.setScaleXEnabled(true);
            // chart.setScaleYEnabled(true);

            // force pinch zoom along both axis
            chart.setPinchZoom(true)
        }

        val xAxis: XAxis
        run {
            // // X-Axis Style // //
            xAxis = chart.getXAxis()

            // vertical grid lines
            xAxis.enableGridDashedLine(10f, 10f, 0f)
            xAxis.axisMinimum = 0F
            xAxis.axisMaximum = 300F
        }

        val yAxis: YAxis
        run {
            // // Y-Axis Style // //
            yAxis = chart.getAxisLeft()

            // disable dual axis (only use LEFT axis)
            chart.getAxisRight().setEnabled(false)

            // horizontal grid lines
            yAxis.enableGridDashedLine(10f, 10f, 0f)

//            // axis range
//            yAxis.axisMinimum = 33000F///230f
//            yAxis.axisMaximum = 37000F///250f
        }


        run {
            // // Create Limit Lines // //
//            val llXAxis = LimitLine(9f, "Index 10")
//            llXAxis.lineWidth = 4f
//            llXAxis.enableDashedLine(10f, 10f, 0f)
//            llXAxis.labelPosition = LimitLine.LimitLabelPosition.RIGHT_BOTTOM
//            llXAxis.textSize = 10f

//            val ll1 = LimitLine(150f, "Upper Limit")
//            ll1.lineWidth = 4f
//            ll1.enableDashedLine(10f, 10f, 0f)
//            ll1.labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
//            ll1.textSize = 10f
//
//            val ll2 = LimitLine(-30f, "Lower Limit")
//            ll2.lineWidth = 4f
//            ll2.enableDashedLine(10f, 10f, 0f)
//            ll2.labelPosition = LimitLine.LimitLabelPosition.RIGHT_BOTTOM
//            ll2.textSize = 10f

            // draw limit lines behind data instead of on top
            //yAxis.setDrawLimitLinesBehindData(true)
            //xAxis.setDrawLimitLinesBehindData(true)

            // add limit lines
            //yAxis.addLimitLine(ll1)
            //yAxis.addLimitLine(ll2)
            //xAxis.addLimitLine(llXAxis);
        }

    }

    fun setData(data: List<Pair<Float, Float>>) {

        val values = data.map {
            BarEntry(it.first, it.second)
        }

        // axis range
        val yAxis = chart.axisLeft
        yAxis.axisMinimum = (data.minBy { it.second }?.let{it.second} ?: 33000F) - 500
        yAxis.axisMaximum = (data.maxBy { it.second }?.let{it.second} ?: 37000F) + 500

        val set1: LineDataSet

        if (chart.data != null && chart.data.dataSetCount > 0) {
            set1 = chart.data.getDataSetByIndex(0) as LineDataSet
            set1.values = values
            set1.setDrawValues(false)
            chart.data.notifyDataChanged()
            chart.notifyDataSetChanged()
            chart.invalidate()
        } else {
            set1 = LineDataSet(values, RED_AVG_VALUES)

            set1.setDrawIcons(false)

            // black lines and points
            set1.color = Color.BLACK
            set1.setCircleColor(Color.BLACK)

            // line thickness and point size
            set1.lineWidth = 1f
            set1.circleRadius = 3f

            // draw points as solid circles
            set1.setDrawCircleHole(false)

            //set1.setColors(ColorTemplate.MATERIAL_COLORS);

            val otherSet = if (chart.data != null && chart.data.dataSetCount > 0) {
                chart.data.getDataSetByLabel(PEAK_VALUES, true)
            } else null

            val lst = ArrayList<ILineDataSet>()
            lst.add(set1)
            if (otherSet != null) {
                lst.add(otherSet)
            }

            val data = LineData(lst)
            data.setValueTextSize(10f)

            chart.setData(data)
        }
    }

    fun setPeakData(data: List<Pair<Float, Float>>) {

        val values = data.map {
            BarEntry(it.first, it.second)
        }

        val set2: LineDataSet

        if (chart.data != null && chart.data.dataSetCount > 1) {
            set2 = chart.data.getDataSetByIndex(1) as LineDataSet
            set2.values = values
            set2.setDrawValues(false)
            chart.data.notifyDataChanged()
            chart.notifyDataSetChanged()
            chart.invalidate()

        } else {
            set2 = LineDataSet(values, PEAK_VALUES)

            set2.setDrawIcons(false)

            // black lines and points
            set2.color = Color.BLUE
            set2.setCircleColor(Color.BLUE)

            // line thickness and point size
            set2.lineWidth = 1f
            set2.circleRadius = 3f

            // draw points as solid circles
            set2.setDrawCircleHole(false)

            //set1.setColors(ColorTemplate.MATERIAL_COLORS);


            val otherSet = if (chart.data != null && chart.data.dataSetCount > 0) {
                chart.data.getDataSetByLabel(RED_AVG_VALUES, true)
            } else null

            val lst = ArrayList<ILineDataSet>()

            if (otherSet != null) {
                lst.add(otherSet)
            }
            lst.add(set2)

            val data = LineData(lst)
            //data.setValueTextSize(10f)

            chart.setData(data)
        }
    }

    companion object {
        const val RED_AVG_VALUES = "red_average_values"
        const val PEAK_VALUES = "peak_values"
    }

}