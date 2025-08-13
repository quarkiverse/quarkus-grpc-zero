package io.quarkus.grpc.examples.hello

import examples.GreeterGrpcKt
import examples.HelloReply
import examples.HelloRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.quarkus.test.junit.QuarkusTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
    suspend fun testHelloWorldServiceUsingBlockingStub() {
        val client = GreeterGrpcKt.GreeterCoroutineStub(channel)
        val reply = client.sayHello(HelloRequest.newBuilder().setName("neo-blocking").build())
        assertThat(reply.message).isEqualTo("Hello neo-blocking")
    }

    @Test
    suspend fun testHelloWorldServiceUsingMutinyStub() {
        val client = GreeterGrpcKt.GreeterCoroutineStub(channel)
        val reply = client.sayHello(HelloRequest.newBuilder().setName("neo-mutiny").build())
        assertThat(reply.message).isEqualTo("Hello neo-mutiny")
    }
} 