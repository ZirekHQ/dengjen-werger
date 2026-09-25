package werger.adapters.crowdin;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class CrowdinWriteE2eTest {
    private static Optional<String> env(String name) {
        return Optional.ofNullable(System.getenv(name)).filter(v -> !v.isEmpty());
    }

    @Test
    void creatingThenApprovingATranslationRoundTripsAgainstRealCrowdin() {
        Optional<String> token = env("CROWDIN_TEST_TOKEN");
        Optional<String> projectId = env("CROWDIN_TEST_PROJECT_ID");
        Optional<String> stringId = env("CROWDIN_TEST_STRING_ID");
        assumeTrue(
                token.isPresent() && projectId.isPresent() && stringId.isPresent(),
                "set CROWDIN_TEST_TOKEN, CROWDIN_TEST_PROJECT_ID and CROWDIN_TEST_STRING_ID to run");

        CrowdinClient crowdin = new CrowdinClient(RestClient.create(), token.get(), Long.parseLong(projectId.get()));
        CrowdinCreatedTranslation created = crowdin.createTranslation(
                "kmr", Long.parseLong(stringId.get()), "TEST - dengjen-werger integration check");
        crowdin.approveTranslation(created.id());
    }
}
