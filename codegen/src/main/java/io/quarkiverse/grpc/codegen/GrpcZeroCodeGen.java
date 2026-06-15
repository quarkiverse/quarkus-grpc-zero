package io.quarkiverse.grpc.codegen;

import static io.roastedroot.protobuf4j.common.Protobuf.collectDependencies;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.nio.file.Files.copy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.compiler.PluginProtos;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import io.quarkus.grpc.protoc.plugin.MutinyGrpcGenerator;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathFilter;
import io.quarkus.runtime.util.HashUtil;
import io.roastedroot.protobuf4j.v4.Protobuf;
import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;
import io.smallrye.common.os.OS;

/**
 * Code generation for gRPC. Generates java classes from proto files placed in either src/main/proto or src/test/proto
 * Inspired by <a href="https://github.com/xolstice/protobuf-maven-plugin">Protobuf Maven Plugin</a>
 */
public class GrpcZeroCodeGen implements CodeGenProvider {
    private static final Logger log = Logger.getLogger(GrpcZeroCodeGen.class);

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

    // Output-resident marker holding the hash of the last successful codegen inputs.
    // Lives in outDir so it shares the generated sources' lifecycle (a `clean` wipes both).
    private static final String CODEGEN_CACHE_MARKER = ".grpc-zero-inputs.sha256";
    // Config keys whose values change generated output; folded into the cache key.
    // GENERATE_KOTLIN is deliberately absent: Kotlin generation can be driven by the presence of
    // the quarkus-kotlin dependency when the config is unset, so the *effective* decision
    // (shouldGenerateKotlin) is hashed instead of the raw config value.
    private static final String[] CACHE_RELEVANT_CONFIG_KEYS = {
            GENERATE_DESCRIPTOR_SET,
            DESCRIPTOR_SET_OUTPUT_DIR,
            DESCRIPTOR_SET_FILENAME,
            POST_PROCESS_SKIP,
            SCAN_DEPENDENCIES_FOR_PROTO,
            SCAN_FOR_IMPORTS,
    };

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
        if (TRUE.toString().equalsIgnoreCase(System.getProperties().getProperty("grpc.zero.codegen.skip", "false"))
                || context.config().getOptionalValue("quarkus.zero.grpc.codegen.skip", Boolean.class).orElse(false)) {
            log.info("Skipping gRPC zero code generation on user's request");
            return false;
        }
        // HACK: if present on the classpath this code generator attempts to disable the "official" Quarkus
        System.getProperties().setProperty("grpc.codegen.skip", "true");

