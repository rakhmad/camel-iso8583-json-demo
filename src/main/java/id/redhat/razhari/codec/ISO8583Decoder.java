package id.redhat.razhari.codec;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.MessageFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Netty inbound handler: reads a length-prefixed ISO 8583 frame from the TCP stream
 * and delegates field parsing to j8583's MessageFactory.
 *
 * Frame format (agreed with payment switch):
 *   [2 bytes unsigned big-endian length][N bytes ISO 8583 payload]
 *
 * ByteToMessageDecoder is NOT @Sharable — a new instance must be created per channel.
 * This is enforced by ISO8583ServerInitializer and ISO8583ClientInitializer which
 * call new ISO8583Decoder(factory) inside ChannelInitializer.initChannel().
 *
 * isoOffset=0 is passed to parseMessage() because the j8583.xml config omits the
 * {@code <header>} element — there is no additional header beyond the Netty length prefix.
 */
public class ISO8583Decoder extends ByteToMessageDecoder {

    private final MessageFactory<IsoMessage> factory;

    public ISO8583Decoder(MessageFactory<IsoMessage> factory) {
        this.factory = factory;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 2) return;            // wait for length header
        int length = in.getUnsignedShort(in.readerIndex());
        if (in.readableBytes() < length + 2) return;  // wait for full frame
        in.skipBytes(2);
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        out.add(factory.parseMessage(bytes, 0));       // isoOffset=0: no extra header bytes
    }
}
