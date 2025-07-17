package io.grpc.quarkus.deployment;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.nio.file.Files.copy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.ImportMemory;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.compiler.PluginProtos;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathFilter;
import io.quarkus.runtime.util.HashUtil;
import io.quarkus.utilities.OS;
import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;

/**
 * Code generation for gRPC. Generates java classes from proto files placed in either src/main/proto or src/test/proto
 * Inspired by <a href="https://github.com/xolstice/protobuf-maven-plugin">Protobuf Maven Plugin</a>
 */
public class GrpcZeroCodeGen implements CodeGenProvider {
    private static final Logger log = Logger.getLogger(GrpcZeroCodeGen.class);

    private static final String quarkusProtocPluginMain = "io.quarkus.grpc.protoc.plugin.MutinyGrpcGenerator";

    private static final String PROTO = ".proto";

    private static final String SCAN_DEPENDENCIES_FOR_PROTO = "quarkus.generate-code.grpc.scan-for-proto";
    private static final String SCAN_DEPENDENCIES_FOR_PROTO_INCLUDE_PATTERN = "quarkus.generate-code.grpc.scan-for-proto-include.\"%s\"";
    private static final String SCAN_DEPENDENCIES_FOR_PROTO_EXCLUDE_PATTERN = "quarkus.generate-code.grpc.scan-for-proto-exclude.\"%s\"";
    private static final String SCAN_FOR_IMPORTS = "quarkus.generate-code.grpc.scan-for-imports";

    private static final String POST_PROCESS_SKIP = "quarkus.generate.code.grpc-post-processing.skip";
    private static final String GENERATE_DESCRIPTOR_SET = "quarkus.generate-code.grpc.descriptor-set.generate";
    private static final String DESCRIPTOR_SET_OUTPUT_DIR = "quarkus.generate-code.grpc.descriptor-set.output-dir";
    private static final String DESCRIPTOR_SET_FILENAME = "quarkus.generate-code.grpc.descriptor-set.name";

    private static final String GENERATE_KOTLIN = "quarkus.generate-code.grpc.kotlin.generate";

    private static final WasmModule PROTOC_GEN_DESCRIPTORS = ProtocGenDescriptors.load();
    private static final WasmModule PROTOC_GEN_GRPC_JAVA = ProtocGenGrpcJava.load();

    private String input;
    private boolean hasQuarkusKotlinDependency;

    @Override
    public String providerId() {
        return "grpc";
    }

    @Override
    public String[] inputExtensions() {
        return new String[] { "proto" };
    }

    @Override
    public String inputDirectory() {
        return "proto";
    }

    @Override
    public Path getInputDirectory() {
        if (input != null) {
            return Path.of(input);
        }
        return null;
    }

    @Override
    public void init(ApplicationModel model, Map<String, String> properties) {
        this.input = properties.get("quarkus.grpc.codegen.proto-directory");
        this.hasQuarkusKotlinDependency = containsQuarkusKotlin(model.getDependencies());
    }

