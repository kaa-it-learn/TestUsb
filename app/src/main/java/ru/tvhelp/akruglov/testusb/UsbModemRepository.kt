package ru.tvhelp.akruglov.testusb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import android.content.IntentFilter
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.github.karczews.rxbroadcastreceiver.RxBroadcastReceivers
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.hoho.android.usbserial.util.SerialInputOutputManager
import io.reactivex.*
import io.reactivex.disposables.Disposable
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
//import io.reactivex.android.schedulers.AndroidSchedulers
//import io.reactivex.schedulers.Schedulers
import java.io.IOException
import java.lang.Exception
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors


const val ACTION_USB_PERMISSION = "ru.tvhelp.ru.testusb.USB_PERMISSION"
const val TAG = "TEST_USB_APP"


class UsbDeviceNotFoundException(override var message:String): Exception(message)
class UsbDevicePermissionDeniedException(override var message: String): Exception(message)
class UsbDeviceConnectionFailedException(override var message: String): Exception(message)
class UsbDeviceWriteFailedException(override var message: String): Exception(message)
class UsbDeviceParserFailedException(override var message: String): Exception(message)


class UsbModemRepository private constructor(private val context: Context): AnkoLogger {

    companion object: SingletonHolder<UsbModemRepository, Context>(::UsbModemRepository)

    private val manager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private lateinit var driver: UsbSerialDriver
    private var port: UsbSerialPort? = null
    private var dataEmitter: ObservableEmitter<ByteArray?>? = null

    private val executor = Executors.newSingleThreadExecutor()
    private var serialIoManager: SerialInputOutputManager? = null
    private var buffer: String = ""

    private var listener = object: SerialInputOutputManager.Listener {
        override fun onRunError(e: Exception?) {
            debug("Serial IO Manager's Listener stopped")
        }

        override fun onNewData(data: ByteArray?) {
            this@UsbModemRepository.updateReceivedData(data)
        }
    }

    // Detect device attach and detach events
    fun listenAttach() : Observable<Boolean> {
        var filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)

