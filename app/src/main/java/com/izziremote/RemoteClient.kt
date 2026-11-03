package com.izziremote

import com.izziremote.proto.RemoteConfigure
import com.izziremote.proto.RemoteDeviceInfo
import com.izziremote.proto.RemoteKeyInject
import com.izziremote.proto.RemoteMessage
import com.izziremote.proto.RemotePingResponse
import com.izziremote.proto.RemoteSetActive
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Conexión de control (puerto 6466). Hace el saludo con la TV,
 * contesta los pings y manda las teclas.
 */
class RemoteClient(
    private val host: String,
    private val onReady: () -> Unit,
    private val onClosed: (String?) -> Unit
) {
    @Volatile var isReady = false
        private set

    @Volatile private var socket: SSLSocket? = null
    private val writer = Executors.newSingleThreadExecutor()

    fun connect() {
        thread(name = "izzi-remote-reader") {
            try {
                val s = Tls.connect(host, 6466)
                socket = s
                val input = s.inputStream
                while (true) {
                    val msg = RemoteMessage.parseDelimitedFrom(input) ?: break
                    handle(msg)
                }
                isReady = false
                onClosed(null)
            } catch (e: Exception) {
                isReady = false
                onClosed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun handle(m: RemoteMessage) {
        if (m.hasRemoteConfigure()) {
            post(
                RemoteMessage.newBuilder().setRemoteConfigure(
                    RemoteConfigure.newBuilder()
                        .setCode1(622)
                        .setDeviceInfo(
                            RemoteDeviceInfo.newBuilder()
                                .setModel("Control Izzi")
                                .setVendor("Android")
                                .setUnknown1(1)
                                .setUnknown2("1")
                                .setPackageName("com.izziremote")
                                .setAppVersion("1.0")
                        )
                ).build()
            )
        }
        if (m.hasRemoteSetActive()) {
            post(
                RemoteMessage.newBuilder()
                    .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(622))
                    .build()
            )
            isReady = true
            onReady()
        }
        if (m.hasRemotePingRequest()) {
            post(
                RemoteMessage.newBuilder()
                    .setRemotePingResponse(
                        RemotePingResponse.newBuilder().setVal1(m.remotePingRequest.val1)
                    ).build()
            )
        }
    }

    /** direction 3 = pulsación corta. Los códigos son los de android.view.KeyEvent. */
    fun sendKey(keyCode: Int) {
        post(
            RemoteMessage.newBuilder()
                .setRemoteKeyInject(
                    RemoteKeyInject.newBuilder().setKeyCode(keyCode).setDirection(3)
                ).build()
        )
    }

    private fun post(msg: RemoteMessage) {
        writer.execute {
            try {
                val out = socket?.outputStream ?: return@execute
                msg.writeDelimitedTo(out)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun close() {
        isReady = false
        try { socket?.close() } catch (_: Exception) {}
        writer.shutdown()
    }
}
