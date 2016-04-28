package com.mps.deepviolet.suite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.bouncycastle.asn1.ASN1Boolean;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.BERTags;
import org.bouncycastle.asn1.DERApplicationSpecific;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.DERVisibleString;
import org.bouncycastle.asn1.DLSequence;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//import sun.security.provider.certpath.OCSP;
//import sun.security.provider.certpath.OCSP.RevocationStatus;

/**
 * Utility class to handle cryptographic functions.  Significant contributions around
 * ciphersuite handling adapted from code examples by Thomas Pornin <pornin@bolet.org>.
 * @author Milton Smith
 * @see http://tools.ietf.org/html/rfc5246
 * @see http://www.iana.org/assignments/tls-parameters/tls-parameters.xhtml
 * @see http://www.bolet.org/TestSSLServer/
 */
public class CipherSuiteUtil {

	private static final Logger logger = LoggerFactory.getLogger("com.mps.deepviolet.suite.CipherSuiteUtil");
	
	private static final HashMap<URL, HostData> hostcache = new HashMap<URL,HostData>();
	
	// Common OIDs to Extension Mappings
	private static final HashMap<String,String> OIDMAP = new HashMap<String,String>();
	
	private static final SSLSocketFactory dsc = HttpsURLConnection.getDefaultSSLSocketFactory();
	
	private static final HostnameVerifier dhv = HttpsURLConnection.getDefaultHostnameVerifier();
	
	static final int MAX_RECORD_LEN = 16384;
	static final int CHANGE_CIPHER_SPEC = 20;
	static final int ALERT              = 21;
	static final int HANDSHAKE          = 22;
	static final int APPLICATION        = 23;
	private static final SecureRandom RNG = new SecureRandom();
	/*
	 * A constant SSLv2 CLIENT-HELLO message. Only one connection
	 * is needed for SSLv2, since the server response will contain
	 * _all_ the cipher suites that the server is willing to
	 * support.
	 *
	 * Note: when (mis)interpreted as a SSLv3+ record, this message
	 * apparently encodes some data of (invalid) 0x80 type, using
	 * protocol version TLS 44.1, and record length of 2 bytes.
	 * Thus, the receiving part will quickly conclude that it will
	 * not support that, instead of stalling for more data from the
	 * client.
	 */
	private static final byte[] SSL2_CLIENT_HELLO = {
		(byte)0x80, (byte)0x2E,  // header (record length)
		(byte)0x01,              // message type (CLIENT HELLO)
		(byte)0x00, (byte)0x02,  // version (0x0002)
		(byte)0x00, (byte)0x15,  // cipher specs list length
		(byte)0x00, (byte)0x00,  // session ID length
		(byte)0x00, (byte)0x10,  // challenge length
		0x01, 0x00, (byte)0x80,  // SSL_CK_RC4_128_WITH_MD5
		0x02, 0x00, (byte)0x80,  // SSL_CK_RC4_128_EXPORT40_WITH_MD5
		0x03, 0x00, (byte)0x80,  // SSL_CK_RC2_128_CBC_WITH_MD5
		0x04, 0x00, (byte)0x80,  // SSL_CK_RC2_128_CBC_EXPORT40_WITH_MD5
		0x05, 0x00, (byte)0x80,  // SSL_CK_IDEA_128_CBC_WITH_MD5
		0x06, 0x00, (byte)0x40,  // SSL_CK_DES_64_CBC_WITH_MD5
		0x07, 0x00, (byte)0xC0,  // SSL_CK_DES_192_EDE3_CBC_WITH_MD5
		0x54, 0x54, 0x54, 0x54,  // challenge data (16 bytes)
		0x54, 0x54, 0x54, 0x54,
		0x54, 0x54, 0x54, 0x54,
		0x54, 0x54, 0x54, 0x54
	};
	static final int CLEAR  = 0; // no encryption
	static final int WEAK   = 1; // weak encryption: 40-bit key
	static final int MEDIUM = 2; // medium encryption: 56-bit key
	static final int STRONG = 3; // strong encryption
	public static final String NO_CIPHERS = "No Ciphers";	
	static Map<Integer, CipherSuite> CIPHER_SUITES =
			new TreeMap<Integer, CipherSuite>();
	
