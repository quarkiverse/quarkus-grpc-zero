package io.quarkiverse.grpc.codegen;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jboss.logging.Logger;

import com.google.protobuf.DescriptorProtos;

import io.quarkus.bootstrap.prebuild.CodeGenException;

/**
 * Tracks protobuf type fully qualified names across proto files and detects conflicts.
 * <p>
 * Identical types (same FQN, same descriptor bytes) from multiple sources are treated
 * as benign duplicates. Different types sharing the same FQN are rejected with a
 * clear error naming both source files.
 * <p>
 * Comparisons use compiled descriptor bytes, so cosmetic differences like comments
 * and whitespace do not cause false positives.
 */
class ProtoTypeConflictDetector {

    private static final Logger log = Logger.getLogger(ProtoTypeConflictDetector.class);

    private final Map<String, byte[]> seenTypeBytes = new LinkedHashMap<>();
    private final Map<String, String> seenTypeSource = new LinkedHashMap<>();

    /**
     * Check all types declared in the given descriptor set against previously seen types.
     *
     * @param fileDescSet the parsed descriptor set for a single proto file
     * @param sourceProtoFile the on-disk path of the proto file (for error messages)
     * @return true if every type in the set was already seen with identical bytes
     * @throws CodeGenException if any type conflicts with a previously seen definition
     */
    boolean checkAndRecord(DescriptorProtos.FileDescriptorSet fileDescSet, String sourceProtoFile)
            throws CodeGenException {
        boolean allDuplicate = true;
        for (DescriptorProtos.FileDescriptorProto file : fileDescSet.getFileList()) {
            String prefix = file.getPackage().isEmpty() ? "." : "." + file.getPackage() + ".";
            allDuplicate &= checkFile(file, prefix, sourceProtoFile);
        }
        return allDuplicate;
    }

    private boolean checkFile(DescriptorProtos.FileDescriptorProto file, String prefix,
            String sourceProtoFile) throws CodeGenException {
        boolean allDup = true;
        for (DescriptorProtos.DescriptorProto message : file.getMessageTypeList()) {
            allDup &= checkMessage(message, prefix, sourceProtoFile);
        }
        for (DescriptorProtos.EnumDescriptorProto e : file.getEnumTypeList()) {
            allDup &= checkType(prefix + e.getName(), e.toByteArray(), sourceProtoFile);
        }
        for (DescriptorProtos.ServiceDescriptorProto s : file.getServiceList()) {
            allDup &= checkType(prefix + s.getName(), s.toByteArray(), sourceProtoFile);
        }
        return allDup;
    }

    private boolean checkMessage(DescriptorProtos.DescriptorProto message, String parentFqn,
            String sourceProtoFile) throws CodeGenException {
        String fqn = parentFqn + message.getName();
        boolean dup = checkType(fqn, message.toByteArray(), sourceProtoFile);

        String childPrefix = fqn + ".";
        for (DescriptorProtos.DescriptorProto nested : message.getNestedTypeList()) {
            dup &= checkMessage(nested, childPrefix, sourceProtoFile);
        }
        for (DescriptorProtos.EnumDescriptorProto nestedEnum : message.getEnumTypeList()) {
            dup &= checkType(childPrefix + nestedEnum.getName(), nestedEnum.toByteArray(), sourceProtoFile);
        }
        return dup;
    }

    private boolean checkType(String fqn, byte[] bytes, String sourceProtoFile) throws CodeGenException {
        byte[] existing = seenTypeBytes.get(fqn);
        if (existing == null) {
            seenTypeBytes.put(fqn, bytes);
            seenTypeSource.put(fqn, sourceProtoFile);
            return false;
        }
        if (!Arrays.equals(existing, bytes)) {
            throw new CodeGenException(
                    "Conflicting proto definitions for " + fqn
                            + ", first declared in " + seenTypeSource.get(fqn)
                            + ", redefined differently in " + sourceProtoFile);
        }
        log.debugf("Duplicate type %s from %s matches existing from %s", fqn, sourceProtoFile, seenTypeSource.get(fqn));
        return true;
    }
}
