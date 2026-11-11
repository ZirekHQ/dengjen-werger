package werger.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import werger.adapters.db.PronunciationFixRepo;
import werger.adapters.db.UserRepo;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationSay;
import werger.domain.ReviewVerdict;
import werger.domain.Role;
import werger.domain.User;
import werger.domain.UserStatus;
import werger.ports.Result;
import werger.service.LexiconRowError;
import werger.service.PronunciationFixService;
import werger.service.ReviewError;

@SpringBootTest
@AutoConfigureMockMvc
class PronunciationFixControllerTest {
    private static final String SUBJECT = "supabase-user-1";
    private static final User ACTIVE = user(UserStatus.ACTIVE);
    private static final PronunciationFix FIX = new PronunciationFix(
            UUID.randomUUID(),
            "kmr",
            "AB",
            new PronunciationSay.Respelling("Yekîtiya Ewropayê"),
            Optional.of("abbreviation"),
            UUID.randomUUID(),
            Instant.parse("2026-11-11T12:00:00Z"));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserRepo users;

    @MockitoBean
    private PronunciationFixRepo fixes;

    @MockitoBean
    private PronunciationFixService service;

    /** Signs in {@link #ACTIVE} unless a test says otherwise. */
    @BeforeEach
    void signIn() {
        when(users.findOrProvision(SUBJECT)).thenReturn(ACTIVE);
    }

    /**
     * A user with this status and the subject the test token carries.
     *
     * @param status the account status
     * @return the user
     */
    private static User user(UserStatus status) {
        return new User(
                UUID.randomUUID(),
                SUBJECT,
                Role.CONTRIBUTOR,
                status,
                ZoneId.of("UTC"),
                Optional.empty(),
                Optional.empty(),
                false);
    }

