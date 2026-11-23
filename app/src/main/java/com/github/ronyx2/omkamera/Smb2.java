package com.github.ronyx2.omkamera;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal SMB 2.0.2 / 2.1 / 3.0 / 3.0.2 / 3.1.1 client for writing files to a share (any NAS, Samba,
 * Windows): NTLMv2 logon wrapped in SPNEGO, message signing when the server requires it
 * (HMAC-SHA256 for SMB 2, AES-CMAC for SMB 3),
 * creating folders and writing files. Pure Java without Android classes, so it also runs on
 * Android 2.3 and can be tested on a PC; small enough for the camera's 24 MB heap.
 */
public final class Smb2 {
    private static final int NEGOTIATE = 0, SESSION_SETUP = 1, LOGOFF = 2, TREE_CONNECT = 3, TREE_DISCONNECT = 4,
            CREATE = 5, CLOSE = 6, WRITE = 9;
    private static final int STATUS_OK = 0, STATUS_PENDING = 0x00000103, STATUS_MORE_PROCESSING = 0xC0000016,
            STATUS_COLLISION = 0xC0000035;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private long messageId;
    private long sessionId;
    private int treeId;
    private int dialect;
    private boolean signing;
    private byte[] signingKey;
    private int maxWrite = 65536;
    private String server;
    private String madeDir = null;
    private byte[] lastSent;
    /** SMB 3.1.1 preauth integrity hash over negotiate and session setup, null for older dialects */
    private byte[] preauth;

