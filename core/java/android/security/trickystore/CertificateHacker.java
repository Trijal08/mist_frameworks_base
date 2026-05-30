package android.security.trickystore;

import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.crypto.digests.SHA256Digest;
import com.android.internal.org.bouncycastle.crypto.params.ECDomainParameters;
import com.android.internal.org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import com.android.internal.org.bouncycastle.crypto.params.RSAKeyParameters;
import com.android.internal.org.bouncycastle.crypto.signers.ECDSASigner;
import com.android.internal.org.bouncycastle.crypto.signers.RSADigestSigner;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.util.ECUtil;
import com.android.internal.org.bouncycastle.jce.ECNamedCurveTable;
import com.android.internal.org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import com.android.internal.org.bouncycastle.jce.spec.ECParameterSpec;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @hide
 */
public final class CertificateHacker {
    private static final String TAG = "CertificateHacker";

    // KeyMint authorization tag numbers that we forge or rebuild.
    private static final int TAG_ROOT_OF_TRUST = 704;
    private static final int TAG_OS_VERSION = 705;
    private static final int TAG_OS_PATCHLEVEL = 706;
    private static final int TAG_VENDOR_PATCHLEVEL = 718;
    private static final int TAG_BOOT_PATCHLEVEL = 719;

    // KeyDescription field indices of the softwareEnforced / teeEnforced authorization lists.
    private static final int SW_ENFORCED_INDEX = 6;
    private static final int TEE_ENFORCED_INDEX = 7;

    private static final Map<String, String> sLeafAlgorithms = new ConcurrentHashMap<>();

    private CertificateHacker() {}

    public static void clearLeafAlgorithms() {
        sLeafAlgorithms.clear();
    }

    public static Certificate[] hackCertificateChain(Certificate[] chain) {
        return hackCertificateChain(chain, null);
    }

    public static Certificate[] hackCertificateChain(Certificate[] chain, String[] packages) {
        if (chain == null || chain.length == 0) {
            return chain;
        }

        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            X509Certificate leaf = (X509Certificate) certFactory.generateCertificate(
                new ByteArrayInputStream(chain[0].getEncoded()));

            byte[] extensionBytes = leaf.getExtensionValue(
                CertificateGenerator.ATTESTATION_OID.getId());
            if (extensionBytes == null) {
                return chain;
            }

            X509CertificateHolder leafHolder = new X509CertificateHolder(leaf.getEncoded());
            Extension extension = leafHolder.getExtension(CertificateGenerator.ATTESTATION_OID);
            ASN1Sequence sequence = ASN1Sequence.getInstance(extension.getExtnValue().getOctets());
            ASN1Encodable[] encodables = sequence.toArray();
            int teeIndex = findRootOfTrustIndex(encodables);
            ASN1Sequence teeEnforced = (ASN1Sequence) encodables[teeIndex];

            String algorithm = leaf.getPublicKey().getAlgorithm();
            KeyBoxManager keyboxManager = TrickyStoreService.getInstance().getKeyBoxManager();
            KeyBoxManager.KeyBox keybox = keyboxManager.getKeybox(algorithm);

            if (keybox == null) {
                Log.e(TAG, "No keybox for algorithm: " + algorithm);
                return chain;
            }

            List<Certificate> certificates = new ArrayList<>(keybox.certificates);
            X509CertificateHolder issuerHolder = new X509CertificateHolder(
                certificates.get(0).getEncoded());

            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                issuerHolder.getSubject(),
                leafHolder.getSerialNumber(),
                leafHolder.getNotBefore(),
                leafHolder.getNotAfter(),
                leafHolder.getSubject(),
                leafHolder.getSubjectPublicKeyInfo()
            );

            ContentSigner signer = createBCSigner(leaf.getSigAlgName(), keybox.keyPair.getPrivate());

            TrickyStoreService.CustomPatchLevel patchLevel =
                TrickyStoreService.getInstance().getCustomPatchLevel(packages);
            Extension hackedExtension = hackAttestExtension(teeEnforced, encodables, teeIndex, patchLevel);
            builder.addExtension(hackedExtension);

            for (Object oid : leafHolder.getExtensions().getExtensionOIDs()) {
                String oidString = oid.toString();
                if (!oidString.equals(CertificateGenerator.ATTESTATION_OID.getId())) {
                    builder.addExtension(leafHolder.getExtension(
                        new ASN1ObjectIdentifier(oidString)));
                }
            }

            X509CertificateHolder builtHolder = builder.build(signer);
            X509Certificate hackedLeaf = (X509Certificate) certFactory.generateCertificate(
                new ByteArrayInputStream(builtHolder.getEncoded()));

