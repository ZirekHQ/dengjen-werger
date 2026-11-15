package werger.adapters;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Connect and read timeouts for a {@code RestClient.Builder}; each client must apply them where it is built. */
public final class HttpTimeouts {
    public static final Duration CONNECT = Duration.ofSeconds(5);
    public static final Duration READ = Duration.ofSeconds(20);

    private HttpTimeouts() {}

    public static RestClient.Builder apply(RestClient.Builder builder) {
        return apply(builder, CONNECT, READ);
    }

    public static RestClient.Builder apply(RestClient.Builder builder, Duration connect, Duration read) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);
        return builder.requestFactory(factory);
    }
}