    @Override
    public boolean trigger(CodeGenContext context) throws CodeGenException {
        if (TRUE.toString().equalsIgnoreCase(System.getProperties().getProperty("grpc.codegen.skip", "false"))
                || context.config().getOptionalValue("quarkus.grpc.codegen.skip", Boolean.class).orElse(false)) {
            log.info("Skipping gRPC code generation on user's request");
            return false;
        }
        Path outDir = context.outDir();
        Path workDir = context.workDir();
        Path inputDir = CodeGenProvider.resolve(context.inputDir());
        Set<String> protoDirs = new LinkedHashSet<>();

        try {
            List<String> protoFiles = new ArrayList<>();
            if (Files.isDirectory(inputDir)) {
                try (Stream<Path> protoFilesPaths = Files.walk(inputDir)) {
                    protoFilesPaths
                            .filter(Files::isRegularFile)
                            .filter(s -> s.toString().endsWith(PROTO))
                            .map(Path::normalize)
                            .map(Path::toAbsolutePath)
                            .map(Path::toString)
                            .forEach(protoFiles::add);
                    protoDirs.add(inputDir.normalize().toAbsolutePath().toString());
                }
            }
            Path dirWithProtosFromDependencies = workDir.resolve("protoc-protos-from-dependencies");
            Collection<Path> protoFilesFromDependencies = gatherProtosFromDependencies(dirWithProtosFromDependencies, protoDirs,
                    context);
            if (!protoFilesFromDependencies.isEmpty()) {
                for (Path files : protoFilesFromDependencies) {
                    var pathToProtoFile = files.normalize().toAbsolutePath();
                    var pathToParentDir = files.getParent();
                    // Add the proto file to the list of proto to compile, but also add the directory containing the
                    // proto file to the list of directories to include (it's a set, so no duplicate).
                    protoFiles.add(pathToProtoFile.toString());
                    protoDirs.add(pathToParentDir.toString());
                }
            }

            byte[] descriptor = null;
            if (!protoFiles.isEmpty()) {
                Collection<String> protosToImport = gatherDirectoriesWithImports(workDir.resolve("protoc-dependencies"),
                        context);

                // need to copy all the protos to import in the VFS
                try (ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                        FileSystem fs = ZeroFs.newFileSystem(
                                Configuration.unix().toBuilder().setAttributeViews("unix").build())) {

                    Path target = fs.getPath("/");

                    var wasiOptsBuilder = WasiOptions.builder()
                            .withStdout(stdout)
                            .withStderr(stderr);

                    List<String> command = new ArrayList<>();
                    command.add("protoc-gen-descriptor");

                    for (String protoDir : protoDirs) {
                        var dest = target.resolve(protoDir);
                        Files.createDirectories(dest.getParent());
                        com.dylibso.chicory.wasi.Files.copyDirectory(Path.of(protoDir), dest);
                        command.add(String.format("-I=%s", escapeWhitespace(protoDir)));
                        wasiOptsBuilder.withDirectory(dest.toString(), dest);
                    }
                    for (String protoImportDir : protosToImport) {
                        var dest = target.resolve(protoImportDir);
                        Files.createDirectories(dest.getParent());
                        com.dylibso.chicory.wasi.Files.copyDirectory(Path.of(protoImportDir), dest);
                        command.add(String.format("-I=%s", escapeWhitespace(protoImportDir)));
                        wasiOptsBuilder.withDirectory(dest.toString(), dest);
                    }

                    for (String protoFile : protoFiles) {
                        var dest = target.resolve(protoFile);
                        Files.createDirectories(dest.getParent());
                        try (InputStream is = Files.newInputStream(Path.of(protoFile))) {
                            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                        command.add(escapeWhitespace(protoFile));
                        wasiOptsBuilder.withDirectory(dest.getParent().toString(), dest.getParent());
                    }

                    var wasiOpts = wasiOptsBuilder
                            .withArguments(command)
                            .withDirectory(target.toString(), target)
                            .build();
                    var wasi = WasiPreview1.builder().withOptions(wasiOpts).build();
                    var imports = ImportValues.builder()
                            .addFunction(wasi.toHostFunctions())
                            .addMemory(
                                    new ImportMemory(
                                            "env",
                                            "memory",
                                            new ByteArrayMemory(
                                                    new MemoryLimits(3, MemoryLimits.MAX_PAGES, true))))
                            .build();

                    try {
                        Instance
                                .builder(PROTOC_GEN_DESCRIPTORS)
                                .withImportValues(imports)
                                .withMachineFactory(ProtocGenDescriptors::create)
                                .build();
                    } catch (WasiExitException exit) {
                        System.out.println(stdout);
                        System.err.println(stderr);
                        if (exit.exitCode() != 0) {
                            throw new RuntimeException("Error running protoc-gen-descriptors: " + exit.exitCode());
                        } else {
                            descriptor = stdout.toByteArray();
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failure in file access " + e);
                }

                log.error("DEBUG - NOW I SHOULD HAVE THE DESCRIPTOR");

                // Load the descriptor set
                DescriptorProtos.FileDescriptorSet descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(descriptor);

                log.error("DEBUG - NEED TO INVOKE GRPC-GEN-JAVA");

                if (true) {
                    throw new IllegalArgumentException("stopping here now");
                }

                log.error("DEBUG - NEED TO INVOKE MUTINY GEN");

                // Initialize the CodeGeneratorRequest.Builder
                PluginProtos.CodeGeneratorRequest.Builder requestBuilder = PluginProtos.CodeGeneratorRequest.newBuilder()
                        .setParameter("out=/out") // Specify the output directory
                        // TODO: this is not correct
                        .addFileToGenerate("helloworld.proto"); // Specify the file to generate

                // Add all FileDescriptorProto entries from the descriptor set
                for (DescriptorProtos.FileDescriptorProto fileDescriptor : descriptorSet.getFileList()) {
                    requestBuilder.addProtoFile(fileDescriptor);
                }

                PluginProtos.CodeGeneratorRequest codeGeneratorRequest = requestBuilder.build();

                //                for (File protoFile : protoFiles) {
                //                    DescriptorProtos.FileDescriptorProto fdp = parseProto(protoFile);
                //                    req.addProtoFile(fdp);
                //                }

                List<String> command = new ArrayList<>();
                command.add("protoc");

                for (String protoDir : protoDirs) {
                    command.add(String.format("-I=%s", escapeWhitespace(protoDir)));
                }
                for (String protoImportDir : protosToImport) {
                    command.add(String.format("-I=%s", escapeWhitespace(protoImportDir)));
                }
                command.add("--include_imports");
                command.add("--include_source_info");
                command.add("--descriptor_set_out=descriptors.pb");
                command.addAll(protoFiles);

                // TODO: need to compile protoc to run this command
                // protoc -I=protoDir -I=protoImportDir --include_imports --include_source_info --descriptor_set_out=descriptors.pb your1.proto your2.proto ...

                log.errorf("Executing command: %s", String.join(" ", command));

                //                command.addAll(asList(
                //                        "--plugin=protoc-gen-grpc=" + executables.grpc,
                //                        "--plugin=protoc-gen-q-grpc=" + executables.quarkusGrpc,
                //                        "--q-grpc_out=" + outDir,
                //                        "--grpc_out=" + outDir,
                //                        "--java_out=" + outDir));

                // TODO: verify how to handle Kotlin
                if (shouldGenerateKotlin(context.config())) {
                    command.add("--kotlin_out=" + outDir);
                }

                //                if (shouldGenerateDescriptorSet(context.config())) {
                //                    command.add(String.format("--descriptor_set_out=%s", getDescriptorSetOutputFile(context)));
                //                }

                //                command.addAll(protoFiles);
                log.debugf("Generating using the MutinyGrpcGenerator");
                // new MutinyGrpcGenerator().generateFiles()

                log.debugf("Executing command: %s", String.join(" ", command));

                int resultCode = 0;
                if (resultCode != 0) {
                    throw new CodeGenException("Failed to generate Java classes from proto files: " + protoFiles +
                            " to " + outDir.toAbsolutePath() + " with command " + String.join(" ", command));
                }
                postprocessing(context, outDir);
                log.info("Successfully finished generating and post-processing sources from proto files");

                return true;
            }
        } catch (IOException e) {
            throw new CodeGenException(
                    "Failed to generate java files from proto file in " + inputDir.toAbsolutePath(), e);
        }

        return false;
    }

    private static void copySanitizedProtoFile(ResolvedDependency artifact, Path protoPath, Path outProtoPath)
            throws IOException {
        boolean genericServicesFound = false;

        try (var reader = Files.newBufferedReader(protoPath);
                var writer = Files.newBufferedWriter(outProtoPath)) {

            String line = reader.readLine();
            while (line != null) {
                // filter java_generic_services to avoid "Tried to write the same file twice"
                // when set to true. Generic services are deprecated and replaced by classes generated by
                // this plugin
                if (!line.contains("java_generic_services")) {
                    writer.write(line);
                    writer.newLine();
                } else {
                    genericServicesFound = true;
                }

                line = reader.readLine();
            }
        }

        if (genericServicesFound) {
            log.infof("Ignoring option java_generic_services in %s:%s%s.", artifact.getGroupId(), artifact.getArtifactId(),
                    protoPath);
        }
    }

    private void postprocessing(CodeGenContext context, Path outDir) {
        if (TRUE.toString().equalsIgnoreCase(System.getProperties().getProperty(POST_PROCESS_SKIP, "false"))
                || context.config().getOptionalValue(POST_PROCESS_SKIP, Boolean.class).orElse(false)) {
            log.info("Skipping gRPC Post-Processing on user's request");
            return;
        }

        new GrpcZeroPostProcessing(context, outDir).postprocess();

    }

    private Collection<Path> gatherProtosFromDependencies(Path workDir, Set<String> protoDirectories,
            CodeGenContext context) throws CodeGenException {
        if (context.test()) {
            return Collections.emptyList();
        }
        Config properties = context.config();
        String scanDependencies = properties.getOptionalValue(SCAN_DEPENDENCIES_FOR_PROTO, String.class)
                .orElse("none");

        if ("none".equalsIgnoreCase(scanDependencies)) {
            return Collections.emptyList();
        }
        boolean scanAll = "all".equalsIgnoreCase(scanDependencies);

        List<String> dependenciesToScan = Arrays.stream(scanDependencies.split(",")).map(String::trim)
                .collect(Collectors.toList());

        ApplicationModel appModel = context.applicationModel();
        List<Path> protoFilesFromDependencies = new ArrayList<>();
        for (ResolvedDependency artifact : appModel.getRuntimeDependencies()) {
            String packageId = String.format("%s:%s", artifact.getGroupId(), artifact.getArtifactId());
            Collection<String> includes = properties
                    .getOptionalValue(String.format(SCAN_DEPENDENCIES_FOR_PROTO_INCLUDE_PATTERN, packageId), String.class)
                    .map(s -> Arrays.stream(s.split(",")).map(String::trim).collect(Collectors.toList()))
                    .orElse(List.of());

            Collection<String> excludes = properties
                    .getOptionalValue(String.format(SCAN_DEPENDENCIES_FOR_PROTO_EXCLUDE_PATTERN, packageId), String.class)
                    .map(s -> Arrays.stream(s.split(",")).map(String::trim).collect(Collectors.toList()))
                    .orElse(List.of());

            if (scanAll
                    || dependenciesToScan.contains(packageId)) {
                extractProtosFromArtifact(workDir, protoFilesFromDependencies, protoDirectories, artifact, includes, excludes,
                        true);
            }
        }
        return protoFilesFromDependencies;
    }

    @Override
    public boolean shouldRun(Path sourceDir, Config config) {
        return CodeGenProvider.super.shouldRun(sourceDir, config)
                || isGeneratingFromAppDependenciesEnabled(config);
    }

    private boolean isGeneratingFromAppDependenciesEnabled(Config config) {
        return config.getOptionalValue(SCAN_DEPENDENCIES_FOR_PROTO, String.class)
                .filter(value -> !"none".equals(value)).isPresent();
    }

    private boolean shouldGenerateKotlin(Config config) {
        return config.getOptionalValue(GENERATE_KOTLIN, Boolean.class).orElse(
                hasQuarkusKotlinDependency);
    }

    private boolean shouldGenerateDescriptorSet(Config config) {
        return config.getOptionalValue(GENERATE_DESCRIPTOR_SET, Boolean.class).orElse(FALSE);
    }

    private Path getDescriptorSetOutputFile(CodeGenContext context) throws IOException {
        var dscOutputDir = context.config().getOptionalValue(DESCRIPTOR_SET_OUTPUT_DIR, String.class)
                .map(context.workDir()::resolve)
                .orElseGet(context::outDir);

        if (Files.notExists(dscOutputDir)) {
            Files.createDirectories(dscOutputDir);
        }

        var dscFilename = context.config().getOptionalValue(DESCRIPTOR_SET_FILENAME, String.class)
                .orElse("descriptor_set.dsc");

        return dscOutputDir.resolve(dscFilename).normalize();
    }

    private Collection<String> gatherDirectoriesWithImports(Path workDir, CodeGenContext context) throws CodeGenException {
        Config properties = context.config();

        String scanForImports = properties.getOptionalValue(SCAN_FOR_IMPORTS, String.class)
                .orElse("com.google.protobuf:protobuf-java");

        if ("none".equals(scanForImports.toLowerCase(Locale.getDefault()))) {
            return Collections.emptyList();
        }

        boolean scanAll = "all".equals(scanForImports.toLowerCase(Locale.getDefault()));
        List<String> dependenciesToScan = Arrays.stream(scanForImports.split(",")).map(String::trim)
                .collect(Collectors.toList());

        Set<String> importDirectories = new HashSet<>();
        ApplicationModel appModel = context.applicationModel();
        for (ResolvedDependency artifact : appModel.getRuntimeDependencies()) {
            if (scanAll
                    || dependenciesToScan.contains(
                            String.format("%s:%s", artifact.getGroupId(), artifact.getArtifactId()))) {
                extractProtosFromArtifact(workDir, new ArrayList<>(), importDirectories, artifact, List.of(),
                        List.of(), false);
            }
        }
        return importDirectories;
    }

    private void extractProtosFromArtifact(Path workDir, Collection<Path> protoFiles,
            Set<String> protoDirectories, ResolvedDependency artifact, Collection<String> filesToInclude,
            Collection<String> filesToExclude, boolean isDependency) throws CodeGenException {

        try {
            artifact.getContentTree(new PathFilter(filesToInclude, filesToExclude)).walk(
                    pathVisit -> {
                        Path path = pathVisit.getPath();
                        if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(PROTO)) {
                            Path root = pathVisit.getRoot();
                            if (Files.isDirectory(root)) {
                                protoFiles.add(path);
                                protoDirectories.add(path.getParent().normalize().toAbsolutePath().toString());
                            } else { // archive
                                Path relativePath = path.getRoot().relativize(path);
                                String uniqueName = artifact.getGroupId() + ":" + artifact.getArtifactId();
                                if (artifact.getVersion() != null) {
                                    uniqueName += ":" + artifact.getVersion();
                                }
                                if (artifact.getClassifier() != null) {
                                    uniqueName += "-" + artifact.getClassifier();
                                }
                                Path protoUnzipDir = workDir
                                        .resolve(HashUtil.sha1(uniqueName))
                                        .normalize().toAbsolutePath();
                                try {
                                    Files.createDirectories(protoUnzipDir);
                                    protoDirectories.add(protoUnzipDir.toString());
                                } catch (IOException e) {
                                    throw new GrpcCodeGenException("Failed to create directory: " + protoUnzipDir, e);
                                }
                                Path outPath = protoUnzipDir;
                                for (Path part : relativePath) {
                                    outPath = outPath.resolve(part.toString());
                                }
                                try {
                                    Files.createDirectories(outPath.getParent());
                                    if (isDependency) {
                                        copySanitizedProtoFile(artifact, path, outPath);
                                    } else {
                                        copy(path, outPath, StandardCopyOption.REPLACE_EXISTING);
                                    }
                                    protoFiles.add(outPath);
                                } catch (IOException e) {
                                    throw new GrpcCodeGenException("Failed to extract proto file" + path + " to target: "
                                            + outPath, e);
                                }
                            }
                        }
                    });
        } catch (GrpcCodeGenException e) {
            throw new CodeGenException(e.getMessage(), e);
        }
    }

    private String escapeWhitespace(String path) {
        if (OS.determineOS() == OS.LINUX) {
            return path.replace(" ", "\\ ");
        } else {
            return path;
        }
    }

    private static boolean containsQuarkusKotlin(Collection<ResolvedDependency> dependencies) {
        return dependencies.stream().anyMatch(new Predicate<ResolvedDependency>() {
            @Override
            public boolean test(ResolvedDependency rd) {
                return rd.getGroupId().equalsIgnoreCase("io.quarkus")
                        && rd.getArtifactId().equalsIgnoreCase("quarkus-kotlin");
            }
        });
    }

    private static class GrpcCodeGenException extends RuntimeException {
        private GrpcCodeGenException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
