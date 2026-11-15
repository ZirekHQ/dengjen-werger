package werger.adapters.dengjen;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import io.grpc.MethodDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The few messages of dengjen's {@code dengjen_grpc.proto} that Werger uses, encoded by hand. Generating the whole
 * service would add a protoc build step for three messages; the field numbers below are the proto's.
 */
final class DengjenProto {
    /** {@code SynthesisMode.MODE_LAZY}: one sentence at a time, which is all a test sentence needs. */
    static final int MODE_LAZY = 1;

    /** {@code LoadVoice(VoiceConfigLocation) returns (VoiceDescriptor)}. */
    static final MethodDescriptor<String, Voice> LOAD_VOICE = MethodDescriptor.<String, Voice>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("dengjen_grpc.DengjenGrpc/LoadVoice")
            .setRequestMarshaller(marshaller(DengjenProto::encodeVoiceLocation, DengjenProto::decodeVoiceLocation))
            .setResponseMarshaller(marshaller(DengjenProto::encodeVoice, DengjenProto::decodeVoice))
            .build();

    /** {@code SynthesizeUtterance(SynthesisRequest) returns (stream SynthesisChunk)}. */
    static final MethodDescriptor<Utterance, byte[]> SYNTHESIZE = MethodDescriptor.<Utterance, byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName("dengjen_grpc.DengjenGrpc/SynthesizeUtterance")
            .setRequestMarshaller(marshaller(DengjenProto::encodeUtterance, DengjenProto::decodeUtterance))
            .setResponseMarshaller(marshaller(DengjenProto::encodeChunk, DengjenProto::decodeChunk))
            .build();

    /**
     * A loaded voice: {@code VoiceDescriptor.voice_key} and its {@code AudioFormat}.
     *
     * @param key the voice key
     * @param sampleRate samples per second
     * @param channels interleaved channels
     * @param sampleWidth bytes per sample
     */
    record Voice(String key, int sampleRate, int channels, int sampleWidth) {}

    /**
     * A {@code SynthesisRequest} without prosody.
     *
     * @param voiceKey the loaded voice
     * @param text what to say
     * @param mode a {@code SynthesisMode} number
     */
    record Utterance(String voiceKey, String text, int mode) {}

    private DengjenProto() {}

    /** Writes one message's fields. */
    @FunctionalInterface
    interface Writer<T> {
        /**
         * Writes {@code value}'s fields.
         *
         * @param value the message
         * @param out where to write
         * @throws IOException if writing fails
         */
        void write(T value, CodedOutputStream out) throws IOException;
    }

    /** Reads one message. */
    @FunctionalInterface
    private interface Reader<T> {
        /**
         * Reads a message.
         *
         * @param in the encoded message
         * @return the message
         * @throws IOException if the bytes are not a valid message
         */
        T read(CodedInputStream in) throws IOException;
    }

