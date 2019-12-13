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
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.*
import org.apache.commons.collections4.queue.CircularFifoQueue
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList


/**
 * Created by <a href="https://about.me/janrabe">Jan Rabe</a>.
 */
open class HeartRateOmeter {

    private val TAG: String = javaClass.simpleName

    companion object {
        var enableLogging: Boolean = false
        val FRAMES_PER_SECOND = 30
        internal val AVERAGE_ARRAY_SIZE = FRAMES_PER_SECOND * 10
        val EPS = 12 //15 30
        val MIN_RED_AVG_VALUE = 32000
        const val FINGER_DEBOUNCE_TIMEOUT_MS = 150L
    }

    enum class PulseType { OFF, ON }

    data class Bpm(
            val value: Int,
            val type: PulseType
    )

    private var wakeLockTimeOut: Long = 5*60000L

    protected var wakelock: PowerManager.WakeLock? = null

    protected val publishSubject: PublishSubject<Int>

    private val fingerDetectionSubject: PublishSubject<Boolean> = PublishSubject.create()

    public val chartDataSubject: PublishSubject<List<Pair<Float, Float>>> = PublishSubject.create()

    public val peakDataSubject: PublishSubject<List<Pair<Float, Float>>> = PublishSubject.create()

    protected var context: WeakReference<Context>? = null

    protected var cameraSupport: CameraSupport? = null

    private var powerManager: PowerManager? = null
        get() = context?.get()?.getSystemService(Context.POWER_SERVICE) as? PowerManager?

    private var fingerDetectionListener: ((Boolean) -> Unit)? = null

    private var fingerDetectionSubscription: Disposable? = null

    init {
        publishSubject = PublishSubject.create<Int>()
    }

    var averageTimer: Int = -1

    fun withAverageAfterSeconds(averageTimer: Int): HeartRateOmeter {
        this.averageTimer = averageTimer
        return this
    }

