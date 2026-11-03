package com.izziremote

import com.google.protobuf.ByteString
import com.izziremote.proto.PairingRequest
import com.izziremote.proto.PoloConfiguration
import com.izziremote.proto.PoloMessage
import com.izziremote.proto.PoloOptions
import com.izziremote.proto.PoloSecret
import com.izziremote.proto.PoloStatus
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/**
 * Emparejado con Android TV (puerto 6467).
 * 1) start(): la TV muestra un código de 6 caracteres en pantalla.
 * 2) finish(codigo): se manda el hash que demuestra que viste el código.
 */
class Pairing(private val host: String) {

    private var socket: SSLSocket? = null
    private var serverKey: RSAPublicKey? = null

    private fun base(): PoloMessage.Builder =
        PoloMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PoloStatus.POLO_STATUS_OK)

    private fun send(msg: PoloMessage) {
        val out = socket!!.outputStream
        msg.writeDelimitedTo(out)
        out.flush()
    }

    private fun receive(): PoloMessage {
        val msg = PoloMessage.parseDelimitedFrom(socket!!.inputStream)
            ?: throw IOException("La TV cerró la conexión")
        if (msg.status != PoloStatus.POLO_STATUS_OK) {
            throw IOException("La TV rechazó el emparejamiento (${msg.status})")
        }
        return msg
    }

    /** Bloqueante: llamar fuera del hilo principal. */
    fun start() {
        val s = Tls.connect(host, 6467, 20000)
        socket = s
        serverKey = s.session.peerCertificates[0].publicKey as RSAPublicKey

        send(
            base().setPairingRequest(
                PairingRequest.newBuilder()
                    .setServiceName("atvremote")
                    .setClientName("Control Izzi")
            ).build()
        )
        receive()

        val encoding = PoloOptions.Encoding.newBuilder()
            .setType(PoloOptions.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(6)
            .build()

        send(
            base().setOptions(
                PoloOptions.newBuilder()
                    .addInputEncodings(encoding)
                    .setPreferredRole(PoloOptions.RoleType.ROLE_TYPE_INPUT)
            ).build()
        )
        receive()

        send(
            base().setConfiguration(
                PoloConfiguration.newBuilder()
                    .setEncoding(encoding)
                    .setClientRole(PoloOptions.RoleType.ROLE_TYPE_INPUT)
            ).build()
        )
        receive()
        // En este punto la TV ya está mostrando el código.
    }

    /** Bloqueante: llamar fuera del hilo principal. */
    fun finish(rawCode: String) {
        val code = rawCode.trim().uppercase()
        require(Regex("[0-9A-F]{6}").matches(code)) {
            "El código debe tener 6 caracteres (números o letras A-F)"
        }
        val clientKey = Identity.certificate().publicKey as RSAPublicKey
        val srvKey = serverKey ?: throw IOException("Primero hay que pedir el código a la TV")

        val md = MessageDigest.getInstance("SHA-256")
        md.update(hex(clientKey.modulus))
        md.update(hex(clientKey.publicExponent))
        md.update(hex(srvKey.modulus))
        md.update(hex(srvKey.publicExponent))
        md.update(hexToBytes(code.substring(2)))
        val hash = md.digest()

        send(
            base().setSecret(
                PoloSecret.newBuilder().setSecret(ByteString.copyFrom(hash))
            ).build()
        )
        receive()
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun hex(n: BigInteger): ByteArray {
        var s = n.toString(16)
        if (s.length % 2 == 1) s = "0$s"
        return hexToBytes(s)
    }

    private fun hexToBytes(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
