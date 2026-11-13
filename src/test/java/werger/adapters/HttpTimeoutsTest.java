package werger.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class HttpTimeoutsTest {
    private static final int CALLS_PER_CROWDIN_PUSH = 4;
    private static final Duration PUSH_CLAIM = Duration.ofMinutes(5);

    @Test
    void aHungUpstreamFailsTheCallAtTheReadTimeout() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            RestClient client = HttpTimeouts.apply(RestClient.builder(), Duration.ofSeconds(1), Duration.ofMillis(300))
                    .build();
            long started = System.nanoTime();

            assertThatThrownBy(() -> client.get()
                            .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                            .retrieve()
                            .toBodilessEntity())
                    .isInstanceOf(ResourceAccessException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aPushOfSequentialCallsFinishesWellInsideItsClaim() {
        Duration worstCase = HttpTimeouts.CONNECT.plus(HttpTimeouts.READ).multipliedBy(CALLS_PER_CROWDIN_PUSH);

        assertThat(worstCase).isLessThanOrEqualTo(PUSH_CLAIM.dividedBy(2));
    }
}