	static {
		/*
		 * SSLv2 cipher suites.
		 */
		S8(0x010080, "RC4_128_WITH_MD5"               );
		S4(0x020080, "RC4_128_EXPORT40_WITH_MD5"      );
		B8(0x030080, "RC2_128_CBC_WITH_MD5"           );
		B4(0x040080, "RC2_128_CBC_EXPORT40_WITH_MD5"  );
		B8(0x050080, "IDEA_128_CBC_WITH_MD5"          );
		B5(0x060040, "DES_64_CBC_WITH_MD5"            );
		B8(0x0700C0, "DES_192_EDE3_CBC_WITH_MD5"      );

		/*
		 * Original suites (SSLv3, TLS 1.0).
		 */
		N(0x0000, "NULL_WITH_NULL_NULL"                );
		N(0x0001, "RSA_WITH_NULL_MD5"                  );
		N(0x0002, "RSA_WITH_NULL_SHA"                  );
		S4(0x0003, "RSA_EXPORT_WITH_RC4_40_MD5"        );
		S8(0x0004, "RSA_WITH_RC4_128_MD5"              );
		S8(0x0005, "RSA_WITH_RC4_128_SHA"              );
		B4(0x0006, "RSA_EXPORT_WITH_RC2_CBC_40_MD5"    );
		B8(0x0007, "RSA_WITH_IDEA_CBC_SHA"             );
		B4(0x0008, "RSA_EXPORT_WITH_DES40_CBC_SHA"     );
		B5(0x0009, "RSA_WITH_DES_CBC_SHA"              );
		B8(0x000A, "RSA_WITH_3DES_EDE_CBC_SHA"         );
		B4(0x000B, "DH_DSS_EXPORT_WITH_DES40_CBC_SHA"  );
		B5(0x000C, "DH_DSS_WITH_DES_CBC_SHA"           );
		B8(0x000D, "DH_DSS_WITH_3DES_EDE_CBC_SHA"      );
		B4(0x000E, "DH_RSA_EXPORT_WITH_DES40_CBC_SHA"  );
		B5(0x000F, "DH_RSA_WITH_DES_CBC_SHA"           );
		B8(0x0010, "DH_RSA_WITH_3DES_EDE_CBC_SHA"      );
		B4(0x0011, "DHE_DSS_EXPORT_WITH_DES40_CBC_SHA" );
		B5(0x0012, "DHE_DSS_WITH_DES_CBC_SHA"          );
		B8(0x0013, "DHE_DSS_WITH_3DES_EDE_CBC_SHA"     );
		B4(0x0014, "DHE_RSA_EXPORT_WITH_DES40_CBC_SHA" );
		B5(0x0015, "DHE_RSA_WITH_DES_CBC_SHA"          );
		B8(0x0016, "DHE_RSA_WITH_3DES_EDE_CBC_SHA"     );
		S4(0x0017, "DH_anon_EXPORT_WITH_RC4_40_MD5"    );
		S8(0x0018, "DH_anon_WITH_RC4_128_MD5"          );
		B4(0x0019, "DH_anon_EXPORT_WITH_DES40_CBC_SHA" );
		B5(0x001A, "DH_anon_WITH_DES_CBC_SHA"          );
		B8(0x001B, "DH_anon_WITH_3DES_EDE_CBC_SHA"     );

		/*
		 * FORTEZZA suites (SSLv3 only; see RFC 6101).
		 */
		N(0x001C, "FORTEZZA_KEA_WITH_NULL_SHA"          );
		B8(0x001D, "FORTEZZA_KEA_WITH_FORTEZZA_CBC_SHA" );

		/* This one is deactivated since it conflicts with
		   one of the Kerberos cipher suites.
		S8(0x001E, "FORTEZZA_KEA_WITH_RC4_128_SHA"      );
		*/

		/*
		 * Kerberos cipher suites (RFC 2712).
		 */
		B5(0x001E, "KRB5_WITH_DES_CBC_SHA"             );
		B8(0x001F, "KRB5_WITH_3DES_EDE_CBC_SHA"        );
		S8(0x0020, "KRB5_WITH_RC4_128_SHA"             );
		B8(0x0021, "KRB5_WITH_IDEA_CBC_SHA"            );
		B5(0x0022, "KRB5_WITH_DES_CBC_MD5"             );
		B8(0x0023, "KRB5_WITH_3DES_EDE_CBC_MD5"        );
		S8(0x0024, "KRB5_WITH_RC4_128_MD5"             );
		B8(0x0025, "KRB5_WITH_IDEA_CBC_MD5"            );
		B4(0x0026, "KRB5_EXPORT_WITH_DES_CBC_40_SHA"   );
		B4(0x0027, "KRB5_EXPORT_WITH_RC2_CBC_40_SHA"   );
		S4(0x0028, "KRB5_EXPORT_WITH_RC4_40_SHA"       );
		B4(0x0029, "KRB5_EXPORT_WITH_DES_CBC_40_MD5"   );
		B4(0x002A, "KRB5_EXPORT_WITH_RC2_CBC_40_MD5"   );
		S4(0x002B, "KRB5_EXPORT_WITH_RC4_40_MD5"       );

		/*
		 * Pre-shared key, no encryption cipher suites (RFC 4785).
		 */
		N(0x002C, "PSK_WITH_NULL_SHA"                  );
		N(0x002D, "DHE_PSK_WITH_NULL_SHA"              );
		N(0x002E, "RSA_PSK_WITH_NULL_SHA"              );

		/*
		 * AES-based suites (TLS 1.1).
		 */
		B8(0x002F, "RSA_WITH_AES_128_CBC_SHA"          );
		B8(0x0030, "DH_DSS_WITH_AES_128_CBC_SHA"       );
		B8(0x0031, "DH_RSA_WITH_AES_128_CBC_SHA"       );
		B8(0x0032, "DHE_DSS_WITH_AES_128_CBC_SHA"      );
		B8(0x0033, "DHE_RSA_WITH_AES_128_CBC_SHA"      );
		B8(0x0034, "DH_anon_WITH_AES_128_CBC_SHA"      );
		B8(0x0035, "RSA_WITH_AES_256_CBC_SHA"          );
		B8(0x0036, "DH_DSS_WITH_AES_256_CBC_SHA"       );
		B8(0x0037, "DH_RSA_WITH_AES_256_CBC_SHA"       );
		B8(0x0038, "DHE_DSS_WITH_AES_256_CBC_SHA"      );
		B8(0x0039, "DHE_RSA_WITH_AES_256_CBC_SHA"      );
		B8(0x003A, "DH_anon_WITH_AES_256_CBC_SHA"      );

		/*
		 * Suites with SHA-256 (TLS 1.2).
		 */
		N(0x003B, "RSA_WITH_NULL_SHA256"               );
		B8(0x003C, "RSA_WITH_AES_128_CBC_SHA256"       );
		B8(0x003D, "RSA_WITH_AES_256_CBC_SHA256"       );
		B8(0x003E, "DH_DSS_WITH_AES_128_CBC_SHA256"    );
		B8(0x003F, "DH_RSA_WITH_AES_128_CBC_SHA256"    );
		B8(0x0040, "DHE_DSS_WITH_AES_128_CBC_SHA256"   );
		B8(0x0067, "DHE_RSA_WITH_AES_128_CBC_SHA256"   );
		B8(0x0068, "DH_DSS_WITH_AES_256_CBC_SHA256"    );
		B8(0x0069, "DH_RSA_WITH_AES_256_CBC_SHA256"    );
		B8(0x006A, "DHE_DSS_WITH_AES_256_CBC_SHA256"   );
		B8(0x006B, "DHE_RSA_WITH_AES_256_CBC_SHA256"   );
		B8(0x006C, "DH_anon_WITH_AES_128_CBC_SHA256"   );
		B8(0x006D, "DH_anon_WITH_AES_256_CBC_SHA256"   );

		/*
		 * Camellia cipher suites (RFC 5932).
		 */
		B8(0x0041, "RSA_WITH_CAMELLIA_128_CBC_SHA"     );
		B8(0x0042, "DH_DSS_WITH_CAMELLIA_128_CBC_SHA"  );
		B8(0x0043, "DH_RSA_WITH_CAMELLIA_128_CBC_SHA"  );
		B8(0x0044, "DHE_DSS_WITH_CAMELLIA_128_CBC_SHA" );
		B8(0x0045, "DHE_RSA_WITH_CAMELLIA_128_CBC_SHA" );
		B8(0x0046, "DH_anon_WITH_CAMELLIA_128_CBC_SHA" );
		B8(0x0084, "RSA_WITH_CAMELLIA_256_CBC_SHA"     );
		B8(0x0085, "DH_DSS_WITH_CAMELLIA_256_CBC_SHA"  );
		B8(0x0086, "DH_RSA_WITH_CAMELLIA_256_CBC_SHA"  );
		B8(0x0087, "DHE_DSS_WITH_CAMELLIA_256_CBC_SHA" );
		B8(0x0088, "DHE_RSA_WITH_CAMELLIA_256_CBC_SHA" );
		B8(0x0089, "DH_anon_WITH_CAMELLIA_256_CBC_SHA" );

		/*
		 * Unsorted (yet), from the IANA TLS registry:
		 * http://www.iana.org/assignments/tls-parameters/
		 */
		S8(0x008A, "TLS_PSK_WITH_RC4_128_SHA"                        );
		B8(0x008B, "TLS_PSK_WITH_3DES_EDE_CBC_SHA"                   );
		B8(0x008C, "TLS_PSK_WITH_AES_128_CBC_SHA"                    );
		B8(0x008D, "TLS_PSK_WITH_AES_256_CBC_SHA"                    );
		S8(0x008E, "TLS_DHE_PSK_WITH_RC4_128_SHA"                    );
		B8(0x008F, "TLS_DHE_PSK_WITH_3DES_EDE_CBC_SHA"               );
		B8(0x0090, "TLS_DHE_PSK_WITH_AES_128_CBC_SHA"                );
		B8(0x0091, "TLS_DHE_PSK_WITH_AES_256_CBC_SHA"                );
		S8(0x0092, "TLS_RSA_PSK_WITH_RC4_128_SHA"                    );
		B8(0x0093, "TLS_RSA_PSK_WITH_3DES_EDE_CBC_SHA"               );
		B8(0x0094, "TLS_RSA_PSK_WITH_AES_128_CBC_SHA"                );
		B8(0x0095, "TLS_RSA_PSK_WITH_AES_256_CBC_SHA"                );
		B8(0x0096, "TLS_RSA_WITH_SEED_CBC_SHA"                       );
		B8(0x0097, "TLS_DH_DSS_WITH_SEED_CBC_SHA"                    );
		B8(0x0098, "TLS_DH_RSA_WITH_SEED_CBC_SHA"                    );
		B8(0x0099, "TLS_DHE_DSS_WITH_SEED_CBC_SHA"                   );
		B8(0x009A, "TLS_DHE_RSA_WITH_SEED_CBC_SHA"                   );
		B8(0x009B, "TLS_DH_anon_WITH_SEED_CBC_SHA"                   );
		S8(0x009C, "TLS_RSA_WITH_AES_128_GCM_SHA256"                 );
		S8(0x009D, "TLS_RSA_WITH_AES_256_GCM_SHA384"                 );
		S8(0x009E, "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"             );
		S8(0x009F, "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"             );
		S8(0x00A0, "TLS_DH_RSA_WITH_AES_128_GCM_SHA256"              );
		S8(0x00A1, "TLS_DH_RSA_WITH_AES_256_GCM_SHA384"              );
		S8(0x00A2, "TLS_DHE_DSS_WITH_AES_128_GCM_SHA256"             );
		S8(0x00A3, "TLS_DHE_DSS_WITH_AES_256_GCM_SHA384"             );
		S8(0x00A4, "TLS_DH_DSS_WITH_AES_128_GCM_SHA256"              );
		S8(0x00A5, "TLS_DH_DSS_WITH_AES_256_GCM_SHA384"              );
		S8(0x00A6, "TLS_DH_anon_WITH_AES_128_GCM_SHA256"             );
		S8(0x00A7, "TLS_DH_anon_WITH_AES_256_GCM_SHA384"             );
		S8(0x00A8, "TLS_PSK_WITH_AES_128_GCM_SHA256"                 );
		S8(0x00A9, "TLS_PSK_WITH_AES_256_GCM_SHA384"                 );
		S8(0x00AA, "TLS_DHE_PSK_WITH_AES_128_GCM_SHA256"             );
		S8(0x00AB, "TLS_DHE_PSK_WITH_AES_256_GCM_SHA384"             );
		S8(0x00AC, "TLS_RSA_PSK_WITH_AES_128_GCM_SHA256"             );
		S8(0x00AD, "TLS_RSA_PSK_WITH_AES_256_GCM_SHA384"             );
		B8(0x00AE, "TLS_PSK_WITH_AES_128_CBC_SHA256"                 );
		B8(0x00AF, "TLS_PSK_WITH_AES_256_CBC_SHA384"                 );
		N(0x00B0, "TLS_PSK_WITH_NULL_SHA256"                         );
		N(0x00B1, "TLS_PSK_WITH_NULL_SHA384"                         );
		B8(0x00B2, "TLS_DHE_PSK_WITH_AES_128_CBC_SHA256"             );
		B8(0x00B3, "TLS_DHE_PSK_WITH_AES_256_CBC_SHA384"             );
		N(0x00B4, "TLS_DHE_PSK_WITH_NULL_SHA256"                     );
		N(0x00B5, "TLS_DHE_PSK_WITH_NULL_SHA384"                     );
		B8(0x00B6, "TLS_RSA_PSK_WITH_AES_128_CBC_SHA256"             );
		B8(0x00B7, "TLS_RSA_PSK_WITH_AES_256_CBC_SHA384"             );
		N(0x00B8, "TLS_RSA_PSK_WITH_NULL_SHA256"                     );
		N(0x00B9, "TLS_RSA_PSK_WITH_NULL_SHA384"                     );
		B8(0x00BA, "TLS_RSA_WITH_CAMELLIA_128_CBC_SHA256"            );
		B8(0x00BB, "TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA256"         );
		B8(0x00BC, "TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA256"         );
		B8(0x00BD, "TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA256"        );
		B8(0x00BE, "TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA256"        );
		B8(0x00BF, "TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA256"        );
		B8(0x00C0, "TLS_RSA_WITH_CAMELLIA_256_CBC_SHA256"            );
		B8(0x00C1, "TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA256"         );
		B8(0x00C2, "TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA256"         );
		B8(0x00C3, "TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA256"        );
		B8(0x00C4, "TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA256"        );
		B8(0x00C5, "TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA256"        );
		/* This one is a fake cipher suite which marks a
		   renegotiation.
		N(0x00FF, "TLS_EMPTY_RENEGOTIATION_INFO_SCSV"                );
		*/
		N(0xC001, "TLS_ECDH_ECDSA_WITH_NULL_SHA"                     );
		S8(0xC002, "TLS_ECDH_ECDSA_WITH_RC4_128_SHA"                 );
		B8(0xC003, "TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA"            );
		B8(0xC004, "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA"             );
		B8(0xC005, "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA"             );
		N(0xC006, "TLS_ECDHE_ECDSA_WITH_NULL_SHA"                    );
		S8(0xC007, "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA"                );
		B8(0xC008, "TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA"           );
		B8(0xC009, "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA"            );
		B8(0xC00A, "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA"            );
		N(0xC00B, "TLS_ECDH_RSA_WITH_NULL_SHA"                       );
		S8(0xC00C, "TLS_ECDH_RSA_WITH_RC4_128_SHA"                   );
		B8(0xC00D, "TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA"              );
		B8(0xC00E, "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA"               );
		B8(0xC00F, "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA"               );
		N(0xC010, "TLS_ECDHE_RSA_WITH_NULL_SHA"                      );
		S8(0xC011, "TLS_ECDHE_RSA_WITH_RC4_128_SHA"                  );
		B8(0xC012, "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA"             );
		B8(0xC013, "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"              );
		B8(0xC014, "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"              );
		N(0xC015, "TLS_ECDH_anon_WITH_NULL_SHA"                     );
		S8(0xC016, "TLS_ECDH_anon_WITH_RC4_128_SHA"                  );
		B8(0xC017, "TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA"             );
		B8(0xC018, "TLS_ECDH_anon_WITH_AES_128_CBC_SHA"              );
		B8(0xC019, "TLS_ECDH_anon_WITH_AES_256_CBC_SHA"              );
		B8(0xC01A, "TLS_SRP_SHA_WITH_3DES_EDE_CBC_SHA"               );
		B8(0xC01B, "TLS_SRP_SHA_RSA_WITH_3DES_EDE_CBC_SHA"           );
		B8(0xC01C, "TLS_SRP_SHA_DSS_WITH_3DES_EDE_CBC_SHA"           );
		B8(0xC01D, "TLS_SRP_SHA_WITH_AES_128_CBC_SHA"                );
		B8(0xC01E, "TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA"            );
		B8(0xC01F, "TLS_SRP_SHA_DSS_WITH_AES_128_CBC_SHA"            );
		B8(0xC020, "TLS_SRP_SHA_WITH_AES_256_CBC_SHA"                );
		B8(0xC021, "TLS_SRP_SHA_RSA_WITH_AES_256_CBC_SHA"            );
		B8(0xC022, "TLS_SRP_SHA_DSS_WITH_AES_256_CBC_SHA"            );
		B8(0xC023, "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256"         );
		B8(0xC024, "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384"         );
		B8(0xC025, "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256"          );
		B8(0xC026, "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384"          );
		B8(0xC027, "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"           );
		B8(0xC028, "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384"           );
		B8(0xC029, "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256"            );
		B8(0xC02A, "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384"            );
		S8(0xC02B, "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"         );
		S8(0xC02C, "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"         );
		S8(0xC02D, "TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256"          );
		S8(0xC02E, "TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384"          );
		S8(0xC02F, "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"           );
		S8(0xC030, "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"           );
		S8(0xC031, "TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256"            );
		S8(0xC032, "TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384"            );
		S8(0xC033, "TLS_ECDHE_PSK_WITH_RC4_128_SHA"                  );
		B8(0xC034, "TLS_ECDHE_PSK_WITH_3DES_EDE_CBC_SHA"             );
		B8(0xC035, "TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA"              );
		B8(0xC036, "TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA"              );
		B8(0xC037, "TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256"           );
		B8(0xC038, "TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA384"           );
		N(0xC039, "TLS_ECDHE_PSK_WITH_NULL_SHA"                      );
		N(0xC03A, "TLS_ECDHE_PSK_WITH_NULL_SHA256"                   );
		N(0xC03B, "TLS_ECDHE_PSK_WITH_NULL_SHA384"                   );
		B8(0xC03C, "TLS_RSA_WITH_ARIA_128_CBC_SHA256"                );
		B8(0xC03D, "TLS_RSA_WITH_ARIA_256_CBC_SHA384"                );
		B8(0xC03E, "TLS_DH_DSS_WITH_ARIA_128_CBC_SHA256"             );
		B8(0xC03F, "TLS_DH_DSS_WITH_ARIA_256_CBC_SHA384"             );
		B8(0xC040, "TLS_DH_RSA_WITH_ARIA_128_CBC_SHA256"             );
		B8(0xC041, "TLS_DH_RSA_WITH_ARIA_256_CBC_SHA384"             );
		B8(0xC042, "TLS_DHE_DSS_WITH_ARIA_128_CBC_SHA256"            );
		B8(0xC043, "TLS_DHE_DSS_WITH_ARIA_256_CBC_SHA384"            );
		B8(0xC044, "TLS_DHE_RSA_WITH_ARIA_128_CBC_SHA256"            );
		B8(0xC045, "TLS_DHE_RSA_WITH_ARIA_256_CBC_SHA384"            );
		B8(0xC046, "TLS_DH_anon_WITH_ARIA_128_CBC_SHA256"            );
		B8(0xC047, "TLS_DH_anon_WITH_ARIA_256_CBC_SHA384"            );
		B8(0xC048, "TLS_ECDHE_ECDSA_WITH_ARIA_128_CBC_SHA256"        );
		B8(0xC049, "TLS_ECDHE_ECDSA_WITH_ARIA_256_CBC_SHA384"        );
		B8(0xC04A, "TLS_ECDH_ECDSA_WITH_ARIA_128_CBC_SHA256"         );
		B8(0xC04B, "TLS_ECDH_ECDSA_WITH_ARIA_256_CBC_SHA384"         );
		B8(0xC04C, "TLS_ECDHE_RSA_WITH_ARIA_128_CBC_SHA256"          );
		B8(0xC04D, "TLS_ECDHE_RSA_WITH_ARIA_256_CBC_SHA384"          );
		B8(0xC04E, "TLS_ECDH_RSA_WITH_ARIA_128_CBC_SHA256"           );
		B8(0xC04F, "TLS_ECDH_RSA_WITH_ARIA_256_CBC_SHA384"           );
		S8(0xC050, "TLS_RSA_WITH_ARIA_128_GCM_SHA256"                );
		S8(0xC051, "TLS_RSA_WITH_ARIA_256_GCM_SHA384"                );
		S8(0xC052, "TLS_DHE_RSA_WITH_ARIA_128_GCM_SHA256"            );
		S8(0xC053, "TLS_DHE_RSA_WITH_ARIA_256_GCM_SHA384"            );
		S8(0xC054, "TLS_DH_RSA_WITH_ARIA_128_GCM_SHA256"             );
		S8(0xC055, "TLS_DH_RSA_WITH_ARIA_256_GCM_SHA384"             );
		S8(0xC056, "TLS_DHE_DSS_WITH_ARIA_128_GCM_SHA256"            );
		S8(0xC057, "TLS_DHE_DSS_WITH_ARIA_256_GCM_SHA384"            );
		S8(0xC058, "TLS_DH_DSS_WITH_ARIA_128_GCM_SHA256"             );
		S8(0xC059, "TLS_DH_DSS_WITH_ARIA_256_GCM_SHA384"             );
		S8(0xC05A, "TLS_DH_anon_WITH_ARIA_128_GCM_SHA256"            );
		S8(0xC05B, "TLS_DH_anon_WITH_ARIA_256_GCM_SHA384"            );
		S8(0xC05C, "TLS_ECDHE_ECDSA_WITH_ARIA_128_GCM_SHA256"        );
		S8(0xC05D, "TLS_ECDHE_ECDSA_WITH_ARIA_256_GCM_SHA384"        );
		S8(0xC05E, "TLS_ECDH_ECDSA_WITH_ARIA_128_GCM_SHA256"         );
		S8(0xC05F, "TLS_ECDH_ECDSA_WITH_ARIA_256_GCM_SHA384"         );
		S8(0xC060, "TLS_ECDHE_RSA_WITH_ARIA_128_GCM_SHA256"          );
		S8(0xC061, "TLS_ECDHE_RSA_WITH_ARIA_256_GCM_SHA384"          );
		S8(0xC062, "TLS_ECDH_RSA_WITH_ARIA_128_GCM_SHA256"           );
		S8(0xC063, "TLS_ECDH_RSA_WITH_ARIA_256_GCM_SHA384"           );
		B8(0xC064, "TLS_PSK_WITH_ARIA_128_CBC_SHA256"                );
		B8(0xC065, "TLS_PSK_WITH_ARIA_256_CBC_SHA384"                );
		B8(0xC066, "TLS_DHE_PSK_WITH_ARIA_128_CBC_SHA256"            );
		B8(0xC067, "TLS_DHE_PSK_WITH_ARIA_256_CBC_SHA384"            );
		B8(0xC068, "TLS_RSA_PSK_WITH_ARIA_128_CBC_SHA256"            );
		B8(0xC069, "TLS_RSA_PSK_WITH_ARIA_256_CBC_SHA384"            );
		S8(0xC06A, "TLS_PSK_WITH_ARIA_128_GCM_SHA256"                );
		S8(0xC06B, "TLS_PSK_WITH_ARIA_256_GCM_SHA384"                );
		S8(0xC06C, "TLS_DHE_PSK_WITH_ARIA_128_GCM_SHA256"            );
		S8(0xC06D, "TLS_DHE_PSK_WITH_ARIA_256_GCM_SHA384"            );
		S8(0xC06E, "TLS_RSA_PSK_WITH_ARIA_128_GCM_SHA256"            );
		S8(0xC06F, "TLS_RSA_PSK_WITH_ARIA_256_GCM_SHA384"            );
		B8(0xC070, "TLS_ECDHE_PSK_WITH_ARIA_128_CBC_SHA256"          );
		B8(0xC071, "TLS_ECDHE_PSK_WITH_ARIA_256_CBC_SHA384"          );
		B8(0xC072, "TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_CBC_SHA256"    );
		B8(0xC073, "TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_CBC_SHA384"    );
		B8(0xC074, "TLS_ECDH_ECDSA_WITH_CAMELLIA_128_CBC_SHA256"     );
		B8(0xC075, "TLS_ECDH_ECDSA_WITH_CAMELLIA_256_CBC_SHA384"     );
		B8(0xC076, "TLS_ECDHE_RSA_WITH_CAMELLIA_128_CBC_SHA256"      );
		B8(0xC077, "TLS_ECDHE_RSA_WITH_CAMELLIA_256_CBC_SHA384"      );
		B8(0xC078, "TLS_ECDH_RSA_WITH_CAMELLIA_128_CBC_SHA256"       );
		B8(0xC079, "TLS_ECDH_RSA_WITH_CAMELLIA_256_CBC_SHA384"       );
		S8(0xC07A, "TLS_RSA_WITH_CAMELLIA_128_GCM_SHA256"            );
		S8(0xC07B, "TLS_RSA_WITH_CAMELLIA_256_GCM_SHA384"            );
		S8(0xC07C, "TLS_DHE_RSA_WITH_CAMELLIA_128_GCM_SHA256"        );
		S8(0xC07D, "TLS_DHE_RSA_WITH_CAMELLIA_256_GCM_SHA384"        );
		S8(0xC07E, "TLS_DH_RSA_WITH_CAMELLIA_128_GCM_SHA256"         );
		S8(0xC07F, "TLS_DH_RSA_WITH_CAMELLIA_256_GCM_SHA384"         );
		S8(0xC080, "TLS_DHE_DSS_WITH_CAMELLIA_128_GCM_SHA256"        );
		S8(0xC081, "TLS_DHE_DSS_WITH_CAMELLIA_256_GCM_SHA384"        );
		S8(0xC082, "TLS_DH_DSS_WITH_CAMELLIA_128_GCM_SHA256"         );
		S8(0xC083, "TLS_DH_DSS_WITH_CAMELLIA_256_GCM_SHA384"         );
		S8(0xC084, "TLS_DH_anon_WITH_CAMELLIA_128_GCM_SHA256"        );
		S8(0xC085, "TLS_DH_anon_WITH_CAMELLIA_256_GCM_SHA384"        );
		S8(0xC086, "TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_GCM_SHA256"    );
		S8(0xC087, "TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_GCM_SHA384"    );
		S8(0xC088, "TLS_ECDH_ECDSA_WITH_CAMELLIA_128_GCM_SHA256"     );
		S8(0xC089, "TLS_ECDH_ECDSA_WITH_CAMELLIA_256_GCM_SHA384"     );
		S8(0xC08A, "TLS_ECDHE_RSA_WITH_CAMELLIA_128_GCM_SHA256"      );
		S8(0xC08B, "TLS_ECDHE_RSA_WITH_CAMELLIA_256_GCM_SHA384"      );
		S8(0xC08C, "TLS_ECDH_RSA_WITH_CAMELLIA_128_GCM_SHA256"       );
		S8(0xC08D, "TLS_ECDH_RSA_WITH_CAMELLIA_256_GCM_SHA384"       );
		S8(0xC08E, "TLS_PSK_WITH_CAMELLIA_128_GCM_SHA256"            );
		S8(0xC08F, "TLS_PSK_WITH_CAMELLIA_256_GCM_SHA384"            );
		S8(0xC090, "TLS_DHE_PSK_WITH_CAMELLIA_128_GCM_SHA256"        );
		S8(0xC091, "TLS_DHE_PSK_WITH_CAMELLIA_256_GCM_SHA384"        );
		S8(0xC092, "TLS_RSA_PSK_WITH_CAMELLIA_128_GCM_SHA256"        );
		S8(0xC093, "TLS_RSA_PSK_WITH_CAMELLIA_256_GCM_SHA384"        );
		B8(0xC094, "TLS_PSK_WITH_CAMELLIA_128_CBC_SHA256"            );
		B8(0xC095, "TLS_PSK_WITH_CAMELLIA_256_CBC_SHA384"            );
		B8(0xC096, "TLS_DHE_PSK_WITH_CAMELLIA_128_CBC_SHA256"        );
		B8(0xC097, "TLS_DHE_PSK_WITH_CAMELLIA_256_CBC_SHA384"        );
		B8(0xC098, "TLS_RSA_PSK_WITH_CAMELLIA_128_CBC_SHA256"        );
		B8(0xC099, "TLS_RSA_PSK_WITH_CAMELLIA_256_CBC_SHA384"        );
		B8(0xC09A, "TLS_ECDHE_PSK_WITH_CAMELLIA_128_CBC_SHA256"      );
		B8(0xC09B, "TLS_ECDHE_PSK_WITH_CAMELLIA_256_CBC_SHA384"      );
		S8(0xC09C, "TLS_RSA_WITH_AES_128_CCM"                        );
		S8(0xC09D, "TLS_RSA_WITH_AES_256_CCM"                        );
		S8(0xC09E, "TLS_DHE_RSA_WITH_AES_128_CCM"                    );
		S8(0xC09F, "TLS_DHE_RSA_WITH_AES_256_CCM"                    );
		S8(0xC0A0, "TLS_RSA_WITH_AES_128_CCM_8"                      );
		S8(0xC0A1, "TLS_RSA_WITH_AES_256_CCM_8"                      );
		S8(0xC0A2, "TLS_DHE_RSA_WITH_AES_128_CCM_8"                  );
		S8(0xC0A3, "TLS_DHE_RSA_WITH_AES_256_CCM_8"                  );
		S8(0xC0A4, "TLS_PSK_WITH_AES_128_CCM"                        );
		S8(0xC0A5, "TLS_PSK_WITH_AES_256_CCM"                        );
		S8(0xC0A6, "TLS_DHE_PSK_WITH_AES_128_CCM"                    );
		S8(0xC0A7, "TLS_DHE_PSK_WITH_AES_256_CCM"                    );
		S8(0xC0A8, "TLS_PSK_WITH_AES_128_CCM_8"                      );
		S8(0xC0A9, "TLS_PSK_WITH_AES_256_CCM_8"                      );
		S8(0xC0AA, "TLS_PSK_DHE_WITH_AES_128_CCM_8"                  );
		S8(0xC0AB, "TLS_PSK_DHE_WITH_AES_256_CCM_8"                  );
	}
	
	

