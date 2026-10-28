package werger.adapters.crowdin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import werger.domain.Language;
import werger.domain.WorkItem;
import werger.domain.WorkItemStatus;
import werger.ports.Result;
import werger.ports.SourceError;

class CrowdinSourceTest {
    private static final long FILE_ID = 661L;
    private static final Language KMR = new Language("kmr", "Kurmanji");

    /** Hand-rolled stub: the project has no mocking library, and the client's public methods are the seam. */
    private static class StubClient extends CrowdinClient {
        final List<CrowdinSourceString> strings;
        final List<CrowdinTranslation> approved;
        final List<CrowdinTranslation> submitted;
        final List<String> calls = new ArrayList<>();
        RuntimeException createFailure;

        StubClient(List<CrowdinSourceString> strings, List<CrowdinTranslation> approved) {
            this(strings, approved, approved);
        }

        /** {@code submitted} is everything Crowdin holds for the string, approved or not. */
        StubClient(
                List<CrowdinSourceString> strings,
                List<CrowdinTranslation> approved,
                List<CrowdinTranslation> submitted) {
            super(RestClient.builder().build(), "test-token", 780748L);
            this.strings = strings;
            this.approved = approved;
            this.submitted = submitted;
        }

        @Override
        public List<CrowdinTranslation> getTranslations(String languageId, long fileId) {
            calls.add("getTranslations");
            return languageId.equals("kmr") && fileId == FILE_ID ? submitted : List.of();
        }

        @Override
        public List<CrowdinSourceString> sourceStrings(long fileId) {
            return fileId == FILE_ID ? strings : List.of();
        }

        @Override
        public Set<Long> approvedTranslationIds(String languageId, long fileId) {
            return approvedTranslations(languageId, fileId).stream()
                    .map(CrowdinTranslation::translationId)
                    .collect(Collectors.toSet());
        }

        @Override
        public List<CrowdinTranslation> approvedTranslations(String languageId, long fileId) {
            return languageId.equals("kmr") && fileId == FILE_ID ? approved : List.of();
        }

        @Override
        public CrowdinCreatedTranslation createTranslation(String languageId, long stringId, String text) {
            calls.add("create:" + languageId + ":" + stringId + ":" + text);
            if (createFailure != null) {
                throw createFailure;
            }
            return new CrowdinCreatedTranslation(900L);
        }

        @Override
        public void approveTranslation(long translationId) {
            calls.add("approve:" + translationId);
        }
    }

    private static CrowdinSourceString string(long id, String text) {
        return new CrowdinSourceString(id, text, "key." + id);
    }

    private static CrowdinTranslation approved(long stringId, String text) {
        return new CrowdinTranslation(stringId * 10, stringId, text);
    }

    private static List<CrowdinTranslation> pluralForms(long stringId, long... translationIds) {
        Set<Long> row = Arrays.stream(translationIds).boxed().collect(Collectors.toSet());
        return Arrays.stream(translationIds)
                .mapToObj(id -> new CrowdinTranslation(id, stringId, "form " + id, row))
                .toList();
    }

    private static WorkItem item(String externalId) {
        return new WorkItem(
                UUID.randomUUID(),
                "crowdin",
                externalId,
                "kmr",
                "Hello",
                Optional.empty(),
                new WorkItemStatus.Available());
    }

    private static CrowdinSource source(StubClient client) {
        return new CrowdinSource(client, FILE_ID);
    }

    @Test
    void fetchWorkItemsReturnsOnlyStringsWithoutAnApprovedTranslation() {
        var client = new StubClient(
                List.of(string(1, "One"), string(2, "Two"), string(3, "Three")), List.of(approved(2, "Du")));

        assertThat(source(client).fetchWorkItems(KMR))
                .extracting(WorkItem::externalId)
                .containsExactly("1", "3");
    }

    @Test
    void fetchWorkItemsMapsStringToAvailableWorkItem() {
        var item = source(new StubClient(List.of(string(7, "Hello")), List.of()))
                .fetchWorkItems(KMR)
                .getFirst();

        assertThat(item.sourceAdapter()).isEqualTo("crowdin");
        assertThat(item.language()).isEqualTo("kmr");
        assertThat(item.sourceText()).isEqualTo("Hello");
        assertThat(item.targetTextDraft()).isEmpty();
        assertThat(item.status()).isInstanceOf(WorkItemStatus.Available.class);
    }

    @Test
    void fetchWorkItemsIncludesUnapprovedTranslationsWithTheirTextAsDraft() {
        var pending = new CrowdinTranslation(20, 2, "Du (pending)");
        var client = new StubClient(List.of(string(1, "One"), string(2, "Two")), List.of(), List.of(pending));

        assertThat(source(client).fetchWorkItems(KMR))
                .extracting(WorkItem::externalId, WorkItem::targetTextDraft)
                .containsExactly(tuple("1", Optional.empty()), tuple("2", Optional.of("Du (pending)")));
    }

