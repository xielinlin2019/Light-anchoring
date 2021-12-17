package com.example.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.util.Log.INFO
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
import androidx.fragment.*
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Level.INFO
import kotlin.math.min

typealias LumaListener = (luma: Double?) -> Unit
typealias AnchorListener = (cx: Float, cy:Float, code:String?) -> Unit
private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1

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
    var encoderIds = arrayOf("01101001")
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
    private var matchedDevice: BluetoothDevice? = null
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private fun promptEnableBluetooth() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            val fragment = FragmentActivity()
            fragment.startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE)
        }
    }

    private val scanResults = mutableListOf<ScanResult>()
    private val characteristicsValue = mutableListOf<ByteArray>()
    private val countDownLatch = CountDownLatch(1)

    private var isScanning = false
    private var isConnecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startBLEScanning()
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
                        detectTextView.text = "encoderID:" + code
                        if(!scanResults.isEmpty()) {
                            val iterator = scanResults.iterator();
                            while(iterator.hasNext()) {
                                val result = iterator.next()
                                if(matchedDevice == null) {
                                    if (result.device.name != null && result.device.name.equals(code)) {
                                        matchedDevice = result.device
                                    }
                                }
                                if(matchedDevice != null){
                                    if(characteristicsValue.isEmpty() && isConnecting == false) {
                                        result.device.connectGatt(this, false, gattCallback)
                                        isConnecting = true
                                    }
                                    if(!characteristicsValue.isEmpty()) {
                                        val iterator2 = characteristicsValue.iterator();
                                        while(iterator2.hasNext()) {
                                            detectTextView.text =
                                                "encoderID:" + code + String(iterator2.next(), Charsets.UTF_8)
                                        }
                                    }
                                }
                            }
                        }
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

    private fun startBLEScanning() {
//        val filters = ArrayList<ScanFilter>()
//        val filter = ScanFilter.Builder()
//            .setDeviceName("ProjectZero")
//            .build()
//        filters.add(filter)
        if(!isScanning) {
            bluetoothAdapter.bluetoothLeScanner.startScan(
                null,
                scanSettings,
                scanCallback
            )
            isScanning = true;
        }
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

    override fun onResume() {
        super.onResume()
        if (!bluetoothAdapter.isEnabled) {
            promptEnableBluetooth()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
                startBLEScanning()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val indexQuery = scanResults.indexOfFirst { it.device.address == result.device.address }
            if (indexQuery != -1) { // A scan result already exists with the same address
                scanResults[indexQuery] = result
            } else {
                scanResults.add(result)
            }
            isScanning = false;
        }

        override fun onScanFailed(errorCode: Int) {
            Log.i("TAG","onScanFailed: code $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        // Result of a characteristic read operation
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    if (characteristic != null && characteristic.value != null) {
                        characteristicsValue.add(characteristic.value)
                    }
                }
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                gatt?.requestMtu(256)
                gatt?.discoverServices()
            } else {
                Log.i("1","1")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val it = gatt?.services?.iterator()
            while(it?.hasNext() == true) {
                val it2 = it.next().characteristics.iterator()
                while(it2.hasNext()) {
                    var temp = it2.next()
                    if(temp.uuid.equals(UUID.fromString("f0001131-0451-4000-b000-000000000000"))) {
                        gatt.readCharacteristic(temp)
                        //if(countDownLatch.await(3, TimeUnit.SECONDS)) {}
                    }
                }
            }

        }


    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
