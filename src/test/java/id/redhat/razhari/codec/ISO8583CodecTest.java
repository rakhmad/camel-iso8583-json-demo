package id.redhat.razhari.codec;

import id.redhat.razhari.config.MessageFactoryProducer;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals(length, buf.readableBytes());
    }

    @Test
    void decoderReconstructsIsoMessage() throws Exception {
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

        assertNotNull(decoded);
        assertEquals(0x0200, decoded.getType());
        assertEquals("000042", decoded.getField(11).toString());
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
        assertNull(decoder.readInbound());

        // Send the rest — now it should decode
        decoder.writeInbound(full);
        assertNotNull(decoder.readInbound());
    }
}
