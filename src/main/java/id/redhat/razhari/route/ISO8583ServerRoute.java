package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionStatus;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;

@ApplicationScoped
public class ISO8583ServerRoute extends RouteBuilder {

    @Inject
    MessageFactory<IsoMessage> messageFactory;

    @Override
    public void configure() {
        onException(Exception.class)
            .handled(true)
            .log("ISO8583 server error: ${exception.message}");

        from("netty:tcp://0.0.0.0:{{camel.iso8583.server.port}}"
             + "?serverInitializerFactory=#iso8583ServerInitializer"
             + "&sync=true"
             + "&keepAlive=true")
            .process(exchange -> {
                // Store original message before isoToJsonProcessor replaces body
                exchange.setProperty("incomingMsg",
                    exchange.getIn().getBody(IsoMessage.class));
            })
            .process("isoToJsonProcessor")
            .process(exchange -> {
                // Build acknowledgment response back to switch
                IsoMessage incoming = exchange.getProperty("incomingMsg", IsoMessage.class);
                int responseMti = incoming.getType() + 0x0010; // 0200→0210, 0400→0410
                IsoMessage ack = messageFactory.newMessage(responseMti);
                if (incoming.hasField(11)) {
                    ack.setValue(11, incoming.getField(11).toString(),
                        IsoType.NUMERIC, 6);
                }
                ack.setValue(39, "00", IsoType.ALPHA, 2);
                exchange.getIn().setBody(ack);
            });
    }
}
