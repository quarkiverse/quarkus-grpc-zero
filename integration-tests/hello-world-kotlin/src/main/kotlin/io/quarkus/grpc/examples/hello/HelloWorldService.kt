package io.quarkus.grpc.examples.hello

import examples.GreeterGrpcKt
import examples.HelloReply
import examples.HelloRequest
import io.quarkus.grpc.GrpcService
import java.util.concurrent.atomic.AtomicInteger

@GrpcService
class HelloWorldService : GreeterGrpcKt.GreeterCoroutineImplBase() {

    private val counter = AtomicInteger()

    override suspend fun sayHello(request: HelloRequest): HelloReply {
        val count = counter.incrementAndGet()
        val name = request.name
        return HelloReply.newBuilder().setMessage("Hello $name").setCount(count).build()
    }
} 