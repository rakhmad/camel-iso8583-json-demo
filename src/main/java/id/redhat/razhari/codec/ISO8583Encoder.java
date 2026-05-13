package id.redhat.razhari.codec;

import com.solab.iso8583.IsoMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class ISO8583Encoder extends MessageToByteEncoder<IsoMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, IsoMessage msg, ByteBuf out) throws Exception {
        byte[] payload = msg.writeData();
        out.writeShort(payload.length);
        out.writeBytes(payload);
    }
}
