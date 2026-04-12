package io.quarkiverse.grpc.spi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import io.quarkiverse.grpc.codegen.GrpcZeroPostProcessor;
import io.quarkus.deployment.CodeGenContext;

public class TestPostProcessor implements GrpcZeroPostProcessor {
    private static final Logger log = Logger.getLogger(TestPostProcessor.class);

    @Override
    public void postprocess(CodeGenContext context, Path outDir) throws IOException {
        log.infof("TestPostProcessor running on %s", outDir);
        try (Stream<Path> paths = Files.walk(outDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            log.infof("Signing file: %s", p);
                            Files.writeString(p, "\n// Signed by TestPostProcessor\n", StandardOpenOption.APPEND);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}