	static {
		
//        Security.addProvider(new com.sun.net.ssl.internal.ssl.Provider());
		
		Security.addProvider( new BouncyCastleProvider() );
        
		OIDMAP.put( "2.5.29.14","SubjectKeyIdentifier");
		OIDMAP.put( "2.5.29.15","KeyUsage");
		OIDMAP.put( "2.5.29.16","PrivateKeyUsage");
		OIDMAP.put( "2.5.29.17","SubjectAlternativeName");
		OIDMAP.put( "2.5.29.18","IssuerAlternativeName");
		OIDMAP.put( "2.5.29.19","BasicConstraints");
		OIDMAP.put( "2.5.29.30","NameConstraints");
		OIDMAP.put( "2.5.29.33","PolicyMappings");
		OIDMAP.put( "2.5.29.35","AuthorityKeyIdentifier");
		OIDMAP.put( "2.5.29.36","PolicyConstraints");
		
		OIDMAP.put( "1.3.6.1.5.5.7.48.1","ocsp");
		OIDMAP.put( "1.3.6.1.5.5.7.48.2","caIssuers");
		OIDMAP.put( "1.2.840.113549.1.1.1","SubjectPublicKeyInfo");
		OIDMAP.put( "1.3.6.1.5.5.7.48.1.1","BasicOCSPResponse");
		OIDMAP.put( "1.2.840.113549.1.1.5","SignatureAlgorithm");
		OIDMAP.put( "1.3.6.1.5.5.7.1.1","AuthorityInfoAccess");
		OIDMAP.put("1.3.6.1.4.1.11129.2.4.2", "SignedCertificateTimestampList");
		OIDMAP.put("1.3.6.1.5.5.7.2.2", "CPSUserNotice");
		OIDMAP.put( "2.5.29.31","CRLDistributionPoints");
		OIDMAP.put( "2.5.29.32","CertificatePolicies");
		OIDMAP.put( "1.3.6.1.4.1.6449.1.2.1.5.1","CertificatePolicyId");
		OIDMAP.put( "2.5.29.32","CertificatePolicies");
		OIDMAP.put( "1.3.6.1.5.5.7.2.1","qualifierID");
		OIDMAP.put( "2.5.29.37","ExtendedKeyUsages");
		OIDMAP.put( "2.5.29.15","KeyUsage");
		
		OIDMAP.put( "2.5.29.14","SubjectKeyIdentifier");
		OIDMAP.put( "2.5.29.15","KeyUsage");
		OIDMAP.put( "2.5.29.15","KeyUsage");
		OIDMAP.put( "2.5.29.15","KeyUsage");
		OIDMAP.put( "2.5.29.15","KeyUsage");
		OIDMAP.put( "2.5.29.15","KeyUsage");
		
	}
		
