package id.redhat.razhari.route;

import id.redhat.razhari.model.TransactionStatus;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;

/**
 * Inbound TCP server route: listens for unsolicited messages from the payment
 * switch (reversals, advices, network management).
 *
 * For each inbound ISO 8583 frame:
 *   1. IsoToJsonProcessor stores the message as a RECEIVED event in the TransactionStore.
 *   2. An acknowledgment (MTI + 0x0010, e.g. 0200→0210, 0400→0410) is built with
 *      the echoed STAN (field 11) and response code 00, then sent back to the switch.
 *
 * The ACK MTI convention (+0x0010) follows the ISO 8583 response pairing standard.
 */
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
             + "&sync=true"       // sync=true required: Netty waits for the ACK body to send back
             + "&keepAlive=true")
            .process(exchange -> {
                // Stash the raw IsoMessage before isoToJsonProcessor replaces the body.
                // It is needed below to build the acknowledgment.
                exchange.setProperty("incomingMsg",
                    exchange.getIn().getBody(IsoMessage.class));
            })
            .process("isoToJsonProcessor")  // stores the inbound message as RECEIVED
            .process(exchange -> {
                IsoMessage incoming = exchange.getProperty("incomingMsg", IsoMessage.class);
                // ISO 8583 response MTI is request MTI + 0x0010 (e.g. 0200→0210, 0400→0410)
                int responseMti = incoming.getType() + 0x0010;
                IsoMessage ack = messageFactory.newMessage(responseMti);
                // Echo field 11 (STAN) so the switch can correlate the acknowledgment
                if (incoming.hasField(11)) {
                    ack.setValue(11, incoming.getField(11).toString(),
                        IsoType.NUMERIC, 6);
                }
                ack.setValue(39, "00", IsoType.ALPHA, 2); // response code 00 = approved
                exchange.getIn().setBody(ack);
            });
    }
}
