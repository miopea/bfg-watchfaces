package com.bfg.watchfaces.mobile.pack

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import com.android.apksig.util.DataSources
import com.android.apksig.util.ReadableDataSink
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

/**
 * Signs an APK on the phone, with a key the phone made and keeps.
 *
 * Approach adapted from Google's Androidify sample (Apache-2.0), which solves
 * the same problem: pack produces an unsigned APK and Watch Face Push will not
 * accept one.
 *
 * ## The key never leaves the device and never needs to
 *
 * It is generated in the Android Keystore on first use and stays there — the
 * private key is not extractable, which is the point. Nothing about a pushed
 * watch face depends on WHO signed it: Watch Face Push trusts the validation
 * token the validator issues for the bytes, not a signing identity. So a
 * per-device self-signed key is not a weaker version of a real signing key, it
 * is the right shape for the job.
 *
 * A consequence worth knowing: two phones produce differently-signed APKs for
 * the same design. That is fine for Push, and it is why the SLUG rather than the
 * signature is what decides whether a face replaces an earlier one.
 *
 * v1, v2 and v3. The v1 (JAR) signature is not optional here: Watch Face Push
 * reads `META-INF/MANIFEST.MF` and rejects an APK without it as malformed, even
 * though Android installs such an APK without complaint and the Push validator
 * issues a token for it. See the comment on the signing call.
 */
object ApkSigning {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "com.bfg.watchfaces.FaceSigningKey"
    private const val CERT_ALIAS = "com.bfg.watchfaces.FaceSigningCert"

    fun sign(unsigned: ByteArray): ByteArray {
        val keyPair = keyPair()
        val certificate = certificate(keyPair)
        val signerConfig = ApkSigner.SignerConfig
            .Builder("CERT", KeyConfig.Jca(keyPair.private), listOf(certificate))
            .build()
        val sink = InMemorySink()
        ApkSigner.Builder(listOf(signerConfig)).apply {
            setInputApk(DataSources.asDataSource(ByteBuffer.wrap(unsigned)))
            setOutputApk(sink)
            // v1 (JAR) signing is REQUIRED, and this was the bug. Watch Face
            // Push on Wear OS 7 rejects a v3-only APK with
            // ERROR_MALFORMED_WATCHFACE_APK -- "The provided watch face is not
            // a valid Android APK" -- even though Android itself installs it
            // happily and the Push validator issues a token for it.
            //
            // It was off because the face's manifest declares minSdkVersion 33,
            // so a JAR signature is dead weight as far as the ANDROID INSTALLER
            // is concerned. That reasoning was sound and about the wrong
            // consumer: WFP reads META-INF/MANIFEST.MF itself.
            //
            // Measured, on a Pixel Watch 5 running Wear OS 7: the identical
            // pack-built APK is rejected without META-INF and accepted with it.
            // The Wear OS 6 emulator accepts both, which is why this survived.
            setV1SigningEnabled(true)
            setV2SigningEnabled(true)
            setV3SigningEnabled(true)
        }.build().sign()
        return sink.toByteArray()
    }

    private fun keyPair(): KeyPair {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let {
            return KeyPair(it.certificate.publicKey, it.privateKey)
        }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setKeySize(2048)
            .build()
        return KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    private fun certificate(keyPair: KeyPair): X509Certificate {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getCertificate(CERT_ALIAS) as? X509Certificate)?.let { return it }

        val now = Date()
        val expiry = Calendar.getInstance().apply { time = now; add(Calendar.YEAR, 25) }.time
        val name = X500Name("CN=BFG Watch Faces")
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger(64, SecureRandom()), now, expiry, name, keyPair.public
        ).build(JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private))
        val certificate = JcaX509CertificateConverter().getCertificate(holder)
        runCatching { store.setCertificateEntry(CERT_ALIAS, certificate) }
        return certificate
    }

    /** apksig writes to a sink; the APK is going over Bluetooth, so keep it in memory. */
    private class InMemorySink : ReadableDataSink {
        private var buffer = ByteArray(0)

        override fun consume(buf: ByteArray, offset: Int, length: Int) =
            append(buf, offset, length)

        override fun consume(buf: ByteBuffer) {
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            append(bytes, 0, bytes.size)
        }

        private fun append(buf: ByteArray, offset: Int, length: Int) {
            val grown = ByteArray(buffer.size + length)
            System.arraycopy(buffer, 0, grown, 0, buffer.size)
            System.arraycopy(buf, offset, grown, buffer.size, length)
            buffer = grown
        }

        override fun size(): Long = buffer.size.toLong()

        override fun getByteBuffer(offset: Long, size: Int): ByteBuffer =
            ByteBuffer.wrap(buffer, offset.toInt(), size).slice()

        override fun feed(offset: Long, size: Long, sink: com.android.apksig.util.DataSink) =
            sink.consume(buffer, offset.toInt(), size.toInt())

        override fun copyTo(offset: Long, size: Int, dest: ByteBuffer) {
            dest.put(buffer, offset.toInt(), size)
        }

        override fun slice(offset: Long, size: Long): com.android.apksig.util.DataSource =
            DataSources.asDataSource(getByteBuffer(offset, size.toInt()))

        fun toByteArray(): ByteArray = buffer
    }
}