    private void preauth(byte[] msg) throws IOException {
        if (preauth == null || msg == null) {
            return;
        }
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-512");
            sha.update(preauth);
            sha.update(msg);
            preauth = sha.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-512 nicht verfügbar");
        }
    }

    /** Connects, logs on and opens the share; user may be "DOMAIN\\user" */
    public void open(String host, int port, String share, String user, String pass) throws IOException {
        server = host;
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port > 0 ? port : 445), 8000);
        socket.setSoTimeout(30000);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        negotiate();
        logon(user, pass);
        treeConnect(share);
    }

    public void close() {
        try {
            if (treeId != 0) {
                call(TREE_DISCONNECT, le16(4, 0), false);
            }
            if (sessionId != 0) {
                call(LOGOFF, le16(4, 0), false);
            }
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
    }

    public String dialectName() {
        return dialect == 0x0311 ? "SMB 3.1.1" : dialect == 0x0302 ? "SMB 3.0.2" : dialect == 0x0300 ? "SMB 3.0" : dialect == 0x0210 ? "SMB 2.1"
                : dialect == 0x0202 ? "SMB 2.0" : "?";
    }

    public boolean signing() {
        return signing;
    }

    // ---- folders and files ----

    /** Creates the folder and all missing parents ("a/b/c" relative to the share) */
    public void makeDirs(String path) throws IOException {
        String p = clean(path);
        if (p.length() == 0 || p.equals(madeDir)) {
            return;
        }
        String cur = "";
        for (String part : p.split("\\\\")) {
            if (part.length() == 0) {
                continue;
            }
            cur = cur.length() == 0 ? part : cur + "\\" + part;
            byte[] id = create(cur, 0x00100080, 0x10, 7, 3 /* FILE_OPEN_IF */, 0x1 /* directory */);
            closeHandle(id);
        }
        madeDir = p;
    }

    /** Writes the file into the folder (relative to the share), replacing an existing one */
    public void store(File file, String dir, String name) throws IOException {
        String d = clean(dir);
        makeDirs(d);
        String path = d.length() == 0 ? name : d + "\\" + name;
        byte[] id = create(path, 0x00120116, 0x80, 1, 5 /* FILE_OVERWRITE_IF */, 0x40 /* non-directory */);
        InputStream fin = new FileInputStream(file);
        try {
            writeAll(id, fin);
        } catch (IOException e) {
            try {
                fin.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        fin.close();
        closeHandle(id);
    }

    /** Writes with up to 8 requests on the way, to keep the WLAN busy */
    private void writeAll(byte[] fileId, InputStream fin) throws IOException {
        int chunk = Math.min(maxWrite, 65536);
        long offset = 0;
        java.util.ArrayDeque<Long> open = new java.util.ArrayDeque<Long>();
        byte[] buf = new byte[chunk];
        boolean eof = false;
        while (!eof || !open.isEmpty()) {
            while (!eof && open.size() < 8) {
                int n = readFully(fin, buf);
                if (n <= 0) {
                    eof = true;
                    break;
                }
                ByteArrayOutputStream b = new ByteArrayOutputStream(48 + n);
                b.write(le16(49, 0x70)); // StructureSize, DataOffset 64 + 48
                b.write(le32(n));
                b.write(le64(offset));
                b.write(fileId);
                b.write(new byte[16]); // Channel, RemainingBytes, WriteChannelInfo, Flags
                b.write(buf, 0, n);
                open.add(send(WRITE, b.toByteArray()));
                offset += n;
                if (n < chunk) {
                    eof = true;
                }
            }
            if (!open.isEmpty()) {
                receive(open.poll(), "Schreiben");
            }
        }
    }

    private static int readFully(InputStream inp, byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int r = inp.read(buf, n, buf.length - n);
            if (r < 0) {
                break;
            }
            n += r;
        }
        return n;
    }

    private byte[] create(String path, int access, int attributes, int shareAccess, int disposition, int options)
            throws IOException {
        byte[] name = utf16(path);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(le16(57, 0)); // StructureSize, SecurityFlags + OplockLevel
        b.write(le32(2)); // ImpersonationLevel: impersonation
        b.write(new byte[16]); // SmbCreateFlags, Reserved
        b.write(le32(access));
        b.write(le32(attributes));
        b.write(le32(shareAccess));
        b.write(le32(disposition));
        b.write(le32(options));
        b.write(le16(64 + 56, name.length));
        b.write(new byte[8]); // CreateContexts
        b.write(name.length > 0 ? name : new byte[1]);
        byte[] r = call(CREATE, b.toByteArray(), true);
        byte[] id = new byte[16];
        System.arraycopy(r, 64 + 64, id, 0, 16);
        return id;
    }

    private void closeHandle(byte[] id) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(le16(24, 0));
        b.write(new byte[4]);
        b.write(id);
        call(CLOSE, b.toByteArray(), true);
    }

    private static String clean(String p) {
        String s = p == null ? "" : p.replace('/', '\\');
        while (s.startsWith("\\")) s = s.substring(1);
        while (s.endsWith("\\")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** "\\\\nas\\photo\\a\\b", "/photo/a/b" or "photo/a/b" → {"photo", "a\\b"} */
    public static String[] splitShare(String target) {
        String raw = target == null ? "" : target.trim().replace('/', '\\');
        String s = clean(raw);
        if (raw.startsWith("\\\\") && s.indexOf('\\') > 0) {
            s = s.substring(s.indexOf('\\') + 1); // UNC path: drop the server name
        }
        int i = s.indexOf('\\');
        return i < 0 ? new String[]{s, ""} : new String[]{s.substring(0, i), s.substring(i + 1)};
    }

    // ---- negotiate, logon, tree ----

    private void negotiate() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(le16(36, 5)); // StructureSize, DialectCount
        b.write(le16(1, 0)); // SecurityMode: signing enabled
        b.write(le32(0)); // Capabilities
        byte[] guid = new byte[16];
        new SecureRandom().nextBytes(guid);
        b.write(guid);
        // SMB 3.1.1: NegotiateContextOffset/Count instead of ClientStartTime
        b.write(le32(112));
        b.write(le16(1, 0));
        b.write(le16(0x0202, 0x0210));
        b.write(le16(0x0300, 0x0302));
        b.write(le16(0x0311, 0)); // last dialect + 2 bytes padding to offset 112
        // Preauth integrity capabilities: SHA-512 with a 32-byte salt
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        b.write(le16(1, 38)); // ContextType, DataLength
        b.write(new byte[4]);
        b.write(le16(1, 32)); // HashAlgorithmCount, SaltLength
        b.write(le16(1, 0)); // SHA-512 (the last two bytes are cut off below)
        byte[] body = b.toByteArray();
        body = java.util.Arrays.copyOf(body, body.length - 2);
        ByteArrayOutputStream bb = new ByteArrayOutputStream();
        bb.write(body);
        bb.write(salt);
        byte[] r;
        try {
            r = call(NEGOTIATE, bb.toByteArray(), true);
        } catch (IOException e) {
            throw new IOException("Server nimmt weder SMB 2 noch SMB 3.0 an – am Server das Mindestprotokoll "
                    + "auf SMB2 oder SMB3 stellen, nicht nur SMB 3.1.1 (" + e.getMessage() + ")");
        }
        int securityMode = u16(r, 64 + 2);
        dialect = u16(r, 64 + 4);
        maxWrite = (int) Math.min(65536, u32(r, 64 + 36));
        signing = (securityMode & 0x2) != 0;
        if (dialect == 0x0311) {
            preauth = new byte[64];
            preauth(lastSent);
            preauth(r);
        }
        if (dialect != 0x0202 && dialect != 0x0210 && dialect != 0x0300 && dialect != 0x0302 && dialect != 0x0311) {
            throw new IOException("Server antwortet mit unbekanntem SMB-Dialekt 0x" + Integer.toHexString(dialect));
        }
    }

    private void logon(String user, String pass) throws IOException {
        String domain = "";
        String u = user == null ? "" : user.trim();
        int bs = u.indexOf('\\');
        if (bs > 0) {
            domain = u.substring(0, bs);
            u = u.substring(bs + 1);
        }
        int at = u.indexOf('@');
        if (bs < 0 && at > 0) {
            domain = u.substring(at + 1);
            u = u.substring(0, at);
        }
        int flags = 0x00000001 | 0x00000004 | 0x00000200 | 0x00008000 | 0x00080000 | 0x00800000 | 0x20000000;
        byte[] neg = ntlmNegotiate(flags);
        long id = send(SESSION_SETUP, sessionSetup(spnegoInit(neg)));
        preauth(lastSent);
        byte[] r = receiveRaw(id);
        preauth(r);
        int status = (int) u32(r, 8);
        if (status != STATUS_MORE_PROCESSING) {
            throw new IOException("Anmeldung nicht möglich (" + statusText(status) + ")");
        }
        sessionId = u64(r, 40);
        byte[] blob = securityBuffer(r);
        int c = indexOf(blob, "NTLMSSP\0".getBytes("US-ASCII"));
        if (c < 0) {
            throw new IOException("Server schickt keine NTLM-Abfrage");
        }
        byte[] challenge = new byte[blob.length - c];
        System.arraycopy(blob, c, challenge, 0, challenge.length);
        byte[][] auth = ntlmAuthenticate(challenge, flags, domain, u, pass == null ? "" : pass);
        id = send(SESSION_SETUP, sessionSetup(spnegoResponse(auth[0])));
        preauth(lastSent);
        r = receiveRaw(id);
        status = (int) u32(r, 8);
        if (status != STATUS_OK) {
            throw new IOException(status == 0xC000006D ? "Benutzer oder Passwort falsch" : "Anmeldung abgelehnt (" + statusText(status) + ")");
        }
        int sessionFlags = u16(r, 64 + 2);
        if ((sessionFlags & 0x3) != 0) {
            signing = false; // guest or anonymous: nothing to sign with
        }
        signingKey = dialect == 0x0311 ? kdf(auth[1], "SMBSigningKey\0".getBytes("US-ASCII"), preauth)
                : dialect >= 0x0300 ? kdf(auth[1], "SMB2AESCMAC\0".getBytes("US-ASCII"), "SmbSign\0".getBytes("US-ASCII"))
                : auth[1];
    }

    private byte[] sessionSetup(byte[] token) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(le16(25, 0x0100)); // StructureSize, Flags 0, SecurityMode: signing enabled
        b.write(le32(0)); // Capabilities
        b.write(le32(0)); // Channel
        b.write(le16(64 + 24, token.length));
        b.write(new byte[8]); // PreviousSessionId
        b.write(token);
        return b.toByteArray();
    }

    private void treeConnect(String share) throws IOException {
        if (share == null || share.trim().length() == 0) {
            throw new IOException("Freigabe fehlt (z. B. photo/OM-Kamera)");
        }
        byte[] path = utf16("\\\\" + server + "\\" + share.trim());
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(le16(9, 0));
        b.write(le16(64 + 8, path.length));
        b.write(path);
        long id = send(TREE_CONNECT, b.toByteArray());
        byte[] r = receiveRaw(id);
        int status = (int) u32(r, 8);
        if (status != STATUS_OK) {
            throw new IOException(status == 0xC00000CC ? "Freigabe „" + share + "“ gibt es nicht"
                    : status == 0xC0000022 ? "Kein Zugriff auf die Freigabe „" + share + "“" : "Freigabe nicht verbunden (" + statusText(status) + ")");
        }
        treeId = (int) u32(r, 36);
    }

    // ---- messages ----

    private byte[] call(int command, byte[] body, boolean check) throws IOException {
        long id = send(command, body);
        return check ? receive(id, commandName(command)) : receiveRaw(id);
    }

    private long send(int command, byte[] body) throws IOException {
        long id = messageId++;
        byte[] msg = new byte[64 + body.length];
        msg[0] = (byte) 0xFE;
        msg[1] = 'S';
        msg[2] = 'M';
        msg[3] = 'B';
        put16(msg, 4, 64);
        put16(msg, 6, dialect > 0x0202 ? 1 : 0); // CreditCharge
        put16(msg, 12, command);
        put16(msg, 14, 64); // CreditRequest
        boolean sign = signing && signingKey != null && command != NEGOTIATE && command != SESSION_SETUP;
        put32(msg, 16, sign ? 0x8 : 0);
        put64(msg, 24, id);
        put32(msg, 36, treeId);
        put64(msg, 40, sessionId);
        System.arraycopy(body, 0, msg, 64, body.length);
        if (sign) {
            byte[] mac = dialect >= 0x0300 ? Cmac.mac(signingKey, msg) : hmac("HmacSHA256", signingKey, msg);
            System.arraycopy(mac, 0, msg, 48, 16);
        }
        lastSent = msg;
        byte[] frame = new byte[4 + msg.length];
        frame[1] = (byte) (msg.length >> 16);
        frame[2] = (byte) (msg.length >> 8);
        frame[3] = (byte) msg.length;
        System.arraycopy(msg, 0, frame, 4, msg.length);
        out.write(frame);
        out.flush();
        return id;
    }

    /** Answers that arrived before they were asked for (servers may answer writes out of order) */
    private final java.util.HashMap<Long, byte[]> early = new java.util.HashMap<Long, byte[]>();

    /** Reads the answer to a message; interim "pending" answers are skipped */
    private byte[] receiveRaw(long id) throws IOException {
        byte[] waiting = early.remove(id);
        if (waiting != null) {
            return waiting;
        }
        while (true) {
            int len = in.readInt() & 0x00FFFFFF;
            byte[] msg = new byte[len];
            in.readFully(msg);
            if (len < 64 || msg[0] != (byte) 0xFE || msg[1] != 'S') {
                throw new IOException("Keine SMB2-Antwort");
            }
            long mid = u64(msg, 24);
            int status = (int) u32(msg, 8);
            if (status == STATUS_PENDING && (u32(msg, 16) & 0x2) != 0) {
                continue; // async interim answer, the real one follows
            }
            if (mid != id) {
                if (early.size() > 64) {
                    throw new IOException("Antworten passen nicht zu den Anfragen");
                }
                early.put(mid, msg);
                continue;
            }
            return msg;
        }
    }

    private byte[] receive(long id, String what) throws IOException {
        byte[] r = receiveRaw(id);
        int status = (int) u32(r, 8);
        if (status != STATUS_OK) {
            throw new IOException(what + ": " + statusText(status));
        }
        return r;
    }

    private static byte[] securityBuffer(byte[] r) {
        int off = u16(r, 64 + 4), len = u16(r, 64 + 6);
        byte[] b = new byte[Math.max(0, Math.min(len, r.length - off))];
        System.arraycopy(r, off, b, 0, b.length);
        return b;
    }

    private static String commandName(int c) {
        switch (c) {
            case CREATE: return "Anlegen";
            case CLOSE: return "Schließen";
            case WRITE: return "Schreiben";
            case TREE_CONNECT: return "Freigabe";
            default: return "SMB";
        }
    }

    static String statusText(int s) {
        switch (s) {
            case 0xC0000022: return "kein Schreibrecht";
            case 0xC000006D: return "Benutzer oder Passwort falsch";
            case 0xC0000071: return "Passwort abgelaufen";
            case 0xC0000072: return "Konto gesperrt";
            case 0xC00000CC: return "Freigabe nicht gefunden";
            case 0xC0000034: return "Name nicht gefunden";
            case 0xC000003A: return "Pfad nicht gefunden";
            case 0xC000007F: return "Datenträger voll";
            case 0xC00000BB: return "nicht unterstützt";
            case 0xC000000D: return "ungültiger Parameter";
            case STATUS_COLLISION: return "Name schon vergeben";
            default: return String.format(Locale.US, "Status 0x%08X", s);
        }
    }

    // ---- NTLMv2 ----

    private static byte[] ntlmNegotiate(int flags) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write("NTLMSSP\0".getBytes("US-ASCII"));
        b.write(le32(1));
        b.write(le32(flags));
        b.write(new byte[16]); // domain and workstation: empty
        return b.toByteArray();
    }

    /** AUTHENTICATE message and the session key */
    private static byte[][] ntlmAuthenticate(byte[] ch, int flags, String domain, String user, String pass) throws IOException {
        int serverFlags = (int) u32(ch, 20);
        byte[] serverChallenge = new byte[8];
        System.arraycopy(ch, 24, serverChallenge, 0, 8);
        int tiLen = u16(ch, 40), tiOff = (int) u32(ch, 44);
        byte[] targetInfo = new byte[Math.max(0, Math.min(tiLen, ch.length - tiOff))];
        System.arraycopy(ch, tiOff, targetInfo, 0, targetInfo.length);

        byte[] timestamp = avTimestamp(targetInfo);
        boolean serverTime = timestamp != null;
        if (timestamp == null) {
            timestamp = le64((System.currentTimeMillis() + 11644473600000L) * 10000L);
        }
        byte[] clientChallenge = new byte[8];
        new SecureRandom().nextBytes(clientChallenge);

        byte[] ntHash = Md4.digest(utf16(pass));
        byte[] ntowf = hmac("HmacMD5", ntHash, utf16(user.toUpperCase(Locale.US) + domain));
        ByteArrayOutputStream temp = new ByteArrayOutputStream();
        temp.write(new byte[]{1, 1, 0, 0, 0, 0, 0, 0});
        temp.write(timestamp);
        temp.write(clientChallenge);
        temp.write(new byte[4]);
        temp.write(targetInfo);
        temp.write(new byte[4]);
        byte[] t = temp.toByteArray();
        byte[] proof = hmac("HmacMD5", ntowf, concat(serverChallenge, t));
        byte[] ntResponse = concat(proof, t);
        byte[] lmResponse = serverTime ? new byte[24]
                : concat(hmac("HmacMD5", ntowf, concat(serverChallenge, clientChallenge)), clientChallenge);
        byte[] sessionKey = hmac("HmacMD5", ntowf, proof);

        byte[] d = utf16(domain), u = utf16(user), w = utf16("OMKAMERA");
        int negotiated = flags & serverFlags | 0x00000001;
        int off = 64;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write("NTLMSSP\0".getBytes("US-ASCII"));
        b.write(le32(3));
        b.write(field(lmResponse.length, off));
        off += lmResponse.length;
        b.write(field(ntResponse.length, off));
        off += ntResponse.length;
        b.write(field(d.length, off));
        off += d.length;
        b.write(field(u.length, off));
        off += u.length;
        b.write(field(w.length, off));
        off += w.length;
        b.write(field(0, off)); // EncryptedRandomSessionKey: none (no key exchange)
        b.write(le32(negotiated));
        b.write(lmResponse);
        b.write(ntResponse);
        b.write(d);
        b.write(u);
        b.write(w);
        return new byte[][]{b.toByteArray(), sessionKey};
    }

    private static byte[] avTimestamp(byte[] ti) {
        int i = 0;
        while (i + 4 <= ti.length) {
            int id = u16(ti, i), len = u16(ti, i + 2);
            if (id == 0) {
                break;
            }
            if (id == 7 && len == 8 && i + 12 <= ti.length) {
                byte[] t = new byte[8];
                System.arraycopy(ti, i + 4, t, 0, 8);
                return t;
            }
            i += 4 + len;
        }
        return null;
    }

    private static byte[] field(int len, int off) {
        byte[] f = new byte[8];
        put16(f, 0, len);
        put16(f, 2, len);
        put32(f, 4, off);
        return f;
    }

    // ---- SPNEGO (just enough DER for one NTLM round) ----

    private static final byte[] OID_SPNEGO = {0x06, 0x06, 0x2b, 0x06, 0x01, 0x05, 0x05, 0x02};
    private static final byte[] OID_NTLM = {0x06, 0x0a, 0x2b, 0x06, 0x01, 0x04, 0x01, (byte) 0x82, 0x37, 0x02, 0x02, 0x0a};

    private static byte[] spnegoInit(byte[] ntlm) throws IOException {
        byte[] mechTypes = der(0xa0, der(0x30, OID_NTLM));
        byte[] mechToken = der(0xa2, der(0x04, ntlm));
        byte[] negTokenInit = der(0xa0, der(0x30, concat(mechTypes, mechToken)));
        return der(0x60, concat(OID_SPNEGO, negTokenInit));
    }

    private static byte[] spnegoResponse(byte[] ntlm) throws IOException {
        return der(0xa1, der(0x30, der(0xa2, der(0x04, ntlm))));
    }

    private static byte[] der(int tag, byte[] content) {
        int n = content.length;
        byte[] len;
        if (n < 0x80) {
            len = new byte[]{(byte) n};
        } else if (n < 0x100) {
            len = new byte[]{(byte) 0x81, (byte) n};
        } else {
            len = new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        }
        byte[] out = new byte[1 + len.length + n];
        out[0] = (byte) tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(content, 0, out, 1 + len.length, n);
        return out;
    }

    // ---- helpers ----

    private static byte[] hmac(String alg, byte[] key, byte[] data) throws IOException {
        try {
            Mac m = Mac.getInstance(alg);
            m.init(new SecretKeySpec(key, alg));
            return m.doFinal(data);
        } catch (Exception e) {
            throw new IOException(alg + " nicht verfügbar: " + e);
        }
    }

    private static byte[] utf16(String s) {
        try {
            return s.getBytes("UTF-16LE");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    private static int indexOf(byte[] h, byte[] n) {
        outer:
        for (int i = 0; i + n.length <= h.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (h[i + j] != n[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] le16(int a, int b) {
        return new byte[]{(byte) a, (byte) (a >> 8), (byte) b, (byte) (b >> 8)};
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] le64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >> (8 * i));
        return b;
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    private static void put32(byte[] b, int o, int v) {
        for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * i));
    }

    private static void put64(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >> (8 * i));
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
    }

    private static long u64(byte[] b, int o) {
        return u32(b, o) | u32(b, o + 4) << 32;
    }

    /** SP800-108 counter-mode KDF with HMAC-SHA256, 128-bit key (SMB 3.0 signing key) */
    private static byte[] kdf(byte[] key, byte[] label, byte[] context) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(new byte[]{0, 0, 0, 1});
        b.write(label);
        b.write(0);
        b.write(context);
        b.write(new byte[]{0, 0, 0, (byte) 0x80});
        byte[] full = hmac("HmacSHA256", key, b.toByteArray());
        byte[] out = new byte[16];
        System.arraycopy(full, 0, out, 0, 16);
        return out;
    }

    /** AES-CMAC (RFC 4493) for SMB 3 signatures */
    static final class Cmac {
        static byte[] mac(byte[] key, byte[] msg) throws IOException {
            try {
                javax.crypto.Cipher aes = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
                aes.init(javax.crypto.Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
                byte[] k1 = shift(aes.doFinal(new byte[16]));
                byte[] k2 = shift(k1);
                int n = Math.max(1, (msg.length + 15) / 16);
                boolean full = msg.length > 0 && msg.length % 16 == 0;
                byte[] x = new byte[16], block = new byte[16];
                for (int i = 0; i < n; i++) {
                    int off = i * 16;
                    if (i < n - 1) {
                        System.arraycopy(msg, off, block, 0, 16);
                    } else {
                        java.util.Arrays.fill(block, (byte) 0);
                        int rest = msg.length - off;
                        System.arraycopy(msg, off, block, 0, rest);
                        if (!full) {
                            block[rest] = (byte) 0x80;
                        }
                        byte[] k = full ? k1 : k2;
                        for (int j = 0; j < 16; j++) block[j] ^= k[j];
                    }
                    for (int j = 0; j < 16; j++) x[j] ^= block[j];
                    x = aes.doFinal(x);
                }
                return x;
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException("AES nicht verfügbar: " + e);
            }
        }

        private static byte[] shift(byte[] b) {
            byte[] o = new byte[16];
            int carry = 0;
            for (int i = 15; i >= 0; i--) {
                int v = (b[i] & 0xff) << 1 | carry;
                o[i] = (byte) v;
                carry = (b[i] & 0x80) != 0 ? 1 : 0;
            }
            if ((b[0] & 0x80) != 0) {
                o[15] ^= (byte) 0x87;
            }
            return o;
        }
    }

    /** MD4 (RFC 1320) for the NT hash; not part of Java's standard providers */
    static final class Md4 {
        static byte[] digest(byte[] msg) {
            int[] h = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
            int n = msg.length;
            int padLen = ((n + 8) / 64 + 1) * 64;
            byte[] m = new byte[padLen];
            System.arraycopy(msg, 0, m, 0, n);
            m[n] = (byte) 0x80;
            long bits = (long) n * 8;
            for (int i = 0; i < 8; i++) m[padLen - 8 + i] = (byte) (bits >>> (8 * i));
            int[] x = new int[16];
            for (int blk = 0; blk < padLen; blk += 64) {
                for (int i = 0; i < 16; i++) {
                    x[i] = (m[blk + 4 * i] & 0xff) | (m[blk + 4 * i + 1] & 0xff) << 8
                            | (m[blk + 4 * i + 2] & 0xff) << 16 | (m[blk + 4 * i + 3] & 0xff) << 24;
                }
                int a = h[0], b = h[1], c = h[2], d = h[3];
                int[] s1 = {3, 7, 11, 19};
                for (int i = 0; i < 16; i++) {
                    int t = a + ((b & c) | (~b & d)) + x[i];
                    a = d; d = c; c = b; b = Integer.rotateLeft(t, s1[i % 4]);
                }
                int[] s2 = {3, 5, 9, 13};
                int[] o2 = {0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15};
                for (int i = 0; i < 16; i++) {
                    int t = a + ((b & c) | (b & d) | (c & d)) + x[o2[i]] + 0x5a827999;
                    a = d; d = c; c = b; b = Integer.rotateLeft(t, s2[i % 4]);
                }
                int[] s3 = {3, 9, 11, 15};
                int[] o3 = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};
                for (int i = 0; i < 16; i++) {
                    int t = a + (b ^ c ^ d) + x[o3[i]] + 0x6ed9eba1;
                    a = d; d = c; c = b; b = Integer.rotateLeft(t, s3[i % 4]);
                }
                h[0] += a; h[1] += b; h[2] += c; h[3] += d;
            }
            byte[] out = new byte[16];
            for (int i = 0; i < 4; i++) put32(out, 4 * i, h[i]);
            return out;
        }
    }
}
