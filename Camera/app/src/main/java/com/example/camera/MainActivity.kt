package com.example.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.collection.CircularArray
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayDeque
import kotlin.math.max
import kotlin.math.min

typealias LumaListener = (luma: Double?) -> Unit
typealias AnchorListener = (cx: Float, cy:Float, code:String?) -> Unit

class Deque(private val maxlen: Int) {
    var deque = ArrayDeque<Int>()
    fun append(a: Int){
        if (deque.size == maxlen)
            deque.removeFirst()
        deque.addLast(a)
    }
    fun getString(): String {
        return deque.joinToString("")+deque.joinToString("")
    }
    override fun toString(): String {
        return deque.toString()
    }
}


private class LuminosityAnalyzer(private val listener: AnchorListener) : ImageAnalysis.Analyzer {
    var cx = 0f
    var cy = 0f
    var m: Int = 0
    var frame = 0
    var codeq = Deque(8)
    var encoderIds = arrayOf("01101001","11111111")
    var code: String? = null

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()    // Rewind the buffer to zero
        val data = ByteArray(remaining())
        get(data)   // Copy the buffer into a byte array
        return data // Return the byte array
    }
    override fun analyze(image: ImageProxy) {
        frame += 1
        val buffer = image.planes[0].buffer
        val data = buffer.toByteArray()

        val pixels = data.map { it.toInt() and 0xFF }

        val l = pixels.size
        val h = image.height
        val w = image.width
        m = max(m, pixels.maxOrNull()!!)
        if (frame % 150 == 0) {
            m = pixels.maxOrNull()!!
        }
        var idx = 0
        var m01 = 0f
        var m10 = 0f
        var m00 = 0f
        for (i in 0 until h){
            for (j in 0 until w){
                if (pixels[i*w+j] > 0.99 * m){
                    m10 += j
                    m01 += i
                    m00 += 1
                }
            }
        }
        if (m00 > 10){
            cx = m10/m00/w
            cy = m01/m00/h
            codeq.append(1)
        }
        else{
            codeq.append(0)
        }
        //println("$cx, $cy, $m, ${codeq}")
        println(codeq.getString())
        for (id in encoderIds){
            if (codeq.getString().contains(id)){
                code = id
            }
        }


        listener(cx, cy, code)

        image.close()
    }
}


class MainActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listener for take photo button

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {}

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview

            var viewFinder = findViewById<PreviewView>(R.id.viewFinder)
            var view = findViewById<View>(R.id.view)
            var detectTextView = findViewById<TextView>(R.id.detectTextView)
            detectTextView.visibility = View.GONE

            println((view.layoutParams as ViewGroup.MarginLayoutParams).topMargin)

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
            imageAnalyzer.setAnalyzer(cameraExecutor, LuminosityAnalyzer { cx, cy, code ->
                println("${(viewFinder.height * cx).toInt()},${(viewFinder.width * cy).toInt()}")
                this@MainActivity.runOnUiThread(java.lang.Runnable {
                    (view.layoutParams as ViewGroup.MarginLayoutParams).apply{
                        topMargin =  (cx * viewFinder.height).toInt()
                        leftMargin = ((1-cy) * viewFinder.width).toInt()
                    }
                    view.visibility=View.GONE
                    view.visibility=View.VISIBLE
                    if (code != null){
                        (detectTextView.layoutParams as ViewGroup.MarginLayoutParams).apply{
                            topMargin =  (cx * viewFinder.height).toInt()
                            leftMargin = ((1-cy) * viewFinder.width).toInt()
                        }
                        detectTextView.visibility = View.VISIBLE
                        detectTextView.text = "encoderID:"+code
                    }
                })


                Log.d(TAG, "Average luminosity: $cx, $cy")
            })


            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}