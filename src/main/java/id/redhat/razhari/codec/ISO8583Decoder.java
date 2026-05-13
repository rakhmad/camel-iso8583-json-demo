package id.redhat.razhari.codec;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.MessageFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class ISO8583Decoder extends ByteToMessageDecoder {

    private final MessageFactory<IsoMessage> factory;

    public ISO8583Decoder(MessageFactory<IsoMessage> factory) {
        this.factory = factory;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 2) return;
        int length = in.getUnsignedShort(in.readerIndex());
        if (in.readableBytes() < length + 2) return;
        in.skipBytes(2);
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        out.add(factory.parseMessage(bytes, 0));
    }
}
