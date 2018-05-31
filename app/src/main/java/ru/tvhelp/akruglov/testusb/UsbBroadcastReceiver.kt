package ru.tvhelp.akruglov.testusb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log

class UsbBroadcastReceiver: BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action

        when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> Log.d("TEST_USB_APP", "Usb device attached")
            UsbManager.ACTION_USB_DEVICE_DETACHED -> Log.d("TEST_USB_APP", "Usb device detached")
        }
    }
}