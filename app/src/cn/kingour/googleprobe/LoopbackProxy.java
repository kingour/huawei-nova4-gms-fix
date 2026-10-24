package cn.kingour.googleprobe;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback proxy: Google HTTP goes through NekoBox; other sockets follow the active VPN. */
public final class LoopbackProxy implements Closeable {
    public interface Listener { void onFatalError(); }
    interface DirectConnector { void connect(Socket socket, String host, int port) throws IOException; }
    private static final int MAX_CONNECTIONS = 128;
    private static final int BACKLOG = 32;
    private static final int PENDING_CONNECTIONS = 32;
    private final Listener listener;
    private final DirectConnector directConnector;
    private final int listenPort;
    private final int upstreamPort;
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger rewritten = new AtomicInteger();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(
            MAX_CONNECTIONS, MAX_CONNECTIONS, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(PENDING_CONNECTIONS), new RejectedExecutionHandler() {
                @Override public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
                    // Backpressure: stop accepting while both workers and the pending queue are full.
                    try {
                        while (!closed.get() && !executor.isShutdown()) {
                            if (executor.getQueue().offer(task, 200, TimeUnit.MILLISECONDS)) {
                                if (executor.isShutdown() && executor.remove(task)) {
                                    throw new RejectedExecutionException("Proxy stopped");
                                }
                                return;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new RejectedExecutionException("Proxy stopped");
                }
            });
    private volatile ServerSocket server;
    private Thread acceptThread;

    public LoopbackProxy(Listener listener) { this(listener, ProxySettings.PORT, 2080); }

    LoopbackProxy(Listener listener, DirectConnector directConnector) {
        this(listener, ProxySettings.PORT, 2080, directConnector);
    }

    LoopbackProxy(Listener listener, int listenPort, int upstreamPort) {
        this(listener, listenPort, upstreamPort, new DirectConnector() {
            @Override public void connect(Socket socket, String host, int port) throws IOException {
                socket.connect(new InetSocketAddress(host, port), 15000);
            }
        });
    }

    private LoopbackProxy(Listener listener, int listenPort, int upstreamPort, DirectConnector directConnector) {
        this.listener = listener;
        this.directConnector = directConnector;
        this.listenPort = listenPort;
        this.upstreamPort = upstreamPort;
        requests.allowCoreThreadTimeOut(true);
    }

    public synchronized void start() throws IOException {
        if (server != null || closed.get()) throw new IOException("Already started or stopped");
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(ProxySettings.HOST, listenPort), BACKLOG);
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "probe-proxy-listener");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    int getLocalPort() { return server == null ? -1 : server.getLocalPort(); }
    public boolean isAlive() { return !closed.get() && server != null && !server.isClosed(); }
    public int getRewriteCount() { return rewritten.get(); }

    private boolean track(Socket socket) {
        synchronized (sockets) {
            if (closed.get()) {
                try { socket.close(); } catch (IOException ignored) { }
                return false;
            }
            sockets.add(socket);
            return true;
        }
    }

    private void acceptLoop() {
        try {
            while (!closed.get()) {
                final Socket client = server.accept();
                if (!track(client)) return;
                try {
                    requests.execute(new Runnable() {
                        @Override public void run() { serve(client); }
                    });
                } catch (RejectedExecutionException stopped) { closeSocket(client); }
            }
        } catch (IOException e) {
            if (!closed.get() && listener != null) listener.onFatalError();
        }
    }

    private void serve(final Socket client) {
        final Socket remote = new Socket();
        Thread uploadThread = null;
        boolean tunnelEstablished = false;
        try {
            if (!track(remote)) return;
            client.setSoTimeout(15000);
            final InputStream input = client.getInputStream();
            String raw = readHeader(input);
            if (raw == null) return;
            String[] first = raw.substring(0, raw.indexOf("\r\n")).split(" ", 3);
            if (first.length != 3 || !("HTTP/1.1".equals(first[2]) || "HTTP/1.0".equals(first[2]))) {
                throw new IOException("Invalid request line");
            }
            boolean connect = "CONNECT".equals(first[0]);
            URI target = new URI(connect ? "http://" + first[1] : first[1]);
            String host = target.getHost();
            int port = target.getPort();
            if (host == null || target.getRawUserInfo() != null || target.getRawFragment() != null
                    || !"http".equalsIgnoreCase(target.getScheme()) || port == 0 || port > 65535
                    || (connect && (port < 1 || !target.getRawPath().isEmpty()
                    || target.getRawQuery() != null))) {
                throw new IOException("Invalid proxy target");
            }
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            if (port == -1) port = 80;
            boolean google = !connect && "www.google.com".equalsIgnoreCase(host) && port == 80;
            // Keep the observed Google probe redirect chain on the same HTTP proxy path.
            // A VPN-bound direct connection to the Hong Kong landing page triggered CAPTCHA/429.
            boolean viaUpstream = google || (!connect && port == 80
                    && "www.google.com.hk".equalsIgnoreCase(host));
            if (viaUpstream) remote.connect(new InetSocketAddress(ProxySettings.HOST, upstreamPort), 15000);
            else directConnector.connect(remote, host, port);
            client.setSoTimeout(300000);
            remote.setSoTimeout(300000);
            final OutputStream output = remote.getOutputStream();
            if (connect) {
                // Establish a plain TCP tunnel. The client owns the TLS handshake and verification.
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                client.getOutputStream().flush();
                tunnelEstablished = true;
            } else {
                output.write(httpHeader(raw, first, target, viaUpstream, google).getBytes(StandardCharsets.ISO_8859_1));
                output.flush();
                if (google) rewritten.incrementAndGet();
            }
            uploadThread = new Thread(new Runnable() {
                @Override public void run() { pipe(input, output, remote); }
            }, "probe-proxy-upload");
            uploadThread.setDaemon(true);
            uploadThread.start();
            pipe(remote.getInputStream(), client.getOutputStream(), client);
        } catch (Exception ignored) {
            // No URLs, headers, bodies or credentials are logged.
            if (!closed.get() && !tunnelEstablished) {
                try {
                    client.getOutputStream().write(("HTTP/1.1 502 Bad Gateway\r\n"
                            + "Connection: close\r\nContent-Length: 0\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                } catch (IOException ignoredWrite) { }
            }
        } finally {
            closeSocket(client);
            closeSocket(remote);
            // Closing sockets releases both directions before this connection's worker is reused.
            if (uploadThread != null) {
                uploadThread.interrupt();
                try { uploadThread.join(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private static String readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int marker = 0;
        while (marker != 0x0d0a0d0a) {
            int b = input.read();
            if (b < 0) return null;
            header.write(b);
            marker = (marker << 8) | b;
            if (header.size() > 65536) throw new IOException("Header too large");
        }
        return new String(header.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String httpHeader(String raw, String[] first, URI target, boolean viaUpstream, boolean google) {
        String path = target.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (target.getRawQuery() != null) path += "?" + target.getRawQuery();
        StringBuilder result = new StringBuilder(first[0]).append(' ')
                .append(viaUpstream ? first[1] : path).append(' ').append(first[2]).append("\r\n");
        String[] lines = raw.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String lower = lines[i].toLowerCase(Locale.ROOT);
            if (lower.startsWith("proxy-connection:") || lower.startsWith("connection:")
                    || lower.startsWith("proxy-authorization:")
                    || (google && lower.startsWith("user-agent:"))) continue;
            result.append(lines[i]).append("\r\n");
        }
        if (google) result.append("User-Agent: curl/8.0\r\n");
        return result.append("Connection: close\r\n\r\n").toString();
    }

    private static void pipe(InputStream input, OutputStream output, Socket target) {
        try {
            byte[] buffer = new byte[16384];
            int size;
            while ((size = input.read(buffer)) >= 0) {
                if (size == 0) continue;
                output.write(buffer, 0, size);
                output.flush();
            }
        } catch (IOException ignored) { }
        finally {
            // EOF, timeout or an error in either direction must release the peer's blocking read.
            try { target.shutdownOutput(); } catch (IOException ignored) { }
        }
    }

    private void closeSocket(Socket socket) {
        sockets.remove(socket);
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        if (acceptThread != null) acceptThread.interrupt();
        requests.shutdownNow();
        synchronized (sockets) {
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            sockets.clear();
        }
    }
}
