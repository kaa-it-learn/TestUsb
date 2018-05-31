package ru.tvhelp.akruglov.testusb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import android.net.ConnectivityManager
import android.content.IntentFilter



class UsbModemRepository(private val context: Context) {

    private lateinit var manager: UsbManager
    private lateinit var driver: UsbSerialDriver
    private lateinit var port: UsbSerialPort
    private lateinit var receiver: UsbBroadcastReceiver

    /*fun requestPermission() {
        manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val prober = UsbSerialProber.getDefaultProber()
        val availableDrivers = prober.findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            // HANDLE ERROR
        } else {
            driver = availableDrivers[0]
            val device = driver.device
            val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent())
        }
    }*/

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
}