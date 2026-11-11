package werger.adapters.dengjen;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class DengjenProtoTest {
    @Test
    void aVoiceDescriptorKeepsItsKeyAndFormatAndSkipsTheRest() throws IOException {
        byte[] descriptor = DengjenProto.encode(null, (ignored, out) -> {
            out.writeString(1, "kmr");
            out.writeTag(2, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            out.writeUInt32NoTag(0);
            out.writeTag(4, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            out.writeUInt32NoTag(11);
            out.writeUInt32(1, 22050);
            out.writeUInt32(2, 1);
            out.writeUInt32(3, 2);
            out.writeString(9, "x");
            out.writeString(5, "kmr");
            out.writeBool(7, true);
        });

        assertThat(DengjenProto.decodeVoice(CodedInputStream.newInstance(descriptor)))
                .isEqualTo(new DengjenProto.Voice("kmr", 22050, 1, 2));
    }

    @Test
    void messagesSurviveARoundTrip() throws IOException {
        DengjenProto.Voice voice = new DengjenProto.Voice("kmr", 16000, 2, 2);
        DengjenProto.Utterance utterance = new DengjenProto.Utterance("kmr", "Rojbaş", DengjenProto.MODE_LAZY);

        assertThat(DengjenProto.decodeVoice(
                        CodedInputStream.newInstance(DengjenProto.encode(voice, DengjenProto::encodeVoice))))
                .isEqualTo(voice);
        assertThat(DengjenProto.decodeUtterance(
                        CodedInputStream.newInstance(DengjenProto.encode(utterance, DengjenProto::encodeUtterance))))
                .isEqualTo(utterance);
        assertThat(DengjenProto.decodeVoiceLocation(CodedInputStream.newInstance(
                        DengjenProto.encode("/v.onnx.json", DengjenProto::encodeVoiceLocation))))
                .isEqualTo("/v.onnx.json");
        assertThat(DengjenProto.decodeChunk(CodedInputStream.newInstance(
                        DengjenProto.encode(new byte[] {7, 8}, DengjenProto::encodeChunk))))
                .containsExactly(7, 8);
    }

    @Test
    void aChunkSkipsItsRealTimeFactorAndUnknownFieldsAreIgnored() throws IOException {
        byte[] chunk = DengjenProto.encode(null, (ignored, out) -> {
            out.writeFloat(2, 0.5f);
            out.writeBytes(1, com.google.protobuf.ByteString.copyFrom(new byte[] {9}));
        });
        byte[] request = DengjenProto.encode(null, (ignored, out) -> {
            out.writeString(1, "kmr");
            out.writeUInt32(3, 0);
            out.writeString(2, "Silav");
        });
        byte[] location = DengjenProto.encode(null, (ignored, out) -> out.writeUInt32(2, 1));

        assertThat(DengjenProto.decodeChunk(CodedInputStream.newInstance(chunk)))
                .containsExactly(9);
        assertThat(DengjenProto.decodeUtterance(CodedInputStream.newInstance(request)))
                .isEqualTo(new DengjenProto.Utterance("kmr", "Silav", 0));
        assertThat(DengjenProto.decodeVoiceLocation(CodedInputStream.newInstance(location)))
                .isEmpty();
    }

    @Test
    void anAudioFormatFieldOfTheWrongTypeIsSkipped() throws IOException {
        byte[] descriptor = DengjenProto.encode(null, (ignored, out) -> {
            out.writeTag(4, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            out.writeUInt32NoTag(5);
            out.writeFixed32(1, 7);
        });

        assertThat(DengjenProto.decodeVoice(CodedInputStream.newInstance(descriptor)))
                .isEqualTo(new DengjenProto.Voice("", 0, 0, 0));
    }
}
