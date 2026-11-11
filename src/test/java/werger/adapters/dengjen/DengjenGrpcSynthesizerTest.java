package werger.adapters.dengjen;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import werger.domain.Speech;
import werger.ports.Result;
import werger.ports.SynthesisError;

class DengjenGrpcSynthesizerTest {
    private static final DengjenProto.Voice VOICE = new DengjenProto.Voice("kmr-voice", 22050, 1, 2);

    private final List<String> loaded = new ArrayList<>();
    private final List<DengjenProto.Utterance> spoken = new ArrayList<>();
    private final AtomicInteger forgetVoiceTimes = new AtomicInteger();
    private Status synthesisFailure;
    private Server server;
    private ManagedChannel channel;

    /** Stops the in-process engine. */
    @AfterEach
    void stop() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    /**
     * Starts an in-process engine that records calls and streams two chunks per utterance.
     *
     * @return a synthesizer connected to it
     * @throws IOException if the server cannot start
     */
    private DengjenGrpcSynthesizer engine() throws IOException {
        String name = InProcessServerBuilder.generateName();
        ServerServiceDefinition service = ServerServiceDefinition.builder("dengjen_grpc.DengjenGrpc")
                .addMethod(DengjenProto.LOAD_VOICE, ServerCalls.asyncUnaryCall((path, response) -> {
                    loaded.add(path);
                    response.onNext(VOICE);
                    response.onCompleted();
                }))
                .addMethod(DengjenProto.SYNTHESIZE, ServerCalls.asyncServerStreamingCall((utterance, response) -> {
                    spoken.add(utterance);
                    if (forgetVoiceTimes.getAndDecrement() > 0) {
                        response.onError(Status.NOT_FOUND
                                .withDescription("voice not loaded")
                                .asRuntimeException());
                    } else if (synthesisFailure != null) {
                        response.onError(synthesisFailure.asRuntimeException());
                    } else {
                        response.onNext(new byte[] {1, 2});
                        response.onNext(new byte[] {3});
                        response.onCompleted();
                    }
                }))
                .build();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return new DengjenGrpcSynthesizer(channel, " /voices/kmr.onnx.json ", Duration.ofSeconds(5));
    }

    @Test
    void speechJoinsTheStreamedChunksInTheVoicesFormat() throws IOException {
        Result<SynthesisError, Speech> result = engine().speak("Silav");

        assertThat(result).isEqualTo(Result.ok(new Speech(new byte[] {1, 2, 3}, 22050, 1, 2)));
        assertThat(spoken).containsExactly(new DengjenProto.Utterance("kmr-voice", "Silav", DengjenProto.MODE_LAZY));
        assertThat(loaded).containsExactly("/voices/kmr.onnx.json");
    }

    @Test
    void theVoiceIsLoadedOnce() throws IOException {
        DengjenGrpcSynthesizer synthesizer = engine();

        synthesizer.speak("yek");
        synthesizer.speak("du");

        assertThat(loaded).hasSize(1);
    }

    @Test
    void aVoiceTheEngineForgotIsLoadedAgain() throws IOException {
        DengjenGrpcSynthesizer synthesizer = engine();
        synthesizer.speak("yek");
        forgetVoiceTimes.set(1);

        assertThat(synthesizer.speak("du")).isInstanceOf(Result.Ok.class);
        assertThat(loaded).hasSize(2);
    }

    @Test
    void aVoiceForgottenTwiceIsReportedUnavailable() throws IOException {
        DengjenGrpcSynthesizer synthesizer = engine();
        forgetVoiceTimes.set(2);

        assertThat(synthesizer.speak("yek")).isInstanceOf(Result.Err.class);
    }

    @Test
    void textTheEngineRefusesIsRejected() throws IOException {
        DengjenGrpcSynthesizer synthesizer = engine();
        synthesisFailure = Status.INVALID_ARGUMENT.withDescription("text too long");

        assertThat(synthesizer.speak("yek"))
                .isEqualTo(Result.err(new SynthesisError.Rejected("INVALID_ARGUMENT: text too long")));
    }

    @Test
    void aBusyEngineIsUnavailable() throws IOException {
        DengjenGrpcSynthesizer synthesizer = engine();
        synthesisFailure = Status.RESOURCE_EXHAUSTED;

        assertThat(synthesizer.speak("yek"))
                .isEqualTo(Result.err(new SynthesisError.Unavailable("RESOURCE_EXHAUSTED")));
    }

    @Test
    void anEngineThatIsNotRunningIsUnavailable() {
        ManagedChannel nowhere =
                InProcessChannelBuilder.forName("no-such-engine").build();
        try {
            assertThat(new DengjenGrpcSynthesizer(nowhere, "/voices/kmr.onnx.json", Duration.ofSeconds(5)).speak("yek"))
                    .isInstanceOf(Result.Err.class)
                    .extracting(result -> ((Result.Err<SynthesisError, Speech>) result).error())
                    .isInstanceOf(SynthesisError.Unavailable.class);
        } finally {
            nowhere.shutdownNow();
        }
    }

    @Test
    void withoutAVoiceThereIsNoSpeech() {
        assertThat(new DengjenGrpcSynthesizer(channel, "  ", Duration.ofSeconds(5)).speak("yek"))
                .isEqualTo(Result.err(new SynthesisError.Unavailable("no voice is configured")));
    }
}
