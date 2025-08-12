package io.quarkus.grpc.examples.hello

import examples.GreeterGrpc
import examples.HelloReply
import examples.HelloRequest
import examples.MutinyGreeterGrpc
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.quarkus.test.junit.QuarkusTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

@QuarkusTest
class HelloWorldServiceTest {

    private lateinit var channel: ManagedChannel

    @BeforeEach
    fun init() {
        channel = ManagedChannelBuilder.forAddress("localhost", 9001).usePlaintext().build()
    }

    @AfterEach
    @Throws(InterruptedException::class)
    fun cleanup() {
        channel.shutdown()
        channel.awaitTermination(10, TimeUnit.SECONDS)
    }

    @Test
    fun testHelloWorldServiceUsingBlockingStub() {
        val client = GreeterGrpc.newBlockingStub(channel)
        val reply = client.sayHello(HelloRequest.newBuilder().setName("neo-blocking").build())
        assertThat(reply.message).isEqualTo("Hello neo-blocking")
    }

    @Test
    fun testHelloWorldServiceUsingMutinyStub() {
        val reply = MutinyGreeterGrpc.newMutinyStub(channel)
            .sayHello(HelloRequest.newBuilder().setName("neo-blocking").build())
            .await().atMost(Duration.ofSeconds(5))
        assertThat(reply.message).isEqualTo("Hello neo-blocking")
    }
} 