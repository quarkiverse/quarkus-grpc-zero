package io.quarkus.grpc.examples.hello

import examples.GreeterGrpcKt
import examples.HelloReply
import examples.HelloRequest
import io.quarkus.grpc.GrpcClient
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path

@Path("/hello")
class HelloWorldEndpoint {

    @GrpcClient("hello")
    lateinit var blockingHelloService: GreeterGrpcKt.GreeterCoroutineStub

    @GrpcClient("hello")
    lateinit var helloService: GreeterGrpcKt.GreeterCoroutineImplBase

    @GET
    @Path("/blocking/{name}")
    suspend fun helloBlocking(name: String): String {
        val reply = blockingHelloService.sayHello(HelloRequest.newBuilder().setName(name).build())
        return generateResponse(reply)
    }

    @GET
    @Path("/mutiny/{name}")
    suspend fun helloMutiny(name: String): String {
        val reply = helloService.sayHello(HelloRequest.newBuilder().setName(name).build())
        return generateResponse(reply)
    }

    private fun generateResponse(reply: HelloReply): String {
        return "${reply.message}! HelloWorldService has been called ${reply.count} number of times."
    }
} 