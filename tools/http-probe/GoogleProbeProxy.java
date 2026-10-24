import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Temporary loopback-only diagnostic proxy; forwards through NekoBox. */
public class GoogleProbeProxy {
    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 31808));
        System.out.println("READY 127.0.0.1:31808 upstream=127.0.0.1:2080");
        while (true) {
            Socket client = server.accept();
            new Thread(new Runnable() {
                public void run() { serve(client); }
            }).start();
        }
    }

    private static void serve(Socket client) {
        try (Socket c = client; Socket upstream = new Socket()) {
            c.setSoTimeout(15000);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int marker = 0;
            while (marker != 0x0d0a0d0a) {
                int b = in.read();
                if (b < 0) return;
                header.write(b);
                marker = (marker << 8) | b;
                if (header.size() > 65536) throw new IOException("header too large");
            }
            String raw = new String(header.toByteArray(), StandardCharsets.ISO_8859_1);
            String[] lines = raw.split("\r\n");
            String[] request = lines[0].split(" ", 3);
            boolean rewrite = false;
            if (request.length == 3 && !"CONNECT".equals(request[0])) {
                URI uri = new URI(request[1]);
                rewrite = "http".equalsIgnoreCase(uri.getScheme())
                    && "www.google.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 80);
            }
            StringBuilder outgoing = new StringBuilder(lines[0]).append("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String lower = lines[i].toLowerCase(Locale.ROOT);
                if (lower.startsWith("proxy-connection:") || lower.startsWith("connection:")) continue;
                if (rewrite && lower.startsWith("user-agent:")) continue;
                outgoing.append(lines[i]).append("\r\n");
            }
            if (rewrite) {
                outgoing.append("User-Agent: curl/8.0\r\n");
                System.out.println("REWRITE google HTTP user-agent " + System.currentTimeMillis());
            }
            if (!"CONNECT".equals(request[0])) outgoing.append("Connection: close\r\n");
            outgoing.append("\r\n");
            upstream.connect(new InetSocketAddress("127.0.0.1", 2080), 15000);
            c.setSoTimeout(300000);
            upstream.setSoTimeout(300000);
            OutputStream upOut = upstream.getOutputStream();
            upOut.write(outgoing.toString().getBytes(StandardCharsets.ISO_8859_1));
            upOut.flush();
            Thread upload = new Thread(new Runnable() {
                public void run() { pipe(in, upOut, upstream); }
            });
            upload.setDaemon(true);
            upload.start();
            pipe(upstream.getInputStream(), c.getOutputStream(), c);
        } catch (Exception e) {
            System.out.println("PROXY_ERROR " + e.getClass().getSimpleName());
        }
    }

    private static void pipe(InputStream in, OutputStream out, Socket target) {
        try {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                out.flush();
            }
            target.shutdownOutput();
        } catch (IOException ignored) { }
    }
}
