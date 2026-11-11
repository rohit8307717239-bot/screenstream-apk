package com.rohit.screenstream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.rohit.screenstream.service.InfiniteStreamService
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var streamIdInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var startStopBtn: Button
    private lateinit var statusText: TextView

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serverUrl = serverUrlInput.text.toString().trim()
            val streamId = streamIdInput.text.toString().trim()
            val password = passwordInput.text.toString()

            getSharedPreferences("screenstream_prefs", Context.MODE_PRIVATE).edit()
                .putString("server_url", serverUrl)
                .putString("stream_id", streamId)
                .apply()

            val serviceIntent = Intent(this, InfiniteStreamService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("PROJECTION_DATA", result.data)
                putExtra("SERVER_URL", serverUrl)
                putExtra("STREAM_ID", streamId)
                putExtra("PASSWORD", password)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            updateUiState(true)
        } else {
            Toast.makeText(this, "Screen Capture Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverUrlInput = findViewById(R.id.serverUrlInput)
        streamIdInput = findViewById(R.id.streamIdInput)
        passwordInput = findViewById(R.id.passwordInput)
        startStopBtn = findViewById(R.id.startStopBtn)
        statusText = findViewById(R.id.statusText)
        val batteryBtn: Button = findViewById(R.id.batteryBtn)

        val prefs = getSharedPreferences("screenstream_prefs", Context.MODE_PRIVATE)
        serverUrlInput.setText(prefs.getString("server_url", "wss://your-app-name.onrender.com/socket"))
        val defaultId = prefs.getString("stream_id", Random.nextInt(100000, 999999).toString())
        streamIdInput.setText(defaultId)

        updateUiState(InfiniteStreamService.isRunning)

        startStopBtn.setOnClickListener {
            if (InfiniteStreamService.isRunning) {
                val stopIntent = Intent(this, InfiniteStreamService::class.java).apply {
                    action = "ACTION_STOP"
                }
                startService(stopIntent)
                updateUiState(false)
            } else {
                val url = serverUrlInput.text.toString().trim()
                if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                    Toast.makeText(this, "Enter valid WebSocket URL starting with wss://", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                captureLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }

        batteryBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Battery Unrestricted Already Enabled!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateUiState(running: Boolean) {
        if (running) {
            statusText.text = "Status: LIVE 24/7 (Will NOT disconnect until you press STOP)"
            startStopBtn.text = "STOP STREAMING"
        } else {
            statusText.text = "Status: Idle (Ready to Stream)"
            startStopBtn.text = "START 24/7 STREAM"
        }
    }
}
