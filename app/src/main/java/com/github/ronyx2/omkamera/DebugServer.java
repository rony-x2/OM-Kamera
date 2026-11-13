package com.github.ronyx2.omkamera;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Small HTTP server for debugging over WLAN while the app runs.
 * Read-only: it shows the log, the camera parameters and the app state, it changes nothing.
 * Only active when switched on in the menu.
 */
public class DebugServer {
    public static final int PORT = 8080;

    /** Answers one request path with content, or null for "not found". Called on a server thread. */
    public interface Provider {
        String[] get(String path);
    }

    private final Provider provider;
    private ServerSocket server;
    private Thread thread;

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
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!s.isClosed()) {
                    try {
                        handle(s.accept());
                    } catch (Throwable t) {
                        if (!s.isClosed()) {
                            Log.error("debug server", t);
                        }
                    }
                }
            }
        }, "debug-server");
        thread.setDaemon(true);
        thread.start();
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
        thread = null;
        Log.info("debug server stopped");
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String line = in.readLine();
            String path = "/";
            if (line != null) {
                String[] parts = line.split(" ");
                if (parts.length >= 2) {
                    path = parts[1];
                }
            }
            String[] answer = provider.get(path);
            int code = answer != null ? 200 : 404;
            String type = answer != null ? answer[0] : "text/plain";
            byte[] body = (answer != null ? answer[1] : "nicht gefunden\n").getBytes("UTF-8");
            OutputStream out = socket.getOutputStream();
            String head = "HTTP/1.0 " + code + (code == 200 ? " OK" : " Not Found") + "\r\n"
                    + "Content-Type: " + type + "; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.write(body);
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