    @Test
    void fetchWorkItemsDraftsTheMostRecentUnapprovedTranslation() {
        var older = new CrowdinTranslation(20, 2, "old");
        var newer = new CrowdinTranslation(21, 2, "new");
        var client = new StubClient(List.of(string(2, "Two")), List.of(), List.of(older, newer));

        assertThat(source(client).fetchWorkItems(KMR).getFirst().targetTextDraft())
                .contains("new");
    }

    @Test
    void fetchWorkItemsFetchesTranslationsOncePerPoll() {
        var client = new StubClient(List.of(string(1, "One")), List.of(approved(1, "Yek")));

        source(client).fetchWorkItems(KMR);

        assertThat(client.calls).containsOnlyOnce("getTranslations");
    }

    @Test
    void fetchWorkItemsReturnsNothingWhenEveryStringIsApproved() {
        var client = new StubClient(List.of(string(1, "One")), List.of(approved(1, "Yek")));

        assertThat(source(client).fetchWorkItems(KMR)).isEmpty();
    }

    @Test
    void fetchMemoryPairsEachApprovedTranslationWithItsSourceText() {
        var client = new StubClient(List.of(string(1, "One"), string(2, "Two")), List.of(approved(2, "Du")));

        assertThat(source(client).fetchMemory(KMR)).singleElement().satisfies(segment -> {
            assertThat(segment.sourceText()).isEqualTo("Two");
            assertThat(segment.targetText()).isEqualTo("Du");
            assertThat(segment.language()).isEqualTo("kmr");
            assertThat(segment.sourceAdapter()).isEqualTo("crowdin");
        });
    }

    @Test
    void fetchWorkItemsKeepsAPluralStringUntilEveryFormIsApproved() {
        var forms = pluralForms(2, 20, 21);
        var client = new StubClient(List.of(string(2, "Two")), List.of(forms.getFirst()), forms);

        assertThat(source(client).fetchWorkItems(KMR))
                .extracting(WorkItem::externalId)
                .containsExactly("2");
    }

    @Test
    void fetchWorkItemsDropsAPluralStringOnceEveryFormIsApproved() {
        var forms = pluralForms(2, 20, 21);
        var client = new StubClient(List.of(string(2, "Two")), forms, forms);

        assertThat(source(client).fetchWorkItems(KMR)).isEmpty();
    }

    @Test
    void fetchWorkItemsDoesNotDraftFromAPluralForm() {
        var forms = pluralForms(2, 20, 21);
        var client = new StubClient(List.of(string(2, "Two")), List.of(), forms);

        assertThat(source(client).fetchWorkItems(KMR).getFirst().targetTextDraft())
                .isEmpty();
    }

    @Test
    void fetchMemorySkipsPluralFormsBecauseTheSourceTextIsNotPerForm() {
        var forms = pluralForms(2, 20, 21);
        var client = new StubClient(List.of(string(2, "Two")), forms);

        assertThat(source(client).fetchMemory(KMR)).isEmpty();
    }

    @Test
    void fetchMemorySkipsTranslationsWhoseSourceStringIsGone() {
        var client = new StubClient(List.of(string(1, "One")), List.of(approved(99, "Orphan")));

        assertThat(source(client).fetchMemory(KMR)).isEmpty();
    }

    @Test
    void submitCreatesTheTranslationWithoutApprovingIt() {
        var client = new StubClient(List.of(), List.of());

        var result = source(client).submit(item("42"), "Silav");

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(client.calls).containsExactly("create:kmr:42:Silav");
    }

    @Test
    void submitReturnsRejectedWhenCrowdinAnswers4xx() {
        var client = new StubClient(List.of(), List.of());
        client.createFailure =
                new RestClientResponseException("bad", HttpStatus.BAD_REQUEST, "Bad Request", null, null, null);

        var error = source(client).submit(item("42"), "Silav");

        assertThat(error)
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Rejected.class));
    }

    @Test
    void submitReturnsTransientWhenCrowdinAnswers5xxOrFailsToConnect() {
        var serverError = new StubClient(List.of(), List.of());
        serverError.createFailure =
                new RestClientResponseException("down", HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null);
        var networkError = new StubClient(List.of(), List.of());
        networkError.createFailure = new IllegalStateException("connection reset");

        assertThat(source(serverError).submit(item("42"), "Silav"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Transient.class));
        assertThat(source(networkError).submit(item("42"), "Silav"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Transient.class));
    }

    @Test
    void submitReturnsRejectedWithoutCallingCrowdinWhenExternalIdIsNotNumeric() {
        var client = new StubClient(List.of(), List.of());

        var result = source(client).submit(item("abc"), "Silav");

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Rejected.class));
        assertThat(client.calls).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = HttpStatus.class,
            names = {"REQUEST_TIMEOUT", "TOO_MANY_REQUESTS"})
    void submitReturnsTransientWhenCrowdinAnswersARetryable4xx(HttpStatus status) {
        var client = new StubClient(List.of(), List.of());
        client.createFailure = new RestClientResponseException("retry", status, status.name(), null, null, null);

        assertThat(source(client).submit(item("42"), "Silav"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Transient.class));
    }
}
