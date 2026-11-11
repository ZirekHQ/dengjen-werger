package werger.adapters.dengjen;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import werger.domain.Speech;
import werger.ports.Result;
import werger.ports.SpeechSynthesizer;
import werger.ports.SynthesisError;

/**
 * Speaks through a {@code dengjen-tts-grpc} server running beside Werger. The voice is loaded on first use and loaded
 * again if the server restarts and forgets it.
 */
public class DengjenGrpcSynthesizer implements SpeechSynthesizer {
    private final Channel channel;
    private final String voiceConfig;
    private final Duration deadline;
    private Optional<DengjenProto.Voice> voice = Optional.empty();

    /**
     * Speaks through {@code channel} with the voice whose config is at {@code voiceConfig} on the server's machine.
     *
     * @param channel the connection to the engine
     * @param voiceConfig the voice config path as the server sees it; blank when no voice is configured
     * @param deadline how long one call may take
     */
    public DengjenGrpcSynthesizer(Channel channel, String voiceConfig, Duration deadline) {
        this.channel = channel;
        this.voiceConfig = voiceConfig.strip();
        this.deadline = deadline;
    }

    /**
     * Speaks {@code text}, loading the voice first if needed.
     *
     * @param text what to say
     * @return the audio, or why there is none
     */
    @Override
    public Result<SynthesisError, Speech> speak(String text) {
        if (voiceConfig.isEmpty()) {
            return Result.err(new SynthesisError.Unavailable("no voice is configured"));
        }
        try {
            return Result.ok(speakWith(loadedVoice(false), text));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                return Result.err(error(e.getStatus()));
            }
        }
        try {
            return Result.ok(speakWith(loadedVoice(true), text));
        } catch (StatusRuntimeException e) {
            return Result.err(error(e.getStatus()));
        }
    }

    /**
     * The loaded voice, loading it when there is none or {@code reload} is set.
     *
     * @param reload whether the server forgot the voice
     * @return the voice
     */
    private synchronized DengjenProto.Voice loadedVoice(boolean reload) {
        if (reload || voice.isEmpty()) {
            voice = Optional.of(
                    ClientCalls.blockingUnaryCall(channel, DengjenProto.LOAD_VOICE, options(), voiceConfig));
        }
        return voice.get();
    }

    /**
     * Synthesizes {@code text} with {@code loaded} and joins the streamed chunks.
     *
     * @param loaded the voice
     * @param text what to say
     * @return the audio
     */
    private Speech speakWith(DengjenProto.Voice loaded, String text) {
        Iterator<byte[]> chunks = ClientCalls.blockingServerStreamingCall(
                channel,
                DengjenProto.SYNTHESIZE,
                options(),
                new DengjenProto.Utterance(loaded.key(), text, DengjenProto.MODE_LAZY));
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        chunks.forEachRemaining(pcm::writeBytes);
        return new Speech(pcm.toByteArray(), loaded.sampleRate(), loaded.channels(), loaded.sampleWidth());
    }

    /**
     * Call options with this synthesizer's deadline.
     *
     * @return the options
     */
    private CallOptions options() {
        return CallOptions.DEFAULT.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Whether retrying later could help.
     *
     * @param status the server's answer
     * @return the error
     */
    static SynthesisError error(Status status) {
        String detail = status.getCode()
                + Optional.ofNullable(status.getDescription())
                        .map(description -> ": " + description)
                        .orElse("");
        return switch (status.getCode()) {
            case INVALID_ARGUMENT, FAILED_PRECONDITION, OUT_OF_RANGE, UNIMPLEMENTED ->
                new SynthesisError.Rejected(detail);
            default -> new SynthesisError.Unavailable(detail);
        };
    }
}
