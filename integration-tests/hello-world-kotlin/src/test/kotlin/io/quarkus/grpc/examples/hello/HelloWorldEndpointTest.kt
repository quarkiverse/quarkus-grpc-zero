package io.quarkus.grpc.examples.hello

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.get
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@QuarkusTest
class HelloWorldEndpointTest {

    @Test
    fun testHelloWorldServiceUsingBlockingStub() {
        val response = get("/hello/blocking/neo").asString()
        assertThat(response).startsWith("Hello neo")
    }

    @Test
    fun testHelloWorldServiceUsingMutinyStub() {
        val response = get("/hello/mutiny/neo-mutiny").asString()
        assertThat(response).startsWith("Hello neo-mutiny")
    }
} 