#! /bin/bash
set -euxo pipefail

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

cd ${SCRIPT_DIR}/protobuf && patch -p1 < ${SCRIPT_DIR}/patch.txt

cp ${SCRIPT_DIR}/main.cc ${SCRIPT_DIR}/protobuf

cat <<EOF >> ${SCRIPT_DIR}/protobuf/CMakeLists.txt
add_custom_target(plugins)

set(protoc-gen-descriptor_files \${protobuf_SOURCE_DIR}/main.cc)
add_executable(protoc-gen-descriptor \${protoc-gen-descriptor_files} \${protobuf_version_rc_file})
target_link_libraries(protoc-gen-descriptor libprotoc libprotobuf libupb \${protobuf_ABSL_USED_TARGETS})
set_target_properties(protoc-gen-descriptor PROPERTIES VERSION \${protobuf_VERSION})
add_dependencies(plugins protoc-gen-descriptor)
EOF

rm ${SCRIPT_DIR}/protobuf/src/google/protobuf/compiler/subprocess.* ${SCRIPT_DIR}/protobuf/src/google/protobuf/compiler/command_line_interface.*
sed -i '/src\/google\/protobuf\/compiler\/subprocess\./d' ${SCRIPT_DIR}/protobuf/src/file_lists.cmake
sed -i '/src\/google\/protobuf\/compiler\/command_line_interface\./d' ${SCRIPT_DIR}/protobuf/src/file_lists.cmake
