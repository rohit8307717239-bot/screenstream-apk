package com.rohit.screenstream.webrtc

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import java.util.concurrent.TimeUnit

class CustomInfiniteSignalingClient(
    private val serverWebsocketUrl: String,
    private val listener: SignalingListener
) {
    interface SignalingListener {
        fun onSignalingConnected()
        fun onClientJoined(clientId: String)
        fun onClientLeft(clientId: String)
        fun onRemoteAnswer(clientId: String, sdp: String)
        fun onRemoteCandidate(clientId: String, candidate: IceCandidate)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var isManuallyStopped = false
    private var currentStreamId: String = ""
    private var currentPassword: String = ""

    fun start(streamId: String, password: String) {
        isManuallyStopped = false
        currentStreamId = streamId
        currentPassword = password
        connectSocket()
    }

    private fun connectSocket() {
        if (isManuallyStopped) return
        val request = try {
            Request.Builder().url(serverWebsocketUrl).build()
        } catch (e: Exception) {
            return
        }

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val registerMsg = JSONObject().apply {
                    put("type", "HOST_REGISTER")
                    put("streamId", currentStreamId)
                    put("password", currentPassword)
                }
                webSocket.send(registerMsg.toString())
                startHeartbeat()
                listener.onSignalingConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "CLIENT_JOINED" -> listener.onClientJoined(json.getString("clientId"))
                        "CLIENT_LEFT" -> listener.onClientLeft(json.getString("clientId"))
                        "ANSWER" -> listener.onRemoteAnswer(json.getString("clientId"), json.getString("sdp"))
                        "CANDIDATE" -> {
                            val clientId = json.getString("clientId")
                            val candObj = json.getJSONObject("candidate")
                            val candidate = IceCandidate(
                                candObj.optString("sdpMid"),
                                candObj.optInt("sdpMLineIndex"),
                                candObj.getString("candidate")
                            )
                            listener.onRemoteCandidate(clientId, candidate)
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isManuallyStopped) scheduleReconnect()
            }
        })
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && !isManuallyStopped) {
                delay(25_000)
                val ping = JSONObject().apply { put("type", "PING") }
                webSocket?.send(ping.toString())
            }
        }
    }

    private fun scheduleReconnect() {
        heartbeatJob?.cancel()
        if (isManuallyStopped) return
        scope.launch {
            delay(4_000)
            connectSocket()
        }
    }

    fun sendOffer(clientId: String, sdp: String) {
        val msg = JSONObject().apply {
            put("type", "OFFER")
            put("clientId", clientId)
            put("sdp", sdp)
        }
        webSocket?.send(msg.toString())
    }

    fun sendIceCandidate(clientId: String, candidate: IceCandidate) {
        val candObj = JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }
        val msg = JSONObject().apply {
            put("type", "CANDIDATE")
            put("clientId", clientId)
            put("candidate", candObj)
        }
        webSocket?.send(msg.toString())
    }

    fun stopManually() {
        isManuallyStopped = true
        heartbeatJob?.cancel()
        val stopMsg = JSONObject().apply { put("type", "HOST_STOP") }
        webSocket?.send(stopMsg.toString())
        webSocket?.close(1000, "Manual Stop")
        webSocket = null
    }
}
