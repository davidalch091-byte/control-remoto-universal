package com.izziremote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

/**
 * Certificado de cliente del celular. Se genera una sola vez dentro del
 * AndroidKeyStore y la TV lo recuerda después de emparejar con el código.
 */
object Identity {
    private const val ALIAS = "izzi_remote_client"

    private fun keyStore(): KeyStore {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (!ks.containsAlias(ALIAS)) {
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            gen.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setKeySize(2048)
                    .setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA1,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512
                    )
                    .setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS
                    )
                    .setCertificateSubject(X500Principal("CN=izzi-remote"))
                    .build()
            )
            gen.generateKeyPair()
        }
        return ks
    }

    fun keyManagers(): Array<KeyManager> {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(), null)
        return kmf.keyManagers
    }

    fun certificate(): X509Certificate = keyStore().getCertificate(ALIAS) as X509Certificate
}
