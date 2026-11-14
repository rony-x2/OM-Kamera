package com.github.ronyx2.omkamera;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Small HTTP server for debugging and remote control over WLAN while the app runs.
 * Every connection gets its own short-lived thread, so live view frames and commands do not block each other.
 */
public class DebugServer {
    public static final int PORT = 8080;

    public static final class Request {
        public final String method;
        public final String path;
        public final Map<String, String> query;
        /** Header names in lower case */
        public final Map<String, String> headers;
        /** Request body as UTF-8 text, empty for GET */
        public final String body;

        Request(String method, String path, Map<String, String> query, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        public String param(String name) {
            return query.get(name);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    public static final class Response {
        public final int code;
        public final String type;
        public final byte[] body;
        /** Streamed instead of body (large files from the card) */
        public final java.io.File file;

        public Response(int code, String type, byte[] body) {
            this.code = code;
            this.type = type;
            this.body = body;
            this.file = null;
        }

        public Response(java.io.File file) {
            this.code = 200;
            this.type = "application/octet-stream";
            this.body = new byte[0];
            this.file = file;
        }

        public static Response text(int code, String type, String body) {
            try {
                return new Response(code, type + "; charset=utf-8", body.getBytes("UTF-8"));
            } catch (Exception e) {
                return new Response(500, "text/plain", new byte[0]);
            }
        }
    }

    /** Answers a request; null means "not found". Called on a server thread. */
    public interface Provider {
        Response handle(Request request);
    }

    private final Provider provider;
    private ServerSocket server;

    public DebugServer(Provider provider) {
        this.provider = provider;
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = new ServerSocket(PORT);
        } catch (Throwable t) {
            Log.error("debug server start", t);
            server = null;
            return;
        }
        final ServerSocket s = server;
        Thread accept = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!s.isClosed()) {
                    try {
                        final Socket socket = s.accept();
                        Thread worker = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handle(socket);
                            }
                        }, "debug-request");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (Throwable t) {
                        if (!s.isClosed()) {
                            Log.error("debug server", t);
                        }
                    }
                }
            }
        }, "debug-server");
        accept.setDaemon(true);
        accept.start();
        Log.info("debug server on port " + PORT);
    }

    public synchronized void stop() {
        if (server == null) {
            return;
        }
        try {
            server.close();
        } catch (Throwable ignored) {
        }
        server = null;
        Log.info("debug server stopped");
    }

    /** One header line in ISO-8859-1 without the line break, null at the end of the stream */
    private static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.write(c);
            }
            if (line.size() > 16 * 1024) {
                break;
            }
        }
        if (c < 0 && line.size() == 0) {
            return null;
        }
        return line.toString("ISO-8859-1");
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String line = readLine(in);
            String method = "GET";
            String target = "/";
            if (line != null) {
                String[] parts = line.split(" ");
                if (parts.length >= 2) {
                    method = parts[0];
                    target = parts[1];
                }
            }
            Map<String, String> headers = new HashMap<String, String>();
            String h;
            while ((h = readLine(in)) != null && h.length() > 0) {
                int colon = h.indexOf(':');
                if (colon > 0) {
                    headers.put(h.substring(0, colon).trim().toLowerCase(), h.substring(colon + 1).trim());
                }
            }
            String body = "";
            int length = 0;
            try {
                length = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0");
            } catch (NumberFormatException ignored) {
            }
            if (length > 0 && length < 1024 * 1024) {
                byte[] buf = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(buf, read, length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                body = new String(buf, 0, read, "UTF-8");
            }
            String path = target;
            Map<String, String> query = new HashMap<String, String>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String pair : target.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        query.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                    } else if (pair.length() > 0) {
                        query.put(URLDecoder.decode(pair, "UTF-8"), "");
                    }
                }
            }
            Response r;
            try {
                r = provider.handle(new Request(method, path, query, headers, body));
            } catch (Throwable t) {
                Log.error("debug handler " + path, t);
                r = Response.text(500, "text/plain", "Fehler: " + t);
            }
            if (r == null) {
                r = Response.text(404, "text/plain", "nicht gefunden\n");
            }
            OutputStream out = socket.getOutputStream();
            String head = "HTTP/1.0 " + r.code + " " + (r.code == 200 ? "OK" : r.code == 202 ? "Accepted" : r.code == 400 ? "Bad Request" : r.code == 401 ? "Unauthorized" : r.code == 404 ? "Not Found" : r.code == 403 ? "Forbidden" : "Error") + "\r\n"
                    + "Content-Type: " + r.type + "\r\n"
                    + "Content-Length: " + (r.file != null ? r.file.length() : r.body.length) + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            if (r.file != null) {
                java.io.FileInputStream fin = new java.io.FileInputStream(r.file);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = fin.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    fin.close();
                }
            } else {
                out.write(r.body);
            }
            out.flush();
        } catch (Throwable t) {
            Log.error("debug request", t);
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
