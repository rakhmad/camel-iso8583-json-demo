package id.redhat.razhari.rest;

import id.redhat.razhari.model.TransactionState;
import id.redhat.razhari.model.TransactionStatus;
import id.redhat.razhari.store.TransactionStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class TransactionRestRouteTest {

    @InjectMock
    TransactionStore store;

    @Test
    void POST_returns_202_with_transaction_id() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "mti": "0200",
                  "pan": "4111111111111111",
                  "amount": 10000,
                  "currency": "840",
                  "terminalId": "TERM0001",
                  "merchantId": "MERCH001"
                }
                """)
        .when()
            .post("/api/v1/transactions")
        .then()
            .statusCode(202)
            .body("transactionId", matchesPattern("[0-9a-f-]{36}"));

        verify(store).save(any(TransactionState.class));
    }

    @Test
    void GET_by_id_returns_200_with_state_when_found() {
        TransactionState state = pendingState("test-id", "000001");
        when(store.findById("test-id")).thenReturn(Optional.of(state));

        given()
        .when()
            .get("/api/v1/transactions/test-id")
        .then()
            .statusCode(200)
            .body("transactionId", equalTo("test-id"))
            .body("status", equalTo("PENDING"));
    }

    @Test
    void GET_by_id_returns_404_when_not_found() {
        when(store.findById("unknown")).thenReturn(Optional.empty());

        given()
        .when()
            .get("/api/v1/transactions/unknown")
        .then()
            .statusCode(404);
    }

    @Test
    void GET_status_returns_lightweight_status_object() {
        TransactionState state = pendingState("id-1", "000001");
        when(store.findById("id-1")).thenReturn(Optional.of(state));

        given()
        .when()
            .get("/api/v1/transactions/id-1/status")
        .then()
            .statusCode(200)
            .body("status", equalTo("PENDING"))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue());
    }

    @Test
    void GET_list_returns_inbound_events_when_type_inbound() {
        TransactionState received = pendingState("id-1", "000001");
        received.status = TransactionStatus.RECEIVED;
        when(store.findByStatus(TransactionStatus.RECEIVED)).thenReturn(List.of(received));

        given()
            .queryParam("type", "inbound")
        .when()
            .get("/api/v1/transactions")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1));
    }

    @Test
    void GET_list_returns_all_when_no_type_param() {
        when(store.findAll()).thenReturn(List.of(
            pendingState("id-1", "000001"),
            pendingState("id-2", "000002")
        ));

        given()
        .when()
            .get("/api/v1/transactions")
        .then()
            .statusCode(200)
            .body("size()", equalTo(2));
    }

    private TransactionState pendingState(String id, String stan) {
        TransactionState s = new TransactionState();
        s.id = id;
        s.stan = stan;
        s.status = TransactionStatus.PENDING;
        s.createdAt = Instant.now();
        s.updatedAt = Instant.now();
        return s;
    }
}
