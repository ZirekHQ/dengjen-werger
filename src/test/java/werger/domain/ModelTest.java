package werger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelTest {
    @Test
    void inProgressCarriesTheLeaseholderAndExpiryNotABareFlag() {
        UUID userId = UUID.randomUUID();
        Instant expiry = Instant.now();
        WorkItemStatus.InProgress status = new WorkItemStatus.InProgress(userId, expiry);
        assertThat(status.userId()).isEqualTo(userId);
        assertThat(status.expiresAt()).isEqualTo(expiry);
    }
}