    /**
     * A gRPC marshaller from a writer and a reader.
     *
     * @param writer encodes a message
     * @param reader decodes a message
     * @param <T> the message type
     * @return the marshaller
     */
    private static <T> MethodDescriptor.Marshaller<T> marshaller(Writer<T> writer, Reader<T> reader) {
        return new MethodDescriptor.Marshaller<>() {
            @Override
            public InputStream stream(T value) {
                return new ByteArrayInputStream(encode(value, writer));
            }

            @Override
            public T parse(InputStream stream) {
                try {
                    return reader.read(CodedInputStream.newInstance(stream));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    /**
     * Encodes a message to bytes.
     *
     * @param value the message
     * @param writer its encoder
     * @param <T> the message type
     * @return the encoded message
     */
    static <T> byte[] encode(T value, Writer<T> writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            writer.write(value, out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code VoiceConfigLocation { string path = 1; }}.
     *
     * @param path the voice config path on the engine's machine
     * @param out where to write
     * @throws IOException if writing fails
     */
    static void encodeVoiceLocation(String path, CodedOutputStream out) throws IOException {
        out.writeString(1, path);
    }

    /**
     * Reads a {@code VoiceConfigLocation}.
     *
     * @param in the encoded message
     * @return the path
     * @throws IOException if the bytes are not a valid message
     */
    static String decodeVoiceLocation(CodedInputStream in) throws IOException {
        String path = "";
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            if (WireFormat.getTagFieldNumber(tag) == 1) {
                path = in.readStringRequireUtf8();
            } else {
                in.skipField(tag);
            }
        }
        return path;
    }

    /**
     * Writes a {@code VoiceDescriptor} with its key (1) and audio format (4).
     *
     * @param voice the voice
     * @param out where to write
     * @throws IOException if writing fails
     */
    static void encodeVoice(Voice voice, CodedOutputStream out) throws IOException {
        out.writeString(1, voice.key());
        out.writeTag(4, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        byte[] format = encode(voice, (v, o) -> {
            o.writeUInt32(1, v.sampleRate());
            o.writeUInt32(2, v.channels());
            o.writeUInt32(3, v.sampleWidth());
        });
        out.writeUInt32NoTag(format.length);
        out.writeRawBytes(format);
    }

    /**
     * Reads a {@code VoiceDescriptor}'s key and audio format, skipping the other fields.
     *
     * @param in the encoded message
     * @return the voice
     * @throws IOException if the bytes are not a valid message
     */
    static Voice decodeVoice(CodedInputStream in) throws IOException {
        String key = "";
        int[] format = {0, 0, 0};
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> key = in.readStringRequireUtf8();
                case 4 -> {
                    int limit = in.pushLimit(in.readRawVarint32());
                    format = decodeAudioFormat(in);
                    in.popLimit(limit);
                }
                default -> in.skipField(tag);
            }
        }
        return new Voice(key, format[0], format[1], format[2]);
    }

    /**
     * Reads an {@code AudioFormat}: sample rate (1), channels (2) and sample width (3).
     *
     * @param in the encoded message, limited to its length
     * @return the three fields in field order, zero when absent
     * @throws IOException if the bytes are not a valid message
     */
    private static int[] decodeAudioFormat(CodedInputStream in) throws IOException {
        int[] format = {0, 0, 0};
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field >= 1 && field <= 3 && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_VARINT) {
                format[field - 1] = in.readUInt32();
            } else {
                in.skipField(tag);
            }
        }
        return format;
    }

    /**
     * Writes a {@code SynthesisRequest}: voice key (1), text (2) and mode (4).
     *
     * @param utterance the request
     * @param out where to write
     * @throws IOException if writing fails
     */
    static void encodeUtterance(Utterance utterance, CodedOutputStream out) throws IOException {
        out.writeString(1, utterance.voiceKey());
        out.writeString(2, utterance.text());
        out.writeEnum(4, utterance.mode());
    }

    /**
     * Reads a {@code SynthesisRequest}'s voice key, text and mode.
     *
     * @param in the encoded message
     * @return the request
     * @throws IOException if the bytes are not a valid message
     */
    static Utterance decodeUtterance(CodedInputStream in) throws IOException {
        String voiceKey = "";
        String text = "";
        int mode = 0;
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> voiceKey = in.readStringRequireUtf8();
                case 2 -> text = in.readStringRequireUtf8();
                case 4 -> mode = in.readEnum();
                default -> in.skipField(tag);
            }
        }
        return new Utterance(voiceKey, text, mode);
    }

    /**
     * Writes a {@code SynthesisChunk}'s audio bytes (1).
     *
     * @param audio the PCM bytes
     * @param out where to write
     * @throws IOException if writing fails
     */
    static void encodeChunk(byte[] audio, CodedOutputStream out) throws IOException {
        out.writeBytes(1, ByteString.copyFrom(audio));
    }

    /**
     * Reads a {@code SynthesisChunk}'s audio bytes, skipping the real-time factor.
     *
     * @param in the encoded message
     * @return the PCM bytes
     * @throws IOException if the bytes are not a valid message
     */
    static byte[] decodeChunk(CodedInputStream in) throws IOException {
        byte[] audio = new byte[0];
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            if (WireFormat.getTagFieldNumber(tag) == 1) {
                audio = in.readByteArray();
            } else {
                in.skipField(tag);
            }
        }
        return audio;
    }
}
