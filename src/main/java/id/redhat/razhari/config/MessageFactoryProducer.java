package id.redhat.razhari.config;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.MessageFactory;
import com.solab.iso8583.parse.ConfigParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.io.IOException;

@ApplicationScoped
public class MessageFactoryProducer {

    @Produces
    @ApplicationScoped
    public MessageFactory<IsoMessage> messageFactory() throws IOException {
        MessageFactory<IsoMessage> factory = new MessageFactory<>();
        factory.setUseBinaryMessages(false);
        factory.setCharacterEncoding("UTF-8");
        factory.setAssignDate(true);
        ConfigParser.configureFromClasspathConfig(factory, "j8583.xml");
        return factory;
    }
}
