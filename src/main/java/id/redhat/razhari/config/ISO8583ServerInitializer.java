package id.redhat.razhari.config;

import id.redhat.razhari.codec.ISO8583Decoder;
import id.redhat.razhari.codec.ISO8583Encoder;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.MessageFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.component.netty.ServerInitializerFactory;
import org.apache.camel.component.netty.NettyConsumer;

@ApplicationScoped
@Named("iso8583ServerInitializer")
public class ISO8583ServerInitializer extends ServerInitializerFactory {

    @Inject
    MessageFactory<IsoMessage> messageFactory;

    @Override
    protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("decoder", new ISO8583Decoder(messageFactory));
        pipeline.addLast("encoder", new ISO8583Encoder());
    }

    @Override
    public ServerInitializerFactory createPipelineFactory(NettyConsumer consumer) {
        return this;
    }
}