	public static synchronized ServerMetadata getServerMetadataInstance( URL url ) throws Exception {
		
		HostData hostdata = null;
		Boolean compress = false;
		
		// return cached instance of server TLS data if available and not expired.
		if ( hostcache.containsKey( url ) ) {
			hostdata = hostcache.get(url);
//TODOMS: FOR NOW DON'T CACHE ANYTHING.  NOT SURE THIS MAKES SENSE.
//			if( !hostdata.isExpired() )
//				return hostdata;
		// No cached instance of TLS data so create some
		} else {
			hostdata = new HostData(url);
			hostcache.put(url, hostdata);	
		}
			
		String name = url.getHost();
		int port = ( url.getPort() > 0 ) ? url.getPort() : 443;
		
		InetSocketAddress isa = new InetSocketAddress(name, port);

		Set<Integer> sv = new TreeSet<Integer>();
		for (int v = 0x0300; v <= 0x0303; v ++) {
			ServerHello sh = connect(isa,
				v, CIPHER_SUITES.keySet());
			if (sh == null) {
				continue;
			}
			sv.add(sh.protoVersion);
			if (sh.compression == 1) {
				compress = true;
				hostdata.setScalarValue("getServerMetadataInstance","DEFLATE_COMPRESSION", "TRUE");
			}else{
				hostdata.setScalarValue("getServerMetadataInstance","DEFLATE_COMPRESSION", "FALSE");
			}
		}
		
		ServerHelloSSLv2 sh2 = connectV2(isa);

		if (sh2 != null) {
			sv.add(0x0200);
		}

		if (sv.size() == 0) {
			logger.error("Server may not be SSL\\TLS enabled. host=" + isa);
			return null;
		}

		Set<Integer> lastSuppCS = null;
		Map<Integer, Set<Integer>> suppCS =
			new TreeMap<Integer, Set<Integer>>();
		Set<String> certID = new TreeSet<String>();
		boolean vulnFREAK = false;
		
		if (sh2 != null) {

			ArrayList<String> listv2 = new ArrayList<String>();
			String[] tmp = new String[0];
			
			Set<Integer> vc2 = new TreeSet<Integer>();
			for (int c : sh2.cipherSuites) {
				vc2.add(c);
			}
			for (int c : vc2) {
				if( !vulnFREAK ) { vulnFREAK = cipherSuiteStringV2(c).indexOf("EXPORT") > -1; }
				listv2.add( cipherSuiteStringV2(c)+"(0x"+Integer.toHexString(c)+")" );

			}
			suppCS.put(0x0200, vc2);
			if (sh2.serverCertName != null) {
				hostdata.setScalarValue("getServerMetadataInstance",sh2.serverCertHash, sh2.serverCertName);				
			}
			hostdata.setVectorValue( "getServerMetadataInstance",versionString(0x0200), listv2.toArray(tmp));
			
		}

		for (int v : sv) {
			if (v == 0x0200) {
				continue;
			}
			Set<Integer> vsc = supportedSuites(isa, v, certID);
			suppCS.put(v, vsc);
			
			ArrayList<String> listv = new ArrayList<String>();
			String[] tmp = new String[0];
			
			if (lastSuppCS == null || !lastSuppCS.equals(vsc)) {
				
				for (int c : vsc) {
					if( !vulnFREAK ) { vulnFREAK = cipherSuiteString(c).indexOf("EXPORT") > -1; }
					listv.add( cipherSuiteString(c)+"(0x"+Integer.toHexString(c)+")" );
				}				
				lastSuppCS = vsc;
			
			} else {

				listv.add( NO_CIPHERS );
			}
			hostdata.setVectorValue( "getServerMetadataInstance",versionString(v), listv.toArray(tmp));
			
		}
		
//		for (int v : sv) {
//			if (v == 0x0200) {
//				continue;
//			}
//			Set<Integer> vsc = supportedSuites(isa, v, certID);
//			suppCS.put(v, vsc);
//		}
		
		
		// Iterate over supported ciphersuites.
		int agMaxStrength = STRONG;
		int agMinStrength = STRONG;
		boolean vulnBEAST = false;
		for (int v : sv) {
			Set<Integer> vsc = suppCS.get(v);
			agMaxStrength = Math.min(
				maxStrength(vsc), agMaxStrength);
			agMinStrength = Math.min(
				minStrength(vsc), agMinStrength);
			if (!vulnBEAST) {
				vulnBEAST = testBEAST(isa, v, vsc);
			}
		}
		
//TODO: NEEDS TO BE CHECKED AND TESTED.  COMMENT OUT AT YOUR OWN RISK.
//		hostdata.setScalarValue("analysis","MINIMAL_ENCRYPTION_STRENGTH", strengthString(agMinStrength));
//		hostdata.setScalarValue("analysis","ACHIEVABLE_ENCRYPTION_STRENGTH", strengthString(agMinStrength));
//		hostdata.setScalarValue("analysis","BEAST_VULNERABLE", vulnBEAST ? "vulnerable" : "protected");
//		hostdata.setScalarValue("analysis","CRIME_VULNERABLE", compress ? "vulnerable" : "protected");
//		hostdata.setScalarValue("analysis","FREAK_VULNERABLE", vulnFREAK ? "vulnerable" : "protected");
		
		return hostdata;
		
	}
	
	
	static String versionString(int version) {
		if (version == 0x0200) {
			return "SSLv2";
		} else if (version == 0x0300) {
			return "SSLv3";
		} else if ((version >>> 8) == 0x03) {
			return "TLSv1." + ((version & 0xFF) - 1);
		} else {
			return String.format("UNKNOWN_VERSION:0x%04X", version);
		}
	}
	
