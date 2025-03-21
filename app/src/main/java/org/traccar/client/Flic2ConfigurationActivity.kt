package org.traccar.client

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flic.flic2libandroid.Flic2Button
import io.flic.flic2libandroid.Flic2ButtonListener
import io.flic.flic2libandroid.Flic2Manager
import io.flic.flic2libandroid.Flic2ScanCallback
import androidx.core.net.toUri

class Flic2ConfigurationActivity : AppCompatActivity() {

    private var isScanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flic2_configuration)

        val buttonScan = findViewById<Button>(R.id.button_scan)
        val textViewStatus = findViewById<TextView>(R.id.text_view_status)

        buttonScan.setOnClickListener {
            if (isScanning) {
                Flic2Manager.getInstance().stopScan()
                isScanning = false
                buttonScan.text = "Scan new button"
                textViewStatus.text = ""
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Build.VERSION.SDK_INT < 31 || applicationInfo.targetSdkVersion < 31) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                            return@setOnClickListener
                        }
                    } else {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
                            return@setOnClickListener
                        }
                    }
                }

                buttonScan.text = "Cancel scan"
                textViewStatus.text = "Press and hold down your Flic2 button until it connects"

                isScanning = true

                Flic2Manager.getInstance().startScan(object : Flic2ScanCallback {
                    override fun onDiscoveredAlreadyPairedButton(button: Flic2Button) {
                        textViewStatus.text = "Found an already paired button. Try another button."
                    }

                    override fun onDiscovered(bdAddr: String) {
                        textViewStatus.text = "Found Flic2, now connecting..."
                    }

                    override fun onConnected() {
                        textViewStatus.text = "Connected. Now pairing..."
                    }

                    override fun onComplete(result: Int, subCode: Int, button: Flic2Button?) {
                        isScanning = false
                        buttonScan.text = "Scan new button"
                        if (result == Flic2ScanCallback.RESULT_SUCCESS) {
                            textViewStatus.text = "Scan wizard success!"
                            button?.let { listenToButton(it) }
                        } else {
                            textViewStatus.text = "Scan wizard failed with code ${Flic2Manager.errorCodeToString(result)}"
                        }
                    }

                    override fun onAskToAcceptPairRequest() {
                        textViewStatus.text = "Please press \"Pair & Connect\" in the system dialog..."
                    }
                })
            }
        }
    }

    private fun listenToButton(button: Flic2Button) {
        button.addListener(object : Flic2ButtonListener() {
            override fun onButtonUpOrDown(
                button: Flic2Button,
                wasQueued: Boolean,
                lastQueued: Boolean,
                timestamp: Long,
                isUp: Boolean,
                isDown: Boolean
            ) {
                if (isDown) {
                    Toast.makeText(this@Flic2ConfigurationActivity, "Button pressed", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (Build.VERSION.SDK_INT < 31 || applicationInfo.targetSdkVersion < 31) {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    findViewById<Button>(R.id.button_scan).performClick()
                } else {
                    Toast.makeText(this, "Scanning needs Location permission, which you have rejected", Toast.LENGTH_SHORT).show()
                }
            } else {
                if (grantResults.size >= 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    findViewById<Button>(R.id.button_scan).performClick()
                } else {
                    Toast.makeText(this, "Scanning needs permissions for finding nearby devices, which you have rejected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val packageName = packageName
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent().apply {
                    action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            }
        }
    }
}