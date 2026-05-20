package id.redhat.razhari.demo;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import com.solab.iso8583.parse.ConfigParser;
import id.redhat.razhari.codec.ISO8583Decoder;
import id.redhat.razhari.codec.ISO8583Encoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone mock ISO 8583 payment switch for local demos.
 * Accepts 0200 authorization requests and replies with 0210 approved responses.
 * Run via: ./scripts/mock-switch.sh [port]
 */
public class MockSwitch {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final AtomicInteger txCount = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8583;

        MessageFactory<IsoMessage> factory = new MessageFactory<>();
        factory.setUseBinaryMessages(false);
        factory.setCharacterEncoding("UTF-8");
        factory.setAssignDate(true);
        ConfigParser.configureFromClasspathConfig(factory, "j8583.xml");

        NioEventLoopGroup boss = new NioEventLoopGroup(1);
        NioEventLoopGroup worker = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(boss, worker)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 10)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast(new ISO8583Decoder(factory))
                        .addLast(new ISO8583Encoder())
                        .addLast(new SwitchHandler(factory));
                }
            });

        Channel server = bootstrap.bind(port).sync().channel();
        log("Mock ISO 8583 switch listening on port " + port + "  (Ctrl+C to stop)");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("Shutting down...");
            server.close().syncUninterruptibly();
            worker.shutdownGracefully();
            boss.shutdownGracefully();
        }));

        server.closeFuture().sync();
    }

    static class SwitchHandler extends SimpleChannelInboundHandler<IsoMessage> {

        private final MessageFactory<IsoMessage> factory;

        SwitchHandler(MessageFactory<IsoMessage> factory) {
            this.factory = factory;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            log("Connected: " + ctx.channel().remoteAddress());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log("Disconnected: " + ctx.channel().remoteAddress());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, IsoMessage msg) throws Exception {
            int n = txCount.incrementAndGet();
            String stan = msg.hasField(11) ? msg.getField(11).toString() : "??????";
            String pan  = msg.hasField(2)  ? mask(msg.getField(2).toString()) : "???";
            String amt  = msg.hasField(4)  ? msg.getField(4).toString() : "???";
            log(String.format("#%d  ← 0200  STAN=%-6s  PAN=%-20s  Amount=%s", n, stan, pan, amt));

            String authCode = String.format("AUTH%02d", n % 100);
            IsoMessage response = factory.newMessage(0x0210);
            if (msg.hasField(11)) {
                response.setValue(11, msg.getField(11).toString(), IsoType.NUMERIC, 6);
            }
            response.setValue(38, authCode, IsoType.ALPHA, 6);
            response.setValue(39, "00",     IsoType.ALPHA, 2);

            ctx.writeAndFlush(response);
            log(String.format("#%d  → 0210  STAN=%-6s  authCode=%-6s  responseCode=00", n, stan, authCode));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log("Error: " + cause.getMessage());
            ctx.close();
        }
    }

    private static String mask(String pan) {
        if (pan == null || pan.length() <= 10) { return pan; }
        return pan.substring(0, 6) + "******" + pan.substring(pan.length() - 4);
    }

    private static void log(String msg) {
        System.out.printf("[%s] %s%n", LocalTime.now().format(TIME_FMT), msg);
    }
}