            List<Certificate> result = new ArrayList<>();
            result.add(hackedLeaf);
            result.addAll(certificates);
            return result.toArray(new Certificate[0]);
        } catch (Exception e) {
            Log.e(TAG, "Failed to hack certificate chain", e);
            return chain;
        }
    }

    private static ContentSigner createBCSigner(String algorithm, PrivateKey privateKey) throws Exception {
        AlgorithmIdentifier sigAlgId = new DefaultSignatureAlgorithmIdentifierFinder().find(algorithm);
        
        if (privateKey instanceof BCECPrivateKey) {
            BCECPrivateKey bcKey = (BCECPrivateKey) privateKey;
            ECParameterSpec ecSpec = bcKey.getParameters();
            if (ecSpec != null) {
                ECDomainParameters domainParams = new ECDomainParameters(
                    ecSpec.getCurve(), ecSpec.getG(), ecSpec.getN(), ecSpec.getH());
                ECPrivateKeyParameters keyParam = new ECPrivateKeyParameters(bcKey.getD(), domainParams);
                return new ECContentSigner(sigAlgId, keyParam);
            } else {
                ECNamedCurveParameterSpec namedSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
                ECDomainParameters domainParams = new ECDomainParameters(
                    namedSpec.getCurve(), namedSpec.getG(), namedSpec.getN(), namedSpec.getH());
                ECPrivateKeyParameters keyParam = new ECPrivateKeyParameters(bcKey.getD(), domainParams);
                return new ECContentSigner(sigAlgId, keyParam);
            }
        } else if (privateKey instanceof ECPrivateKey) {
            ECPrivateKeyParameters keyParam = (ECPrivateKeyParameters) ECUtil.generatePrivateKeyParameter(privateKey);
            return new ECContentSigner(sigAlgId, keyParam);
        } else if (privateKey instanceof RSAPrivateCrtKey) {
            RSAPrivateCrtKey rsaKey = (RSAPrivateCrtKey) privateKey;
            RSAKeyParameters keyParam = new RSAKeyParameters(true, rsaKey.getModulus(), rsaKey.getPrivateExponent());
            return new RSAContentSigner(sigAlgId, keyParam);
        }
        
        throw new IllegalArgumentException("Unsupported key type: " + privateKey.getClass());
    }

    private static class ECContentSigner implements ContentSigner {
        private final AlgorithmIdentifier algId;
        private final ECPrivateKeyParameters keyParam;
        private final ByteArrayOutputStream stream;

        ECContentSigner(AlgorithmIdentifier algId, ECPrivateKeyParameters keyParam) {
            this.algId = algId;
            this.keyParam = keyParam;
            this.stream = new ByteArrayOutputStream();
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return algId;
        }

        @Override
        public OutputStream getOutputStream() {
            return stream;
        }

        @Override
        public byte[] getSignature() {
            try {
                byte[] data = stream.toByteArray();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(data);
                
                ECDSASigner signer = new ECDSASigner();
                signer.init(true, keyParam);
                BigInteger[] sig = signer.generateSignature(hash);
                
                ASN1EncodableVector v = new ASN1EncodableVector();
                v.add(new ASN1Integer(sig[0]));
                v.add(new ASN1Integer(sig[1]));
                return new DERSequence(v).getEncoded();
            } catch (Exception e) {
                throw new RuntimeException("Failed to generate ECDSA signature", e);
            }
        }
    }

    private static class RSAContentSigner implements ContentSigner {
        private final AlgorithmIdentifier algId;
        private final RSAKeyParameters keyParam;
        private final ByteArrayOutputStream stream;

        RSAContentSigner(AlgorithmIdentifier algId, RSAKeyParameters keyParam) {
            this.algId = algId;
            this.keyParam = keyParam;
            this.stream = new ByteArrayOutputStream();
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return algId;
        }

        @Override
        public OutputStream getOutputStream() {
            return stream;
        }

        @Override
        public byte[] getSignature() {
            try {
                byte[] data = stream.toByteArray();
                RSADigestSigner signer = new RSADigestSigner(new SHA256Digest());
                signer.init(true, keyParam);
                signer.update(data, 0, data.length);
                return signer.generateSignature();
            } catch (Exception e) {
                throw new RuntimeException("Failed to generate RSA signature", e);
            }
        }
    }

    /**
     * Rebuild the teeEnforced authorization list with a forged RootOfTrust and patch levels.
     *
     * <p>Original tags are indexed by number and re-emitted in ascending order so the result is
     * DER-canonical, exactly as a genuine KeyMint attestation would be. Patch-level tags are
     * <em>replaced in place</em> (never duplicated) and honor TEESimulator's keyword semantics.
     */
    private static Extension hackAttestExtension(
            ASN1Sequence teeEnforced,
            ASN1Encodable[] originalEncodables,
            int teeIndex,
            TrickyStoreService.CustomPatchLevel cpl) throws Exception {

        TreeMap<Integer, ASN1Encodable> entries = new TreeMap<>();
        byte[] originalBootHash = null;
        for (ASN1Encodable element : teeEnforced) {
            ASN1TaggedObject tagged = (ASN1TaggedObject) element;
            int tag = tagged.getTagNo();
            if (tag == TAG_ROOT_OF_TRUST) {
                originalBootHash = extractBootHash(tagged.getBaseObject().toASN1Primitive());
                continue;                       // Rebuilt below.
            }
            entries.put(tag, tagged);
        }

        // RootOfTrust: report a verified, locked device. Prefer the real verified-boot values
        // (boot key + the original cert's boot hash) so the forged structure stays plausible.
        byte[] bootKey = AttestationUtils.getBootKey();
        byte[] bootHash = AttestationUtils.getBootHashFromProp();
        if (bootHash == null) {
            bootHash = originalBootHash;
        }
        if (bootHash == null) {
            bootHash = AttestationUtils.getBootHash();
        }
        ASN1Encodable[] rootOfTrustElements = new ASN1Encodable[] {
            new DEROctetString(bootKey),
            ASN1Boolean.TRUE,
            new ASN1Enumerated(0),
            new DEROctetString(bootHash)
        };
        entries.put(TAG_ROOT_OF_TRUST, new DERTaggedObject(true, TAG_ROOT_OF_TRUST,
            new DERSequence(rootOfTrustElements)));

        // osVersion is device-derived; there is no per-app override for it.
        entries.put(TAG_OS_VERSION, new DERTaggedObject(true, TAG_OS_VERSION,
            new ASN1Integer(AttestationUtils.getOsVersion())));

        // Patch levels: osPatchLevel is YYYYMM, vendor/boot are YYYYMMDD.
        applyPatch(entries, TAG_OS_PATCHLEVEL, cpl == null ? null : cpl.system, false);
        applyPatch(entries, TAG_VENDOR_PATCHLEVEL, cpl == null ? null : cpl.vendor, true);
        applyPatch(entries, TAG_BOOT_PATCHLEVEL, cpl == null ? null : cpl.boot, true);

        ASN1EncodableVector vector = new ASN1EncodableVector();
        for (ASN1Encodable entry : entries.values()) {
            vector.add(entry);
        }
        DERSequence hackedEnforced = new DERSequence(vector);

        ASN1Encodable[] newEncodables = originalEncodables.clone();
        newEncodables[teeIndex] = hackedEnforced;
        DERSequence hackedSequence = new DERSequence(newEncodables);
        DEROctetString hackedOctets = new DEROctetString(hackedSequence);

        return new Extension(CertificateGenerator.ATTESTATION_OID, false, hackedOctets);
    }

    /**
     * Apply one patch-level tag according to the resolved token:
     * <ul>
     *   <li>{@code null} (no config) -&gt; forge the device's real security patch date</li>
     *   <li>{@link AttestationUtils#PATCH_KEEP} -&gt; leave the certificate's original value</li>
     *   <li>{@link AttestationUtils#PATCH_OMIT} -&gt; drop the tag entirely</li>
     *   <li>a date -&gt; encode and use it</li>
     * </ul>
     */
    private static void applyPatch(TreeMap<Integer, ASN1Encodable> entries,
            int tag, String token, boolean isLong) {
        String resolved = AttestationUtils.resolvePatchToken(token);
        if (AttestationUtils.PATCH_KEEP.equals(resolved)) {
            return;                             // Keep the original cert value (already mapped).
        }
        if (AttestationUtils.PATCH_OMIT.equals(resolved)) {
            entries.remove(tag);
            return;
        }
        int value = (resolved == null)
            ? AttestationUtils.convertPatchLevel(android.os.Build.VERSION.SECURITY_PATCH, isLong)
            : AttestationUtils.convertPatchLevel(resolved, isLong);
        entries.put(tag, new DERTaggedObject(true, tag, new ASN1Integer(value)));
    }

    /**
     * Locate the authorization list that actually carries the RootOfTrust (tag 704). It is
     * normally the teeEnforced list at index 7, but a few vendor certificates emit the
     * software/tee lists in the opposite order, so scan both candidate indices and fall back to
     * the canonical position when neither matches.
     */
    private static int findRootOfTrustIndex(ASN1Encodable[] encodables) {
        for (int i = SW_ENFORCED_INDEX; i <= TEE_ENFORCED_INDEX && i < encodables.length; i++) {
            try {
                ASN1Sequence list = (ASN1Sequence) encodables[i];
                for (ASN1Encodable element : list) {
                    if (((ASN1TaggedObject) element).getTagNo() == TAG_ROOT_OF_TRUST) {
                        return i;
                    }
                }
            } catch (Exception ignored) {
                // Not an auth-list sequence; keep scanning.
            }
        }
        return TEE_ENFORCED_INDEX;
    }

    private static byte[] extractBootHash(ASN1Encodable rootOfTrust) {
        try {
            if (rootOfTrust instanceof ASN1Sequence) {
                ASN1Sequence rot = (ASN1Sequence) rootOfTrust;
                if (rot.size() >= 4) {
                    ASN1Encodable hashElement = rot.getObjectAt(3);
                    if (hashElement instanceof ASN1OctetString) {
                        return ((ASN1OctetString) hashElement).getOctets();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract boot hash from original RootOfTrust", e);
        }
        return null;
    }

    public static void storeLeafAlgorithm(String alias, int uid, String algorithm) {
        sLeafAlgorithms.put(alias + "_" + uid, algorithm);
    }

    public static String getLeafAlgorithm(String alias, int uid) {
        return sLeafAlgorithms.remove(alias + "_" + uid);
    }
}
