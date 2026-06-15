package io.quarkiverse.grpc.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Unit tests for the codegen cache: the input-hash key ({@link GrpcZeroCodeGen#computeInputHash})
 * and the up-to-date gate ({@link GrpcZeroCodeGen#isUpToDate}).
 *
 * <p>
 * A bug in the key means either stale output (never invalidates) or no caching (never hits),
 * so it must be stable for identical inputs and change for any output-affecting input. A bug in
 * the gate means the build trusts a marker even when the generated sources are gone.
 */
class GrpcZeroCodeGenCacheTest {

    @TempDir
    Path tempDir;

    private final GrpcZeroCodeGen codegen = new GrpcZeroCodeGen();

    private static final String PROTO_A = "syntax = \"proto3\";\npackage p.a;\nmessage A { string x = 1; }\n";

    private Path protoRoot() throws Exception {
        Path dir = tempDir.resolve("protos");
        Files.createDirectories(dir);
        return dir;
    }

    /** Writes a proto under the shared proto root, creating intermediate dirs, and returns its absolute path. */
    private Path writeProto(String relativePath, String content) throws Exception {
        Path p = protoRoot().resolve(relativePath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p.toAbsolutePath();
    }

    private Set<String> roots() throws Exception {
        return Set.of(protoRoot().toAbsolutePath().toString());
    }

    private static Config config(String... keyValues) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            builder.withDefaultValue(keyValues[i], keyValues[i + 1]);
        }
        return builder.build();
    }

    // ---- computeInputHash ------------------------------------------------------------------

    @Test
    void identicalInputsProduceIdenticalHash() throws Exception {
        List<String> protos = List.of(writeProto("a.proto", PROTO_A).toString());
        assertEquals(
                codegen.computeInputHash(protos, roots(), List.of(), config()),
                codegen.computeInputHash(protos, roots(), List.of(), config()),
                "Identical protos + config must hash identically, otherwise the cache could never hit");
    }

    @Test
    void editingProtoContentChangesHash() throws Exception {
        Path a = writeProto("a.proto", PROTO_A);
        String before = codegen.computeInputHash(List.of(a.toString()), roots(), List.of(), config());

        Files.writeString(a, PROTO_A.replace("string x = 1;", "string x = 1;\n  int32 y = 2;"), StandardCharsets.UTF_8);
        String after = codegen.computeInputHash(List.of(a.toString()), roots(), List.of(), config());

        assertNotEquals(before, after, "Editing a proto's content must invalidate the cache");
    }

    @Test
    void addingProtoChangesHash() throws Exception {
        Path a = writeProto("a.proto", PROTO_A);
        String oneFile = codegen.computeInputHash(List.of(a.toString()), roots(), List.of(), config());

        Path b = writeProto("b.proto", "syntax = \"proto3\";\npackage p.b;\nmessage B { string y = 1; }\n");
        String twoFiles = codegen.computeInputHash(List.of(a.toString(), b.toString()), roots(), List.of(), config());

        assertNotEquals(oneFile, twoFiles, "Adding a proto must invalidate the cache");
    }

    @Test
    void movingProtoWithIdenticalContentChangesHash() throws Exception {
        // Same bytes, different import-relative path. The relative name lands in the generated
        // descriptor and Java layout, so the move MUST invalidate even though content is identical.
        Path atA = writeProto("a/foo.proto", PROTO_A);
        String hashA = codegen.computeInputHash(List.of(atA.toString()), roots(), List.of(), config());

        Path atB = writeProto("b/foo.proto", PROTO_A);
        String hashB = codegen.computeInputHash(List.of(atB.toString()), roots(), List.of(), config());

        assertNotEquals(hashA, hashB,
                "Moving a proto to a different import path must invalidate the cache even with identical content");
    }

    @Test
    void changingOutputAffectingConfigChangesHash() throws Exception {
        List<String> protos = List.of(writeProto("a.proto", PROTO_A).toString());
        String defaults = codegen.computeInputHash(protos, roots(), List.of(), config());
        String kotlinEnabled = codegen.computeInputHash(protos, roots(), List.of(),
                config("quarkus.generate-code.grpc.kotlin.generate", "true"));

        assertNotEquals(defaults, kotlinEnabled, "An output-affecting config change must invalidate the cache");
    }

    @Test
    void editingAnImportedProtoChangesHash() throws Exception {
        List<String> protos = List.of(writeProto("a.proto", PROTO_A).toString());

        Path importDir = tempDir.resolve("imports");
        Files.createDirectories(importDir);
        Path imported = importDir.resolve("dep.proto");
        Files.writeString(imported, "syntax = \"proto3\";\npackage dep;\nmessage Dep { string z = 1; }\n");
        List<String> importDirs = List.of(importDir.toAbsolutePath().toString());

        String before = codegen.computeInputHash(protos, roots(), importDirs, config());
        Files.writeString(imported, "syntax = \"proto3\";\npackage dep;\nmessage Dep { string z = 1; int32 w = 2; }\n");
        String after = codegen.computeInputHash(protos, roots(), importDirs, config());

        assertNotEquals(before, after, "Editing an imported proto must invalidate the cache");
    }

    // ---- isUpToDate ------------------------------------------------------------------------

    private static final String HASH = "deadbeef";

    /** Lays out an outDir with a cache marker and (optionally) a generated .java file. */
    private Path outDirWith(String markerContent, boolean withJava) throws Exception {
        Path outDir = tempDir.resolve("out");
        Files.createDirectories(outDir);
        if (markerContent != null) {
            Files.writeString(outDir.resolve(".grpc-zero-inputs.sha256"), markerContent);
        }
        if (withJava) {
            Files.writeString(outDir.resolve("Generated.java"), "class Generated {}");
        }
        return outDir;
    }

    @Test
    void upToDateWhenMarkerMatchesAndSourcesPresent() throws Exception {
        Path outDir = outDirWith(HASH, true);
        assertTrue(codegen.isUpToDate(HASH, outDir.resolve(".grpc-zero-inputs.sha256"), outDir, false, null),
                "Matching marker + generated sources present is a valid cache hit");
    }

    @Test
    void staleWhenMarkerMissing() throws Exception {
        Path outDir = outDirWith(null, true);
        assertFalse(codegen.isUpToDate(HASH, outDir.resolve(".grpc-zero-inputs.sha256"), outDir, false, null),
                "A missing marker is never a cache hit");
    }

    @Test
    void staleWhenHashDiffers() throws Exception {
        Path outDir = outDirWith("an-older-hash", true);
        assertFalse(codegen.isUpToDate(HASH, outDir.resolve(".grpc-zero-inputs.sha256"), outDir, false, null),
                "A marker from a different input set must not hit");
    }

    @Test
    void staleWhenNoGeneratedSources() throws Exception {
        // Marker matches but the generated sources were wiped (e.g. partial clean): regenerate.
        Path outDir = outDirWith(HASH, false);
        assertFalse(codegen.isUpToDate(HASH, outDir.resolve(".grpc-zero-inputs.sha256"), outDir, false, null),
                "A matching marker with no .java on disk must not hit");
    }

    @Test
    void staleWhenDescriptorRequiredButMissing() throws Exception {
        Path outDir = outDirWith(HASH, true);
        Path descriptor = outDir.resolve("services.dsc"); // never created
        assertFalse(codegen.isUpToDate(HASH, outDir.resolve(".grpc-zero-inputs.sha256"), outDir, true, descriptor),
                "When a descriptor set is requested but absent, the cache must not hit");
    }

    @Test
    void upToDateWhenDescriptorRequiredAndPresent() throws Exception {
        Path outDir = outDirWith(HASH, true);
        Path descriptor = outDir.resolve("services.dsc");
        Files.writeString(descriptor, "dsc");
        assertTrue(codegen.isUpToDate(HASH, outDir.resolve(".grpc-zero-inputs.sha256"), outDir, true, descriptor),
                "Requested descriptor present on disk is a valid cache hit");
    }

    @Test
    void staleWhenDescriptorRequiredButUnresolved() throws Exception {
        Path outDir = outDirWith(HASH, true);
        assertFalse(codegen.isUpToDate(HASH, outDir.resolve(".grpc-zero-inputs.sha256"), outDir, true, null),
                "An unresolved descriptor target (null) must not hit when a descriptor was requested");
    }
}
