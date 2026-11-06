package com.lepao.companion.capture

import android.content.Context
import android.util.Base64
import com.lepao.companion.core.DiagLog
import com.lepao.companion.core.SecureStore
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The local certificate authority used for TLS termination.
 *
 * A throwaway CA and an `api2.lptiyu.com` leaf are generated on first use (BouncyCastle), then kept
 * as a PKCS12 blob encrypted by [SecureStore] in the app's private directory. Nothing is exported and
 * nothing is installed into the system trust store: the official application accepts this
 * certificate at connection time, which is what the capture design relies on.
 */
class CertAuthority internal constructor(
    private val leafKey: PrivateKey,
    private val leafChain: Array<X509Certificate>,
) {

    /**
     * Server-side context for [TlsTerminator]; no client certificate is requested.
     *
     * 这里必须用 KeyStore + KeyManagerFactory，而不是自己实现 X509ExtendedKeyManager：
     * 手写的 key manager 在 JSSE 的候选筛选里会得到 "No available authentication scheme"，
     * TLS 握手在 ServerHello 阶段直接失败，官方应用的请求全部断在这里。
     */
    fun serverContext(): SSLContext {
        val store = KeyStore.getInstance(PKCS12)
        store.load(null, null)
        val password = "lepao-capture".toCharArray()
        store.setKeyEntry(KEY_ALIAS, leafKey, password, leafChain)
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, password)
        val context = SSLContext.getInstance("TLS")
        context.init(factory.keyManagers, null, SecureRandom())
        return context
    }

    companion object {
        private const val CAPTURE_CA_FILE = "capture-ca.enc.json"
        private const val KEY_ALIAS = "api2.lptiyu.com"
        private const val CA_ALIAS = "lepao-local-ca"
        private const val LEAF_HOST = "api2.lptiyu.com"
        private const val CA_NAME = "CN=Lepao Companion Local CA"
        private const val LEAF_NAME = "CN=api2.lptiyu.com"
        private const val VALIDITY_DAYS = 3650L
        private const val KEY_SIZE = 2048
        private const val PKCS12 = "PKCS12"
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        @Volatile
        private var cached: CertAuthority? = null

        /** Loads the persisted authority or creates it once. */
        @Synchronized
        fun ensure(context: Context): CertAuthority {
            cached?.let { return it }
            val appContext = context.applicationContext
            val secure = SecureStore(appContext)
            val file = File(appContext.filesDir, CAPTURE_CA_FILE)
            val authority = load(secure, file) ?: create(secure, file)
            cached = authority
            return authority
        }

        /** In-memory instance from a previous [ensure] call, if any. */
        fun peek(): CertAuthority? = cached

        fun reset(context: Context) {
            cached = null
            File(context.applicationContext.filesDir, CAPTURE_CA_FILE).delete()
        }

        private fun load(secure: SecureStore, file: File): CertAuthority? {
            val envelope = try {
                secure.readJson(file)
            } catch (_: Exception) {
                null
            } ?: return null
            val password = envelope.optString("pin")
            if (password.isEmpty()) return null
            val bundle = try {
                Base64.decode(envelope.optString("p12"), Base64.NO_WRAP)
            } catch (_: Exception) {
                return null
            }
            return try {
                val keyStore = KeyStore.getInstance(PKCS12)
                keyStore.load(ByteArrayInputStream(bundle), password.toCharArray())
                val key = keyStore.getKey(KEY_ALIAS, password.toCharArray()) as? PrivateKey ?: return null
                val chain = keyStore.getCertificateChain(KEY_ALIAS)
                    ?.filterIsInstance<X509Certificate>()
                    ?.toTypedArray()
                    ?: return null
                if (chain.isEmpty()) return null
                CertAuthority(key, chain)
            } catch (_: Exception) {
                null
            }
        }

        private fun create(secure: SecureStore, file: File): CertAuthority {
            val random = SecureRandom()
            val caPair = generateKeyPair()
            val leafPair = generateKeyPair()
            val now = System.currentTimeMillis()
            val notBefore = Date(now - DAY_MS)
            val notAfter = Date(now + VALIDITY_DAYS * DAY_MS)
            val caCertificate = createAuthorityCertificate(caPair, notBefore, notAfter, random)
            val leafCertificate = createLeafCertificate(leafPair, caPair, caCertificate, notBefore, notAfter, random)

            val password = Base64.encodeToString(ByteArray(18).also(random::nextBytes), Base64.NO_WRAP)
            val bundle = packKeyStore(caPair, caCertificate, leafPair, leafCertificate, password)
            val envelope = JSONObject().apply {
                put("v", 1)
                put("pin", password)
                put("p12", Base64.encodeToString(bundle, Base64.NO_WRAP))
            }
            secure.writeJson(file, envelope)
            DiagLog.info("capture certificate authority created for $LEAF_HOST")
            return CertAuthority(leafPair.private, arrayOf(leafCertificate, caCertificate))
        }

        private fun createAuthorityCertificate(
            pair: KeyPair,
            notBefore: Date,
            notAfter: Date,
            random: SecureRandom,
        ): X509Certificate {
            val name = X500Name(CA_NAME)
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger(64, random),
                notBefore,
                notAfter,
                name,
                pair.public,
            )
            val extensions = JcaX509ExtensionUtils()
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(pair.public))
            builder.addExtension(Extension.authorityKeyIdentifier, false, extensions.createAuthorityKeyIdentifier(pair.public))
            return sign(builder, pair)
        }

        private fun createLeafCertificate(
            pair: KeyPair,
            authorityPair: KeyPair,
            authority: X509Certificate,
            notBefore: Date,
            notAfter: Date,
            random: SecureRandom,
        ): X509Certificate {
            val builder = JcaX509v3CertificateBuilder(
                X500Name(authority.subjectX500Principal.name),
                BigInteger(64, random),
                notBefore,
                notAfter,
                X500Name(LEAF_NAME),
                pair.public,
            )
            val extensions = JcaX509ExtensionUtils()
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(GeneralName(GeneralName.dNSName, LEAF_HOST)),
            )
            builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(pair.public))
            builder.addExtension(Extension.authorityKeyIdentifier, false, extensions.createAuthorityKeyIdentifier(authority))
            return sign(builder, authorityPair)
        }

        private fun packKeyStore(
            caPair: KeyPair,
            caCertificate: X509Certificate,
            leafPair: KeyPair,
            leafCertificate: X509Certificate,
            password: String,
        ): ByteArray {
            val keyStore = KeyStore.getInstance(PKCS12)
            keyStore.load(null, null)
            val characters = password.toCharArray()
            keyStore.setKeyEntry(CA_ALIAS, caPair.private, characters, arrayOf(caCertificate))
            keyStore.setKeyEntry(KEY_ALIAS, leafPair.private, characters, arrayOf(leafCertificate, caCertificate))
            val output = ByteArrayOutputStream()
            keyStore.store(output, characters)
            return output.toByteArray()
        }

        private fun generateKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(KEY_SIZE, SecureRandom())
            return generator.generateKeyPair()
        }

        private fun sign(builder: JcaX509v3CertificateBuilder, pair: KeyPair): X509Certificate {
            val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(pair.private)
            return JcaX509CertificateConverter().getCertificate(builder.build(signer))
        }
    }
}
