package com.pifa.storage;

import com.pifa.config.PifaProperties;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link VirusScanner} that streams the file to a ClamAV daemon using clamd's INSTREAM protocol,
 * <b>always over TLS</b>. Active only when {@code pifa.clamav.enabled=true}.
 *
 * <p>clamd itself speaks plain TCP, so the file content would be readable by anyone able to watch the
 * network. The daemon therefore runs behind a TLS terminator (a stunnel/socat/sidecar in front of it;
 * see {@code infra/docker-compose.yml}) and this client only ever opens an {@link SSLSocket}. There is
 * deliberately no plain-text mode.
 *
 * <p>Trust: the server certificate is verified against the CA certificate in {@code pifa.clamav.ca-cert}
 * (a PEM file) when set, otherwise against the JVM's default trust store, and the host name is verified
 * against the certificate (endpoint identification).
 *
 * <p>Protocol: send {@code zINSTREAM\0}, then chunks as (4-byte big-endian length, bytes), then a zero
 * length to finish; clamd answers e.g. {@code stream: OK} or {@code stream: Eicar-Test-Signature FOUND}.
 */
@Component
@ConditionalOnProperty(name = "pifa.clamav.enabled", havingValue = "true")
public class ClamAvScanner implements VirusScanner {

    private static final int CHUNK = 8192;
    private final String host;
    private final int port;
    private final SSLSocketFactory sockets;

    /**
     * Prepares the TLS socket factory.
     *
     * @param props supplies the clamd host, port and the optional CA certificate path
     * @throws IllegalStateException if the CA certificate file cannot be read or parsed
     */
    public ClamAvScanner(PifaProperties props) {
        this.host = props.clamav().host();
        this.port = props.clamav().port();
        this.sockets = socketFactory(props.clamav().caCert());
    }

    /**
     * Builds the TLS socket factory.
     *
     * @param caCertPath path of a PEM file with the CA (or self-signed server) certificate to trust, or
     *                   null/blank to use the JVM's default trust store
     * @return a factory whose sockets verify the server certificate
     * @throws IllegalStateException if the PEM file is unreadable or invalid
     */
    static SSLSocketFactory socketFactory(String caCertPath) {
        try {
            if (caCertPath == null || caCertPath.isBlank()) {
                return (SSLSocketFactory) SSLSocketFactory.getDefault();
            }
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null); // empty in-memory store; only the configured certificate(s) are trusted
            try (InputStream in = Files.newInputStream(Path.of(caCertPath))) {
                Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
                int i = 0;
                for (Certificate c : certs) trust.setCertificateEntry("clamav-ca-" + i++, c);
                if (i == 0) throw new IllegalStateException("No certificate found in " + caCertPath);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            // TLS 1.3 context: enables TLS 1.3 and 1.2 only; the SSL and TLS 1.0/1.1 versions are not offered.
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx.getSocketFactory();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load ClamAV CA certificate " + caCertPath + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isClean(byte[] data) {
        try (SSLSocket socket = (SSLSocket) sockets.createSocket()) {
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setSoTimeout(60_000);
            // Without this the JDK checks the certificate chain but NOT that it was issued for this host.
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            // Also applies when the JVM default socket factory is used: never negotiate anything older than TLS 1.2.
            params.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            socket.setSSLParameters(params);
            socket.startHandshake();

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int off = 0; off < data.length; off += CHUNK) {
                int len = Math.min(CHUNK, data.length - off);
                out.writeInt(len);          // DataOutputStream writes big-endian
                out.write(data, off, len);
            }
            out.writeInt(0);                // end of stream
            out.flush();
            String reply = readReply(socket.getInputStream());
            if (reply.endsWith("OK")) return true;
            if (reply.contains("FOUND")) return false;
            throw new IllegalStateException("Unexpected ClamAV reply: " + reply);
        } catch (IOException e) {
            throw new IllegalStateException("ClamAV scan failed: " + e.getMessage(), e);
        }
    }

    /** Reads clamd's reply up to its terminating NUL byte (or end of stream) and trims it. */
    private static String readReply(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Reader r = new InputStreamReader(in, StandardCharsets.US_ASCII)) {
            int b;
            while ((b = r.read()) != -1 && b != 0) sb.append((char) b);
        }
        return sb.toString().trim();
    }
}
