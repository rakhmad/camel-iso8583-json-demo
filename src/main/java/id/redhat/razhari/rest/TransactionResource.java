package id.redhat.razhari.rest;

import id.redhat.razhari.model.*;
import id.redhat.razhari.route.IsoMessageSender;
import id.redhat.razhari.store.TransactionStore;
import id.redhat.razhari.util.StanGenerator;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TransactionResource {

    @Inject TransactionStore store;
    @Inject IsoMessageSender sender;
    @Inject StanGenerator stanGenerator;

    @POST
    public Response submit(TransactionRequest request) {
        TransactionState state = new TransactionState();
        state.id = UUID.randomUUID().toString();
        state.stan = stanGenerator.next();
        state.status = TransactionStatus.PENDING;
        state.request = request;
        state.createdAt = Instant.now();
        state.updatedAt = Instant.now();

        store.save(state);
        sender.asyncSend(state);

        return Response.accepted(Map.of("transactionId", state.id)).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") String id) {
        return store.findById(id)
            .map(s -> Response.ok(toResponse(s)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/{id}/status")
    public Response getStatus(@PathParam("id") String id) {
        return store.findById(id)
            .map(s -> Response.ok(Map.of(
                "status",    s.status,
                "createdAt", s.createdAt,
                "updatedAt", s.updatedAt
            )).build())
            .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    public Response list(@QueryParam("type") String type) {
        List<TransactionState> states = "inbound".equals(type)
            ? store.findByStatus(TransactionStatus.RECEIVED)
            : store.findAll();
        List<TransactionResponse> responses = states.stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
        return Response.ok(responses).build();
    }

    private TransactionResponse toResponse(TransactionState s) {
        TransactionResponse r = new TransactionResponse();
        r.transactionId = s.id;
        r.status = s.status;
        r.createdAt = s.createdAt;
        r.updatedAt = s.updatedAt;
        r.result = s.result;
        return r;
    }
}
