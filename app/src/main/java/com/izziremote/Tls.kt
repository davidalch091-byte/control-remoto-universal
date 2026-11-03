package com.izziremote

import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Tls {
    // La TV usa un certificado propio (autofirmado), por eso no se valida la cadena.
    // Solo se usa dentro de tu red local.
    private object TrustTv : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun connect(host: String, port: Int, readTimeoutMs: Int = 0): SSLSocket {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(Identity.keyManagers(), arrayOf<TrustManager>(TrustTv), null)
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), 6000)
        socket.soTimeout = readTimeoutMs
        socket.startHandshake()
        return socket
    }
}
