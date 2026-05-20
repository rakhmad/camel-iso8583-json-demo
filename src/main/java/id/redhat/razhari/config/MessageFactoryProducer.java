package id.redhat.razhari.config;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.MessageFactory;
import com.solab.iso8583.parse.ConfigParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@ApplicationScoped
public class MessageFactoryProducer {

    @Produces
    @ApplicationScoped
    public MessageFactory<IsoMessage> messageFactory() throws IOException {
        MessageFactory<IsoMessage> factory = new MessageFactory<>();
        factory.setUseBinaryMessages(false);
        factory.setCharacterEncoding("UTF-8");
        factory.setAssignDate(true);
        // Use this class's own ClassLoader rather than Thread.currentThread().getContextClassLoader().
        // In Quarkus dev mode the factory may be initialized on a Vert.x IO thread whose context
        // ClassLoader is the JDK system loader and cannot see application resources like j8583.xml.
        try (InputStream stream = MessageFactoryProducer.class.getResourceAsStream("/j8583.xml")) {
            if (stream == null) {
                throw new IOException("j8583.xml not found on classpath");
            }
            ConfigParser.configureFromReader(factory, new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return factory;
    }
}