	/*
	 * Get cipher suites supported by the server. This is done by
	 * repeatedly contacting the server, each time removing from our
	 * list of supported suites the suite which the server just
	 * selected. We keep on until the server can no longer respond
	 * to us with a ServerHello.
	 */
	static Set<Integer> supportedSuites(InetSocketAddress isa, int version,
		Set<String> serverCertID)
	{
		Set<Integer> cs = new TreeSet<Integer>(CIPHER_SUITES.keySet());
		Set<Integer> rs = new TreeSet<Integer>();
		for (;;) {
			ServerHello sh = connect(isa, version, cs);
			if (sh == null) {
				break;
			}
			if (!cs.contains(sh.cipherSuite)) {
				logger.error("[ERR: server wants to use"
					+ " cipher suite 0x%04X which client"
					+ " did not announce]", sh.cipherSuite);
				break;
			}
			cs.remove(sh.cipherSuite);
			rs.add(sh.cipherSuite);
			if (sh.serverCertName != null) {
				serverCertID.add(sh.serverCertHash
					+ ": " + sh.serverCertName);
			}
		}
		return rs;
	}

	
	static int minStrength(Set<Integer> supp)
	{
		int m = STRONG;
		for (int suite : supp) {
			CipherSuite cs = CIPHER_SUITES.get(suite);
			if (cs == null) {
				continue;
			}
			if (cs.strength < m) {
				m = cs.strength;
			}
		}
		return m;
	}

	static int maxStrength(Set<Integer> supp)
	{
		int m = CLEAR;
		for (int suite : supp) {
			CipherSuite cs = CIPHER_SUITES.get(suite);
			if (cs == null) {
				continue;
			}
			if (cs.strength > m) {
				m = cs.strength;
			}
		}
		return m;
	}
	
	/**
	 * Utility method to convert an <code>javax.security.cert.X509Certificate</code> to
	 * <code>java.security.cert.X509Certificate</code>.  No doubt, a bit of legacy.
	 * @param cert X509 certificate to convert.
	 * @return java.security.cert.X509Certificate Converted object
	 * @see http://exampledepot.8waytrips.com/egs/javax.security.cert/ConvertCert.html
	 */
	public static java.security.cert.X509Certificate convert(javax.security.cert.X509Certificate cert) {
	    try {
	        byte[] encoded = cert.getEncoded();
	        ByteArrayInputStream bis = new ByteArrayInputStream(encoded);
	        java.security.cert.CertificateFactory cf
	            = java.security.cert.CertificateFactory.getInstance("X.509");
	        return (java.security.cert.X509Certificate)cf.generateCertificate(bis);
	    } catch (javax.security.cert.CertificateEncodingException e) {
			logger.error(e.getMessage(),e);
	    } catch (java.security.cert.CertificateException e) {
			logger.error(e.getMessage(),e);
	    }
	    return null;
	}
	
	/**
	 * Analysis to determine ciphersuite strength.
	 * @param protocol Ciphersuite protocol to test.
	 * @return String indicating strength, CLEAR(no encryption), WEAK, MEDIUM, STRONG. 
	 */
	public static final String getStrength(String protocol) {
		
		String clear = "CLEAR(no encryption)";
		String weak = "WEAK";
		String medium = "MEDIUM";
		String strong = "STRONG";
		
		String result = clear;
		
		if (protocol.contains("_NULL_") ) {
			
			result = clear;
			
		} else if ( protocol.contains("DES40") ||
				    protocol.contains("_40_") ||
				    protocol.contains("_EXPORT40_") ) {
		
			result = weak;
		
		} else if ( protocol.contains("_DES_") ||
			    protocol.contains("_DES64_") ||
			    protocol.contains("_DES192_") ) {
	
		result = medium;
		
		} else {
			
			result = strong;
		}
	
	    return result;
		
	}
	
	/**
	 * Retrieve a server certificate based upon URL.
	 * @param url Target URL
	 * @return X509Certificate Server certificate.
	 * @throws Exception Thrown on problems.
	 */
	public static final X509Certificate getServerCertificate(URL url) throws Exception {
		
		X509Certificate[] certs = getServerCertificateChain(url);
		
		return certs[0];
		
	}
	
	/**
	 * Return server responses
	 * @param url Target URL
	 * @return Map HTTPS response headers
	 * @throws  Thrown on problems.
	 */
	public static final Map<String, List<String>> getHttpResponseHeaders(URL url) throws Exception {
		
		HttpsURLConnection conn = null;
		
		Map<String, List<String>> result = new HashMap<String, List<String>>();
		
		try {
			
			enableTLSChainTesting(false);
		
	        conn = (HttpsURLConnection)url.openConnection();
	        
	        conn.connect();
	        
	        result = conn.getHeaderFields();
			
		} finally {
			
			enableTLSChainTesting(true);
		}
		
		
        return result;
        
	}
	
//	public static final synchronized ServerMetadata getHttpResponseHeaders(URL url) throws Exception {
//		
//		HostData hostdata = null;
//		
//		// return cached instance of server TLS data if available and not expired.
//		if ( hostcache.containsKey( url ) ) {
//			hostdata = hostcache.get(url);
//			if( !hostdata.isExpired() )
//				return hostdata;
//		}
//		
//		// No cached instance of TLS data so create some
//		hostdata = new HostData(url);
//		hostcache.put(url, hostdata);	
//		
//        HttpsURLConnection conn = (HttpsURLConnection)url.openConnection();
//        conn.connect();
//        Map<String, List<String>> headers = conn.getHeaderFields();
//        
//        Set<String> k = headers.keySet();
//        Iterator<String> keys = k.iterator();
//        
//        while( keys.hasNext() ) {
//        	
//        	String key = keys.next();
//        	hostdata.setVectorValue(key,(String[])headers.get(key).toArray() );
//        	
//        }
//		
//        return hostdata;
//        
//	}
	
	/**
	 * Enable default testing for TLS certificate trust chains.
	 * @param value true, chain will be tested.  false, chain will not be tested.
	 * @throws Exception 
	 */
	public static final void enableTLSChainTesting( boolean value ) throws Exception {

		if( value ) {
			
			HttpsURLConnection.setDefaultSSLSocketFactory(dsc);
			HttpsURLConnection.setDefaultHostnameVerifier(dhv);
			
		} else {
			
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, new TrustManager[] { new TrustAllX509TrustManager() }, new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
			HttpsURLConnection.setDefaultHostnameVerifier( new HostnameVerifier(){
			    public boolean verify(String string,SSLSession ssls) {
			        return true;
			    }
			});
			
		}
		
	}
	
	/**
	 * Retrieve a certificate chain based upon URL.  Note this API will return
	 * certificates with unvalidated and possibly bad trust chains.
	 * @param url Target URL
	 * @return X509Certificate Certificate chain
	 * @throws  Thrown on problems.
	 * @see http://stackoverflow.com/questions/19723415/java-overriding-function-to-disable-ssl-certificate-check
	 */
	public static final X509Certificate[] getServerCertificateChain(URL url) throws Exception {

        ArrayList<X509Certificate> list = new ArrayList<X509Certificate>();
		
		try {
			
			enableTLSChainTesting(false);
			
	        HttpsURLConnection conn = (HttpsURLConnection)url.openConnection();
	        conn.connect();
	        Certificate[] certs = conn.getServerCertificates();
	        
	        for (Certificate cert : certs) {
	        	
	            if(cert instanceof X509Certificate) {            	
	            	list.add( (X509Certificate)cert );           
	            } else {
	            	logger.info("Unsupported certificate type.  type="+cert.getClass().getName());
	            }
	        }
	        
		} finally {
			
			enableTLSChainTesting(true);
			
		}
	
        return list.toArray(new X509Certificate[0]);
	}
	
