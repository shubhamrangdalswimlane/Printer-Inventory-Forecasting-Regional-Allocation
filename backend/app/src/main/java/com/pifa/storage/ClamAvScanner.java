package com.pifa.storage;

import com.pifa.config.PifaProperties;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link VirusScanner} that streams the file to a ClamAV daemon using clamd's INSTREAM protocol.
 * Active only when {@code pifa.clamav.enabled=true}.
 *
 * <p>Protocol: send {@code zINSTREAM\0}, then chunks as (4-byte big-endian length, bytes), then a
 * zero length to finish; clamd answers e.g. {@code stream: OK} or {@code stream: Eicar-Test-Signature FOUND}.
 */
@Component
@ConditionalOnProperty(name = "pifa.clamav.enabled", havingValue = "true")
public class ClamAvScanner implements VirusScanner {

    private static final int CHUNK = 8192;
    private final String host;
    private final int port;

    /** @param props supplies the clamd host and port */
    public ClamAvScanner(PifaProperties props) {
        this.host = props.clamav().host();
        this.port = props.clamav().port();
    }

    @Override
    public boolean isClean(byte[] data) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setSoTimeout(60_000);
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
        int b;
        while ((b = in.read()) != -1 && b != 0) sb.append((char) b);
        return sb.toString().trim();
    }
}
