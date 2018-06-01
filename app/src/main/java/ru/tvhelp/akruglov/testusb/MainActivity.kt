package ru.tvhelp.akruglov.testusb

import android.support.v7.app.AppCompatActivity
import android.os.Bundle

class MainActivity : AppCompatActivity() {

    lateinit var usbRepository: UsbModemRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbRepository = UsbModemRepository(applicationContext)
        usbRepository.initRx()
    }
}
