package werger.adapters;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Bounds every outbound call, so a hung upstream cannot hold a push claim past its lease. */
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