	/**
	 * Get a list of the Java root certificates
	 * @return
	 * @throws Exception
	 * @see http://stackoverflow.com/questions/3508050/how-can-i-get-a-list-of-trusted-root-certificates-in-java
	 */
	public static final X509Certificate[] getJavaRootCertificates() throws Exception {
		
		//TODO: Maybe be good to consider caching this at some point (at least for a few seconds)
		
		// Load the JDK's cacerts keystore file
		String filename = System.getProperty("java.home") + "/lib/security/cacerts".replace('/', File.separatorChar);
    	logger.debug("CACERTS file, "+filename);
		FileInputStream is = new FileInputStream(filename);
		KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
		String password = "changeit"; //default password
		keystore.load(is, password.toCharArray());
		
		// This class retrieves the most-trusted CAs from the keystore
		PKIXParameters params = new PKIXParameters(keystore);
		
		// Get the set of trust anchors, which contain the most-trusted CA certificates,
		// and return the java root certs.
		Iterator<TrustAnchor> it = params.getTrustAnchors().iterator();
		ArrayList<X509Certificate> result = new ArrayList<X509Certificate>();
		while( it.hasNext() ) {
			TrustAnchor ta = it.next();
			result.add(ta.getTrustedCert());
		}
		
		return (X509Certificate[])result.toArray(new X509Certificate[0]);

	}

	/**
	 * Test to see if a particular SHA1 hash is a root in the Java system keystore.
	 * @param sha1hash
	 * @return true, SHA1 hash belongs to a Java root.  false, no Java root found.
	 */
	public static final boolean isJavaRootCertificateSHA1(String sha1hash) throws Exception {
		
		boolean result = false;
		
		for( X509Certificate cert : getJavaRootCertificates() ) {
			
			String fingerprint = sha1Fingerprint(cert.getEncoded());
		
			if( fingerprint.equals(sha1hash) ) {
				
				result = true; break;
			}
		}
		
		return result;
	}
	
	/**
	 * Test to see if a particular IssuerDN is a root in the Java system keystore.
	 * @param IssuerDN
	 * @return true, IssuerDN matches a Java root.  false, no matching IssuerDN found.
	 */
	public static final boolean isJavaRootCertificateDN(String IssuerDN) throws Exception {
		
		boolean result = false;
		
		for( X509Certificate cert : getJavaRootCertificates() ) {
			
			if ( cert.getIssuerDN().getName().equals(IssuerDN) ) {
				
				result = true; break;
			}
		}
		
		return result;
	}
	
	/**
	 * Check trust status of each certificate in the chain.
	 * @param certs Chain of X509Certificates to test.
	 * @param url Server URL to test against.
	 * @throws KeyStoreException
	 * @throws NoSuchAlgorithmException
	 * @throws UnknownHostException
	 * @throws IOException
	 * @return True, the certificate chain is trusted.  False, the chain is not trusted.
	 */
	public static final boolean checkTrustedCertificate( X509Certificate[] certs, URL url) throws KeyStoreException,
			NoSuchAlgorithmException, UnknownHostException, IOException {
		
		boolean valid = false;
		
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket socket = (SSLSocket)factory.createSocket(url.getHost(), url.getDefaultPort());  
        SSLSession session = socket.getSession();
        String keyexchalgo = getKeyExchangeAlgorithm(session);
		try { socket.close(); } catch( IOException e ) {}
		
		TrustManagerFactory trustManagerFactory =
			    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trustManagerFactory.init((KeyStore)null);
			// you could use a non-default KeyStore as your truststore too, instead of null.

			for (TrustManager trustManager: trustManagerFactory.getTrustManagers()) {  
			    if (trustManager instanceof X509TrustManager) {  
			        X509TrustManager x509TrustManager = (X509TrustManager)trustManager;  
			        try { 
			        	x509TrustManager.checkServerTrusted(certs,keyexchalgo);
			        	valid = true;
			        } catch( CertificateException e ) {
			        	// Eat the stacktrace
			        	logger.error( "url="+url.toString() );
			        }
			    }
			        
			}
		return valid;
	}
	
	// check OCSP
//	public static final String checkOCSPStatus(X509Certificate cert, X509Certificate issuer) {
//		
//		// init PKIX parameters
//        PKIXParameters params = null;
//	    params = new PKIXParameters(trustedCertsSet);
//	    params.addCertStore(store);
//	
//	    // enable OCSP
//	    Security.setProperty("ocsp.enable", "true");
//	    if (ocspServer != null) {
//			Security.setProperty("ocsp.responderURL", args[1]);
//			Security.setProperty("ocsp.responderCertSubjectName",
//			    ocspCert.getSubjectX500Principal().getName());
//	    }
//	
//	    // perform validation
//	    CertPathValidator cpv = CertPathValidator.getInstance("PKIX");
//	    PKIXCertPathValidatorResult cpv_result  =
//		(PKIXCertPathValidatorResult) cpv.validate(cp, params);
//	    X509Certificate trustedCert = (X509Certificate)
//		cpv_result.getTrustAnchor().getTrustedCert();
//	
//	    if (trustedCert == null) {
//	    	System.out.println("Trsuted Cert = NULL");
//	    } else {
//	    	System.out.println("Trusted CA DN = " +
//	    			trustedCert.getSubjectDN());
//	    }
//		
//		return buff.toString();	
//		
//    }
	
	/**
	 * Parse out the Cipher's Key Exchange Algorithm
	 * @param session Target SSLSession
	 * @return String TLS key exchange algorithm.
	 */
	private static final String getKeyExchangeAlgorithm( SSLSession session ) {
		
		String cipher = session.getCipherSuite().toString();
		
		int i1 = cipher.indexOf('_')+1;
		
		int i2 = cipher.indexOf("_WITH");
		
		String keyexch = cipher.substring(i1, i2);
		
		return keyexch;
		
	}
	
	/**
	 * Is this test certificate a self-signed certificate.
	 * @param cert Target certificate to test.
	 * @return boolean True, certificate is self-signed.  False, certificate is not self-signed. 
	 */
	public static final boolean isSelfSignedCertificate( X509Certificate cert ) {
		
		boolean result = false;
		
		if (cert != null ) {
			
			if ( cert.getIssuerDN().equals(cert.getSubjectDN()) )
				result = true;
			
		}
		
			return result;
		
		}
	
	   /**
	    * Generate signer fingerprint from certificate bytes
	    * @param der Certificate in bytes
	    * @param String signatureAlgorithm algorithm the certificate was signed, ex: SHA256
	    * @return String Signer fingerprint in hex.
	    * @throws NoSuchAlgorithmException
	    */
	   public static final String signerFingerprint( byte[] der, String signatureAlgorithm ) throws NoSuchAlgorithmException {
		   
		   MessageDigest sha1 = MessageDigest.getInstance(signatureAlgorithm);
		   sha1.update( der );
		   
		   StringBuffer buff = new StringBuffer();
		   buff.append("0x");
		   buff.append(byteArrayToHex(sha1.digest()));
		   
		   return buff.toString();
		   
	   }	
	
	   /**
	    * Generate SHA1 fingerprint from certificate bytes
	    * @param der Certificate in bytes
	    * @return String SHA1 fingerprint in hex.
	    * @throws NoSuchAlgorithmException
	    */
	   public static final String sha1Fingerprint( byte[] der ) throws NoSuchAlgorithmException {
		   
		   MessageDigest sha1 = MessageDigest.getInstance("SHA1");
		   sha1.update( der );
		   
		   StringBuffer buff = new StringBuffer();
		   buff.append("0x");
		   buff.append(byteArrayToHex(sha1.digest()));
		   
		   return buff.toString();
		   
	   }
	   
	   /**
	    * Generate MD5 fingerprint from certificate bytes
	    * @param der Certificate in bytes
	    * @return String MD5 fingerprint in hex.
	    * @throws NoSuchAlgorithmException
	    */	   
	   public static final String md5Fingerprint( byte[] der ) throws NoSuchAlgorithmException {
		   
		   MessageDigest sha1 = MessageDigest.getInstance("MD5");
		   sha1.update( der );
		   
		   StringBuffer buff = new StringBuffer();
		   buff.append("0x");
		   buff.append(byteArrayToHex(sha1.digest()));
		   
		   return buff.toString();
		   
	   }
	   
	   /**
	    * Convert an array of bytes to a String based hex representation
	    * @param a Target byte array to convert.
	    * @return String String based hex representation of target byte array. 
	    */
	   public static String byteArrayToHex(byte[] a) {
		   StringBuilder sb = new StringBuilder(a.length * 2);
		   for(byte b: a) {
		      sb.append(String.format("%02x", b & 0xff));
		      sb.append(':');
		   }
		   sb.setLength(sb.length()-1);
		   return sb.toString().toUpperCase();
		}

	   public static String getOIDKeyName(String oidkey) {
		   
		   // TODO: Need to figure out a better way to do this.
		   return (OIDMAP.get(oidkey)!=null) ? OIDMAP.get(oidkey) : oidkey;
		   
	   }

	/**
	 * Convert <code>der</code> encoded data to <code>ASN1Primitive</code>. 
	 * @param data byte[] of <code>der</code> encoded data
	 * @return <code>ASN1Primitive</code> representation of <code>der</code> encoded data
	 * @throws IOException
	 * @see http://stackoverflow.com/questions/2409618/how-do-i-decode-a-der-encoded-string-in-java
	 */
	public static final ASN1Primitive toDERObject(byte[] data) throws IOException
	{


		   
		ByteArrayInputStream inStream = new ByteArrayInputStream(data);
		
		ASN1InputStream asnInputStream = new ASN1InputStream(inStream);
	    
	    ASN1Primitive p = asnInputStream.readObject();

	    asnInputStream.close();
	    
	    return p;
	}

