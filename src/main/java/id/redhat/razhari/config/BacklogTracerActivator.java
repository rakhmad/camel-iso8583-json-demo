package id.redhat.razhari.config;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.spi.BacklogTracer;

@ApplicationScoped
@IfBuildProfile("dev")
public class BacklogTracerActivator {

    @Inject
    CamelContext camelContext;

    void onStart(@Observes StartupEvent ev) {
        BacklogTracer tracer = camelContext.getCamelContextExtension()
            .getContextPlugin(BacklogTracer.class);
        if (tracer != null) {
            tracer.setEnabled(true);
            tracer.setRemoveOnDump(false);
        }
    }
}
