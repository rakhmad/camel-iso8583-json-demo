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
import org.apache.camel.component.netty.ClientInitializerFactory;
import org.apache.camel.component.netty.NettyProducer;
import org.apache.camel.component.netty.handlers.ClientChannelHandler;

/**
 * Custom Netty client pipeline initializer for ISO 8583.
 *
 * createPipelineFactory is called by Camel with the active NettyProducer
 * and returns a new instance per channel so that:
 *  - ISO8583Decoder (ByteToMessageDecoder, non-@Sharable) is fresh per channel
 *  - ClientChannelHandler receives the producer reference needed for
 *    synchronous request-response correlation (sync=true mode)
 */
@ApplicationScoped
@Named("iso8583ClientInitializer")
public class ISO8583ClientInitializer extends ClientInitializerFactory {

    @Inject
    MessageFactory<IsoMessage> messageFactory;

    // Set by createPipelineFactory; null in the CDI-managed singleton bean itself
    private NettyProducer producer;

    @Override
    protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("decoder", new ISO8583Decoder(messageFactory));
        pipeline.addLast("encoder", new ISO8583Encoder());
        // ClientChannelHandler completes the Camel exchange when the response arrives.
        // Without it, sync=true mode never unblocks.
        if (producer != null) {
            pipeline.addLast("handler", new ClientChannelHandler(producer));
        }
    }

    @Override
    public ClientInitializerFactory createPipelineFactory(NettyProducer producer) {
        // Return a new instance per channel so each channel gets its own (non-sharable) decoder
        ISO8583ClientInitializer copy = new ISO8583ClientInitializer();
        copy.messageFactory = this.messageFactory;
        copy.producer = producer;
        return copy;
    }
}
