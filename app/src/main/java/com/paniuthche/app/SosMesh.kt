package com.paniuthche.app

import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

private const val SERVICE_ID = "com.paniuthche.sos"

sealed interface MeshStatus {
    data object Off : MeshStatus
    data object Searching : MeshStatus
    data class Error(val message: String) : MeshStatus
}

/**
 * Store-and-forward SOS mesh over Nearby Connections (Bluetooth + Wi-Fi, no internet).
 * Every phone advertises and discovers at once; on connect, phones swap all known messages,
 * and each new message is forwarded to every other peer until MAX_HOPS.
 * shortcut: messages live in memory only and connections are auto-accepted without auth;
 * move to Room and sign messages before real deployment.
 */
class SosMesh(context: Context) {
    private val client = Nearby.getConnectionsClient(context)
    private val myName = Build.MODEL
    private val peers = mutableSetOf<String>()

    val messages = mutableStateListOf<SosMessage>()
    val peerCount = mutableStateOf(0)
    val status = mutableStateOf<MeshStatus>(MeshStatus.Off)

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val msg = runCatching { SosMessage.fromBytes(bytes) }.getOrNull() ?: return
            accept(msg, from = endpointId)
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) return
            peers += endpointId
            peerCount.value = peers.size
            // Catch the new peer up on everything we know.
            messages.forEach { client.sendPayload(endpointId, Payload.fromBytes(it.toBytes())) }
        }
        override fun onDisconnected(endpointId: String) {
            peers -= endpointId
            peerCount.value = peers.size
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Both sides may request at once; the duplicate request just fails harmlessly.
            client.requestConnection(myName, endpointId, connectionCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    fun start() {
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(myName, SERVICE_ID, connectionCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { status.value = MeshStatus.Error(it.message.orEmpty()) }
        client.startDiscovery(SERVICE_ID, discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { status.value = MeshStatus.Searching }
            .addOnFailureListener { status.value = MeshStatus.Error(it.message.orEmpty()) }
    }

    fun stop() {
        client.stopAllEndpoints()
        client.stopAdvertising()
        client.stopDiscovery()
        peers.clear()
        peerCount.value = 0
        status.value = MeshStatus.Off
    }

    fun send(msg: SosMessage) = accept(msg.copy(hops = -1), from = null)

    private fun accept(msg: SosMessage, from: String?) {
        val copy = relayCopy(msg, messages.mapTo(mutableSetOf()) { it.id }) ?: return
        messages.add(0, copy)
        val bytes = copy.toBytes()
        peers.filter { it != from }.forEach { client.sendPayload(it, Payload.fromBytes(bytes)) }
    }
}
