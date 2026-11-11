package werger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SpeechTest {
    private static final Speech SPEECH = new Speech(new byte[] {1, 0, 2, 0}, 22050, 1, 2);

    @Test
    void theWavFileDescribesThePcm() {
        ByteBuffer wav = ByteBuffer.wrap(SPEECH.wav()).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(new String(SPEECH.wav(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(wav.getInt(4)).isEqualTo(40);
        assertThat(new String(SPEECH.wav(), 8, 8, StandardCharsets.US_ASCII)).isEqualTo("WAVEfmt ");
        assertThat(wav.getShort(20)).isEqualTo((short) 1);
        assertThat(wav.getShort(22)).isEqualTo((short) 1);
        assertThat(wav.getInt(24)).isEqualTo(22050);
        assertThat(wav.getInt(28)).isEqualTo(44100);
        assertThat(wav.getShort(32)).isEqualTo((short) 2);
        assertThat(wav.getShort(34)).isEqualTo((short) 16);
        assertThat(new String(SPEECH.wav(), 36, 4, StandardCharsets.US_ASCII)).isEqualTo("data");
        assertThat(wav.getInt(40)).isEqualTo(4);
        assertThat(SPEECH.wav()).hasSize(48).endsWith(1, 0, 2, 0);
    }

    @Test
    void speechIsEqualWhenItsAudioIs() {
        assertThat(new Speech(new byte[] {1, 0, 2, 0}, 22050, 1, 2))
                .isEqualTo(SPEECH)
                .hasSameHashCodeAs(SPEECH)
                .isNotEqualTo(new Speech(new byte[] {1, 0, 2, 0}, 16000, 1, 2))
                .isNotEqualTo(new Speech(new byte[] {1, 0, 2, 0}, 22050, 2, 2))
                .isNotEqualTo(new Speech(new byte[] {1, 0, 2, 0}, 22050, 1, 1))
                .isNotEqualTo(new Speech(new byte[] {1}, 22050, 1, 2))
                .isNotEqualTo("speech");
        assertThat(SPEECH.toString()).isEqualTo("Speech[4 bytes, 22050 Hz, 1 ch, 2 B]");
    }

    @Test
    void theSamplesCannotBeChangedFromOutside() {
        byte[] pcm = {1, 2};
        Speech speech = new Speech(pcm, 8000, 1, 1);
        pcm[0] = 9;
        speech.pcm()[1] = 9;

        assertThat(speech.pcm()).containsExactly(1, 2);
    }
}
