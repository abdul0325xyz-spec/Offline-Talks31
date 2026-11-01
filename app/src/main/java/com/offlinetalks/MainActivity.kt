package com.offlinetalks

import android.Manifest
import androidx.activity.ComponentActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.nearby.connection.Payload
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline Talks 2.0
 * Local peer-to-peer transport uses Google Nearby Connections P2P_CLUSTER.
 * The Nearby library negotiates Bluetooth/Wi-Fi local transports; no internet
 * service or cloud backend is used by this app's data path.
 */
class MainActivity : ComponentActivity() {
    private val client by lazy { Nearby.getConnectionsClient(this) }
    private val strategy = Strategy.P2P_CLUSTER
    private val serviceId = "com.offlinetalks.localchat"
    private val handler = Handler(Looper.getMainLooper())
    private val endpoints = ConcurrentHashMap<String, String>()
    private val messages = mutableListOf<ChatMessage>()
    private val incomingFiles = ConcurrentHashMap<Long, File>()
    private val pendingMeta = ConcurrentHashMap<Long, JSONObject>()
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null
    private var callRecorder: android.media.AudioRecord? = null
    private var callTrack: android.media.AudioTrack? = null
    @Volatile private var inCall = false
    @Volatile private var callMuted = false
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var compose: EditText
    private lateinit var scroll: ScrollView
    private lateinit var peerPanel: LinearLayout
    private var localName = "Offline User"

    data class ChatMessage(
        val id: String,
        var sender: String,
        var body: String,
        val mine: Boolean,
        val attachment: String? = null,
        val path: String? = null,
        var deleted: Boolean = false,
        var delivery: String = ""
    )

    private data class ChunkAssembly(
        val messageId: String,
        val sender: String,
        val total: Int,
        val chunks: Array<String?>,
        var received: Int = 0
    )