	/**
	 * Reentrant method to decode ASN1Primiatives.  ASN1Primiatives types handled: <code>DEROctetString</code>,
	 * <code>DLSequence</code>, <code>DERSequence</code>, <code>DERIA5String</code>, <code>DERBitString</code>,
	 * <code>ASN1Boolean</code>, <code>ASN1Integer</code>, <code>DERTaggedObject</code>,
	 * <code>ASN1ObjectIdentifier</code>.  NOTE: this does not decode all OIDs.  For supported
	 * OIDs please refer to the code.  This is mostly a trial and error process as follows, 1) review logs
	 * for unsupported OIDs, 2) include code to support new OIDs, 3) explore more web sites, 4) goto step 1
	 * @param primitive
	 * @param buff
	 * @throws IOException
	 */
	public static final void walkASN1Sequence( ASN1Primitive primitive, StringBuffer buff ) throws IOException {
		
		
	    if (primitive instanceof DEROctetString) {
	    	
	    	byte[] bytes = ((DEROctetString) primitive).getOctets();
	    	
	    	ASN1Primitive p = null;
	    	
	    	try {
	    	
	    		p = toDERObject(bytes);
		    	walkASN1Sequence(p, buff);
	
	    	} catch (IOException e ) {
	    	
	    		buff.append( byteArrayToHex(bytes));
	    		
	    	}
	    	
	    } else if( primitive instanceof DLSequence ) {
	    	
	    	DLSequence dl = (DLSequence)primitive;
	    	
	    	for (int i=0; i < dl.size() ; i++ ) {
	    		
	    		ASN1Primitive p = dl.getObjectAt(i).toASN1Primitive();
	    		
	    		walkASN1Sequence( p, buff );            		
	    		
	    	}
	    	
	    } else if( primitive instanceof DERSequence ) {
	    	
	    	DERSequence ds = (DERSequence)primitive;
	    	
	    	for (int i=0; i < ds.size() ; i++ ) {
	    		
	    		ASN1Primitive p = ds.getObjectAt(i).toASN1Primitive();
	    		
	    		walkASN1Sequence( p, buff );            		
	    		
	    	}
	    	
	    } else if( primitive instanceof DERApplicationSpecific ) {
	    	
	    	//TODO: May be useful to parse differently depending upon tag type in future.
	    	DERApplicationSpecific app = (DERApplicationSpecific)primitive;
	    	int tag = app.getApplicationTag();

    		StringBuffer buff2 = new StringBuffer();
    		buff2.append( "tag="+tag+" ");
            String hex = CipherSuiteUtil.byteArrayToHex(app.getContents());
            buff2.append( " 0x"+hex );
            buff.append(buff2.toString());

	    // Assistance by https://svn.cesecore.eu/svn/ejbca/branches/Branch_3_11/ejbca/conf/extendedkeyusage.properties
	    } else if (primitive instanceof ASN1ObjectIdentifier ) {
	    	
	    	ASN1ObjectIdentifier i = (ASN1ObjectIdentifier)primitive;
	    	
	    	String kn = CipherSuiteUtil.getOIDKeyName(i.toString());
	    	
	    	if ( kn.equals("2.5.29.37.0") ) {
	    		buff.append( "anyextendedkeyusage ");
	    		
	    	} else if (kn.equals("1.3.6.1.5.5.7.3.1") ) {
	    		buff.append( "serverauth ");
	    		
	    	} else if (kn.equals("1.3.6.1.5.5.7.3.2") ) {
	    		buff.append( "clientauth ");
	    		
	    	} else if (kn.equals("1.3.6.1.5.5.7.3.3") ) {
	    		buff.append( "codesigning ");
	    		
	    	} else if (kn.equals("1.3.6.1.5.5.7.3.4") ) {
	    		buff.append( "emailprotection ");
	    		
	    	} else if (kn.equals("1.3.6.1.5.5.7.3.8") ) {
	    		buff.append( "timestamping ");
	    		
	    	} else if (kn.equals("1.3.6.1.5.5.7.3.9") ) {
	    		buff.append( "ocspsigner ");
	    		
	    	} else if (kn.equals("1.3.6.1.5.5.7.3.15") ) {
	    		buff.append( "scvpserver ");
	    		
	    	} else if (kn.equals("1.3.6.1.5.5.7.3.16") ) {
	    		buff.append( "scvpclient ");
	    		
	    	} else if (kn.equals("1.3.6.1.5.5.7.3.22") ) {
	    		buff.append( "eku_pkix_sshserver ");
	    		
	    	} else if (kn.equals("1.3.6.1.5.5.7.3.21") ) {
	    		buff.append( "eku_pkix_sshclient ");
	   
	    	} else {	
	    		    	
	    		buff.append( CipherSuiteUtil.getOIDKeyName(i.toString()) );
	    		buff.append( "=" );
	    	
	    	}
	    	
	    } else if (primitive instanceof DERVisibleString ) {
	    	
	    	DERVisibleString vstring = (DERVisibleString)primitive;
	    	buff.append( vstring.getString() );
	    	buff.append( ' ' );
	    	
	    } else if (primitive instanceof DERIA5String ) {
	    	
	    	DERIA5String ia5string = (DERIA5String)primitive;
	    	buff.append( ia5string.getString() );
	    	buff.append( ' ' );
	    	
	    } else if (primitive instanceof DERUTF8String ) {
	    	
	    	DERUTF8String utf8string = (DERUTF8String)primitive;
	    	buff.append( utf8string.toString() );
	    	buff.append( ' ' );
	    	
	    } else if (primitive instanceof DERBitString ) {
	    	
	    	DERBitString bitstring = (DERBitString)primitive;
	    	int v = bitstring.intValue();
	    
		    	
		    if( (v & 0x1) == 1 )
		    	buff.append( "digitial_signature ");
		    
		    if ((v & 0x2) == 2)
		    	buff.append( "nonrepudiation ");
		    	
		    if ((v & 0x4) == 4)
		    	buff.append( "keyencipherment ");

			if ((v & 0x8) == 8)
		    	buff.append( "dataencipherment ");

			if ((v & 0x16) == 16)
		    	buff.append( "keyagreement ");

			if ((v & 0x32) == 32)
		    	buff.append( "keycertsign ");
		    	
			if ((v & 0x64) == 64)
		    	buff.append( "crlsign ");
		    	
			if ((v & 0x128) == 128)
		    	buff.append( "encipheronly ");
		    		
			if ((v & 0x256) == 256)
		    	buff.append( "decipherOnly ");

	    	
	    } else if (primitive instanceof ASN1Boolean ) {
	    	
	    	ASN1Boolean ans1boolean = (ASN1Boolean)primitive;
	    	buff.append( ans1boolean.isTrue() ? "TRUE" : "FALSE" );
	    	buff.append( ' ' );
	    	
	    } else if (primitive instanceof ASN1Integer ) {
	    	
	    	ASN1Integer ans1int = (ASN1Integer)primitive;
	    	buff.append( ans1int.toString() );
	    	buff.append( ' ' );
	    	
	    } else if (primitive instanceof DERSet ) {
	    	
	    	DERSet derset = (DERSet)primitive;
	    	buff.append( derset.toString() );
	    	buff.append( ' ' );
	    	
	    // Assistance fm http://stackoverflow.com/questions/16058889/java-bouncy-castle-ocsp-url
	    } else if (primitive instanceof DERTaggedObject ) {
	    	
	    	DERTaggedObject t = (DERTaggedObject)primitive;
	    	byte[] b = t.getEncoded();
            int length = b[1];
	    	
	    	if( t.getTagNo() == 6 ) { // Several
	            buff.append( new String(b, 2, length) );
	            buff.append( " | ");
	    	} else if( t.getTagNo() == 2 ) { // SubjectAlternativeName
		        buff.append( new String(b, 2, length) );
		        buff.append( " | ");
	    	} else if( t.getTagNo() == 1 ) { // NameContraints
	    		ASN1Primitive p = t.getObject();
	    		walkASN1Sequence( p, buff ); 
	    	} else if( t.getTagNo() == 0 ) { // CRLDistributionPoints	
	    		ASN1Primitive p = t.getObject();
	    		walkASN1Sequence( p, buff ); 
	    	} else if( t.getTagNo() == 4 ) { // AuthorityKeyIdentifier	
	    		ASN1Primitive p = t.getObject();
	    		walkASN1Sequence( p, buff ); 
	    	} else {
	    		
	    		StringBuffer buff2 = new StringBuffer();
	    		
	    		buff2.append( "type="+t.getTagNo()+" ");
	            String hex = CipherSuiteUtil.byteArrayToHex(b);
	            buff2.append( " 0x"+hex );
	            buff2.append( " | ");
	    		
	            buff.append(buff2.toString());
	            
	    		logger.info("Unhandled DERTaggedObject type. RAW="+buff2.toString() );
	    	}
	    	
	    } else {
	    	
            buff.append( "Unhandled type, see log" );
            buff.append( " | ");
	    	
    		logger.error("Unhandled primitive data type, type="+primitive.getClass().getName() );
	    	
	    }
	    
	}

	/**
	 * Return the value of the OID associated with the X509Certificate.
	 * @param X509Certificate Certificate to test.
	 * @param oid OID to retrieve.
	 * @return String String value for the specified OID parameter. 
	 * @throws IOException
	 * @see http://stackoverflow.com/questions/2409618/how-do-i-decode-a-der-encoded-string-in-java
	 */
	public static final String getExtensionValue(X509Certificate X509Certificate, String oid) throws IOException {
		
		StringBuffer buff = new StringBuffer();
		
		buff.append('[');
		
	    byte[] extensionValue = X509Certificate.getExtensionValue(oid);
	
	    if (extensionValue == null)  return null;
	    	
	    walkASN1Sequence( toDERObject(extensionValue), buff);
	    
	    if( buff.toString().endsWith("| "))
	    	buff.setLength(buff.length()-2);
		
	    buff.append(']');
	
	    return buff.toString();
	
	}
	   

	//************************************************************
	//************************************************************
	//************************************************************
	//************************************************************
	//************************************************************
	
	/*
	 * Connect to the server, send a ClientHello, and decode the
	 * response (ServerHello). On error, null is returned.
	 */
	static ServerHello connect(InetSocketAddress isa,
		int version, Collection<Integer> cipherSuites)
	{
		Socket s = null;
		try {
			s = new Socket();
			try {
				s.connect(isa);
			} catch (IOException ioe) {
				System.err.println("could not connect to "
					+ isa + ": " + ioe.toString());
				return null;
			}
			byte[] ch = makeClientHello(version, cipherSuites);
			OutputRecord orec = new OutputRecord(
				s.getOutputStream());
			orec.setType(HANDSHAKE);
			orec.setVersion(version);
			orec.write(ch);
			orec.flush();
			return new ServerHello(s.getInputStream());
		} catch (IOException ioe) {
			// ignored
		} finally {
			try {
				s.close();
			} catch (IOException ioe) {
				// ignored
			}
		}
		return null;
	}
	
