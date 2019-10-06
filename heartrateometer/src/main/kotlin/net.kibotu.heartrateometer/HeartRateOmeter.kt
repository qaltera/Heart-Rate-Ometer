package net.kibotu.heartrateometer

import android.content.Context
import android.graphics.Point
import android.hardware.Camera
import android.os.Build
import android.os.PowerManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            internal val peaksMap: MutableMap<Int, Int> = LinkedHashMap()

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

                cameraSupport?.addCallbackBuffer(data)



                GlobalScope.launch {
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

//                log("imageAverage: " + imageAverage)
//
//                var averageArrayAverage = 0
//                var averageArrayCount = 0
//
//                averageArray.subList(averageArray.size - 4, averageArray.size)
//                        .map { averageEntry ->
//                    if (averageEntry > 0) {
//                        averageArrayAverage += averageEntry
//                        averageArrayCount++
//                    }
//                }
//
//                val rollingAverage = if (averageArrayCount > 0) averageArrayAverage / averageArrayCount else 0
//
//                log("rollingAverage: " + rollingAverage)
//
//                var newType = currentPixelType
//
//                if (imageAverage < rollingAverage) {
//                    newType = PulseType.ON
//                    if (newType != currentPixelType) {
//                        beats++
//                    }
//                } else if (imageAverage > rollingAverage) {
//                    newType = PulseType.OFF
//                }
//
                                while (averageArray.size >= AVERAGE_ARRAY_SIZE) {
                                    averageArray.removeFirst()
                                }
                                averageArray.add(imageAverage)
//
//                if (newType != currentPixelType) {
//                    currentPixelType = newType
//                    publishSubject.onNext(Bpm(previousBeatsAverage, currentPixelType))
//                }

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
                                    val eps = 1//15,30

                                    //in peaksMap key is peak value and value is position
                                    peaksMap.clear()
                                    for (i in 0..derivArray.size) {
                                        if (i >= eps && i < derivArray.size - eps) {
                                            val value = derivArray.get(i)
                                            if (value == findMax(derivArray.subList(i - eps, i + 1)) &&
                                                    value == findMax(derivArray.subList(i, i + eps + 1))) {
                                                peaksMap.put(value, i)
                                            }
                                        }
                                    }

                                    // This map is sorted by peak size
                                    val sortedPeaksMap = peaksMap.toSortedMap(reverseOrder())

                                    // GET SETS OF k HIGHEST PEAKS, k from 5 to 20
                                    // CALCULATE DISTANCES BETWEEN PEAKS IN EACH SET

                                    val distancesList = ArrayList<ArrayList<Int>>()
                                    for (k in 5..20) {
                                        // Find dispersion of distances between peaks in each set
                                        val distances = ArrayList<Int>()
                                        val iterator = sortedPeaksMap.values.iterator()
                                        var previous: Int = -1
                                        var i = 0
                                        while (iterator.hasNext() && i < k) {
                                            val value = iterator.next()
                                            if (previous != -1) {
                                                distances.add(Math.abs(value - previous))
                                            }
                                            previous = value
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
                                        val mean =  if (resultDistanceList.size != 0) {
                                            resultDistanceList.sum() / resultDistanceList.size
                                        } else {
                                            0
                                        }
                                        //if (indexOfMin >= 0 && indexOfMin < meanList.size) {
                                        //val resultMean = meanList[indexOfMin]
                                        val FRAMES_PER_SECOND = 30
                                        val heartRate = FRAMES_PER_SECOND * 60 / mean
                                        previousBeatsAverage = heartRate
                                        Log.d("HeartCheck", "heartRate=$heartRate" +
                                                " index=$indexOfMin mean=$mean peaks=${peaksMap.size}")
                                        publishSubject.onNext(Bpm(heartRate, PulseType.ON))
                                        //}
                                    }

                                    // CALCULATE HR
                                    // HR = frame_rate*60/mean


//                    val beatsPerSecond = beats / totalTimeInSecs
//                    val beatsPerMinute = (beatsPerSecond * 60.0).toInt()
//                    if (beatsPerMinute < 30 || beatsPerMinute > 180) {
//                        startTime = System.currentTimeMillis()
//                        beats = 0.0
//                        PROCESSING.set(false)
//                        return
//                    }
//
//                    if (beatsIndex == BEATS_ARRAY_SIZE) {
//                        beatsIndex = 0
//                    }
//
//                    BEATS_ARRAY[beatsIndex] = beatsPerMinute
//                    beatsIndex++
//
//                    var beatsArrayAverage = 0
//                    var beatsArrayCount = 0
//
//                    for (beatsEntry in BEATS_ARRAY) {
//                        if (beatsEntry > 0) {
//                            beatsArrayAverage += beatsEntry
//                            beatsArrayCount++
//                        }
//                    }
//
//                    val beatsAverage = beatsArrayAverage / beatsArrayCount
//                    previousBeatsAverage = beatsAverage
//                    log("beatsAverage: " + beatsAverage)
//                    publishSubject.onNext(Bpm(beatsAverage, currentPixelType))
//
//                    startTime = System.currentTimeMillis()
//                    beats = 0.0
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