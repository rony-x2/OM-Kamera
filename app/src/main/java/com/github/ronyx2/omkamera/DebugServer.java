package com.github.ronyx2.omkamera;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

        Request(String method, String path, Map<String, String> query) {
            this.method = method;
            this.path = path;
            this.query = query;
        }

        public String param(String name) {
            return query.get(name);
        }
    }

    public static final class Response {
        public final int code;
        public final String type;
        public final byte[] body;

        public Response(int code, String type, byte[] body) {
            this.code = code;
            this.type = type;
            this.body = body;
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

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String line = in.readLine();
            String method = "GET";
            String target = "/";
            if (line != null) {
                String[] parts = line.split(" ");
                if (parts.length >= 2) {
                    method = parts[0];
                    target = parts[1];
                }
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
                r = provider.handle(new Request(method, path, query));
            } catch (Throwable t) {
                Log.error("debug handler " + path, t);
                r = Response.text(500, "text/plain", "Fehler: " + t);
            }
            if (r == null) {
                r = Response.text(404, "text/plain", "nicht gefunden\n");
            }
            OutputStream out = socket.getOutputStream();
            String head = "HTTP/1.0 " + r.code + " " + (r.code == 200 ? "OK" : r.code == 404 ? "Not Found" : r.code == 403 ? "Forbidden" : "Error") + "\r\n"
                    + "Content-Type: " + r.type + "\r\n"
                    + "Content-Length: " + r.body.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.write(r.body);
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
