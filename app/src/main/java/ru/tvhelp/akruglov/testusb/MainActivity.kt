package ru.tvhelp.akruglov.testusb

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
import org.jetbrains.anko.toast
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity(), AnkoLogger {

    private lateinit var usbRepository: UsbModemRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbRepository = UsbModemRepository.getInstance(applicationContext)

        initUsb()

        usbRepository.listenAttach().subscribe {
            if (it) {
                initUsb()
            } else {
                textView.text = "USB device is not connected"
            }
        }
    }

    private fun initUsb() {
        usbRepository.requestPermission()
                .andThen(usbRepository.open(921600))
                .subscribe({
                    textView.text = "Device connected"

                    getModemInfo()
                }, {
                    textView.text = it.message!!
                })
    }

    private fun getModemInfo() {
        usbRepository.read()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
            textView.text = it
        })
        usbRepository.write("{test}").subscribe({}, {
            debug(it.message)
        })
    }
}


