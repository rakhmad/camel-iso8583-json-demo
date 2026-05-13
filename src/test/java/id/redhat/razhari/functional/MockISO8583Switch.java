package id.redhat.razhari.functional;

import id.redhat.razhari.codec.ISO8583Decoder;
import id.redhat.razhari.codec.ISO8583Encoder;
import id.redhat.razhari.config.MessageFactoryProducer;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.io.IOException;

public class MockISO8583Switch {

    private final int port;
    private Channel serverChannel;
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private final MessageFactory<IsoMessage> factory;

    public MockISO8583Switch(int port) throws IOException {
        this.port = port;
        this.factory = new MessageFactoryProducer().messageFactory();
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        MessageFactory<IsoMessage> f = factory;

        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new ISO8583Decoder(f));
                    ch.pipeline().addLast(new ISO8583Encoder());
                    ch.pipeline().addLast(new SimpleChannelInboundHandler<IsoMessage>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, IsoMessage msg)
                                throws Exception {
                            IsoMessage response = f.newMessage(0x0210);
                            if (msg.hasField(11)) {
                                response.setValue(11, msg.getField(11).toString(),
                                    IsoType.NUMERIC, 6);
                            }
                            response.setValue(38, "AUTH01", IsoType.ALPHA, 6);
                            response.setValue(39, "00",     IsoType.ALPHA, 2);
                            ctx.writeAndFlush(response);
                        }
                    });
                }
            });

        serverChannel = bootstrap.bind(port).sync().channel();
    }

    public void stop() {
        if (serverChannel != null) serverChannel.close().syncUninterruptibly();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }
}