        Path outDir = context.outDir();
        Path workDir = context.workDir();
        Path inputDir = CodeGenProvider.resolve(context.inputDir());
        Set<String> protoDirs = new LinkedHashSet<>();

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
            } catch (IOException e) {
                throw new CodeGenException("Failed to walk inputDir", e);
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

        if (!protoFiles.isEmpty()) {
            Collection<String> protosToImport = gatherDirectoriesWithImports(workDir.resolve("protoc-dependencies"),
                    context);

            // Input-hash cache: skip the (slow) WASM codegen when the proto set, the
            // output-affecting config and the grpc-zero version are all unchanged and
            // the previously generated output is still present.
            String inputHash = computeInputHash(protoFiles, protoDirs, protosToImport, context.config());
            Path cacheMarker = outDir.resolve(CODEGEN_CACHE_MARKER);
            boolean descriptorRequired = shouldGenerateDescriptorSet(context.config());
            Path descriptorFile = null;
            if (descriptorRequired) {
                try {
                    descriptorFile = getDescriptorSetOutputFile(context);
                } catch (IOException e) {
                    // Cannot resolve the descriptor target -> cannot prove the cache is valid; regenerate.
                    // The real descriptor write below re-throws if this is a genuine I/O problem.
                    log.debugf(e, "Grpc Zero: could not resolve descriptor target for cache check; will regenerate");
                    descriptorFile = null;
                }
            }
            if (isUpToDate(inputHash, cacheMarker, outDir, descriptorRequired, descriptorFile)) {
                log.info("Grpc Zero: proto inputs and config unchanged - skipping code generation (cache hit)");
                return true;
            }

            try (FileSystem fs = ZeroFs.newFileSystem(
                    Configuration.unix().toBuilder().setAttributeViews("unix").build())) {
                var workdir = fs.getPath(".");
                for (String protoDir : protoDirs) {
                    copyDirectory(Path.of(protoDir), workdir);
                }
                for (String protoImportDir : protosToImport) {
                    copyDirectory(Path.of(protoImportDir), workdir);
                }

                DescriptorProtos.FileDescriptorSet.Builder descriptorSetBuilder = DescriptorProtos.FileDescriptorSet
                        .newBuilder();
                PluginProtos.CodeGeneratorRequest.Builder requestBuilder = PluginProtos.CodeGeneratorRequest.newBuilder();

                var protobuf = Protobuf.builder().withWorkdir(workdir).build();

                processProtoFiles(protoFiles, protoDirs, protobuf, descriptorSetBuilder, requestBuilder);

                // Load the previously generated descriptor
                DescriptorProtos.FileDescriptorSet descriptorSet = descriptorSetBuilder.build();

                // Add all FileDescriptorProto entries from the descriptor set
                // and all from dependencies
                try {
                    resolveDependencies(descriptorSet, requestBuilder);
                } catch (RuntimeException e) {
                    throw new CodeGenException(
                            "Grpc Zero failed while resolving transitive proto dependencies. "
                                    + "Check gathered proto imports and file integrity before code generation.",
                            e);
                }

                PluginProtos.CodeGeneratorRequest codeGeneratorRequest = requestBuilder.build();

                // protoc based plugins
                var javaResponse = Protobuf.runNativePlugin(
                        io.roastedroot.protobuf4j.common.Protobuf.NativePlugin.JAVA,
                        codeGeneratorRequest,
                        workdir);
                writeResultToDisk(javaResponse.getFileList(), outDir);
                var grpcJavaResponse = Protobuf.runNativePlugin(
                        io.roastedroot.protobuf4j.common.Protobuf.NativePlugin.GRPC_JAVA,
                        codeGeneratorRequest,
                        workdir);
                writeResultToDisk(grpcJavaResponse.getFileList(), outDir);

                log.info("Running MutinyGrpcGenerator plugin");
                List<PluginProtos.CodeGeneratorResponse.File> mutinyResponse = new MutinyGrpcGenerator()
                        .generateFiles(codeGeneratorRequest);

                writeResultToDisk(mutinyResponse, outDir);

                // Additional Generators via Custom SPI
                ServiceLoader<GrpcZeroGenerator> generators = ServiceLoader.load(GrpcZeroGenerator.class,
                        GrpcZeroCodeGen.class.getClassLoader());
                for (GrpcZeroGenerator generator : generators) {
                    log.infof("Running %s plugin", generator.getClass().getName());
                    List<PluginProtos.CodeGeneratorResponse.File> response = generator.generate(codeGeneratorRequest)
                            .collect(Collectors.toList());
                    writeResultToDisk(response, outDir);
                }

                if (shouldGenerateKotlin(context.config())) {
                    log.info("Running KotlinGenerator plugin");
                    var grpcKotlinResponse = Protobuf.runNativePlugin(
                            io.roastedroot.protobuf4j.common.Protobuf.NativePlugin.KOTLIN,
                            codeGeneratorRequest,
                            workdir);
                    writeResultToDisk(grpcKotlinResponse.getFileList(), outDir);
                }

                if (shouldGenerateDescriptorSet(context.config())) {
                    Files.write(getDescriptorSetOutputFile(context), descriptorSet.toByteArray());
                }

                postprocessing(context, outDir);
                log.info("Grpc Zero: Successfully finished generating and post-processing sources from proto files");

                writeCacheMarker(cacheMarker, inputHash);
                return true;
            } catch (IOException e) {
                throw new CodeGenException("Failed to generate files from proto file in " + inputDir.toAbsolutePath(), e);
            }
        }

        return false;
    }

    /**
     * Computes a content hash over everything that influences generated output: the compiled
     * proto files, the imported proto files, the output-affecting config options and the
     * grpc-zero version. Used to skip regeneration when nothing has changed.
     *
     * <p>
     * Each proto contributes its <em>import-relative</em> name (the path protoc sees, relative
     * to its proto root) plus its content. The relative name matters because it lands in the
     * generated {@code FileDescriptorProto.name} and drives the Java output layout, so a
     * move/rename within the tree must invalidate the cache even when the bytes are identical.
     */
    // Package-private for unit testing.
    String computeInputHash(List<String> protoFiles, Set<String> protoDirs, Collection<String> importDirs, Config config)
            throws CodeGenException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String version = GrpcZeroCodeGen.class.getPackage().getImplementationVersion();
            md.update(("grpc-zero\0" + (version == null ? "dev" : version)).getBytes(StandardCharsets.UTF_8));
            for (String key : CACHE_RELEVANT_CONFIG_KEYS) {
                md.update(key.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(config.getOptionalValue(key, String.class).orElse("").getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            // Effective Kotlin decision (config OR the quarkus-kotlin dependency), so toggling the
            // dependency without setting the config still invalidates the cache.
            md.update(("kotlin\0" + shouldGenerateKotlin(config)).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            // Every proto that affects output (compiled + imported), mapped to the import-relative
            // name protoc will see. Ordered by absolute path for a deterministic digest.
            TreeMap<Path, String> protos = new TreeMap<>();
            for (String f : protoFiles) {
                protos.put(Path.of(f).toAbsolutePath().normalize(), relativizeProtoFile(f, protoDirs));
            }
            for (String dir : importDirs) {
                Path d = Path.of(dir).toAbsolutePath().normalize();
                if (Files.isDirectory(d)) {
                    try (Stream<Path> walk = Files.walk(d)) {
                        walk.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(PROTO))
                                .forEach(p -> {
                                    Path abs = p.toAbsolutePath().normalize();
                                    protos.put(abs, d.relativize(abs).toString().replace("\\", "/"));
                                });
                    }
                }
            }
            for (Map.Entry<Path, String> entry : protos.entrySet()) {
                // import-relative name (path-sensitive) + content
                md.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(entry.getKey()));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new CodeGenException("Failed to compute grpc-zero codegen input hash", e);
        }
    }

    /**
     * Returns true when a prior run with the same input hash is still valid: the marker matches,
     * generated sources are still present, and (when a descriptor set was requested) it still
     * exists on disk.
     *
     * @param descriptorRequired whether descriptor-set generation is enabled for this build
     * @param descriptorFile the resolved descriptor target, or {@code null} when it could not
     *        be resolved (treated as "not present" so the build regenerates)
     */
    // Package-private for unit testing.
    boolean isUpToDate(String inputHash, Path cacheMarker, Path outDir, boolean descriptorRequired, Path descriptorFile) {
        try {
            if (!Files.isRegularFile(cacheMarker) || !inputHash.equals(Files.readString(cacheMarker).strip())) {
                return false;
            }
            boolean hasGeneratedJava;
            try (Stream<Path> walk = Files.walk(outDir)) {
                hasGeneratedJava = walk.anyMatch(p -> p.toString().endsWith(".java"));
            }
            if (!hasGeneratedJava) {
                return false;
            }
            return !descriptorRequired || (descriptorFile != null && Files.isRegularFile(descriptorFile));
        } catch (IOException e) {
            // Any uncertainty -> regenerate (the safe direction).
            log.debugf(e, "Grpc Zero: cache up-to-date check failed; will regenerate");
            return false;
        }
    }

    private void writeCacheMarker(Path cacheMarker, String inputHash) {
        try {
            Files.createDirectories(cacheMarker.getParent());
            Files.writeString(cacheMarker, inputHash);
        } catch (IOException e) {
            // Caching is best-effort; a marker write failure must never fail the build.
            log.debugf(e, "Grpc Zero: failed to write codegen cache marker at %s", cacheMarker);
        }
    }

    void processProtoFiles(List<String> protoFiles, Set<String> protoDirs,
            Protobuf protobuf, DescriptorProtos.FileDescriptorSet.Builder descriptorSetBuilder,
            PluginProtos.CodeGeneratorRequest.Builder requestBuilder) throws CodeGenException {

        ProtoTypeConflictDetector conflictDetector = new ProtoTypeConflictDetector();
        Set<String> alreadyRequested = new LinkedHashSet<>();

        for (String protoFile : protoFiles) {
            log.info("resolving proto file: " + protoFile);
            var protoName = relativizeProtoFile(protoFile, protoDirs);
            log.info("final proto name: " + protoName);

            DescriptorProtos.FileDescriptorSet fileDescSet = loadDescriptorSet(protobuf, protoName, protoFile);

            boolean allDuplicate = conflictDetector.checkAndRecord(fileDescSet, protoFile);

            if (allDuplicate && alreadyRequested.contains(protoName)) {
                log.infof("Skipping duplicate proto file (all types already resolved): %s", protoFile);
                continue;
            }

            alreadyRequested.add(protoName);
            descriptorSetBuilder.addAllFile(fileDescSet.getFileList());
            requestBuilder.addFileToGenerate(protoName);
        }
    }

    private static DescriptorProtos.FileDescriptorSet loadDescriptorSet(Protobuf protobuf, String protoName,
            String protoFile) throws CodeGenException {
        try {
            return protobuf.getDescriptors(List.of(protoName));
        } catch (RuntimeException e) {
            throw new CodeGenException(
                    "Grpc Zero failed while parsing proto '" + protoName + "' (source: " + protoFile + "). "
                            + "This is commonly caused by malformed proto syntax, unresolved imports, or duplicated "
                            + "merged content in a single file.",
                    e);
        }
    }

    private static String relativizeProtoFile(String protoFile, Set<String> protoDirs) {
        Path protoFilePath = Path.of(protoFile).toAbsolutePath().normalize();
        for (String dir : protoDirs) {
            Path baseDir = Path.of(dir).toAbsolutePath().normalize();
            if (protoFilePath.startsWith(baseDir)) {
                return baseDir.relativize(protoFilePath).toString().replace("\\", "/");
            }
        }
        return protoFilePath.getFileName().toString();
    }

    private static void writeResultToDisk(List<PluginProtos.CodeGeneratorResponse.File> responseFileList, Path outDir)
            throws IOException {
        for (PluginProtos.CodeGeneratorResponse.File file : responseFileList) {
            Path outputPath = outDir.resolve(file.getName());
            // TODO: add a check when hitting root?
            Files.createDirectories(outputPath.getParent());
            log.info("grpc file generated: " + outputPath);
            Files.writeString(outputPath, file.getContent());
        }
    }

    private static void resolveDependencies(DescriptorProtos.FileDescriptorSet descriptorSet,
            PluginProtos.CodeGeneratorRequest.Builder requestBuilder)
            throws CodeGenException {
        // Use protobuf4j's buildFileDescriptors to resolve all dependencies
        List<Descriptors.FileDescriptor> fileDescriptors = Protobuf.buildFileDescriptors(descriptorSet);

        // Collect all file descriptors (including transitive dependencies) into a new descriptor set
        DescriptorProtos.FileDescriptorSet.Builder allDescriptorsBuilder = DescriptorProtos.FileDescriptorSet.newBuilder();
        Set<String> added = new HashSet<>();

        // For each file descriptor in the original set, collect all its dependencies
        for (Descriptors.FileDescriptor fileDescriptor : fileDescriptors) {
            collectDependencies(fileDescriptor, allDescriptorsBuilder, added);
        }

        // Add all collected FileDescriptorProto objects to the request builder
        DescriptorProtos.FileDescriptorSet allDescriptors = allDescriptorsBuilder.build();
        requestBuilder.addAllProtoFile(allDescriptors.getFileList());
    }

    public static void copyDirectory(final Path source, final Path target) throws IOException {
        java.nio.file.Files.walkFileTree(source, new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (java.nio.file.Files.isSymbolicLink(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                } else {
                    Path relativePath = source.relativize(dir).normalize();
                    String relative = relativePath.toString().replace("\\", "/");
                    Path directory = target.resolve(relative).normalize();
                    if (!directory.toString().equals("/")) {
                        FileAttribute<?>[] attributes = new FileAttribute[0];
                        PosixFileAttributeView attributeView = Files.getFileAttributeView(dir, PosixFileAttributeView.class);
                        if (attributeView != null) {
                            Set<PosixFilePermission> permissions = attributeView.readAttributes().permissions();
                            FileAttribute<Set<PosixFilePermission>> attribute = PosixFilePermissions
                                    .asFileAttribute(permissions);
                            attributes = new FileAttribute[] { attribute };
                        }

                        java.nio.file.Files.createDirectories(directory, attributes);
                    }

                    return FileVisitResult.CONTINUE;
                }
            }

            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relativePath = source.relativize(file).normalize();
                String relative = relativePath.toString().replace("\\", "/");
                Path path = target.resolve(relative).normalize();
                java.nio.file.Files.copy(file, path, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
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

    private void postprocessing(CodeGenContext context, Path outDir) throws CodeGenException {
        if (TRUE.toString().equalsIgnoreCase(System.getProperties().getProperty(POST_PROCESS_SKIP, "false"))
                || context.config().getOptionalValue(POST_PROCESS_SKIP, Boolean.class).orElse(false)) {
            log.info("Skipping gRPC Post-Processing on user's request");
            return;
        }

        try {
            new GrpcZeroPostProcessing(context, outDir).postprocess();
        } catch (IOException e) {
            throw new CodeGenException("Failed during default gRPC post-processing", e);
        }

        // Run additional Post-Processors via SPI
        ServiceLoader<GrpcZeroPostProcessor> loaders = ServiceLoader.load(GrpcZeroPostProcessor.class,
                GrpcZeroCodeGen.class.getClassLoader());
        for (GrpcZeroPostProcessor loader : loaders) {
            log.infof("Running %s post-processor", loader.getClass().getName());
            try {
                loader.postprocess(context, outDir);
            } catch (IOException | GrpcZeroPostProcessing.PostProcessingException e) {
                throw new CodeGenException("Failed during additional gRPC post-processing in " + loader.getClass().getName(),
                        e);
            }
        }
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
                .toList();

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
                .toList();

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
        if (OS.current() == OS.LINUX) {
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
