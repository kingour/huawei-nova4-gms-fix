package cn.kingour.googleprobe;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real loopback sockets, no Google, VPN, Android device or external test libraries. */
public final class LoopbackProxyTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        googleHttpUsesUpstreamAndRewritesUa();
        googleHongKongHttpUsesUpstreamWithoutRewritingUa();
        otherHttpGoesDirectWithOriginFormAndOriginalUa();
        connectRelaysOpaqueBytesDirectly();
        clientHalfCloseStillReceivesResponse();
        malformedRequestDoesNotStopListener();
        connection129WaitsAndResumesAfterSlotReleased();
        shutdownClosesActiveAndQueuedConnections();
        System.out.println("PASS: " + passed + " proxy integration tests");
    }

    private static void googleHttpUsesUpstreamAndRewritesUa() throws Exception {
        try (Endpoint upstream = httpEndpoint();
             LoopbackProxy proxy = proxy(upstream.port())) {
            for (String authority : new String[]{"www.google.com", "www.google.com:80"}) {
                try (Socket client = client(proxy)) {
                    send(client, "GET http://" + authority + "/search?q=test HTTP/1.1\r\n"
                            + "Host: " + authority + "\r\nUser-Agent: Dalvik/test\r\n"
                            + "uSeR-aGeNt: duplicate\r\n\r\n");
                    check(header(client.getInputStream()).startsWith("HTTP/1.1 200"), "Google upstream response relayed");
                    String request = upstream.request();
                    check(request.startsWith("GET http://" + authority + "/search?q=test HTTP/1.1\r\n"),
                            "Google keeps absolute-form request for NekoBox");
                    check(headerValue(request, "user-agent").equals("curl/8.0"), "Google UA rewritten");
                    check(headerCount(request, "user-agent") == 1, "duplicate Google UA removed");
                }
            }
            check(proxy.getRewriteCount() == 2, "Google rewrite count");
        }
        pass("Google HTTP uses upstream and rewrites only one UA header");
    }

    private static void googleHongKongHttpUsesUpstreamWithoutRewritingUa() throws Exception {
        try (Endpoint upstream = httpEndpoint();
             LoopbackProxy proxy = proxy(upstream.port())) {
            for (String authority : new String[]{"www.google.com.hk", "www.google.com.hk:80"}) {
                try (Socket client = client(proxy)) {
                    send(client, "GET http://" + authority + "/url?q=test HTTP/1.1\r\n"
                            + "Host: " + authority + "\r\nUser-Agent: original-HK-UA\r\n\r\n");
                    check(header(client.getInputStream()).startsWith("HTTP/1.1 200"), "Google HK upstream response relayed");
                    String request = upstream.request();
                    check(request.startsWith("GET http://" + authority + "/url?q=test HTTP/1.1\r\n"),
                            "Google HK keeps absolute-form request for NekoBox");
                    check(headerValue(request, "user-agent").equals("original-HK-UA"), "Google HK UA preserved");
                    check(headerCount(request, "user-agent") == 1, "Google HK has no extra UA header");
                }
            }
            check(proxy.getRewriteCount() == 0, "Google HK compatibility forwarding does not count as UA rewrite");
        }
        pass("Google HK HTTP uses upstream without rewriting UA");
    }

    private static void otherHttpGoesDirectWithOriginFormAndOriginalUa() throws Exception {
        try (Endpoint upstream = httpEndpoint(); Endpoint target = httpEndpoint();
             LoopbackProxy proxy = proxy(upstream.port()); Socket client = client(proxy)) {
            send(client, "GET http://127.0.0.1:" + target.port() + "/a%20b?q=x%2Fy HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + target.port() + "\r\nUser-Agent: original-UA\r\n"
                    + "Proxy-Authorization: Basic should-not-reach-origin\r\nProxy-Connection: keep-alive\r\n\r\n");
            check(header(client.getInputStream()).startsWith("HTTP/1.1 200"), "direct HTTP response");
            String request = target.request();
            check(request.startsWith("GET /a%20b?q=x%2Fy HTTP/1.1\r\n"), "direct HTTP uses intact origin-form path/query");
            check(headerValue(request, "user-agent").equals("original-UA"), "other HTTP UA preserved");
            check(headerCount(request, "proxy-authorization") == 0, "proxy credentials do not leak to origin");
            check(headerCount(request, "proxy-connection") == 0, "proxy-only header removed");
            check(upstream.accepted.get() == 0, "other HTTP bypasses NekoBox HTTP entry");
            check(proxy.getRewriteCount() == 0, "other HTTP not counted as rewrite");
        }
        pass("Other HTTP connects to origin directly and preserves UA");
    }

    private static void connectRelaysOpaqueBytesDirectly() throws Exception {
        try (Endpoint upstream = httpEndpoint(); Endpoint target = echoEndpoint();
             LoopbackProxy proxy = proxy(upstream.port()); Socket client = tunnel(proxy, target.port())) {
            byte[] payload = new byte[]{0x16, 3, 1, 0, 7, 0, -1, 42, 0, 13, 10};
            client.getOutputStream().write(payload);
            client.getOutputStream().flush();
            byte[] reply = readExactly(client.getInputStream(), payload.length);
            check(java.util.Arrays.equals(payload, reply), "CONNECT opaque payload round-trip");
            check(upstream.accepted.get() == 0, "CONNECT bypasses NekoBox HTTP entry");
            check(proxy.getRewriteCount() == 0, "CONNECT never rewrites UA");
        }
        pass("CONNECT establishes direct opaque tunnel");
    }

    private static void clientHalfCloseStillReceivesResponse() throws Exception {
        try (Endpoint upstream = httpEndpoint(); Endpoint target = new Endpoint(new Handler() {
            @Override public void handle(Socket socket) throws Exception {
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                byte[] buf = new byte[128];
                int count;
                while ((count = socket.getInputStream().read(buf)) != -1) received.write(buf, 0, count);
                check(new String(received.toByteArray(), StandardCharsets.UTF_8).equals("request-body"),
                        "all upload bytes before half-close");
                send(socket, "response-after-upload-eof");
            }
        }); LoopbackProxy proxy = proxy(upstream.port()); Socket client = tunnel(proxy, target.port())) {
            send(client, "request-body");
            client.shutdownOutput();
            String reply = new String(readExactly(client.getInputStream(), 25), StandardCharsets.UTF_8);
            check(reply.equals("response-after-upload-eof"), "response survives client upload half-close");
        }
        pass("Client upload half-close does not lose pending response");
    }

    private static void malformedRequestDoesNotStopListener() throws Exception {
        try (Endpoint upstream = httpEndpoint(); Endpoint target = httpEndpoint();
             LoopbackProxy proxy = proxy(upstream.port())) {
            try (Socket bad = client(proxy)) {
                send(bad, "BROKEN\r\n\r\n");
                try { bad.getInputStream().read(); } catch (SocketException expected) { }
            }
            try (Socket good = client(proxy)) {
                send(good, "GET http://127.0.0.1:" + target.port() + "/ HTTP/1.1\r\nHost: localhost\r\n\r\n");
                check(header(good.getInputStream()).startsWith("HTTP/1.1 200"), "valid request after malformed request");
            }
            check(proxy.isAlive(), "malformed request does not kill listener");
        }
        pass("Malformed request leaves listener usable");
    }

    private static void connection129WaitsAndResumesAfterSlotReleased() throws Exception {
        List<Socket> active = new ArrayList<Socket>();
        try (Endpoint upstream = httpEndpoint(); Endpoint target = echoEndpoint();
             LoopbackProxy proxy = proxy(upstream.port())) {
            try {
                for (int i = 0; i < 128; i++) active.add(tunnel(proxy, target.port()));
                target.awaitAccepted(128);
                try (Socket waiting = client(proxy)) {
                    connectRequest(waiting, target.port());
                    waiting.setSoTimeout(250);
                    try {
                        int result = waiting.getInputStream().read();
                        throw new AssertionError("129th connection must wait, not close/respond; got " + result);
                    } catch (SocketTimeoutException expected) { }
                    active.remove(0).close();
                    waiting.setSoTimeout(5000);
                    check(header(waiting.getInputStream()).startsWith("HTTP/1.1 200"), "queued connection resumes after capacity is released");
                    send(waiting, "queued-request");
                    check(new String(readExactly(waiting.getInputStream(), 14), StandardCharsets.UTF_8).equals("queued-request"),
                            "resumed queued connection transports data");
                }
            } finally { closeAll(active); }
        }
        pass("128 connections work; the 129th waits and resumes");
    }

    private static void shutdownClosesActiveAndQueuedConnections() throws Exception {
        List<Socket> clients = new ArrayList<Socket>();
        try (Endpoint upstream = httpEndpoint(); Endpoint target = echoEndpoint();
             final LoopbackProxy proxy = proxy(upstream.port())) {
            try {
                for (int i = 0; i < 128; i++) clients.add(tunnel(proxy, target.port()));
                // More than the 32 application queue entries: exercise a blocked acceptor as well.
                for (int i = 0; i < 42; i++) {
                    Socket c = client(proxy);
                    connectRequest(c, target.port());
                    clients.add(c);
                }
                long start = System.nanoTime();
                proxy.close();
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000, "close must not wait for queued work");
                for (Socket c : clients) {
                    c.setSoTimeout(3000);
                    try { check(c.getInputStream().read() == -1, "shutdown closes active/queued sockets"); }
                    catch (SocketException expected) { }
                }
                check(!proxy.isAlive(), "closed listener reports stopped");
                // Idempotent shutdown is required by stop/onDestroy cleanup paths.
                proxy.close();
            } finally { closeAll(clients); }
        }
        pass("Saturated shutdown closes active and waiting sockets promptly");
    }

    private static LoopbackProxy proxy(int upstreamPort) throws Exception {
        LoopbackProxy proxy = new LoopbackProxy(null, 0, upstreamPort);
        proxy.start();
        return proxy;
    }

    private static Socket client(LoopbackProxy proxy) throws IOException {
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", proxy.getLocalPort()), 3000);
        client.setSoTimeout(5000);
        return client;
    }

    private static Socket tunnel(LoopbackProxy proxy, int targetPort) throws Exception {
        Socket client = client(proxy);
        try {
            connectRequest(client, targetPort);
            check(header(client.getInputStream()).startsWith("HTTP/1.1 200"), "CONNECT acknowledged");
            return client;
        } catch (Exception e) { client.close(); throw e; }
    }

    private static void connectRequest(Socket client, int port) throws IOException {
        send(client, "CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n");
    }

    private static void send(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    private static String header(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int marker = 0;
        while (marker != 0x0d0a0d0a) {
            int b = in.read();
            if (b == -1) throw new IOException("Connection closed before complete HTTP header");
            out.write(b);
            marker = (marker << 8) | b;
            if (out.size() > 65536) throw new IOException("Oversized test header");
        }
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readExactly(InputStream in, int size) throws IOException {
        byte[] bytes = new byte[size];
        int offset = 0;
        while (offset < size) {
            int count = in.read(bytes, offset, size - offset);
            if (count == -1) throw new IOException("Unexpected EOF after " + offset + " of " + size + " bytes");
            offset += count;
        }
        return bytes;
    }

    private static String headerValue(String raw, String name) {
        for (String line : raw.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
        }
        return "";
    }

    private static int headerCount(String raw, String name) {
        int count = 0;
        for (String line : raw.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) count++;
        }
        return count;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void pass(String description) {
        passed++;
        System.out.println("PASS " + description);
    }

    private static void closeAll(List<Socket> sockets) {
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
    }

    private interface Handler { void handle(Socket socket) throws Exception; }

    private static Endpoint httpEndpoint() throws IOException {
        final BlockingQueue<String> headers = new LinkedBlockingQueue<String>();
        Endpoint endpoint = new Endpoint(new Handler() {
            @Override public void handle(Socket socket) throws Exception {
                headers.add(header(socket.getInputStream()));
                send(socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK");
            }
        });
        endpoint.headers = headers;
        return endpoint;
    }

    private static Endpoint echoEndpoint() throws IOException {
        return new Endpoint(new Handler() {
            @Override public void handle(Socket socket) throws Exception {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = socket.getInputStream().read(buffer)) != -1) {
                    socket.getOutputStream().write(buffer, 0, count);
                    socket.getOutputStream().flush();
                }
            }
        });
    }

    private static final class Endpoint implements Closeable {
        private final ServerSocket server;
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
        private final BlockingQueue<Throwable> failures = new LinkedBlockingQueue<Throwable>();
        private BlockingQueue<String> headers;
        final AtomicInteger accepted = new AtomicInteger();
        private volatile boolean closed;

        Endpoint(final Handler handler) throws IOException {
            server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", 0), 256);
            Thread acceptor = new Thread(new Runnable() {
                @Override public void run() {
                    while (!closed) {
                        try {
                            final Socket socket = server.accept();
                            sockets.add(socket);
                            synchronized (accepted) {
                                accepted.incrementAndGet();
                                accepted.notifyAll();
                            }
                            Thread worker = new Thread(new Runnable() {
                                @Override public void run() {
                                    try { handler.handle(socket); }
                                    catch (Throwable e) { if (!closed) failures.add(e); }
                                    finally { try { socket.close(); } catch (IOException ignored) { } }
                                }
                            }, "test-origin-worker");
                            worker.setDaemon(true);
                            worker.start();
                        } catch (IOException e) { if (!closed) failures.add(e); }
                    }
                }
            }, "test-origin-listener");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() { return server.getLocalPort(); }

        void awaitAccepted(int expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (accepted) {
                while (accepted.get() < expected) {
                    long remaining = deadline - System.nanoTime();
                    check(remaining > 0, "origin must accept " + expected + " simultaneous connections");
                    TimeUnit.NANOSECONDS.timedWait(accepted, remaining);
                }
            }
        }

        String request() throws Exception {
            String request = headers.poll(5, TimeUnit.SECONDS);
            check(request != null, "origin must receive request header");
            return request;
        }

        @Override public void close() throws IOException {
            closed = true;
            server.close();
            synchronized (sockets) { closeAll(sockets); }
            Throwable failure = failures.poll();
            if (failure instanceof AssertionError) throw (AssertionError) failure;
        }
    }
}
