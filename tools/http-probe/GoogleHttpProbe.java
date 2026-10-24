import java.net.*;
import java.io.*;
import java.util.Arrays;

public class GoogleHttpProbe {
    public static void main(String[] args) {
        String target = args.length > 3 ? args[3] : "http://www.google.com";
        boolean follow = args.length > 0 && "follow".equals(args[0]);
        long start = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            System.out.println("TARGET=" + target + " FOLLOW_REDIRECTS=" + follow);
            System.out.println("DEFAULT_USER_AGENT=" + System.getProperty("http.agent"));
            System.out.println("DNS=" + Arrays.toString(InetAddress.getAllByName("www.google.com")));
            boolean explicitProxy = args.length > 1 && "proxy".equals(args[1]);
            System.out.println("EXPLICIT_HTTP_PROXY=" + explicitProxy);
            conn = (HttpURLConnection) (explicitProxy
                ? new URL(target).openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 2080)))
                : new URL(target).openConnection());
            conn.setInstanceFollowRedirects(follow);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setUseCaches(false);
            if (args.length > 2 && !"default".equals(args[2])) conn.setRequestProperty("User-Agent", args[2]);
            int status = conn.getResponseCode();
            System.out.println("HTTP_STATUS=" + status);
            System.out.println("FINAL_URL=" + conn.getURL());
            System.out.println("LOCATION=" + conn.getHeaderField("Location"));
            System.out.println("CONTENT_TYPE=" + conn.getContentType());
            System.out.println("CONTENT_LENGTH=" + conn.getContentLength());
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream != null) {
                try (InputStream body = stream) {
                    byte[] buf = new byte[4096];
                    int total = 0, count;
                    while (total < 262144 && (count = body.read(buf)) != -1) total += count;
                    System.out.println("BODY_BYTES_READ=" + total);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.out);
        } finally {
            if (conn != null) conn.disconnect();
            System.out.println("ELAPSED_MS=" + (System.currentTimeMillis() - start));
        }
    }
}
