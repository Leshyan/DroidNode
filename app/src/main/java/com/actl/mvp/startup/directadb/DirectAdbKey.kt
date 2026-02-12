package com.actl.mvp.startup.directadb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

class DirectAdbKey(private val keyStore: DirectAdbKeyStore, private val keyName: String) {

    private val encryptionKey: Key = getOrCreateEncryptionKey()
        ?: throw DirectAdbKeyException(IllegalStateException("Failed to load AndroidKeyStore key"))

    private val privateKey: RSAPrivateKey = getOrCreatePrivateKey()
    private val publicKey: RSAPublicKey = KeyFactory.getInstance("RSA")
        .generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey

    private val certificate: X509Certificate = run {
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val cert = X509v3CertificateBuilder(
            X500Name("CN=00"),
            BigInteger.ONE,
            Date(0),
            Date(2_461_449_600_000L),
            Locale.ROOT,
            X500Name("CN=00"),
            SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        ).build(signer)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(cert.encoded)) as X509Certificate
    }

    val adbPublicKey: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        publicKey.toAdbEncoded(keyName)
    }

    val sslContext: SSLContext by lazy(LazyThreadSafetyMode.NONE) {
        val context = runCatching { SSLContext.getInstance("TLSv1.3") }
            .getOrElse { SSLContext.getInstance("TLS") }
        context.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        context
    }

    fun sign(data: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(data)
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        val aad = ByteArray(16)
        "adbkey".toByteArray().copyInto(aad)

        val restored = keyStore.get()?.let { encrypted ->
            runCatching {
                val plaintext = decrypt(encrypted, aad) ?: return@runCatching null
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateKey
            }.getOrNull()
        }
        if (restored != null) {
            return restored
        }

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
        generator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        val created = generator.generateKeyPair().private as RSAPrivateKey
        encrypt(created.encoded, aad)?.let { keyStore.put(it) }
        return created
    }

    private fun getOrCreateEncryptionKey(): Key? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        return ks.getKey(ENCRYPTION_KEY_ALIAS, null) ?: run {
            val spec = KeyGenParameterSpec.Builder(
                ENCRYPTION_KEY_ALIAS,
                KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(spec)
            generator.generateKey()
        }
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray?): ByteArray? {
        if (plaintext.size > Int.MAX_VALUE - IV_SIZE_IN_BYTES - TAG_SIZE_IN_BYTES) {
            return null
        }
        val out = ByteArray(IV_SIZE_IN_BYTES + plaintext.size + TAG_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        cipher.doFinal(plaintext, 0, plaintext.size, out, IV_SIZE_IN_BYTES)
        System.arraycopy(cipher.iv, 0, out, 0, IV_SIZE_IN_BYTES)
        return out
    }

    private fun decrypt(ciphertext: ByteArray, aad: ByteArray?): ByteArray? {
        if (ciphertext.size < IV_SIZE_IN_BYTES + TAG_SIZE_IN_BYTES) {
            return null
        }
        val params = GCMParameterSpec(TAG_SIZE_IN_BYTES * 8, ciphertext, 0, IV_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, params)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext, IV_SIZE_IN_BYTES, ciphertext.size - IV_SIZE_IN_BYTES)
    }

    private val keyManager = object : X509ExtendedKeyManager() {
        private val alias = "key"

        override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out Principal>?, socket: Socket?): String? {
            return keyTypes.firstOrNull { it == "RSA" }?.let { alias }
        }

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            return if (alias == this.alias) arrayOf(certificate) else null
        }

        override fun getPrivateKey(alias: String?): PrivateKey? {
            return if (alias == this.alias) privateKey else null
        }

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    private val trustManager = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "_actl_adbkey_encryption_key_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE_IN_BYTES = 12
        private const val TAG_SIZE_IN_BYTES = 16

        private val PADDING = byteArrayOf(
            0x00, 0x01, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x00,
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
            0x04, 0x14
        )

    }
}

interface DirectAdbKeyStore {
    fun put(bytes: ByteArray)
    fun get(): ByteArray?
}

class PreferenceDirectAdbKeyStore(private val preferences: android.content.SharedPreferences) : DirectAdbKeyStore {
    private val preferenceKey = "adbkey"

    override fun put(bytes: ByteArray) {
        preferences.edit {
            putString(preferenceKey, String(Base64.encode(bytes, Base64.NO_WRAP)))
        }
    }

    override fun get(): ByteArray? {
        if (!preferences.contains(preferenceKey)) {
            return null
        }
        return Base64.decode(preferences.getString(preferenceKey, null), Base64.NO_WRAP)
    }
}

private fun BigInteger.toAdbEncodedWordArray(): IntArray {
    val encoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)

    var tmp = this.add(BigInteger.ZERO)
    for (i in encoded.indices) {
        val out = tmp.divideAndRemainder(r32)
        tmp = out[0]
        encoded[i] = out[1].toInt()
    }
    return encoded
}

private fun RSAPublicKey.toAdbEncoded(name: String): ByteArray {
    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSA_PUBLIC_KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncodedWordArray().forEach { buffer.putInt(it) }
    rr.toAdbEncodedWordArray().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())

    val base64Bytes = Base64.encode(buffer.array(), Base64.NO_WRAP)
    val nameBytes = " $name\u0000".toByteArray()
    val output = ByteArray(base64Bytes.size + nameBytes.size)
    base64Bytes.copyInto(output)
    nameBytes.copyInto(output, base64Bytes.size)
    return output
}

private const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
private const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
private const val RSA_PUBLIC_KEY_SIZE = 524
