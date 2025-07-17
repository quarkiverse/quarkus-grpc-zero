plugins = [
    {
        "name": "cpp",
        "include": "google/protobuf/compiler/cpp/generator.h",    
        "generator": "::google::protobuf::compiler::cpp::CppGenerator",
    },
    {
        "name": "csharp",
        "include": "google/protobuf/compiler/csharp/csharp_generator.h",    
        "generator": "::google::protobuf::compiler::csharp::Generator",
    },
    {
        "name": "java",
        "include": "google/protobuf/compiler/java/generator.h",
        "generator": "::google::protobuf::compiler::java::JavaGenerator",
    },
    {
        "name": "kotlin",
        "include": "google/protobuf/compiler/java/kotlin_generator.h",
        "generator": "::google::protobuf::compiler::java::KotlinGenerator",
    },
    {
        "name": "objc",
        "include": "google/protobuf/compiler/objectivec/generator.h",
        "generator": "::google::protobuf::compiler::objectivec::ObjectiveCGenerator",
    },
    {
        "name": "php",
        "include": "google/protobuf/compiler/php/php_generator.h",
        "generator": "::google::protobuf::compiler::php::Generator",
    },
    {
        "name": "python",
        "include": "google/protobuf/compiler/python/generator.h",
        "generator": "::google::protobuf::compiler::python::Generator",
    },
    {
        "name": "pyi",
        "include": "google/protobuf/compiler/python/pyi_generator.h",
        "generator": "::google::protobuf::compiler::python::PyiGenerator",
    },
    {
        "name": "ruby",
        "include": "google/protobuf/compiler/ruby/ruby_generator.h",
        "generator": "::google::protobuf::compiler::ruby::Generator",
    },
    {
        "name": "rust",
        "include": "google/protobuf/compiler/rust/generator.h",
        "generator": "::google::protobuf::compiler::rust::RustGenerator",
    },
]

with open('main.cc', 'r') as maincc:
    main_template = maincc.read()

cmake_addition = """
add_custom_target(plugins)
"""

for plugin in plugins:
    main = main_template.replace('{{INCLUDE_PATH}}', plugin["include"]).replace("{{GENERATOR_TYPE}}", plugin["generator"])
    with open(f'src/main_{plugin["name"]}.cc', 'w') as maincc:
        maincc.write(main)
    cmake_addition += f"""

    
set(protoc-gen-{plugin['name']}_files ${{protobuf_SOURCE_DIR}}/src/java_plugin.cc )
add_executable(protoc-gen-{plugin['name']} ${{protoc-gen-{plugin['name']}_files}} ${{protobuf_version_rc_file}})
target_link_libraries(protoc-gen-{plugin['name']} libprotoc libprotobuf ${{protobuf_ABSL_USED_TARGETS}})
set_target_properties(protoc-gen-{plugin['name']} PROPERTIES VERSION ${{protobuf_VERSION}})
add_dependencies(plugins protoc-gen-{plugin['name']})
"""
    
print(cmake_addition)

with open('CMakeLists.txt', 'a') as cmakelists:
    cmakelists.write(cmake_addition)
