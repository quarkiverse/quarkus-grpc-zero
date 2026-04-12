package io.quarkiverse.grpc.codegen;

import java.io.IOException;
import java.nio.file.Path;

import io.quarkus.deployment.CodeGenContext;

/**
 * SPI for additional post-processing of the generated gRPC source tree,
 * running after the built-in {@link GrpcZeroPostProcessing}. Implementations
 * are discovered via {@link java.util.ServiceLoader}.
 */
public interface GrpcZeroPostProcessor {
    void postprocess(CodeGenContext context, Path outDir) throws IOException;
}
