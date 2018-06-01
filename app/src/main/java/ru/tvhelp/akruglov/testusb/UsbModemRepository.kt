package ru.tvhelp.akruglov.testusb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.github.karczews.rxbroadcastreceiver.RxBroadcastReceivers
import com.hoho.android.usbserial.util.SerialInputOutputManager
//import io.reactivex.android.schedulers.AndroidSchedulers
//import io.reactivex.schedulers.Schedulers
import org.json.JSONException
import java.io.IOError
import java.io.IOException
import java.lang.Exception
import java.util.concurrent.Executors


class UsbModemRepository(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "ru.tvhelp.ru.testusb.USB_PERMISSION"
        const val TAG = "TEST_USB_APP"
    }

    private lateinit var manager: UsbManager
    private lateinit var driver: UsbSerialDriver
    private var port: UsbSerialPort? = null
    private lateinit var receiver: UsbBroadcastReceiver

    private val executor = Executors.newSingleThreadExecutor()
    private var serialIoManager: SerialInputOutputManager? = null
    private var listener = object: SerialInputOutputManager.Listener {
        override fun onRunError(e: Exception?) {
            Log.d(TAG, "Runner stopped")
        }

        override fun onNewData(data: ByteArray?) {
            this@UsbModemRepository.updateReceivedData(data)
        }
    }

    fun init() {
        manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        receiver = UsbBroadcastReceiver()

        //val attachedIntent = PendingIntent.getBroadcast(context, 0, Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED), 0)

        val attachedFilter = IntentFilter()
        attachedFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        context.registerReceiver(receiver, attachedFilter)

        val detachedFilter = IntentFilter()
        detachedFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        context.registerReceiver(receiver, detachedFilter)


    }

    fun initRx() {
        manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        var filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)

        RxBroadcastReceivers.fromIntentFilter(context, filter)
                //.subscribeOn(Schedulers.io())
                //.observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    when (it.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            Log.d(TAG, "Usb device attached")
                            onDeviceAttached()
                        }
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> Log.d(TAG, "Usb device detached")
                    }
                }



        filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)

        RxBroadcastReceivers.fromIntentFilter(context, filter)
                //.subscribeOn(Schedulers.io())
                //.observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    if (it.action == ACTION_USB_PERMISSION) {
                        synchronized(this) {
                            val device = it.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

                            if (it.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                Log.d(TAG, "Permission accessed")
                            } else {
                                Log.d(TAG, "Permission denied")
                            }
                        }
                    }
                }
    }

    private fun onDeviceAttached() {
        requestPermission()
    }

    // Request permission the user for the app to use the USB/serial port
    // IN THREAD
    private fun requestPermission() {
        val prober = UsbSerialProber.getDefaultProber()
        val availableDrivers = prober.findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            // HANDLE ERROR
            Log.d(TAG, "No available drivers")
        } else {
            driver = availableDrivers[0]
            val device = driver.device
            val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), 0)


            manager.requestPermission(device, pendingIntent)
        }
    }

    // Open the serial port
    // IN THREAD
    fun open(baudRate: Int) {
        val connection: UsbDeviceConnection? = manager.openDevice(driver.device)

        if (connection != null) {
            port = driver.ports[0]

            try {

                port.open(connection)
                port.setParameters(baudRate, UsbSerialPort.DATABITS_8,
                        UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (e: IOError) {
                Log.d(TAG, e.message)
                // HANDLE error
            } catch (e: JSONException) {
                Log.d(TAG, e.message)
                // HANDLE error
            }

            Log.d(TAG, "Serial port opened!")
            // HANDLE SUCCESS
        } else {
            Log.d(TAG, "Cannot connect to the device!")
            // HANDLE ERROR
        }
        onDeviceStateChange()
    }

    // Write on the serial port
    // IN THREAD
    fun write(data: String) {
        if (port == null) {
            // HANDLE ERROR
        } else {
            try {
                Log.d(TAG, data)
                val buffer = data.toByteArray()
                port.write(buffer, 1000)
                // HANDLE SUCCESS
            } catch(e: IOException) {
                Log.d(TAG, e.message)
                // HANDLE ERROR
            }
        }
    }

    // Close the serial port
    // IN THREAD
    fun close() {
        try {
            if (port != null) {
                port.close()
            }
            port = null
            // HANDLE SUCCESS
        } catch (e: IOException) {
            Log.d(TAG, e.message)
            // HANDLE ERROR
        }
        onDeviceStateChange()
    }

    // Restart the observation of the serial connection
    private fun onDeviceStateChange() {
        stopIoManager()
        startIoManager()
    }

    // Start observing serial connection
    private fun startIoManager() {
        if (port != null) {
            Log.d(TAG, "Starting IO manager")
            serialIoManager = SerialInputOutputManager(port, listener)
            executor.submit(serialIoManager)
        }
    }

    // Stop observing serial connection
    private fun stopIoManager() {
        if (serialIoManager != null) {
            Log.d(TAG, "Stopping IO manager")
            serialIoManager.stop()
            serialIoManager = null
        }
    }

    // Dispatch read data
    private fun updateReceivedData(data: ByteArray?) {
        // TODO
        // if (readCallback != null)
        //     readCallback(data)
    }

    // Register callback for read data
    // IN THREAD
    fun registerReadCallback(/*call*/) {
        Log.d(TAG, "Registering Read Callback")
        // readCallback = call
    }
}