	/*
	 * Connect to the server, send a SSLv2 CLIENT HELLO, and decode
	 * the response (SERVER HELLO). On error, null is returned.
	 */
	static ServerHelloSSLv2 connectV2(InetSocketAddress isa)
	{
		Socket s = null;
		try {
			s = new Socket();
			try {
				s.connect(isa);
			} catch (IOException ioe) {
				System.err.println("could not connect to "
					+ isa + ": " + ioe.toString());
				return null;
			}
			s.getOutputStream().write(SSL2_CLIENT_HELLO);
			return new ServerHelloSSLv2(s.getInputStream());
		} catch (IOException ioe) {
			// ignored
		} finally {
			try {
				s.close();
			} catch (IOException ioe) {
				// ignored
			}
		}
		return null;
	}
	
	static void readFully(InputStream in, byte[] buf)
			throws IOException
		{
			readFully(in, buf, 0, buf.length);
		}

	static void readFully(InputStream in, byte[] buf, int off, int len)
		throws IOException
	{
		while (len > 0) {
			int rlen = in.read(buf, off, len);
			if (rlen < 0) {
				throw new EOFException();
			}
			off += rlen;
			len -= rlen;
		}
	}
	
	/*
	 * A custom stream which encodes data bytes into SSL/TLS records
	 * (no encryption).
	 */
	static class OutputRecord extends OutputStream {

		private OutputStream out;
		private byte[] buffer = new byte[MAX_RECORD_LEN + 5];
		private int ptr;
		private int version;
		private int type;

		OutputRecord(OutputStream out)
		{
			this.out = out;
			ptr = 5;
		}

		void setType(int type)
		{
			this.type = type;
		}

		void setVersion(int version)
		{
			this.version = version;
		}

		public void flush()
			throws IOException
		{
			buffer[0] = (byte)type;
			enc16be(version, buffer, 1);
			enc16be(ptr - 5, buffer, 3);
			out.write(buffer, 0, ptr);
			out.flush();
			ptr = 5;
		}

		public void write(int b)
			throws IOException
		{
			buffer[ptr ++] = (byte)b;
			if (ptr == buffer.length) {
				flush();
			}
		}

		public void write(byte[] buf, int off, int len)
			throws IOException
		{
			while (len > 0) {
				int clen = Math.min(buffer.length - ptr, len);
				System.arraycopy(buf, off, buffer, ptr, clen);
				ptr += clen;
				off += clen;
				len -= clen;
				if (ptr == buffer.length) {
					flush();
				}
			}
		}
	}

	
	static final void enc16be(int val, byte[] buf, int off)
	{
		buf[off] = (byte)(val >>> 8);
		buf[off + 1] = (byte)val;
	}

	static final void enc24be(int val, byte[] buf, int off)
	{
		buf[off] = (byte)(val >>> 16);
		buf[off + 1] = (byte)(val >>> 8);
		buf[off + 2] = (byte)val;
	}

	static final void enc32be(int val, byte[] buf, int off)
	{
		buf[off] = (byte)(val >>> 24);
		buf[off + 1] = (byte)(val >>> 16);
		buf[off + 2] = (byte)(val >>> 8);
		buf[off + 3] = (byte)val;
	}

	static final int dec16be(byte[] buf, int off)
	{
		return ((buf[off] & 0xFF) << 8)
			| (buf[off + 1] & 0xFF);
	}

	static final int dec24be(byte[] buf, int off)
	{
		return ((buf[off] & 0xFF) << 16)
			| ((buf[off + 1] & 0xFF) << 8)
			| (buf[off + 2] & 0xFF);
	}

	static final int dec32be(byte[] buf, int off)
	{
		return ((buf[off] & 0xFF) << 24)
			| ((buf[off + 1] & 0xFF) << 16)
			| ((buf[off + 2] & 0xFF) << 8)
			| (buf[off + 3] & 0xFF);
	}

	/*
	 * Compute the SHA-1 hash of some bytes, returning the hash
	 * value in hexadecimal.
	 */
	static String doSHA1(byte[] buf)
	{
		return doSHA1(buf, 0, buf.length);
	}

	static String doSHA1(byte[] buf, int off, int len)
	{
		try {
			MessageDigest md = MessageDigest.getInstance("SHA1");
			md.update(buf, off, len);
			byte[] hv = md.digest();
			Formatter f = new Formatter();
			for (byte b : hv) {
				f.format("%02x", b & 0xFF);
			}
			return f.toString();
		} catch (NoSuchAlgorithmException nsae) {
			throw new Error(nsae);
		}
	}
	
	/*
	 * Build a ClientHello message, with the specified maximum
	 * supported version, and list of cipher suites.
	 */
	static byte[] makeClientHello(int version,
		Collection<Integer> cipherSuites)
	{
		try {
			return makeClientHello0(version, cipherSuites);
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	static byte[] makeClientHello0(int version,
		Collection<Integer> cipherSuites)
		throws IOException
	{
		ByteArrayOutputStream b = new ByteArrayOutputStream();

		/*
		 * Message header:
		 *   message type: one byte (1 = "ClientHello")
		 *   message length: three bytes (this will be adjusted
		 *   at the end of this method).
		 */
		b.write(1);
		b.write(0);
		b.write(0);
		b.write(0);

		/*
		 * The maximum version that we intend to support.
		 */
		b.write(version >>> 8);
		b.write(version);

		/*
		 * The client random has length 32 bytes, but begins with
		 * the client's notion of the current time, over 32 bits
		 * (seconds since 1970/01/01 00:00:00 UTC, not counting
		 * leap seconds).
		 */
		byte[] rand = new byte[32];
		RNG.nextBytes(rand);
		enc32be((int)(System.currentTimeMillis() / 1000), rand, 0);
		b.write(rand);

		/*
		 * We send an empty session ID.
		 */
		b.write(0);

		/*
		 * The list of cipher suites (list of 16-bit values; the
		 * list length in bytes is written first).
		 */
		int num = cipherSuites.size();
		byte[] cs = new byte[2 + num * 2];
		enc16be(num * 2, cs, 0);
		int j = 2;
		for (int s : cipherSuites) {
			enc16be(s, cs, j);
			j += 2;
		}
		b.write(cs);

		/*
		 * Compression methods: we claim to support Deflate (1)
		 * and the standard no-compression (0), with Deflate
		 * being preferred.
		 */
		b.write(2);
		b.write(1);
		b.write(0);

		/*
		 * If we had extensions to add, they would go here.
		 */

		/*
		 * We now get the message as a blob. The message length
		 * must be adjusted in the header.
		 */
		byte[] msg = b.toByteArray();
		enc24be(msg.length - 4, msg, 1);
		return msg;
	}
	
	static final String strengthString(int strength)
	{
		switch (strength) {
		case CLEAR:  return "no encryption";
		case WEAK:   return "weak encryption (40-bit)";
		case MEDIUM: return "medium encryption (56-bit)";
		case STRONG: return "strong encryption (96-bit or more)";
		default:
			throw new Error("strange strength: " + strength);
		}
	}

	static final String cipherSuiteString(int suite)
	{
		CipherSuite cs = CIPHER_SUITES.get(suite);
		if (cs == null) {
			return String.format("UNKNOWN_SUITE:0x%04X", cs);
		} else {
			return cs.name;
		}
	}

	static final String cipherSuiteStringV2(int suite)
	{
		CipherSuite cs = CIPHER_SUITES.get(suite);
		if (cs == null) {
			return String.format("UNKNOWN_SUITE:%02X,%02X,%02X",
				suite >> 16, (suite >> 8) & 0xFF, suite & 0XFF);
		} else {
			return cs.name ;
		}
	}

	private static final void makeCS(int suite, String name,
		boolean isCBC, int strength)
	{
		CipherSuite cs = new CipherSuite();
		cs.suite = suite;
		cs.name = name;
		cs.isCBC = isCBC;
		cs.strength = strength;
		CIPHER_SUITES.put(suite, cs);

		/*
		 * Consistency test: the strength and CBC status can normally
		 * be inferred from the name itself.
		 */
		boolean inferredCBC = name.contains("_CBC_");
		int inferredStrength;
		if (name.contains("_NULL_")) {
			inferredStrength = CLEAR;
		} else if (name.contains("DES40") || name.contains("_40_")
			|| name.contains("EXPORT40"))
		{
			inferredStrength = WEAK;
		} else if ((name.contains("_DES_") || name.contains("DES_64"))
			&& !name.contains("DES_192"))
		{
			inferredStrength = MEDIUM;
		} else {
			inferredStrength = STRONG;
		}
		if (inferredStrength != strength || inferredCBC != isCBC) {
			throw new RuntimeException(
				"wrong classification: " + name);
		}
	}

	private static final void N(int suite, String name)
	{
		makeCS(suite, name, false, CLEAR);
	}

	private static final void S4(int suite, String name)
	{
		makeCS(suite, name, false, WEAK);
	}

	private static final void S8(int suite, String name)
	{
		makeCS(suite, name, false, STRONG);
	}

	private static final void B4(int suite, String name)
	{
		makeCS(suite, name, true, WEAK);
	}

	private static final void B5(int suite, String name)
	{
		makeCS(suite, name, true, MEDIUM);
	}

	private static final void B8(int suite, String name)
	{
		makeCS(suite, name, true, STRONG);
	}
	
	static boolean testBEAST(InetSocketAddress isa,
			int version, Set<Integer> supp)
		{
			/*
			 * TLS 1.1+ is not vulnerable to BEAST.
			 * We do not test SSLv2 either.
			 */
			if (version < 0x0300 || version > 0x0301) {
				return false;
			}

			/*
			 * BEAST attack works if the server allows the client to
			 * use a CBC cipher. Existing clients also supports RC4,
			 * so we consider that a server protects the clients if
			 * it chooses RC4 over CBC streams when given the choice.
			 * We only consider strong cipher suites here.
			 */
			List<Integer> strongCBC = new ArrayList<Integer>();
			List<Integer> strongStream = new ArrayList<Integer>();
			for (int suite : supp) {
				CipherSuite cs = CIPHER_SUITES.get(suite);
				if (cs == null) {
					continue;
				}
				if (cs.strength < STRONG) {
					continue;
				}
				if (cs.isCBC) {
					strongCBC.add(suite);
				} else {
					strongStream.add(suite);
				}
			}
			if (strongCBC.size() == 0) {
				return false;
			}
			if (strongStream.size() == 0) {
				return true;
			}
			List<Integer> ns = new ArrayList<Integer>(strongCBC);
			ns.addAll(strongStream);
			ServerHello sh = connect(isa, version, ns);
			return !strongStream.contains(sh.cipherSuite);
		}

	
}


