package id.redhat.razhari.codec;

import id.redhat.razhari.config.MessageFactoryProducer;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ISO8583CodecTest {

    static MessageFactory<IsoMessage> factory;

    @BeforeAll
    static void init() throws Exception {
        factory = new MessageFactoryProducer().messageFactory();
    }

    @Test
    void encoderWritesTwoByteLengthPrefixThenPayload() throws Exception {
        IsoMessage msg = factory.newMessage(0x0200);
        msg.setValue(2, "4111111111111111", IsoType.LLVAR, 0);
        msg.setValue(11, "000001", IsoType.NUMERIC, 6);

        EmbeddedChannel ch = new EmbeddedChannel(new ISO8583Encoder());
        ch.writeOutbound(msg);
        ByteBuf buf = ch.readOutbound();

        int length = buf.readUnsignedShort();
        assertThat(buf.readableBytes()).isEqualTo(length);
    }

    @Test
    void decoderReconstructsISOMessage() throws Exception {
        IsoMessage original = factory.newMessage(0x0200);
        original.setValue(2, "4111111111111111", IsoType.LLVAR, 0);
        original.setValue(11, "000042", IsoType.NUMERIC, 6);

        // Encode first
        EmbeddedChannel encoder = new EmbeddedChannel(new ISO8583Encoder());
        encoder.writeOutbound(original);
        ByteBuf encoded = encoder.readOutbound();

        // Decode
        EmbeddedChannel decoder = new EmbeddedChannel(new ISO8583Decoder(factory));
        decoder.writeInbound(encoded);
        IsoMessage decoded = decoder.readInbound();

        assertThat(decoded).isNotNull();
        assertThat(decoded.getType()).isEqualTo(0x0200);
        assertThat(decoded.getField(11).toString()).isEqualTo("000042");
    }

    @Test
    void decoderWaitsForFullFrame() throws Exception {
        IsoMessage original = factory.newMessage(0x0200);
        original.setValue(11, "000001", IsoType.NUMERIC, 6);

        EmbeddedChannel encoder = new EmbeddedChannel(new ISO8583Encoder());
        encoder.writeOutbound(original);
        ByteBuf full = encoder.readOutbound();

        // Send only first byte — decoder must not produce output yet
        EmbeddedChannel decoder = new EmbeddedChannel(new ISO8583Decoder(factory));
        decoder.writeInbound(full.readSlice(1).retain());
        assertThat((Object) decoder.readInbound()).isNull();

        // Send the rest — now it should decode
        decoder.writeInbound(full);
        assertThat((Object) decoder.readInbound()).isNotNull();
    }
}
