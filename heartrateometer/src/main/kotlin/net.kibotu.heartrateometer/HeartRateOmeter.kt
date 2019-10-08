package net.kibotu.heartrateometer

import android.content.Context
import android.graphics.Point
import android.hardware.Camera
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import de.charite.balsam.utils.camera.CameraModule
import de.charite.balsam.utils.camera.CameraSupport
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.*
import org.apache.commons.collections4.queue.CircularFifoQueue
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap


/**
 * Created by <a href="https://about.me/janrabe">Jan Rabe</a>.
 */
open class HeartRateOmeter {

    private val TAG: String = javaClass.simpleName

    companion object {
        var enableLogging: Boolean = false
    }

    enum class PulseType { OFF, ON }

    data class Bpm(val value: Int, val type: PulseType)

    private var wakeLockTimeOut: Long = 10_000

    protected var surfaceHolder: SurfaceHolder? = null

    protected var wakelock: PowerManager.WakeLock? = null

    protected lateinit var previewCallback: Camera.PreviewCallback

    protected lateinit var surfaceCallback: SurfaceHolder.Callback

    protected val publishSubject: PublishSubject<Bpm>

    public val chartDataSubject: PublishSubject<List<Pair<Float, Float>>>
            = PublishSubject.create()

    public val peakDataSubject: PublishSubject<List<Pair<Float, Float>>>
            = PublishSubject.create()

    protected var context: WeakReference<Context>? = null

    protected var cameraSupport: CameraSupport? = null

    private var powerManager: PowerManager? = null
        get() = context?.get()?.getSystemService(Context.POWER_SERVICE) as? PowerManager?

    private var fingerDetectionListener: ((Boolean) -> Unit)? = null

    init {
        publishSubject = PublishSubject.create<Bpm>()
    }

    var averageTimer: Int = -1

    fun withAverageAfterSeconds(averageTimer: Int): HeartRateOmeter {
        this.averageTimer = averageTimer
        return this
    }

    fun bpmUpdates(surfaceView: SurfaceView): Observable<Bpm> {
        return bpmUpdates(surfaceView.context, surfaceView.holder)
    }

    protected fun bpmUpdates(context: Context, surfaceHolder: SurfaceHolder): Observable<Bpm> {

        previewCallback = if (averageTimer == -1)
            createCameraPreviewCallback()
        else
            createCameraPreviewCallback2()

        surfaceCallback = createSurfaceHolderCallback()

        this.context = WeakReference(context)
        this.surfaceHolder = surfaceHolder
        return publishSubject
                .doOnSubscribe {
                    publishSubject.onNext(Bpm(-1, PulseType.OFF))
                    start()
                }
                .doOnDispose { cleanUp() }
    }

    protected fun start() {
        log("start")

        wakelock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, context?.get()?.javaClass?.canonicalName)
        wakelock?.acquire(wakeLockTimeOut)

        context?.get()?.let {
            cameraSupport = CameraModule.provideCameraSupport(context = it).open(0)
        }

        // portrait
        cameraSupport?.setDisplayOrientation(90)
        log(cameraSupport?.getOrientation(0).toString())

