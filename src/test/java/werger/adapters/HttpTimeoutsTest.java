package werger.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import werger.adapters.crowdin.CrowdinSource;
import werger.service.ApprovalService;

class HttpTimeoutsTest {
    @Test
    void aPushOfSequentialCallsFinishesWellInsideItsClaim() {
        Duration worstCase =
                HttpTimeouts.CONNECT.plus(HttpTimeouts.READ).multipliedBy(CrowdinSource.HTTP_CALLS_PER_PUSH);

        assertThat(worstCase).isLessThanOrEqualTo(ApprovalService.PUSH_CLAIM.dividedBy(2));
    }
}