        return RxBroadcastReceivers.fromIntentFilter(context, filter)
                //.subscribeOn(Schedulers.io())
                //.observeOn(AndroidSchedulers.mainThread())
                .map {
                    when (it.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            debug( "Usb device attached")
                            onDeviceAttached()
                            true
                        }
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            debug( "Usb device detached")
                            onDeviceDetached()
                            false
                        }
                        else -> false
                    }
                }
    }

    private fun listenIntent(emitter: CompletableEmitter) {
        val filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)

        RxBroadcastReceivers.fromIntentFilter(context, filter).subscribe(object: Observer<Intent> {

            private lateinit var disposable: Disposable

            override fun onSubscribe(d: Disposable) {
                disposable = d
            }

            override fun onNext(intent: Intent) {
                synchronized(this@UsbModemRepository) {

                    disposable.dispose()

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        debug( "Permission accessed")

                        emitter.onComplete()
                    } else {
                        debug( "Permission denied")
                        emitter.onError(UsbDevicePermissionDeniedException("USB device permission denied"))
                    }
                }
            }

            override fun onError(e: Throwable) {

            }

            override fun onComplete() {

            }

        })

        val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), 0)
        manager.requestPermission(driver.device, pendingIntent)
    }

    private fun onDeviceAttached() {

    }

    private fun onDeviceDetached() {
        close()
    }

    // Check user permissions before open usb device
    // IN THREAD
    fun requestPermission(): Completable = Completable.create {
        val prober = UsbSerialProber.getDefaultProber()
        val availableDrivers = prober.findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            debug( "No available drivers")
            it.onError(UsbDeviceNotFoundException("No USB device attached with suitable driver"))
        } else {
            driver = availableDrivers[0]
            val device = driver.device

            if (!manager.hasPermission(device)) {
                listenIntent(it)
            } else {
                it.onComplete()
            }
        }
    }

    // Open the serial port
    // IN THREAD
    fun open(baudRate: Int): Completable = Completable.create {
        val connection: UsbDeviceConnection =
                manager.openDevice(driver.device) ?:
                throw UsbDeviceConnectionFailedException("Can not open usb device")

        port = driver.ports[0]

        try {
            port!!.open(connection)
            port!!.setParameters(baudRate, UsbSerialPort.DATABITS_8,
                                 UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Throwable) {
            it.onError(UsbDeviceConnectionFailedException(e.message!!))
        }

        Log.d(TAG, "Serial port opened!")

        onDeviceStateChange()

        it.onComplete()
    }

    // Write on the serial port
    // IN THREAD
    fun write(data: String): Completable = Completable.create {
        if (port == null) {
            it.onError(UsbDeviceWriteFailedException("Write to closed port"))
        }

        port?.let { p ->

            try {
                Log.d(TAG, data)
                val buffer = data.toByteArray()
                p.write(buffer, 1000)
                it.onComplete()
            } catch (e: IOException) {
                Log.d(TAG, e.message)
                it.onError(UsbDeviceWriteFailedException(e.message!!))
            }
        }
    }

    // Close the serial port
    // IN THREAD, HANDLE EXCEPTIONS
    private fun close() {
        try {
            if (port != null) {
                port!!.close()
            }
            port = null
            // HANDLE SUCCESS
        } catch (e: IOException) {
            debug(e.message)
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
            Log.d(TAG,  "Stopping IO manager")
            serialIoManager?.stop()
            serialIoManager = null
        }
    }

    // Dispatch read data
    private fun updateReceivedData(data: ByteArray?) {
        Log.d("TAG", data.toString())
        dataEmitter?.let {
            it.onNext(data!!)
        }
    }

    // Register callback for read data
    // IN THREAD
    private fun readRaw(): Observable<ByteArray?> = Observable.create{
        Log.d(TAG, "Registering Read Callback")
        dataEmitter = it
    }

    fun read(): Observable<String> = readRaw().flatMap {
        Observable.create<String> { emitter -> parseRaw(it, emitter) }
    }

    private fun parseRaw(data: ByteArray?, emitter: ObservableEmitter<String>) {

        data?.let {
            if (data.isNotEmpty()) {
                extractPackage(data.toString(StandardCharsets.UTF_8), emitter)
            }
        }
    }

    private fun extractPackage(str: String, emitter: ObservableEmitter<String>) {
        val s = str.trim()

        val endOfPackage = s.indexOf("}")

        if (endOfPackage == -1) {
            // Строка не содержит '}'
            buffer += s
        } else {
            // Строка содержит
            val startOfPackage = buffer.lastIndexOf("{")

            if (startOfPackage != -1) {
                // Начало пакета в буфере
                var pkg = buffer.substring(startOfPackage + 1)
                buffer = buffer.substring(0, startOfPackage)
                pkg += s.substring(0, endOfPackage)
                parsePackage(pkg, emitter)
                extractPackage(s.substring(endOfPackage + 1), emitter)
            } else {
                // Начала пакета нет в буфере
                val startOfPackage = s.indexOf("{")

                if (startOfPackage == -1) {
                    emitter.onError(UsbDeviceParserFailedException("Wrong package recevied"))
                } else {
                    parsePackage(s.substring(startOfPackage + 1, endOfPackage), emitter)
                    extractPackage(s.substring(endOfPackage + 1), emitter)
                }
            }
        }
    }

    private fun parsePackage(pkg: String, emitter: ObservableEmitter<String>) {
        createObject(pkg)
        emitter.onNext(pkg)
    }

    private fun createObject(s: String) {
        val gson = Gson()
        try {
            val modemInfo = gson.fromJson("{$s}", ModemInfo::class.java)
            Log.d(TAG, modemInfo.toString())
        } catch(e: JsonSyntaxException) {
            Log.d(TAG, e.message)
        }
    }
}

data class ModemInfo(
        val dir: Int,
        val level: Int,
        val nid: Int,
        val group: Int,
        val mac: String,
        val smac: String,
        val rssi: Int,
        val mrssi: Int,
        val rfch: Int,
        val rfpwr: Int,
        val pwm: Int,
        val pwmct: Int,
        val pow: Int,
        val lux: Int,
        val temp: Int,
        val energy: Int,
        val rng: Int,
        val tlevel: Int,
        val date: Int,
        val lat: Double,
        val lon: Double,
        val vtd: Int,
        val vll: Int,
        val rise: String,
        val set: String,
        val id: Int,
        val d1: Int,
        val p1: Int,
        val d2: Int,
        val p2: Int
)