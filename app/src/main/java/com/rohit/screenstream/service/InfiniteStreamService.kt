package com.rohit.screenstream.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rohit.screenstream.MainActivity
import com.rohit.screenstream.webrtc.CustomInfiniteSignalingClient
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap

class InfiniteStreamService : Service(), CustomInfiniteSignalingClient.SignalingListener {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "ScreenStreamInfiniteChannel"
        private const val NOTIF_ID = 1001
    }

    private val wakeLockHelper = KeepAliveWakeLockHelper()
    private var signalingClient: CustomInfiniteSignalingClient? = null
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var screenCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "ACTION_STOP") {
            stopStreamManually()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra("PROJECTION_DATA")
        val serverUrl = intent?.getStringExtra("SERVER_URL") ?: ""
        val streamId = intent?.getStringExtra("STREAM_ID") ?: "123456"
        val password = intent?.getStringExtra("PASSWORD") ?: ""

        if (data != null && !isRunning) {
            isRunning = true
            startForeground(NOTIF_ID, buildNotification("Streaming 24/7 (ID: $streamId)"))
            wakeLockHelper.acquire(this)
            initWebRtcAndCapture(data)
            signalingClient = CustomInfiniteSignalingClient(serverUrl, this).also {
                it.start(streamId, password)
            }
        }

        return START_STICKY
    }

    private fun initWebRtcAndCapture(projectionPermissionData: Intent) {
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        screenCapturer = ScreenCapturerAndroid(projectionPermissionData, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
            }
        })

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoSource = peerConnectionFactory!!.createVideoSource(screenCapturer!!.isScreencast)
        screenCapturer!!.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
        screenCapturer!!.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory!!.createVideoTrack("ARDAMSv0", videoSource)
        localVideoTrack?.setEnabled(true)
    }

    override fun onSignalingConnected() {}

    override fun onClientJoined(clientId: String) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val pc = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    signalingClient?.sendIceCandidate(clientId, candidate)
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: return

        localVideoTrack?.let { track ->
            pc.addTrack(track, listOf("ARDAMS"))
        }

        peerConnections[clientId] = pc

        val constraints = MediaConstraints()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            signalingClient?.sendOffer(clientId, desc.description)
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, desc)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    override fun onClientLeft(clientId: String) {
        peerConnections.remove(clientId)?.close()
    }

    override fun onRemoteAnswer(clientId: String, sdp: String) {
        val pc = peerConnections[clientId] ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun onRemoteCandidate(clientId: String, candidate: IceCandidate) {
        peerConnections[clientId]?.addIceCandidate(candidate)
    }

    private fun stopStreamManually() {
        isRunning = false
        signalingClient?.stopManually()
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        try { screenCapturer?.stopCapture() } catch (_: Exception) {}
        screenCapturer?.dispose()
        videoSource?.dispose()
        peerConnectionFactory?.dispose()
        eglBase?.release()
        wakeLockHelper.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (isRunning) stopStreamManually()
        super.onDestroy()
    }

    private fun buildNotification(content: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "24/7 Screen Stream", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenStream 24/7 Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }
}
