package io.quarkiverse.grpc.codegen;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.nio.file.Files.copy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import com.dylibso.chicory.runtime.TrapException;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.compiler.PluginProtos;

import io.grpc.kotlin.generator.GeneratorRunner;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import io.quarkus.grpc.protoc.plugin.MutinyGrpcGenerator;
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

    private static final WasmModule PROTOC_WRAPPER = ProtocWrapper.load();

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

                for (String protoFile : protoFiles) {
                    try (InputStream is = Files.newInputStream(Path.of(protoFile))) {
                        Files.copy(is, workdir.resolve(Path.of(protoFile).getFileName().toString()),
                                StandardCopyOption.REPLACE_EXISTING);
                    }

                    log.info("resolving proto file: " + protoFile);
                    var protoName = realitivizeProtoFile(protoFile, protoDirs);
                    log.info("final proto name: " + protoName);

                    descriptorSetBuilder.addAllFile(getDescriptor(workdir, protoName).getFileList());
                    requestBuilder.addFileToGenerate(protoName);
                }

                // Load the previously generated descriptor
                DescriptorProtos.FileDescriptorSet descriptorSet = descriptorSetBuilder.build();

                // Add all FileDescriptorProto entries from the descriptor set
                // and all from dependencies
                resolveDependencies(workdir, descriptorSet, requestBuilder);

                PluginProtos.CodeGeneratorRequest codeGeneratorRequest = requestBuilder.build();

                // protoc based plugins
                List<String> availablePlugins = new ArrayList<>();
                availablePlugins.add("java");
                availablePlugins.add("grpc-java");

                for (String pluginName : availablePlugins) {
                    log.info("Running grpc plugin " + pluginName);
                    PluginProtos.CodeGeneratorResponse response = runNativePlugin(pluginName, codeGeneratorRequest, workdir);

                    writeResultToDisk(response.getFileList(), outDir);
                }

                log.info("Running MutinyGrpcGenerator plugin");
                List<PluginProtos.CodeGeneratorResponse.File> mutinyResponse = new MutinyGrpcGenerator()
                        .generateFiles(codeGeneratorRequest);

                writeResultToDisk(mutinyResponse, outDir);

                if (shouldGenerateKotlin(context.config())) {
                    log.info("Running KotlinGenerator plugin");
                    ByteArrayInputStream input = new ByteArrayInputStream(codeGeneratorRequest.toByteArray());
                    ByteArrayOutputStream output = new ByteArrayOutputStream();

                    GeneratorRunner.INSTANCE.mainAsProtocPlugin(input, output);

                    var response = PluginProtos.CodeGeneratorResponse.parseFrom(output.toByteArray());

                    writeResultToDisk(response.getFileList(), outDir);
                }

                if (shouldGenerateDescriptorSet(context.config())) {
                    Files.write(getDescriptorSetOutputFile(context), descriptorSet.toByteArray());
                }

                postprocessing(context, outDir);
                log.info("Grpc Zero: Successfully finished generating and post-processing sources from proto files");

                return true;
            } catch (IOException e) {
                throw new CodeGenException("Failed to generate files from proto file in " + inputDir.toAbsolutePath(), e);
            }
        }

        return false;
    }

    public static boolean isInSubtree(Path baseDir, Path candidate) {
        Path base = baseDir.toAbsolutePath().normalize();
        Path cand = candidate.toAbsolutePath().normalize();

        return cand.startsWith(base);
    }

    // TODO: verify is all this dance can be simplified somehow ...
    private static String realitivizeProtoFile(String protoFile, Set<String> protoDir) {
        Path protoFilePath = Path.of(protoFile);
        for (String dir : protoDir) {
            try {
                if (isInSubtree(Path.of(dir), protoFilePath)) {
                    Path base = Path.of(dir).toAbsolutePath().normalize();
                    Path file = protoFilePath.toAbsolutePath().normalize();
                    return base.relativize(file).toString();
                }
            } catch (IllegalArgumentException e) {
                // cannot be relativized, skip
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

    private static ImportMemory getDefaultMemory() {
        return new ImportMemory(
                "env",
                "memory",
                new ByteArrayMemory(
                        new MemoryLimits(10, MemoryLimits.MAX_PAGES, true)));
    }

    private static void resolveDependencies(Path workdir,
            DescriptorProtos.FileDescriptorSet descriptorSet, PluginProtos.CodeGeneratorRequest.Builder requestBuilder)
            throws CodeGenException {
        resolveDependencies(workdir, descriptorSet, requestBuilder, new ArrayList<>());
    }

    // TODO: this might be expensive, we should probably push the logic down to cpp
    private static void resolveDependencies(Path workdir,
            DescriptorProtos.FileDescriptorSet descriptorSet, PluginProtos.CodeGeneratorRequest.Builder requestBuilder,
            List<String> visited)
            throws CodeGenException {
        for (DescriptorProtos.FileDescriptorProto fileDescriptor : descriptorSet.getFileList()) {
            log.info("adding descriptor: " + fileDescriptor.getName());
            for (String dep : fileDescriptor.getDependencyList()) {
                if (!visited.contains(dep)) {
                    log.info("Getting dependency descriptor for: " + dep);
                    var depFdSet = getDescriptor(workdir, dep);
                    resolveDependencies(workdir, depFdSet, requestBuilder, visited);
                    visited.add(dep);
                }
            }
            if (!visited.contains(fileDescriptor.getName())) {
                requestBuilder.addProtoFile(fileDescriptor);
                requestBuilder.addSourceFileDescriptors(fileDescriptor);
                visited.add(fileDescriptor.getName());
            }
        }
    }

    public static void copyDirectory(final Path source, final Path target) throws IOException {
        java.nio.file.Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (java.nio.file.Files.isSymbolicLink(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                } else {
                    Path directory = target.resolve(source.relativize(dir).toString());
                    if (!directory.toString().equals("/")) {
                        FileAttribute<?>[] attributes = new FileAttribute[0];
                        PosixFileAttributeView attributeView = (PosixFileAttributeView) java.nio.file.Files
                                .getFileAttributeView(dir, PosixFileAttributeView.class);
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
                String relative = source.relativize(file).toString().replace("\\", "/");
                Path path = target.resolve(relative);
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

    private void postprocessing(CodeGenContext context, Path outDir) {
        if (TRUE.toString().equalsIgnoreCase(System.getProperties().getProperty(POST_PROCESS_SKIP, "false"))
                || context.config().getOptionalValue(POST_PROCESS_SKIP, Boolean.class).orElse(false)) {
            log.info("Skipping gRPC Post-Processing on user's request");
            return;
        }

        new GrpcZeroPostProcessing(context, outDir).postprocess();

    }

    private static DescriptorProtos.FileDescriptorSet getDescriptor(Path workdir, String fileName)
            throws CodeGenException {
        return getDescriptor(workdir, List.of(fileName));
    }

    private static DescriptorProtos.FileDescriptorSet getDescriptor(Path workdir, List<String> fileNames)
            throws CodeGenException {
        try (ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream()) {
            var wasiOptsBuilder = WasiOptions.builder()
                    .withStdout(stdout)
                    .withStderr(stderr);

            List<String> command = new ArrayList<>();
            command.add("protoc-wrapper");
            command.add("descriptors");

            command.addAll(fileNames);

            var wasiOpts = wasiOptsBuilder
                    .withArguments(command)
                    .withDirectory(workdir.toString(), workdir)
                    .build();
            try (var wasi = WasiPreview1.builder().withOptions(wasiOpts).build()) {
                var imports = ImportValues.builder()
                        .addFunction(wasi.toHostFunctions())
                        .addMemory(getDefaultMemory())
                        .build();

                log.debug("protoc command: " + command.stream().collect(Collectors.joining(" ")));
                Instance
                        .builder(PROTOC_WRAPPER)
                        .withImportValues(imports)
                        .withMachineFactory(ProtocWrapper::create)
                        .build();
            } catch (TrapException trap) {
                System.out.println(stdout);
                System.err.println(stderr);
                throw new CodeGenException("Error running protoc-wrapper, trapped");
            } catch (WasiExitException exit) {
                System.out.println(stdout);
                System.err.println(stderr);
                if (exit.exitCode() != 0) {
                    throw new CodeGenException("Error running protoc-wrapper: " + exit.exitCode());
                }
            }
            return DescriptorProtos.FileDescriptorSet.parseFrom(stdout.toByteArray());
        } catch (IOException e) {
            throw new CodeGenException(
                    "Failed to generate java files from proto files " + fileNames.stream().collect(Collectors.joining(", ")),
                    e);
        }
    }

    private static PluginProtos.CodeGeneratorResponse runNativePlugin(String pluginName,
            PluginProtos.CodeGeneratorRequest codeGeneratorRequest, Path workdir) throws CodeGenException {
        try (ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream()) {

            var wasiOptsBuilder = WasiOptions.builder()
                    .withStdout(stdout)
                    .withStderr(stderr);

            var wasiOpts = wasiOptsBuilder
                    .withStdin(new ByteArrayInputStream(codeGeneratorRequest.toByteArray()))
                    .withArguments(List.of("protoc-wrapper", pluginName))
                    .withDirectory(workdir.toString(), workdir)
                    .build();
            try (var wasi = WasiPreview1.builder().withOptions(wasiOpts).build()) {
                var imports = ImportValues.builder()
                        .addFunction(wasi.toHostFunctions())
                        .addMemory(getDefaultMemory())
                        .build();

                Instance.builder(PROTOC_WRAPPER)
                        .withImportValues(imports)
                        .withMachineFactory(ProtocWrapper::create)
                        .build();
            } catch (Exception e) {
                log.error("Error running protoc native plugin ", e);
                System.out.println(stdout);
                System.err.println(stderr);
                throw new CodeGenException("Error running protoc native plugin.", e);
            }

            return PluginProtos.CodeGeneratorResponse.parseFrom(stdout.toByteArray());
        } catch (IOException e) {
            throw new CodeGenException("Failed to run native protoc plugin " + pluginName, e);
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
