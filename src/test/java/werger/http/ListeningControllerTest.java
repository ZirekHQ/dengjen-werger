package werger.http;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import werger.adapters.db.UserRepo;
import werger.domain.Role;
import werger.domain.Speech;
import werger.domain.User;
import werger.domain.UserStatus;
import werger.ports.Result;
import werger.ports.SynthesisError;
import werger.service.ListeningService;

@SpringBootTest
@AutoConfigureMockMvc
class ListeningControllerTest {
    private static final String SUBJECT = "supabase-user-2";
    private static final Speech SPEECH = new Speech(new byte[] {1, 0}, 8000, 1, 2);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserRepo users;

    @MockitoBean
    private ListeningService listening;

    /** Signs in an active contributor. */
    @BeforeEach
    void signIn() {
        when(users.findOrProvision(SUBJECT))
                .thenReturn(new User(
                        UUID.randomUUID(),
                        SUBJECT,
                        Role.CONTRIBUTOR,
                        UserStatus.ACTIVE,
                        ZoneId.of("UTC"),
                        Optional.empty(),
                        Optional.empty(),
                        false));
    }

    @Test
    void theSentencesAreListedWithTheirPositions() throws Exception {
        when(listening.sentences("kmr")).thenReturn(List.of("Yek.", "Du."));

        mvc.perform(get("/listening/sentences").with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].index").value(1))
                .andExpect(jsonPath("$[1].text").value("Du."));
    }

    @Test
    void aSentenceIsServedAsWav() throws Exception {
        when(listening.sentenceAudio("kmr", 0)).thenReturn(Optional.of(Result.ok(SPEECH)));

        mvc.perform(get("/listening/sentences/0/audio").with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/wav"))
                .andExpect(content().bytes(SPEECH.wav()));
    }

    @Test
    void anUnknownSentenceIsNotFound() throws Exception {
        when(listening.sentenceAudio("kmr", 99)).thenReturn(Optional.empty());

        mvc.perform(get("/listening/sentences/99/audio").with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnavailableEngineAnswersServiceUnavailable() throws Exception {
        when(listening.sentenceAudio("kmr", 0))
                .thenReturn(Optional.of(Result.err(new SynthesisError.Unavailable("down"))));

        mvc.perform(get("/listening/sentences/0/audio").with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("speech-unavailable"));
    }

    @Test
    void aPreviewIsSpokenOrRejected() throws Exception {
        when(listening.preview("Yekîtî")).thenReturn(Result.ok(SPEECH));
        when(listening.preview("")).thenReturn(Result.err(new SynthesisError.Rejected("type something")));

        mvc.perform(post("/listening/preview")
                        .with(jwt().jwt(token -> token.subject(SUBJECT)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Yekîtî\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/wav"));
        mvc.perform(post("/listening/preview")
                        .with(jwt().jwt(token -> token.subject(SUBJECT)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("type something"));
    }

    @Test
    void listeningNeedsSignIn() throws Exception {
        mvc.perform(get("/listening/sentences")).andExpect(status().isUnauthorized());
    }
}
