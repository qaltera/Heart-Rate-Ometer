package de.charite.balsam.utils.camera

import android.hardware.Camera
import android.os.Handler
import android.view.SurfaceHolder
import android.os.HandlerThread
import kotlinx.coroutines.*
import java.lang.Runnable
import java.util.concurrent.locks.ReentrantLock
import android.graphics.ImageFormat
import android.util.Log


class CameraPreLolipop : CameraSupport() {

    private var camera: Camera? = null

    override var parameters: Camera.Parameters?
        get() = camera?.parameters
        set(value) {
            camera?.parameters = value
        }

    override fun open(cameraId: Int): CameraSupport {
        runBlocking {
            withContext(newSingleThreadContext("camera_thread")) {
                openCamera(cameraId)
            }
        }
        return this
    }

    private fun addBuffer(size: Int) {
        val frameBuffer = ByteArray(size)
        Log.d("HeartCheck", "Adding buffers of size $size")
        camera?.addCallbackBuffer(frameBuffer)
    }

    override fun getOrientation(cameraId: Int): @CameraOrientation Int {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        return info.orientation
    }

    override fun setDisplayOrientation(orientation: @CameraOrientation Int) {
        camera?.setDisplayOrientation(orientation)
    }

    override fun setPreviewDisplay(holder: SurfaceHolder?) {
        camera?.setPreviewDisplay(holder)
    }

    override fun addCallbackBuffer(ba: ByteArray) {
        camera?.addCallbackBuffer(ba)
    }

    override fun setPreviewCallback(previewCallback: Camera.PreviewCallback?) {
        if (previewCallback != null) {
            Log.d("HeartCheck", "setPreviewCallback")

            camera?.setPreviewCallbackWithBuffer(previewCallback)
        }
//        camera?.setPreviewCallback(previewCallback)
    }

    override fun addBuffers() {
        val size = bufferSize()
        if (size > 0) {
            for (i in 0..10) {
                addBuffer(size)
            }
        }
    }

    fun bufferSize(): Int {
        val parameters = parameters
        return parameters?.let {
            val previewFormat = parameters.getPreviewFormat()
            val bitsperpixel = ImageFormat.getBitsPerPixel(previewFormat)
            val byteperpixel = bitsperpixel / 8F
            val camerasize = parameters.getPreviewSize()
            camerasize.width * camerasize.height * byteperpixel
        }?.toInt() ?: 0
    }

    override fun startPreview() {
        camera?.startPreview()
    }

    override fun stopPreview() {
        camera?.stopPreview()
    }

    override fun release() {
        camera?.release()
    }

    override fun hasFlash(): Boolean {
        if (camera == null) {
            return false
        }

        if (parameters?.flashMode == null) {
            return false
        }

        val supportedFlashModes = parameters?.supportedFlashModes
        return !(supportedFlashModes == null
                || supportedFlashModes.isEmpty()
                || supportedFlashModes.size == 1 && supportedFlashModes[0] == Camera.Parameters.FLASH_MODE_OFF)
    }

    override fun setFlash(flashMode: Int): Boolean {
        if (camera == null) {
            return false
        }

        if (parameters?.flashMode == null) {
            return false
        }

        val supportedFlashModes = parameters?.supportedFlashModes
        if (flashMode == 1 && supportedFlashModes?.get(2) == Camera.Parameters.FLASH_MODE_AUTO) {
            parameters?.flashMode = Camera.Parameters.FLASH_MODE_AUTO
        } else if (flashMode == 2 && supportedFlashModes?.get(1) == Camera.Parameters.FLASH_MODE_ON) {
            parameters?.flashMode = Camera.Parameters.FLASH_MODE_ON
        } else if (flashMode == 3 && supportedFlashModes?.get(0) == Camera.Parameters.FLASH_MODE_OFF) {
            parameters?.flashMode = Camera.Parameters.FLASH_MODE_OFF
        }
        parameters = parameters
        return true
    }

    private fun openCamera(cameraId: Int) {
        this.camera = Camera.open(cameraId)
    }
}