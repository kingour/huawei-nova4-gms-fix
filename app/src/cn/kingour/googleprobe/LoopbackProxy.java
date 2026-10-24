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
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded loopback HTTP forward proxy. HTTPS CONNECT is relayed without decrypting it. */
public final class LoopbackProxy implements Closeable {
    public interface Listener { void onFatalError(); }
    private final Listener listener;
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger rewritten = new AtomicInteger();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>());
    private final ThreadPoolExecutor uploads = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>());
    private ServerSocket server;

    public LoopbackProxy(Listener listener) { this.listener = listener; }

    public synchronized void start() throws IOException {
        if (server != null) throw new IOException("Already started");
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(ProxySettings.HOST, ProxySettings.PORT), 16);
        Thread accept = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "probe-proxy-listener");
        accept.setDaemon(true);
        accept.start();
    }

    public boolean isAlive() { return !closed.get() && server != null && !server.isClosed(); }
    public int getRewriteCount() { return rewritten.get(); }

    private void acceptLoop() {
        try {
            while (!closed.get()) {
                final Socket client = server.accept();
                sockets.add(client);
                if (closed.get()) { closeSocket(client); return; }
                try {
                    requests.execute(new Runnable() {
                        @Override public void run() { serve(client); }
                    });
                } catch (RuntimeException rejected) { closeSocket(client); }
            }
        } catch (IOException e) {
            if (!closed.get() && listener != null) listener.onFatalError();
        }
    }

    private void serve(final Socket client) {
        final Socket upstream = new Socket();
        sockets.add(upstream);
        try {
            if (closed.get()) return;
            client.setSoTimeout(15000);
            final InputStream input = client.getInputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int marker = 0;
            while (marker != 0x0d0a0d0a) {
                int b = input.read();
                if (b < 0) return;
                header.write(b);
                marker = (marker << 8) | b;
                if (header.size() > 65536) throw new IOException("Header too large");
            }
            String raw = new String(header.toByteArray(), StandardCharsets.ISO_8859_1);
            String outgoing = rewriteHeader(raw);
            upstream.connect(new InetSocketAddress("127.0.0.1", 2080), 15000);
            client.setSoTimeout(300000);
            upstream.setSoTimeout(300000);
            final OutputStream upload = upstream.getOutputStream();
            upload.write(outgoing.getBytes(StandardCharsets.ISO_8859_1));
            upload.flush();
            uploads.execute(new Runnable() {
                @Override public void run() { pipe(input, upload, upstream); }
            });
            pipe(upstream.getInputStream(), client.getOutputStream(), client);
        } catch (Exception ignored) {
            // Do not log URLs, headers, bodies, or credentials from unrelated traffic.
        } finally {
            closeSocket(client);
            closeSocket(upstream);
        }
    }

    String rewriteHeader(String raw) throws Exception {
        String[] lines = raw.split("\r\n");
        String[] first = lines[0].split(" ", 3);
        if (first.length != 3) throw new IOException("Invalid request line");
        boolean connect = "CONNECT".equals(first[0]);
        boolean rewrite = false;
        if (!connect) {
            URI uri = new URI(first[1]);
            rewrite = "http".equalsIgnoreCase(uri.getScheme())
                    && "www.google.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 80);
        }
        StringBuilder result = new StringBuilder(lines[0]).append("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String lower = lines[i].toLowerCase(Locale.ROOT);
            if (lower.startsWith("proxy-connection:") || lower.startsWith("connection:")) continue;
            if (rewrite && lower.startsWith("user-agent:")) continue;
            result.append(lines[i]).append("\r\n");
        }
        if (rewrite) {
            result.append("User-Agent: curl/8.0\r\n");
            rewritten.incrementAndGet();
        }
        if (!connect) result.append("Connection: close\r\n");
        return result.append("\r\n").toString();
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
            target.shutdownOutput();
        } catch (IOException ignored) { }
    }

    private void closeSocket(Socket socket) {
        sockets.remove(socket);
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        synchronized (sockets) {
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            sockets.clear();
        }
        requests.shutdownNow();
        uploads.shutdownNow();
    }
}