    /**
     * {@code request} as the signed-in test user, with a JSON body.
     *
     * @param request the request
     * @param json the body
     * @return the signed-in request
     */
    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request, String json) {
        return request.with(jwt().jwt(token -> token.subject(SUBJECT)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    @Test
    void meDescribesTheSignedInVolunteer() throws Exception {
        mvc.perform(get("/me").with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ACTIVE.id().toString()))
                .andExpect(jsonPath("$.role").value("contributor"))
                .andExpect(jsonPath("$.timezone").value("UTC"));
    }

    @Test
    void aSuspendedVolunteerIsRefused() throws Exception {
        when(users.findOrProvision(SUBJECT)).thenReturn(user(UserStatus.SUSPENDED));

        mvc.perform(get("/me").with(jwt().jwt(token -> token.subject(SUBJECT)))).andExpect(status().isForbidden());
    }

    @Test
    void aSuggestionIsStored() throws Exception {
        when(service.submit("kmr", "AB", "Yekîtiya Ewropayê", Optional.of("abbreviation"), ACTIVE.id()))
                .thenReturn(Result.ok(FIX));

        mvc.perform(signedIn(
                        post("/pronunciation-fixes"),
                        "{\"language\":\"kmr\",\"written\":\"AB\",\"say\":\"Yekîtiya Ewropayê\","
                                + "\"note\":\"abbreviation\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(FIX.id().toString()))
                .andExpect(jsonPath("$.sayKind").value("respelling"))
                .andExpect(jsonPath("$.say").value("Yekîtiya Ewropayê"))
                .andExpect(jsonPath("$.note").value("abbreviation"))
                .andExpect(jsonPath("$.submitter").doesNotExist());
    }

    @Test
    void aSuggestionThatBreaksTheLexiconRulesSaysWhatToCorrect() throws Exception {
        when(service.submit("", "", "", Optional.empty(), ACTIVE.id()))
                .thenReturn(Result.err(new LexiconRowError.EmptyWritten()));

        mvc.perform(signedIn(post("/pronunciation-fixes"), "{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("empty-written"));
    }

    @Test
    void aSuggestionForAnUnknownLanguageIsRefused() throws Exception {
        when(service.submit(eq("xx"), anyString(), anyString(), any(), eq(ACTIVE.id())))
                .thenThrow(new DataIntegrityViolationException("fk"));

        mvc.perform(signedIn(post("/pronunciation-fixes"), "{\"language\":\"xx\",\"written\":\"a\",\"say\":\"b\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("unknown-language"));
    }

    @Test
    void theNextSuggestionToReviewHidesWhoMadeIt() throws Exception {
        when(service.nextForReview(ACTIVE.id())).thenReturn(Optional.of(FIX));

        mvc.perform(get("/pronunciation-fixes/next").with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value("AB"))
                .andExpect(jsonPath("$.submitter").doesNotExist());
    }

    @Test
    void anEmptyReviewQueueAnswersNoContent() throws Exception {
        when(service.nextForReview(ACTIVE.id())).thenReturn(Optional.empty());

        mvc.perform(get("/pronunciation-fixes/next").with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isNoContent());
    }

    @Test
    void aVerdictIsRecorded() throws Exception {
        when(fixes.find(FIX.id())).thenReturn(Optional.of(FIX));
        when(service.review(FIX, ACTIVE.id(), ReviewVerdict.APPROVED, Optional.empty()))
                .thenReturn(Result.ok(null));

        mvc.perform(signedIn(post("/pronunciation-fixes/" + FIX.id() + "/review"), "{\"verdict\":\"APPROVED\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void reviewingOnesOwnSuggestionIsForbidden() throws Exception {
        when(fixes.find(FIX.id())).thenReturn(Optional.of(FIX));
        when(service.review(FIX, ACTIVE.id(), ReviewVerdict.REJECTED, Optional.of("no")))
                .thenReturn(Result.err(new ReviewError.OwnSubmission()));

        mvc.perform(signedIn(
                        post("/pronunciation-fixes/" + FIX.id() + "/review"),
                        "{\"verdict\":\"REJECTED\",\"comment\":\"no\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("own-submission"));
    }

    @Test
    void aSuggestionAlreadyDecidedIsAConflict() throws Exception {
        when(fixes.find(FIX.id())).thenReturn(Optional.of(FIX));
        when(service.review(FIX, ACTIVE.id(), ReviewVerdict.APPROVED, Optional.empty()))
                .thenReturn(Result.err(new ReviewError.AlreadyDecided()));

        mvc.perform(signedIn(post("/pronunciation-fixes/" + FIX.id() + "/review"), "{\"verdict\":\"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("already-decided"));
    }

    @Test
    void reviewingAnUnknownSuggestionIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(fixes.find(unknown)).thenReturn(Optional.empty());

        mvc.perform(signedIn(post("/pronunciation-fixes/" + unknown + "/review"), "{\"verdict\":\"APPROVED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aReviewWithoutAVerdictIsRefused() throws Exception {
        mvc.perform(signedIn(post("/pronunciation-fixes/" + FIX.id() + "/review"), "{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("missing-verdict"));
    }

    @Test
    void everyLexiconRuleHasAMessage() {
        java.util.List<LexiconRowError> errors = java.util.List.of(
                new LexiconRowError.EmptyWritten(),
                new LexiconRowError.NotOneWord("a b"),
                new LexiconRowError.EmptySay(),
                new LexiconRowError.UnclosedPhonemes(),
                new LexiconRowError.EmptyPhonemes(),
                new LexiconRowError.ForbiddenCharacters("!"));

        org.assertj.core.api.Assertions.assertThat(errors.stream()
                        .map(PronunciationFixController::problem)
                        .map(PronunciationFixController.Problem::error)
                        .distinct())
                .hasSize(errors.size());
    }

    @Test
    void phonemesAreShownAsPhonemes() {
        PronunciationFix phonemes = new PronunciationFix(
                FIX.id(),
                "kmr",
                "AB",
                new PronunciationSay.Phonemes("ab"),
                Optional.empty(),
                FIX.submitter(),
                Instant.EPOCH);

        org.assertj.core.api.Assertions.assertThat(PronunciationFixController.view(phonemes))
                .extracting(PronunciationFixController.FixView::sayKind, PronunciationFixController.FixView::note)
                .containsExactly("phonemes", null);
    }
}