        surfaceHolder?.addCallback(surfaceCallback)
        surfaceHolder?.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)

        addCallbacks()

        startPreview()
    }

    private fun addCallbacks() {
        try {
            cameraSupport?.setPreviewDisplay(surfaceHolder!!)
            cameraSupport?.setPreviewCallback(previewCallback)
        } catch (throwable: Throwable) {
            if (enableLogging)
                throwable.printStackTrace()
        }
    }

    data class Dimension(val width: Int, val height: Int)

    private fun getScreenDimensions(): Dimension {

        val dm = DisplayMetrics()
        val display: android.view.Display = (context?.get()?.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getMetrics(dm)

        var screenWidth = dm.widthPixels
        var screenHeight = dm.heightPixels

        if (Build.VERSION.SDK_INT in 14..16) {
            try {
                screenWidth = android.view.Display::class.java.getMethod("getRawWidth").invoke(display) as Int
                screenHeight = android.view.Display::class.java.getMethod("getRawHeight").invoke(display) as Int
            } catch (ignored: Exception) {
            }

        }
        if (Build.VERSION.SDK_INT >= 17) {
            try {
                val realSize = Point()
                android.view.Display::class.java.getMethod("getRealSize", Point::class.java).invoke(display, realSize)
                screenWidth = realSize.x
                screenHeight = realSize.y
            } catch (ignored: Exception) {
            }

        }

        return Dimension(screenWidth, screenHeight)
    }

    private fun getScreenDimensionsLandscape(): Dimension {
        val (width, height) = getScreenDimensions()
        return Dimension(Math.max(width, height), Math.min(width, height))
    }

    private fun startPreview() {
        val screenDimensionsLandscape = getScreenDimensionsLandscape()
        setCameraParameter(screenDimensionsLandscape.width, screenDimensionsLandscape.height)
        cameraSupport?.startPreview()
    }

    protected fun setCameraParameter(width: Int, height: Int) {

        val parameters = cameraSupport?.parameters
        parameters?.flashMode = Camera.Parameters.FLASH_MODE_TORCH

        if (parameters?.maxExposureCompensation != parameters?.minExposureCompensation) {
            //  parameters?.exposureCompensation = 0
        }
        if (parameters?.isAutoExposureLockSupported == true) {
            // parameters.autoExposureLock = true
        }
        if (parameters?.isAutoWhiteBalanceLockSupported == true) {
            // parameters.autoWhiteBalanceLock = true
        }


        // parameters?.setPreviewSize(width, height)
        getSmallestPreviewSize(width, height, parameters)?.let {
            parameters?.setPreviewSize(it.width, it.height)
            Log.d("HeartCheck","Using width ${it.width} and height ${it.height}")
        }

        cameraSupport?.parameters = parameters

        cameraSupport?.addBuffers()

    }

    protected fun createSurfaceHolderCallback(): SurfaceHolder.Callback {
        return object : SurfaceHolder.Callback {

            override fun surfaceCreated(holder: SurfaceHolder) {
                addCallbacks()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                startPreview()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        }
    }

    private var fingerDetected: Boolean = false
        set(value) {
            if (field != value)
                fingerDetectionListener?.invoke(value)
            field = value
        }

    protected fun createCameraPreviewCallback(): Camera.PreviewCallback {
        return object : Camera.PreviewCallback {

            val PROCESSING = AtomicBoolean(false)
            val sampleSize = 256
            var counter = 0
            var bpm: Int = -1

            val fft = FFT(sampleSize)

            val sampleQueue = CircularFifoQueue<Double>(sampleSize)
            val timeQueue = CircularFifoQueue<Long>(sampleSize)
            val bpmQueue = CircularFifoQueue<Int>(40)

            override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {

                if (data == null) {
                    log("Data is null!")
                    return
                }

                if (camera == null) {
                    log("Camera is null!")
                    return
                }

                val size = camera.parameters.previewSize
                if (size == null) {
                    log("Size is null!")
                    return
                }

                if (!PROCESSING.compareAndSet(false, true)) {
                    log("Have to return...")
                    return
                }

                val width = size.width
                val height = size.height


                val imgAvg = MathHelper.decodeYUV420SPtoRedAvg(data.clone(), width, height)
                if (imgAvg == 0 || imgAvg < 199) {
                    PROCESSING.set(false)
                    fingerDetected = false
                    return
                }

                fingerDetected = true

                sampleQueue.add(imgAvg.toDouble())
                timeQueue.add(System.currentTimeMillis())

                val y = DoubleArray(sampleSize)
                val x = toPrimitive(sampleQueue.toArray(arrayOfNulls<Double>(0)) as Array<Double>)
                val time = toPrimitive(timeQueue.toArray(arrayOfNulls<Long>(0)) as Array<Long>)

                if (timeQueue.size < sampleSize) {
                    PROCESSING.set(false)

                    return
                }

                val Fs = timeQueue.size.toDouble() / (time!![timeQueue.size - 1] - time[0]).toDouble() * 1000

                fft.fft(x!!, y)

                val low = Math.round(((sampleSize * 40).toDouble() / 60.0 / Fs).toFloat())
                val high = Math.round(((sampleSize * 160).toDouble() / 60.0 / Fs).toFloat())

                var bestI = 0
                var bestV = 0.0
                for (i in low until high) {
                    val value = Math.sqrt(x[i] * x[i] + y[i] * y[i])

                    if (value > bestV) {
                        bestV = value
                        bestI = i
                    }
                }

                bpm = Math.round((bestI.toDouble() * Fs * 60.0 / sampleSize).toFloat())
                bpmQueue.add(bpm)

                // log("bpm=$bpm")

                publishSubject.onNext(Bpm(bpm, PulseType.ON))

                counter++

                PROCESSING.set(false)
            }
        }
    }

    protected fun createCameraPreviewCallback2(): Camera.PreviewCallback {

        return object : Camera.PreviewCallback {

            internal var beatsIndex = 0
            internal var beats = 0.0
            internal var startTime = System.currentTimeMillis()

            internal val PROCESSING = AtomicBoolean(false)

            internal val AVERAGE_ARRAY_SIZE = 300
            internal val averageArray: LinkedList<Int> = LinkedList()
            internal val derivArray: MutableList<Int> = ArrayList()
            internal val peaks: MutableList<Pair<Int, Int>> = ArrayList<Pair<Int, Int>>()

            var framesCount: Int = 0
            var timeAtLastFramePortionStart: Long = 0L

            var buffer: ByteArray? = null

//            internal val BEATS_ARRAY_SIZE = 3
//            internal val BEATS_ARRAY = IntArray(BEATS_ARRAY_SIZE)

            internal var currentPixelType: PulseType = PulseType.OFF

            private var previousBeatsAverage: Int = 0

            override fun onPreviewFrame(data: ByteArray?, camera: Camera) {
                if (data == null) {
                    log("Data is null!")
                    return
                }

                val size = camera.parameters.previewSize
                if (size == null) {
                    log("Size is null!")
                    return
                }
                val buf = buffer
                if (buf == null) {
                    buffer = data.clone()
                } else {
                    data.copyInto(buf)
                }

                if (timeAtLastFramePortionStart == 0L) {
                    timeAtLastFramePortionStart = SystemClock.elapsedRealtime()
                }

                framesCount++
                if (framesCount == 30) {
                    val now = SystemClock.elapsedRealtime()
                    Log.d("TimeCheck", "received 30 frames in ${now - timeAtLastFramePortionStart} ms")
                    timeAtLastFramePortionStart = now
                    framesCount = 0
                }

                cameraSupport?.addCallbackBuffer(data)



                runBlocking {
                    withContext(Dispatchers.Default) {


                        if (!PROCESSING.compareAndSet(false, true)) {
                            log("Have to return...")

                        } else {


                            val width = size.width
                            val height = size.height

                            // Logger.d("SIZE: width: " + width + ", height: " + height);

                            Log.d("HeartCheck", "buffer size " + data.size + "width= " + width +
                                    "height=" + height)
                            val imageAverage = MathHelper.decodeYUV420SPtoRedAvg(buf, width, height)
                            log("imageAverage not started: " + imageAverage)
                            if (imageAverage == 0 || imageAverage < 199) {
                                PROCESSING.set(false)
                                fingerDetected = false

                            } else {
                                fingerDetected = true

                                while (averageArray.size >= AVERAGE_ARRAY_SIZE) {
                                    averageArray.removeFirst()
                                }
                                averageArray.add(imageAverage)

                                val chartData = ArrayList<Pair<Float,Float>>()
                                for (i in 0 until averageArray.size) {
                                    chartData.add(i.toFloat() to averageArray[i].toFloat())
                                }

                                chartDataSubject.onNext(chartData)

                                val endTime = System.currentTimeMillis()
                                val totalTimeInSecs = (endTime - startTime) / 1000.0
                                log("totalTimeInSecs: " + totalTimeInSecs + " >= averageTimer: " + averageTimer)
                                if (totalTimeInSecs >= averageTimer) {
                                    // NEW ALGORITHM

                                    // STEP 1. CALCULATE ARRAY OF DERIVATIVES

                                    derivArray.clear()
                                    for (i in 0..averageArray.size) {
                                        if (i > 0 && i < averageArray.size - 1) {
                                            val value = (averageArray.get(i - 1) + averageArray.get(i + 1)) / 2
                                            derivArray.add(value)
                                        }
                                    }

                                    // STEP 2 CALCULATE PEAKS
                                    val eps = 7//15,30

                                    //in peaksMap key is peak value and value is position
                                    peaks.clear()
                                    val chartPeaks = ArrayList<Int>()
                                    for (i in 0..derivArray.size) {
                                        if (i >= eps && i < derivArray.size - eps) {
                                            val value = derivArray.get(i)
                                            if (value == findMax(derivArray.subList(i - eps, i + 1)) &&
                                                    value == findMax(derivArray.subList(i, i + eps + 1))) {
                                                peaks.add(i to value)
                                                chartPeaks.add(i)
                                            }
                                        }
                                    }

//                                    val peaksChartData = chartPeaks.map {
//                                        it.toFloat() to averageArray[it].toFloat()
//                                    }
//
//                                    peakDataSubject.onNext(peaksChartData)

                                    Log.d("PeakCheck", "peaksMap size=${peaks.size}" +
                                            "chartPeaks size = ${chartPeaks.size}")



                                    // This map is sorted by peak size
                                    val sortedPeaks = peaks.sortedByDescending { it.second }

                                    // GET SETS OF k HIGHEST PEAKS, k from 5 to 20
                                    // CALCULATE DISTANCES BETWEEN PEAKS IN EACH SET

                                    val distancesList = ArrayList<ArrayList<Int>>()
                                    for (k in 5..20) {
                                        // Find dispersion of distances between peaks in each set
                                        val distances = ArrayList<Int>()
                                        val iterator = sortedPeaks.iterator()
                                        var previous: Int = -1
                                        var i = 0
                                        while (iterator.hasNext() && i < k) {
                                            val value = iterator.next()
                                            if (previous != -1) {
                                                distances.add(Math.abs(value.first - previous))
                                            }
                                            previous = value.first
                                            i++
                                        }
                                        distancesList.add(distances)
                                    }

                                    // CALCULATE DISPERSION FOR EACH SET OF PEAKS

                                    val meanList = ArrayList<Int>()

                                    val dispersions = distancesList.map { distancesSet ->
                                        if (distancesSet.size == 0) {
                                            Int.MAX_VALUE
                                        } else {
                                            val mean = distancesSet.sum() / distancesSet.size
                                            meanList.add(mean)
                                            distancesSet.map { distance ->
                                                (distance - mean) * (distance - mean)
                                            }.sum() / distancesSet.size
                                        }
                                    }

                                    // CHOOSE SET WITH MIN DISPERSION

                                    val indexOfMin = dispersions.withIndex()
                                            .minBy { (_, dispersion) -> dispersion }?.index

                                    indexOfMin?.also {
                                        val resultDistanceList = distancesList[indexOfMin]
                                        // key - peak value - distance
                                        if (distancesList.size > 0 && sortedPeaks.size > 2) {
                                            val itt = sortedPeaks.iterator()
                                            val resultPeaksList = ArrayList<Int>()
                                            while (itt.hasNext() && resultPeaksList.size <
                                                    resultDistanceList.size + 1) {
                                                val item = itt.next()
                                                resultPeaksList.add(item.first)
                                            }

                                            // these are x-values, sort by x
                                            val sortedResultPeaksList = resultPeaksList.sorted()



                                            val resultPeaksList2 = ArrayList<Int>()
                                            val resultPeaksList3 = ArrayList<Int>()

                                            val minDistance = 30 * 60 / 200
                                            for (k in 0 until sortedResultPeaksList.size) {
                                                val item = sortedResultPeaksList[k]
                                                val prev = if (k == 0) null else sortedResultPeaksList[k - 1]
                                                val next = if (k == sortedResultPeaksList.size - 1) 0 else
                                                    sortedResultPeaksList[k + 1]
                                                val excludeByPrev = if (prev == null) false else {
                                                    Math.abs(item - prev) < minDistance
                                                }
                                                val excludeByNext = if (next == null) false else {
                                                    Math.abs(item - next) < minDistance
                                                }
                                                if (!excludeByPrev && !excludeByNext) {
                                                    resultPeaksList2.add(item)
                                                }
                                            }

                                            if (resultPeaksList2.size >= 2) {

                                                var acc = 0
                                                for (i in 1 until resultPeaksList2.size) {
                                                    acc += Math.abs(resultPeaksList2[i] - resultPeaksList2[i - 1])
                                                }
                                                val meanDistance2 = acc / (resultPeaksList2.size - 1)

                                                for (k in 0 until resultPeaksList2.size) {
                                                    val item = resultPeaksList2[k]
                                                    val prev = if (k == 0) null else resultPeaksList2[k - 1]
                                                    val next = if (k == resultPeaksList2.size - 1) 0 else
                                                        resultPeaksList2[k + 1]
                                                    val excludeByPrev = if (prev == null) false else {
                                                        val dist = Math.abs(item - prev)
                                                        (dist - meanDistance2) * 100 / dist > 25
                                                    }
                                                    val excludeByNext = if (next == null) false else {
                                                        val dist = Math.abs(item - next)
                                                        (dist - meanDistance2) * 100 / dist > 25
                                                    }
                                                    if (!excludeByPrev && !excludeByNext) {
                                                        resultPeaksList3.add(item)
                                                    }
                                                }

//                                                val peaksChartData = resultPeaksList3.map {
//                                                    it.toFloat() to averageArray[it].toFloat()
//                                                }
//
//                                                peakDataSubject.onNext(peaksChartData)


                                                val resultDistances = ArrayList<Int>()
                                                for (i in 1 until resultPeaksList3.size) {
                                                    resultDistances.add(Math.abs(resultPeaksList3[i] - resultPeaksList3[i - 1]))
                                                }

                                                if (resultPeaksList3.size >= 2 && resultDistances.size > 0) {
                                                    val distMean = resultDistances.sum() / resultDistances.size
                                                    val distIt = resultDistances.iterator()
                                                    while(distIt.hasNext()) {
                                                        val dst = distIt.next()
                                                        if (Math.abs(dst - distMean) > 25*dst/100) {
                                                            Log.d("Msf", "Remove too uneven distance dst=$dst mean=$distMean")
                                                            distIt.remove()
                                                        }
                                                    }
                                                    if (resultDistances.size > 0) {
                                                        val mean = resultDistances.sum() / resultDistances.size

                                                        val peaksChartData = resultPeaksList3.map {
                                                            it.toFloat() to averageArray[it].toFloat()
                                                        }

                                                        peakDataSubject.onNext(peaksChartData)


                                                        val FRAMES_PER_SECOND = 30
                                                        val heartRate = FRAMES_PER_SECOND * 60 / mean
                                                        previousBeatsAverage = heartRate
                                                        Log.d("HeartCheck", "heartRate=$heartRate" +
                                                                " index=$indexOfMin mean=$mean peaks=${peaks.size}")
                                                        publishSubject.onNext(Bpm(heartRate, PulseType.ON))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                PROCESSING.set(false)
                            }
                        }
                    }

                }
            }
        }
    }

    fun findMax(list: List<Int>?): Int? {

        // check list is empty or not
        if (list == null || list.size == 0) {
            return Integer.MIN_VALUE
        }

        // create a new list to avoid modification
        // in the original list
        val sortedlist = ArrayList(list)

        // sort list in natural order
        Collections.sort(sortedlist)

        // last element in the sorted list would be maximum
        return sortedlist[sortedlist.size - 1]
    }

    /**
     * An empty immutable `long` array.
     */
    protected val EMPTY_LONG_ARRAY = LongArray(0)

    /**
     *
     * Converts an array of object Longs to primitives.
     *
     *
     * This method returns `null` for a `null` input array.
     *
     * @param array  a `Long` array, may be `null`
     * @return a `long` array, `null` if null array input
     * @throws NullPointerException if array content is `null`
     */
    protected fun toPrimitive(array: Array<Long>?): LongArray? {
        if (array == null) {
            return null
        } else if (array.isEmpty()) {
            return EMPTY_LONG_ARRAY
        }
        val result = LongArray(array.size)
        for (i in array.indices) {
            result[i] = array[i]
        }
        return result
    }

    /**
     * An empty immutable `double` array.
     */
    protected val EMPTY_DOUBLE_ARRAY = DoubleArray(0)

    /**
     *
     * Converts an array of object Doubles to primitives.
     *
     *
     * This method returns `null` for a `null` input array.
     *
     * @param array  a `Double` array, may be `null`
     * @return a `double` array, `null` if null array input
     * @throws NullPointerException if array content is `null`
     */
    protected fun toPrimitive(array: Array<Double>?): DoubleArray? {
        if (array == null) {
            return null
        } else if (array.isEmpty()) {
            return EMPTY_DOUBLE_ARRAY
        }
        val result = DoubleArray(array.size)
        for (i in array.indices) {
            result[i] = array[i]
        }
        return result
    }

    protected fun getSmallestPreviewSize(width: Int, height: Int, parameters: Camera.Parameters?): Camera.Size? {

        var result: Camera.Size? = null

        parameters?.supportedPreviewSizes?.let {
            it
                    .asSequence()
                    .filter { it.width <= width && it.height <= height }
                    .forEach {
                        if (result == null) {
                            result = it
                        } else {
                            if (it.width * it.height < result!!.width * result!!.height)
                                result = it
                        }
                    }
        }

        return result
    }

    protected fun cleanUp() {
        log("cleanUp")

        if (wakelock?.isHeld == true) {
            wakelock?.release()
        }

        cameraSupport?.apply {
            setPreviewCallback(null)
            stopPreview()
            release()
        }

        cameraSupport = null
    }

    private fun log(message: String?) {
        if (enableLogging)
            Log.d(TAG, "" + message)
    }

    fun setFingerDetectionListener(fingerDetectionListener: ((Boolean) -> Unit)?): HeartRateOmeter {
        this.fingerDetectionListener = fingerDetectionListener
        return this
    }
}