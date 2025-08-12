package io.quarkus.grpc.examples.hello

import examples.Greeter
import examples.HelloReply
import examples.HelloRequest
import io.quarkus.grpc.GrpcService
import io.smallrye.mutiny.Uni
import java.util.concurrent.atomic.AtomicInteger

@GrpcService
class HelloWorldService : Greeter {

    private val counter = AtomicInteger()

    override fun sayHello(request: HelloRequest): Uni<HelloReply> {
        val count = counter.incrementAndGet()
        val name = request.name
        return Uni.createFrom().item("Hello $name")
            .map { res -> HelloReply.newBuilder().setMessage(res).setCount(count).build() }
    }
} 