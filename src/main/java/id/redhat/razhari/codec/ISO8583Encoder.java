package id.redhat.razhari.codec;

import com.solab.iso8583.IsoMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Netty outbound handler: serialises an IsoMessage to a length-prefixed TCP frame.
 *
 * Frame format:
 *   [2 bytes unsigned big-endian length][N bytes ISO 8583 payload from IsoMessage.writeData()]
 *
 * The 2-byte length prefix is the contract shared with ISO8583Decoder and the payment switch.
 * It allows the decoder to know exactly how many bytes to wait for before attempting to parse.
 */
public class ISO8583Encoder extends MessageToByteEncoder<IsoMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, IsoMessage msg, ByteBuf out) throws Exception {
        byte[] payload = msg.writeData();
        out.writeShort(payload.length);  // 2-byte length prefix
        out.writeBytes(payload);
    }
}
