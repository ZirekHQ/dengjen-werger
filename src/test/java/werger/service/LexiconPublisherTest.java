package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.adapters.db.PronunciationFixRepo;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationFixStatus;
import werger.domain.PronunciationSay;
import werger.ports.LexiconTarget;
import werger.ports.Result;
import werger.ports.SourceError;

class LexiconPublisherTest {
    private static final String LEXICON = "written\tsay\tnote\nAB\tYekîtiya Ewropayê\n";
    private static final URI PULL_REQUEST = URI.create("https://github.com/ZirekHQ/dengjen/pull/1");

    /** A hand-rolled stub: the query and the status change are proven against real Postgres in PronunciationFixRepoIT. */
    private static final class StubFixes extends PronunciationFixRepo {
        final List<PronunciationFix> approved = new ArrayList<>();
        final Map<UUID, PronunciationFixStatus> settled = new LinkedHashMap<>();

        StubFixes() {
            super(new JdbcTemplate());
        }

        @Override
        public List<PronunciationFix> approvedAwaitingLanding(String language) {
            return approved;
        }

        @Override
        public void settle(UUID id, PronunciationFixStatus status) {
            settled.put(id, status);
        }
    }

    private static final class StubTarget implements LexiconTarget {
        Result<SourceError, Snapshot> current = Result.ok(new Snapshot(LEXICON, "main-sha"));
        Result<SourceError, URI> proposal = Result.ok(PULL_REQUEST);
        final List<String> proposed = new ArrayList<>();
        final List<Snapshot> bases = new ArrayList<>();

        @Override
        public Result<SourceError, Snapshot> current(String language) {
            return current;
        }

        @Override
        public Result<SourceError, URI> propose(String language, Snapshot base, String content, String description) {
            bases.add(base);
            proposed.add(content);
            return proposal;
        }
    }

    private final StubFixes fixes = new StubFixes();
    private final StubTarget target = new StubTarget();
    private final LexiconPublisher publisher = new LexiconPublisher(fixes, target, NoTransactions.template());

    private static PronunciationFix respelling(String written, String say) {
        return LexiconFileTest.fix(written, new PronunciationSay.Respelling(say), Optional.empty());
    }

    @Test
    void aMissingFixIsProposedOnTopOfTheCurrentLexicon() {
        PronunciationFix fix = respelling("NY", "Neteweyên Yekbûyî");
        fixes.approved.add(fix);

        assertThat(publisher.publish("kmr"))
                .isEqualTo(Result.ok(new LexiconPublication.Proposed(PULL_REQUEST, List.of(fix.id()))));
        assertThat(target.proposed).containsExactly(LEXICON + "NY\tNeteweyên Yekbûyî\n");
        assertThat(target.bases).containsExactly(new LexiconTarget.Snapshot(LEXICON, "main-sha"));
        assertThat(fixes.settled).isEmpty();
    }

    @Test
    void aFixWhoseRowIsAlreadyInTheLexiconLandsAndNothingIsProposed() {
        PronunciationFix fix = respelling("AB", "Yekîtiya Ewropayê");
        fixes.approved.add(fix);

        assertThat(publisher.publish("kmr")).isEqualTo(Result.ok(new LexiconPublication.UpToDate()));
        assertThat(fixes.settled).containsExactly(Map.entry(fix.id(), PronunciationFixStatus.LANDED));
        assertThat(target.proposed).isEmpty();
    }

    @Test
    void aLaterApprovalForTheSameWordSupersedesTheEarlierOne() {
        PronunciationFix earlier = respelling("NY", "Netewe");
        PronunciationFix later = respelling("NY", "Neteweyên Yekbûyî");
        fixes.approved.addAll(List.of(earlier, later));

        assertThat(publisher.publish("kmr"))
                .isEqualTo(Result.ok(new LexiconPublication.Proposed(PULL_REQUEST, List.of(later.id()))));
        assertThat(fixes.settled).containsExactly(Map.entry(earlier.id(), PronunciationFixStatus.SUPERSEDED));
        assertThat(target.proposed).containsExactly(LEXICON + "NY\tNeteweyên Yekbûyî\n");
    }

    @Test
    void anEarlierFixAlreadyInTheLexiconLandsWhenALaterOneReplacesIt() {
        PronunciationFix earlier = respelling("AB", "Yekîtiya Ewropayê");
        PronunciationFix later = respelling("AB", "Ewropa");
        fixes.approved.addAll(List.of(earlier, later));

        assertThat(publisher.publish("kmr"))
                .isEqualTo(Result.ok(new LexiconPublication.Proposed(PULL_REQUEST, List.of(later.id()))));
        assertThat(fixes.settled).containsExactly(Map.entry(earlier.id(), PronunciationFixStatus.LANDED));
        assertThat(target.proposed).containsExactly("written\tsay\tnote\nAB\tEwropa\n");
    }

    @Test
    void anEarlierFixNeverOverwritesALaterOneThatAlreadyLanded() {
        PronunciationFix earlier = respelling("AB", "Ewropa");
        PronunciationFix later = respelling("AB", "Yekîtiya Ewropayê");
        fixes.approved.addAll(List.of(earlier, later));

        assertThat(publisher.publish("kmr")).isEqualTo(Result.ok(new LexiconPublication.UpToDate()));
        assertThat(fixes.settled)
                .containsExactly(
                        Map.entry(earlier.id(), PronunciationFixStatus.SUPERSEDED),
                        Map.entry(later.id(), PronunciationFixStatus.LANDED));
        assertThat(target.proposed).isEmpty();
    }

    @Test
    void anUnreadableLexiconSettlesAndProposesNothing() {
        fixes.approved.add(respelling("AB", "Yekîtiya Ewropayê"));
        target.current = Result.err(new SourceError.Transient("GitHub is down"));

        assertThat(publisher.publish("kmr")).isEqualTo(Result.err(new SourceError.Transient("GitHub is down")));
        assertThat(fixes.settled).isEmpty();
        assertThat(target.proposed).isEmpty();
    }

    @Test
    void aFailedProposalIsReportedAndTheFixStaysApprovedForTheNextRun() {
        fixes.approved.add(respelling("NY", "Neteweyên Yekbûyî"));
        target.proposal = Result.err(new SourceError.Rejected("branch protected"));

        assertThat(publisher.publish("kmr")).isEqualTo(Result.err(new SourceError.Rejected("branch protected")));
        assertThat(fixes.settled).isEmpty();
    }

    @Test
    void theDescriptionFencesTheRowsBeyondAnyBackticksInANote() {
        PronunciationFix fix = LexiconFileTest.fix(
                "AB", new PronunciationSay.Respelling("Ewropa"), Optional.of("not ```` code @someone"));

        assertThat(LexiconPublisher.description(List.of(fix)))
                .contains("`````\nAB\tEwropa\tnot ```` code @someone\n`````\n");
    }

    @Test
    void aDescriptionWithoutBackticksUsesTheShortestFence() {
        assertThat(LexiconPublisher.description(List.of(respelling("AB", "Ewropa"))))
                .endsWith("\n```\nAB\tEwropa\n```\n");
    }
}
