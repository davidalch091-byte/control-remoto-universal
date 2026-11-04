package com.izziremote

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory

/**
 * Certificado de cliente del celular. Se genera una sola vez y se guarda en
 * un archivo privado de la app (la TV lo recuerda despues de emparejar).
 * No usa el AndroidKeyStore porque ese falla con el TLS de Android TV.
 */
object Identity {
    private const val ALIAS = "izzi_remote_client"
    private val PASS = "izzi".toCharArray()
    private lateinit var file: File
    private var ks: KeyStore? = null

    fun init(context: Context) {
        file = File(context.filesDir, "identity.p12")
    }

    @Synchronized
    private fun keyStore(): KeyStore {
        ks?.let { return it }
        val store = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            try {
                FileInputStream(file).use { store.load(it, PASS) }
                if (store.containsAlias(ALIAS)) {
                    ks = store
                    return store
                }
            } catch (_: Exception) {
                file.delete()
            }
        }
        val fresh = KeyStore.getInstance("PKCS12")
        fresh.load(null, null)

        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()

        val name = X500Name("CN=izzi-remote")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 24L * 3600 * 1000),
            Date(now + 20L * 365 * 24 * 3600 * 1000),
            name,
            pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        fresh.setKeyEntry(ALIAS, pair.private, PASS, arrayOf(cert))
        FileOutputStream(file).use { fresh.store(it, PASS) }
        ks = fresh
        return fresh
    }

    fun keyManagers(): Array<KeyManager> {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(), PASS)
        return kmf.keyManagers
    }

    fun certificate(): X509Certificate = keyStore().getCertificate(ALIAS) as X509Certificate
}
