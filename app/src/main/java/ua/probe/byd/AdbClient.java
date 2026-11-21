package ua.probe.byd;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;

/**
 * Minimal ADB client (legacy TCP transport with RSA auth), enough to run shell commands
 * on the car's own adbd at 127.0.0.1. No third-party libraries.
 */
public class AdbClient implements Closeable {

    public interface Lines { void line(String s); }

    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;

    static final int A_VERSION = 0x01000001;
    static final int MAX_PAYLOAD = 256 * 1024;

    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    /** PKCS#1 v1.5 type-1 padding + SHA-1 DigestInfo prefix for a 2048-bit key (236 bytes). */
    static final byte[] SIGNATURE_PADDING;
    static {
        byte[] digestInfo = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        int padLen = 256 - 20;
        SIGNATURE_PADDING = new byte[padLen];
        SIGNATURE_PADDING[0] = 0x00;
        SIGNATURE_PADDING[1] = 0x01;
        int ffEnd = padLen - digestInfo.length - 1;
        for (int i = 2; i < ffEnd; i++) SIGNATURE_PADDING[i] = (byte) 0xff;
        SIGNATURE_PADDING[ffEnd] = 0x00;
        System.arraycopy(digestInfo, 0, SIGNATURE_PADDING, ffEnd + 1, digestInfo.length);
    }

    static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private AdbClient(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    // ---------- keys ----------

    public static KeyPair loadOrCreateKeys(File dir) throws Exception {
        File priv = new File(dir, "adbkey");
        File pub = new File(dir, "adbkey.pub.der");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv)));
            PublicKey pu = kf.generatePublic(new X509EncodedKeySpec(readAll(pub)));
            return new KeyPair(pu, pk);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        writeAll(priv, kp.getPrivate().getEncoded());
        writeAll(pub, kp.getPublic().getEncoded());
        return kp;
    }

    /** Public key in the format adbd expects (Android mincrypt RSAPublicKey struct, base64). */
    public static byte[] adbPublicKey(RSAPublicKey pub, String name) {
        BigInteger n = pub.getModulus();
        int words = 2048 / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(pub.getPublicExponent().intValue());

        String s = Base64.getEncoder().encodeToString(b.array()) + " " + name + "\u0000";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);
        c.update(SIGNATURE_PADDING);
        return c.doFinal(token);
    }

    // ---------- connection ----------

    /**
     * Connects and authenticates. If the key is new, adbd shows the
     * "Allow USB debugging?" dialog; we wait up to authWaitMs for the user.
     */
    public static AdbClient connect(String host, int port, KeyPair kp, int authWaitMs, Lines log)
            throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 3000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(authWaitMs);
        AdbClient c = new AdbClient(s);
        try {
            c.send(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".getBytes(StandardCharsets.UTF_8));
            boolean sentSig = false, sentKey = false;
            while (true) {
                Msg m = c.read();
                if (m.cmd == A_CNXN) {
                    log.line("ADB connected: " + new String(m.data, StandardCharsets.UTF_8).trim());
                    s.setSoTimeout(0);
                    return c;
                }
                if (m.cmd == A_AUTH && m.arg0 == AUTH_TOKEN) {
                    if (!sentSig) {
                        c.send(A_AUTH, AUTH_SIGNATURE, 0, sign(kp.getPrivate(), m.data));
                        sentSig = true;
                    } else if (!sentKey) {
                        c.send(A_AUTH, AUTH_RSAPUBLICKEY, 0,
                                adbPublicKey((RSAPublicKey) kp.getPublic(), "bydprobe@car"));
                        sentKey = true;
                        log.line(">>> Confirm \"Allow USB debugging?\" on the screen, tick \"Always allow\" <<<");
                        log.line(">>> Підтвердіть запит налагодження на екрані, поставте \"Завжди дозволяти\" <<<");
                    } else {
                        throw new IOException("ADB auth rejected");
                    }
                }
            }
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    /** Runs a shell command, streams output line by line, returns when the command ends. */
    public void shell(String cmd, int timeoutMs, Lines lines) throws IOException {
        int localId = nextLocalId++;
        int remoteId = 0;
        socket.setSoTimeout(timeoutMs);
        send(A_OPEN, localId, 0, ("shell:" + cmd + "\u0000").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        try {
            while (true) {
                Msg m = read();
                if (m.arg1 != localId) continue;
                if (m.cmd == A_OKAY) {
                    remoteId = m.arg0;
                } else if (m.cmd == A_WRTE) {
                    remoteId = m.arg0;
                    send(A_OKAY, localId, remoteId, new byte[0]);
                    for (byte bt : m.data) {
                        if (bt == '\n') {
                            lines.line(pending.toString("UTF-8").replace("\r", ""));
                            pending.reset();
                        } else {
                            pending.write(bt);
                        }
                    }
                } else if (m.cmd == A_CLSE) {
                    send(A_CLSE, localId, m.arg0, new byte[0]);
                    break;
                }
            }
        } finally {
            if (pending.size() > 0) lines.line(pending.toString("UTF-8"));
            socket.setSoTimeout(0);
        }
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---------- wire format ----------

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += (b & 0xff);
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        b.put(data);
        out.write(b.array());
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("bad ADB packet length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream i = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
    }
}