    fun observeFingerDetection(): Observable<Boolean> {
        return fingerDetectionSubject.startWith(false)
                .distinctUntilChanged()
                .debounce(FINGER_DEBOUNCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    fun observeBpmUpdates(surfaceView: SurfaceView): Observable<Int> {
        return observeBpmUpdates(surfaceView.context, surfaceView.holder)
    }

    protected fun observeBpmUpdates(context: Context, surfaceHolder: SurfaceHolder): Observable<Int> {

        val previewCallback = if (averageTimer == -1) {
            createCameraPreviewCallback()
        } else {
            createCameraPreviewCallback2()
        }

        val surfaceCallback = createSurfaceHolderCallback()

        this.context = WeakReference(context)
        return publishSubject
                .startWith(-1)
                .doOnSubscribe {
                    start(surfaceHolder, previewCallback, surfaceCallback)
                }
                .doOnDispose { cleanUp() }
    }

    protected fun start(
            surfaceHolder: SurfaceHolder,
            previewCallback: Camera.PreviewCallback,
            surfaceHolderCallback: SurfaceHolder.Callback
    ) {
        log("start")

        wakelock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, context?.get()?.javaClass?.canonicalName)
        wakelock?.acquire(wakeLockTimeOut)

        context?.get()?.let {
            cameraSupport = CameraModule.provideCameraSupport(context = it).open(0)
        }

        chartDataSubject.onNext(emptyList())
        peakDataSubject.onNext(emptyList())

        // portrait
        cameraSupport?.also {
            it.setDisplayOrientation(90)
            log(it.getOrientation(0).toString())
        }

        surfaceHolder.apply {
            addCallback(surfaceHolderCallback)
            setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
            addCameraSupportCallbacks(this, previewCallback)
        }

        fingerDetectionSubscription = observeFingerDetection().subscribe({ detected ->
            fingerDetected = detected
        }, Throwable::printStackTrace)

        startPreview()
    }

    private fun addCameraSupportCallbacks(
            surfaceHolder: SurfaceHolder,
            previewCallback: Camera.PreviewCallback? = null
    ) {
        try {
            cameraSupport?.apply {
                setPreviewDisplay(surfaceHolder)
                previewCallback?.also { previewCallback ->
                    setPreviewCallback(previewCallback)
                }
            }
        } catch (throwable: Throwable) {
            if (enableLogging) {
                throwable.printStackTrace()
            }
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
        }

        cameraSupport?.parameters = parameters

        cameraSupport?.addBuffers()
    }

    protected fun createSurfaceHolderCallback(): SurfaceHolder.Callback {
        return object : SurfaceHolder.Callback {

            override fun surfaceCreated(holder: SurfaceHolder) {
                addCameraSupportCallbacks(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                startPreview()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        }
    }

    private var fingerDetected: Boolean = false
        set(value) {
            if (field != value) {
                fingerDetectionListener?.invoke(value)
                if (value == false) {
                    //clearChartData()
                    wakelock?.also { wakelock ->
                        if (wakelock.isHeld() == true) {
                            Log.d(TAG,"release wakelock")
                            wakelock.release()
                        }
                    }
                } else {
                    wakelock?.also { wakelock ->
                        if (wakelock.isHeld() != true) {
                            Log.d(TAG, "acquire wakelock")
                            wakelock.acquire(wakeLockTimeOut)
                        }
                    }
                }
            }
            field = value
        }

    private fun clearChartData() {
        peakDataSubject.onNext(emptyList())
        chartDataSubject.onNext(emptyList())
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

                if (cameraSupport == null) {
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
                    fingerDetectionSubject.onNext(false)
                    return
                }

                fingerDetectionSubject.onNext(true)

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

                publishSubject.onNext(bpm)

                counter++

                PROCESSING.set(false)
            }
        }
    }

    protected fun createCameraPreviewCallback2(): Camera.PreviewCallback {

        return object : Camera.PreviewCallback {
            var startTime = System.currentTimeMillis()

            val averageArray: LinkedList<Int> = LinkedList()
            val derivArray: MutableList<Int> = ArrayList()
            val peakPositions = ArrayList<Int>()

            var buffer: ByteArray? = null

            override fun onPreviewFrame(data: ByteArray?, camera: Camera) {
                if (data == null) {
                    log("Data is null!")
                    return
                }

                if (cameraSupport == null) {
                    return
                }

                val size = camera.parameters.previewSize
                if (size == null) {
                    log("Size is null!")
                    return
                }

//                buffer?.also { buffer ->
//                    data.copyInto(buffer)
//                } ?: data.clone()

                val buf = buffer
                if (buf == null) {
                    buffer = data.clone()
                } else {
                    data.copyInto(buf)
                }

                cameraSupport?.addCallbackBuffer(data)

                if (getTotalTimeSec(startTime) < averageTimer) {
                    return
                }

                runBlocking {
                    withContext(Dispatchers.Default) {
                        val heartRate = calculateBpm(
                                buffer,
                                averageArray,
                                derivArray,
                                peakPositions,
                                size
                        )
                        if (heartRate > 0) {
                            publishSubject.onNext(heartRate)
                        }
                    }
                }
            }
        }
    }

    suspend fun calculateBpm(
            buf: ByteArray?,
            averageArray: LinkedList<Int>,
            derivArray: MutableList<Int>,
            peakPositions: MutableList<Int>,
            size: Camera.Size
    ): Int {
        val width = size.width
        val height = size.height
        log("buffer width=${width} height=$height")
        val imageAverage = MathHelper.decodeYUV420SPtoRedAvg(buf, width, height)
        if (imageAverage == 0 || imageAverage < MIN_RED_AVG_VALUE) {
            log("fingerDetected is false")
            fingerDetectionSubject.onNext(false)
        } else {
            log("fingerDetected is true")
            if (fingerDetected == false) {
                clearChartData()
                averageArray.clear()
            }
            fingerDetectionSubject.onNext(true)

            updateAverageArray(
                    averageArray,
                    imageAverage,
                    AVERAGE_ARRAY_SIZE
            )

            updateChartData(averageArray)

            // Step 1. Calculate array of derivatives
            calculateDerivativesArray(
                    averageArray,
                    derivArray
            )

            // Step 2. Calculate peaks

            findPeakPositions(peakPositions, derivArray, EPS)

            // Step 3. Calculate distances between peaks
            val distances = distancesFromPeaks(peakPositions)

            // Step 4. Remove distances which are too far
            //  from mean value
            val finalDistances = removeDistancesFarFromMean(distances)

            if (finalDistances.isNotEmpty()) {
                updatePeakData(peakPositions, averageArray)

                val mean = finalDistances.sum() / finalDistances.size

                val heartRate = FRAMES_PER_SECOND * 60 / mean
                log("heartRate=$heartRate" +
                        "  mean=$mean peaks=${peakPositions.size}")
                return heartRate
            }
        }
        return 0
    }

    private fun getTotalTimeSec(startTime: Long): Int {
        val endTime = System.currentTimeMillis()
        val totalTimeInSecs = (endTime - startTime) / 1000.0
        log("totalTimeInSecs: " + totalTimeInSecs + " >= averageTimer: " + averageTimer)
        return totalTimeInSecs.toInt()
    }

    private fun updateAverageArray(averageArray: LinkedList<Int>,
                                   imageAverage: Int,
                                   maxSize: Int) {
        while (averageArray.size >= maxSize) {
            averageArray.removeFirst()
        }
        averageArray.add(imageAverage)
    }

    private fun updateChartData(averageArray: List<Int>) {
        val chartData = ArrayList<Pair<Float, Float>>()
        for (i in 0 until averageArray.size) {
            chartData.add(i.toFloat() to averageArray[i].toFloat())
        }
        chartDataSubject.onNext(chartData)
    }

    private fun findPeakPositions(
            peakPositions: MutableList<Int>,
            derivArray: List<Int>,
            eps: Int) {
        peakPositions.clear()
        for (i in 0..derivArray.size) {
            if (i >= eps && i < derivArray.size - eps) {
                val value = derivArray.get(i)
                if (value == findMin(derivArray.subList(i - eps, i + 1)) &&
                        value == findMin(derivArray.subList(i, i + eps + 1))) {
                    //peaks.add(i to value)
                    peakPositions.add(i)
                }
            }
        }
    }

    private fun calculateDerivativesArray(
            averageArray: List<Int>,
            derivArray: MutableList<Int>) {
        derivArray.clear()
        for (i in 0..averageArray.size) {
            if (i > 0 && i < averageArray.size - 1) {
                val value = (averageArray.get(i - 1) + averageArray.get(i + 1)) / 2
                derivArray.add(value)
            }
        }
    }

    private fun getResultPeaks(peaks: List<Pair<Int, Int>>, distancesSize: Int)
            : ArrayList<Int> {
        val iterator = peaks.iterator()
        val resultPeaksList = ArrayList<Int>()
        while (iterator.hasNext() && resultPeaksList.size <
                distancesSize + 1) {
            val item = iterator.next()
            resultPeaksList.add(item.first)
        }
        return resultPeaksList
    }

    private fun distancesFromPeaks(list: List<Int>): ArrayList<Int> {
        val resultDistances = ArrayList<Int>()
        for (i in 1 until list.size) {
            resultDistances.add(Math.abs(list[i] - list[i - 1]))
        }
        return resultDistances
    }

    private fun updatePeakData(list: List<Int>, averageArray: LinkedList<Int>) {
        val peaksChartData = list.map {
            it.toFloat() to averageArray[it].toFloat()
        }

        peakDataSubject.onNext(peaksChartData)
    }

    private fun excludeIfDistanceLessThanMin(list: List<Int>): List<Int> {
        val result = ArrayList<Int>()

        val minDistance = 30 * 60 / 200
        for (k in 0 until list.size) {
            val item = list[k]
            val prev = if (k == 0) null else list[k - 1]
            val next = if (k == list.size - 1) 0 else
                list[k + 1]
            val excludeByPrev = if (prev == null) false else {
                Math.abs(item - prev) < minDistance
            }
            val excludeByNext = if (next == null) false else {
                Math.abs(item - next) < minDistance
            }
            if (!excludeByPrev && !excludeByNext) {
                result.add(item)
            }
        }
        return result
    }

    private fun removePeaksWithDistancesFarFromMean(list: List<Int>): List<Int> {
        val resultList = ArrayList<Int>()
        var acc = 0
        for (i in 1 until list.size) {
            acc += Math.abs(list[i] - list[i - 1])
        }
        val meanDistance2 = acc / (list.size - 1)

        for (k in 0 until list.size) {
            val item = list[k]
            val prev = if (k == 0) null else list[k - 1]
            val next = if (k == list.size - 1) 0 else
                list[k + 1]
            val excludeByPrev = if (prev == null) false else {
                val dist = Math.abs(item - prev)
                (dist - meanDistance2) * 100 / dist > 25
            }
            val excludeByNext = if (next == null) false else {
                val dist = Math.abs(item - next)
                (dist - meanDistance2) * 100 / dist > 25
            }
            if (!excludeByPrev && !excludeByNext) {
                resultList.add(item)
            }
        }
        return resultList
    }

    private fun removeDistancesFarFromMean(resultDistances: MutableList<Int>): List<Int> {
        if (resultDistances.isEmpty()) {
            return resultDistances
        }
        val distMean = resultDistances.sum() / resultDistances.size
        val distIt = resultDistances.iterator()
        while (distIt.hasNext()) {
            val dst = distIt.next()
            if (Math.abs(dst - distMean) > 25 * dst / 100) {
                distIt.remove()
            }
        }
        return resultDistances
    }

    fun findMin(list: List<Int>?): Int? {

        // check list is empty or not
        if (list == null || list.size == 0) {
            return Integer.MIN_VALUE
        }

        // create a new list to avoid modification
        // in the original list
        val sortedlist = ArrayList(list)

        // sort list in natural order
        sortedlist.sortDescending()

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

        fingerDetectionSubscription?.also {
            it.dispose()
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