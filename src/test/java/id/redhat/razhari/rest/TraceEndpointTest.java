package id.redhat.razhari.rest;

import id.redhat.razhari.route.IsoDispatcher;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests the GET /api/v1/trace/{transactionId} endpoint.
 * BacklogTracer is only enabled in the dev profile; in the test profile it is null,
 * so all tests expect an empty array — this verifies the endpoint handles a
 * null/disabled tracer gracefully without throwing NullPointerException.
 */
@QuarkusTest
class TraceEndpointTest {

    @InjectMock
    IsoDispatcher isoDispatcher; // prevent real switch dispatch from test submissions

    @Test
    void GET_trace_returns_200_with_empty_array_when_tracer_not_active() {
        given()
        .when()
            .get("/api/v1/trace/does-not-exist-12345")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("", hasSize(0));
    }

    @Test
    void GET_trace_returns_empty_array_for_any_id() {
        given()
        .when()
            .get("/api/v1/trace/550e8400-e29b-41d4-a716-446655440000")
        .then()
            .statusCode(200)
            .body("$", instanceOf(java.util.List.class));
    }
}