    // 12 KiB raw chunks keep each Base64 JSON BYTES payload comfortably below
    // Nearby Connections' practical single-payload size limits.
    private val chunkSize = 12 * 1024
    private val maxTextBytes = 10 * 1024 * 1024
    private val assemblies = ConcurrentHashMap<String, ChunkAssembly>()
    private val pendingAcks = ConcurrentHashMap<String, MutableSet<String>>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) startDiscoveryAndAdvertising()
            else setStatus("Nearby and microphone permissions are needed for full features.")
        }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            sendAttachment(uri)
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendAttachment(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localName = getSharedPreferences("profile", MODE_PRIVATE)
            .getString("name", Build.MODEL ?: "Offline User") ?: "Offline User"
        buildUi()
        requestPermissionsForFeatures()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xfff2f6f3.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            setBackgroundColor(0xff075b43.toInt())
        }
        header.addView(TextView(this).apply {
            text = "Offline Talks"; textSize = 23f; setTextColor(-1); setTypeface(null, 1)
        })
        status = TextView(this).apply {
            text = "Private nearby chat • Internet not required"
            textSize = 12f; setTextColor(0xffd7f8e8.toInt())
        }
        header.addView(status)
        root.addView(header)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        toolbar.addView(actionButton("Find nearby") { startDiscoveryAndAdvertising() }, weight())
        toolbar.addView(actionButton("Image") { pickImage.launch("image/*") }, weight())
        toolbar.addView(actionButton("File") { pickFile.launch(arrayOf("*/*")) }, weight())
        root.addView(toolbar)

        peerPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(peerPanel) }, LinearLayout.LayoutParams(-1, dp(100)))

        scroll = ScrollView(this)
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        compose = EditText(this).apply {
            hint = "Message"; minLines = 1; maxLines = 4
        }
        composerRow.addView(compose, LinearLayout.LayoutParams(0, -2, 1f))
        composerRow.addView(actionButton("Send") { sendText() })
        root.addView(composerRow)

        val mediaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mediaRow.addView(actionButton("Voice note") { startVoiceNote() }, weight())
        mediaRow.addView(actionButton("Stop & send") { stopVoiceNote() }, weight())
        mediaRow.addView(actionButton("Call") { startCall() }, weight())
        mediaRow.addView(actionButton("End") { endCall() }, weight())
        root.addView(mediaRow)
        root.addView(actionButton("Mute / unmute call") {
            callMuted = !callMuted
            setStatus(if (callMuted) "Microphone muted" else "Microphone unmuted")
        })
        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun weight() = LinearLayout.LayoutParams(0, dp(46), 1f)
    private fun actionButton(text: String, action: () -> Unit) =
        Button(this).apply { this.text = text; textSize = 11f; setOnClickListener { action() } }

    private fun requestPermissionsForFeatures() {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            p += Manifest.permission.NEARBY_WIFI_DEVICES
            p += Manifest.permission.POST_NOTIFICATIONS
        } else {
            p += Manifest.permission.ACCESS_FINE_LOCATION
        }
        p += Manifest.permission.RECORD_AUDIO
        val missing = p.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startDiscoveryAndAdvertising()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startDiscoveryAndAdvertising() {
        val adv = AdvertisingOptions.Builder().setStrategy(strategy).build()
        val disc = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(localName, serviceId, lifecycleCallback, adv)
            .addOnSuccessListener { setStatus("Advertising nearby • Internet not used") }
            .addOnFailureListener { setStatus("Advertising error: ${it.localizedMessage}") }
        client.startDiscovery(serviceId, discoveryCallback, disc)
            .addOnSuccessListener { setStatus("Searching nearby phones…") }
            .addOnFailureListener { setStatus("Discovery error: ${it.localizedMessage}") }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            handler.post {
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(TextView(this@MainActivity).apply {
                    text = info.endpointName; textSize = 14f
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                row.addView(actionButton("Connect") {
                    client.requestConnection(localName, id, lifecycleCallback)
                        .addOnFailureListener { setStatus("Connection failed: ${it.localizedMessage}") }
                })
                peerPanel.addView(row)
            }
        }
        override fun onEndpointLost(id: String) {
            handler.post { setStatus("A nearby device went out of range.") }
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            client.acceptConnection(id, payloadCallback)
            handler.post { setStatus("Connecting to ${info.endpointName}…") }
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            handler.post {
                if (result.status.isSuccess) {
                    endpoints[id] = id
                    setStatus("Connected: ${endpoints.size} device(s) • Offline")
                } else setStatus("Connection failed (${result.status.statusCode})")
            }
        }
        override fun onDisconnected(id: String) {
            endpoints.remove(id)
            handler.post { setStatus("Device disconnected • ${endpoints.size} remaining") }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val raw = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
                    handleControl(id, raw)
                }
                Payload.Type.FILE -> {
                    val file = payload.asFile()?.asJavaFile() ?: return
                    val meta = pendingMeta.remove(payload.id)
                    handler.post {
                        addMessage(ChatMessage(
                            UUID.randomUUID().toString(),
                            endpoints[id] ?: "Nearby",
                            meta?.optString("name") ?: file.name,
                            false, meta?.optString("kind") ?: "FILE", file.absolutePath
                        ))
                    }
                }
                Payload.Type.STREAM -> {
                    val stream = payload.asStream()?.asInputStream() ?: return
                    val out = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
                    stream.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
                    handler.post {
                        addMessage(ChatMessage(UUID.randomUUID().toString(), "Nearby", "Voice note", false, "VOICE", out.absolutePath))
                    }
                }
                else -> {}
            }
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) handler.post { setStatus("Transfer complete") }
            if (update.status == PayloadTransferUpdate.Status.FAILURE) handler.post { setStatus("Transfer failed") }
        }
    }

    private fun broadcast(json: JSONObject) {
        val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
        endpoints.keys.forEach { client.sendPayload(it, payload) }
    }

    private fun sendText() {
        val body = compose.text.toString()
        if (body.isBlank()) return
        if (endpoints.isEmpty()) {
            setStatus("Connect to a nearby phone before sending.")
            return
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxTextBytes) {
            setStatus("Message is too large. Maximum supported text is 10 MiB.")
            return
        }
        val id = UUID.randomUUID().toString()
        val targets = endpoints.keys.toMutableSet()
        pendingAcks[id] = ConcurrentHashMap.newKeySet<String>().apply { addAll(targets) }
        addMessage(ChatMessage(id, localName, body, true, delivery = "Sending…"))
        sendChunkedText(id, bytes, targets)
        compose.setText("")
    }

    private fun sendChunkedText(messageId: String, bytes: ByteArray, targets: Set<String>) {
        val total = maxOf(1, (bytes.size + chunkSize - 1) / chunkSize)
        targets.forEach { endpoint ->
            for (index in 0 until total) {
                val start = index * chunkSize
                val end = minOf(bytes.size, start + chunkSize)
                val part = if (start < end) bytes.copyOfRange(start, end) else byteArrayOf()
                val packet = JSONObject()
                    .put("type", "TEXT_CHUNK")
                    .put("messageId", messageId)
                    .put("sender", localName)
                    .put("index", index)
                    .put("total", total)
                    .put("data", Base64.encodeToString(part, Base64.NO_WRAP))
                client.sendPayload(endpoint, Payload.fromBytes(packet.toString().toByteArray(Charsets.UTF_8)))
                    .addOnFailureListener { error ->
                        handler.post {
                            updateDelivery(messageId, "Failed")
                            setStatus("Message transfer failed: ${error.localizedMessage}")
                        }
                    }
            }
        }
        setStatus("Sending ${total} text part(s)…")
    }

    private fun updateDelivery(messageId: String, state: String) {
        messages.find { it.id == messageId }?.let {
            it.delivery = state
            renderMessages()
        }
    }

    private fun sendAck(endpoint: String, messageId: String) {
        val ack = JSONObject().put("type", "TEXT_ACK").put("messageId", messageId)
        client.sendPayload(endpoint, Payload.fromBytes(ack.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun receiveTextChunk(endpoint: String, obj: JSONObject) {
        val id = obj.optString("messageId")
        val total = obj.optInt("total")
        val index = obj.optInt("index")
        if (id.isBlank() || total !in 1..900 || index !in 0 until total) return
        val key = "$endpoint:$id"
        val assembly = assemblies.getOrPut(key) {
            ChunkAssembly(
                id,
                obj.optString("sender", "Nearby"),
                total,
                arrayOfNulls(total)
            )
        }
        if (assembly.total != total) return
        if (assembly.chunks[index] == null) {
            assembly.chunks[index] = obj.optString("data")
            assembly.received++
        }
        if (assembly.received == assembly.total) {
            try {
                val output = java.io.ByteArrayOutputStream()
                assembly.chunks.forEach { encoded ->
                    output.write(Base64.decode(encoded ?: return, Base64.NO_WRAP))
                    require(output.size() <= maxTextBytes) { "Message exceeds maximum size" }
                }
                val body = output.toByteArray().toString(Charsets.UTF_8)
                handler.post {
                    addMessage(ChatMessage(id, assembly.sender, body, false))
                    sendAck(endpoint, id)
                    setStatus("Message received completely • confirmation sent")
                }
            } catch (e: Exception) {
                handler.post { setStatus("Could not reassemble message: ${e.localizedMessage}") }
            } finally {
                assemblies.remove(key)
            }
        }
    }

    private fun handleControl(endpoint: String, raw: String) {
        try {
            val obj = JSONObject(raw)
            when (obj.optString("type")) {
                "TEXT_CHUNK" -> receiveTextChunk(endpoint, obj)
                "TEXT_ACK" -> handler.post {
                    val messageId = obj.optString("messageId")
                    val waiting = pendingAcks[messageId]
                    waiting?.remove(endpoint)
                    if (waiting != null && waiting.isEmpty()) {
                        pendingAcks.remove(messageId)
                        updateDelivery(messageId, "Delivered")
                        setStatus("Message delivered")
                    }
                }
                "TEXT" -> handler.post {
                    addMessage(ChatMessage(obj.optString("id"), obj.optString("sender", "Nearby"), obj.optString("body"), false))
                }
                "DELETE" -> handler.post {
                    val target = obj.optString("id")
                    messages.find { it.id == target }?.let { it.deleted = true; it.body = "This message was deleted"; renderMessages() }
                }
                "CALL_START" -> handler.post { showIncomingCall(endpoint, obj.optString("sender")) }
                "CALL_ACCEPT" -> handler.post { setStatus("Call accepted • connecting audio") }
                "CALL_END" -> handler.post { endCall(false); setStatus("Call ended") }
                "CALL_MUTE" -> handler.post { setStatus("Other participant changed microphone state") }
            }
        } catch (_: Exception) {}
    }

    private fun addMessage(message: ChatMessage) {
        if (messages.any { it.id == message.id }) return
        messages += message
        renderMessages()
    }

    private fun renderMessages() {
        chat.removeAllViews()
        messages.forEach { m ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundColor(if (m.mine) 0xffd9fdd3.toInt() else -1)
            }
            card.addView(TextView(this).apply {
                text = m.sender; textSize = 11f; setTextColor(0xff08765a.toInt())
            })
            card.addView(TextView(this).apply {
                text = if (m.deleted) "This message was deleted" else m.body
                textSize = 15f; setTextColor(0xff172d24.toInt())
            })
            if (m.mine && m.delivery.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = m.delivery; textSize = 10f; setTextColor(0xff08765a.toInt())
                    gravity = Gravity.END
                })
            }
            if (!m.deleted && m.attachment != null && m.path != null) {
                val buttonText = when (m.attachment) {
                    "VOICE" -> "▶ Play voice note"
                    "IMAGE" -> "Open image"
                    else -> "Open file"
                }
                card.addView(actionButton(buttonText) { openAttachment(m) })
            }
            card.setOnLongClickListener {
                val options = if (m.mine) arrayOf("Delete for everyone", "Delete for me", "Copy") else arrayOf("Delete for me", "Copy")
                android.app.AlertDialog.Builder(this).setItems(options) { _, which ->
                    when (options[which]) {
                        "Delete for everyone" -> {
                            m.deleted = true; m.body = "This message was deleted"; renderMessages()
                            broadcast(JSONObject().put("type", "DELETE").put("id", m.id))
                        }
                        "Delete for me" -> { messages.remove(m); renderMessages() }
                        "Copy" -> {
                            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", m.body))
                        }
                    }
                }.show()
                true
            }
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (m.mine) Gravity.END else Gravity.START
                setPadding(dp(4), dp(3), dp(4), dp(3))
                addView(card, LinearLayout.LayoutParams(-2, -2).apply { width = (resources.displayMetrics.widthPixels * .78f).toInt() })
            }
            chat.addView(wrapper)
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun openAttachment(m: ChatMessage) {
        val path = m.path ?: return
        if (m.attachment == "VOICE") {
            try {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(path); prepare(); start()
                    setOnCompletionListener { it.release(); player = null }
                }
            } catch (e: Exception) { setStatus("Audio playback failed: ${e.localizedMessage}") }
        } else {
            val f = File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            val mime = if (m.attachment == "IMAGE") "image/*" else "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    private fun sendAttachment(uri: android.net.Uri) {
        val name = queryName(uri)
        val kind = if (contentResolver.getType(uri)?.startsWith("image/") == true) "IMAGE" else "FILE"
        val file = File(cacheDir, "send_${System.currentTimeMillis()}_$name")
        try {
            contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
            val meta = Payload.fromBytes(JSONObject().put("type", "ATTACHMENT_META").put("name", name).put("kind", kind).toString().toByteArray())
            endpoints.keys.forEach { client.sendPayload(it, meta) }
            val payload = Payload.fromFile(file)
            endpoints.keys.forEach { client.sendPayload(it, payload) }
            addMessage(ChatMessage(UUID.randomUUID().toString(), localName, name, true, kind, file.absolutePath))
            setStatus("Sending $name…")
        } catch (e: Exception) { setStatus("Could not send file: ${e.localizedMessage}") }
    }

    private fun queryName(uri: android.net.Uri): String {
        var name = "attachment"
        contentResolver.query(uri, null, null, null, null)?.use {
            val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && index >= 0) name = it.getString(index)
        }
        return name
    }

    private fun startVoiceNote() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsForFeatures(); return
        }
        try {
            val f = File(cacheDir, "voice_note_${System.currentTimeMillis()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96000); r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath); r.prepare(); r.start()
            recorder = r; recordingFile = f
            setStatus("Recording voice note…")
        } catch (e: Exception) { setStatus("Recording error: ${e.localizedMessage}") }
    }

    private fun stopVoiceNote() {
        val r = recorder ?: return
        try {
            r.stop(); r.release(); recorder = null
            val f = recordingFile ?: return
            val payload = Payload.fromFile(f)
            endpoints.keys.forEach { client.sendPayload(it, payload) }
            addMessage(ChatMessage(UUID.randomUUID().toString(), localName, "Voice note", true, "VOICE", f.absolutePath))
            setStatus("Voice note sending…")
        } catch (e: Exception) { setStatus("Recording was too short or failed") }
        finally { recorder = null; recordingFile = null }
    }

    private fun startCall() {
        val endpoint = endpoints.keys.firstOrNull()
        if (endpoint == null) { setStatus("Connect to a nearby phone first."); return }
        broadcast(JSONObject().put("type", "CALL_START").put("sender", localName))
        setStatus("Calling nearby user…")
    }

    private fun showIncomingCall(endpoint: String, caller: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Incoming offline call")
            .setMessage("$caller is calling")
            .setPositiveButton("Accept") { _, _ ->
                broadcast(JSONObject().put("type", "CALL_ACCEPT"))
                beginAudioCall()
            }
            .setNegativeButton("Reject") { _, _ ->
                broadcast(JSONObject().put("type", "CALL_END"))
            }.show()
    }

    /**
     * Audio capture/playback loop scaffold. The call signaling UI is present,
     * but full duplex Nearby STREAM routing is not yet implemented in this MVP.
     */
    private fun beginAudioCall() {
        setStatus("Call accepted. Audio stream engine requires device validation.")
        // Deliberately avoid claiming a working call until stream negotiation and
        // full-duplex audio routing have been implemented and tested.
    }

    private fun endCall(sendSignal: Boolean = true) {
        inCall = false
        if (sendSignal) broadcast(JSONObject().put("type", "CALL_END"))
        try { callRecorder?.stop() } catch (_: Exception) {}
        try { callRecorder?.release() } catch (_: Exception) {}
        try { callTrack?.stop() } catch (_: Exception) {}
        try { callTrack?.release() } catch (_: Exception) {}
        callRecorder = null; callTrack = null
    }

    private fun setStatus(text: String) = handler.post { status.text = text }

    override fun onDestroy() {
        endCall(false)
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        player?.release()
        client.stopAdvertising()
        client.stopDiscovery()
        endpoints.keys.forEach { client.disconnectFromEndpoint(it) }
        super.onDestroy()
    }
}
