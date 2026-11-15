package werger.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Synthesized audio as signed little-endian PCM, as the engine returns it.
 *
 * @param pcm the samples
 * @param sampleRate samples per second
 * @param channels interleaved channels
 * @param sampleWidth bytes per sample
 */
public record Speech(byte[] pcm, int sampleRate, int channels, int sampleWidth) {
    private static final int HEADER_BYTES = 44;

    /** Copies {@code pcm} so the record stays immutable. */
    public Speech {
        pcm = pcm.clone();
    }

    /**
     * The samples.
     *
     * @return a copy of the PCM bytes
     */
    @Override
    public byte[] pcm() {
        return pcm.clone();
    }

    /**
     * The audio as a WAV file, which every browser plays.
     *
     * @return a RIFF/WAVE file holding the PCM
     */
    public byte[] wav() {
        int byteRate = sampleRate * channels * sampleWidth;
        return ByteBuffer.allocate(HEADER_BYTES + pcm.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(ascii("RIFF"))
                .putInt(HEADER_BYTES - 8 + pcm.length)
                .put(ascii("WAVE"))
                .put(ascii("fmt "))
                .putInt(16)
                .putShort((short) 1)
                .putShort((short) channels)
                .putInt(sampleRate)
                .putInt(byteRate)
                .putShort((short) (channels * sampleWidth))
                .putShort((short) (sampleWidth * 8))
                .put(ascii("data"))
                .putInt(pcm.length)
                .put(pcm)
                .array();
    }

    /**
     * Records compare arrays by reference; speech is equal when its audio is.
     *
     * @param other the object to compare
     * @return whether both hold the same audio
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Speech(byte[] otherPcm, int rate, int otherChannels, int width)
                && Arrays.equals(pcm, otherPcm)
                && sampleRate == rate
                && channels == otherChannels
                && sampleWidth == width;
    }

    /**
     * A hash consistent with {@link #equals(Object)}.
     *
     * @return the hash
     */
    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(pcm) + java.util.Objects.hash(sampleRate, channels, sampleWidth);
    }

    /**
     * A short description without the samples.
     *
     * @return the format and length
     */
    @Override
    public String toString() {
        return "Speech[" + pcm.length + " bytes, " + sampleRate + " Hz, " + channels + " ch, " + sampleWidth + " B]";
    }

    /**
     * The bytes of an ASCII tag.
     *
     * @param tag four ASCII characters
     * @return their bytes
     */
    private static byte[] ascii(String tag) {
        return tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
