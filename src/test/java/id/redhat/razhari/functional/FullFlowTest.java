package id.redhat.razhari.functional;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullFlowTest {

    static MockISO8583Switch mockSwitch;

    @BeforeAll
    static void startMockSwitch() throws Exception {
        mockSwitch = new MockISO8583Switch(19999);
        mockSwitch.start();
    }

    @AfterAll
    static void stopMockSwitch() {
        if (mockSwitch != null) mockSwitch.stop();
    }

    @Test
    @Order(1)
    void submitsTransactionAndPollsForCompletedResult() {
        // Submit
        String transactionId = given()
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
            .extract().path("transactionId");

        // Poll until COMPLETED (switch responds asynchronously)
        await().atMost(20, TimeUnit.SECONDS).until(() -> {
            String status = given()
                .get("/api/v1/transactions/" + transactionId + "/status")
                .then()
                .statusCode(200)
                .extract().path("status");
            return "COMPLETED".equals(status);
        });

        // Verify full response
        given()
        .when()
            .get("/api/v1/transactions/" + transactionId)
        .then()
            .statusCode(200)
            .body("status",              equalTo("COMPLETED"))
            .body("result.responseCode", equalTo("00"))
            .body("result.authCode",     equalTo("AUTH01"))
            .body("result.stan",         notNullValue());
    }

    @Test
    @Order(2)
    void returns404ForUnknownTransactionId() {
        given()
        .when()
            .get("/api/v1/transactions/does-not-exist")
        .then()
            .statusCode(404);
    }
}
