package io.quarkiverse.grpc.codegen;

import java.util.stream.Stream;

import com.google.protobuf.compiler.PluginProtos;

/**
 * SPI for contributing additional generated files after the built-in
 * generators (protoc Java, grpc-java, Mutiny) have run. Implementations are
 * discovered via {@link java.util.ServiceLoader}.
 */
public interface GrpcZeroGenerator {

    /**
     * @param request the same CodeGeneratorRequest handed to the built-in plugins
     * @return files to write to the output directory; may be empty
     */
    Stream<PluginProtos.CodeGeneratorResponse.File> generate(PluginProtos.CodeGeneratorRequest request);
}
