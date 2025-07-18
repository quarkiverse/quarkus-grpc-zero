// Protocol Buffers - Google's data interchange format
// Copyright 2008 Google Inc.  All rights reserved.
//
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file or at
// https://developers.google.com/open-source/licenses/bsd

#include <google/protobuf/descriptor.h>
#include <google/protobuf/descriptor.pb.h>
#include <google/protobuf/compiler/importer.h>
#include <fstream>
#include <iostream>
#include <vector>
#include <string>
#include <cstring>

int main(int argc, char** argv) {
  if (argc < 2) {
    std::cerr << "Usage: " << argv[0] << " [options] file.proto [file2.proto ...]" << std::endl;
    std::cerr << "Options:\n  -I=PATH            Add include path\n" << std::endl;
    return 1;
  }

  // Argument parsing
  std::vector<std::string> proto_files;
  std::vector<std::string> include_paths;

  for (int i = 1; i < argc; ++i) {
    std::string arg = argv[i];
    if (arg.rfind("-I=", 0) == 0) {
      std::string path = arg.substr(3);
      include_paths.push_back(path);
    } else if (!arg.empty() && arg[0] != '-') {
      proto_files.push_back(arg);
    } else {
      // Unknown or ignored argument
    }
  }

  if (proto_files.empty()) {
    std::cerr << "[ERROR] No .proto files specified." << std::endl;
    return 1;
  }

  // Set up the importer
  google::protobuf::compiler::DiskSourceTree source_tree;
  if (include_paths.empty()) {
    source_tree.MapPath("", ".");
  } else {
    for (const auto& path : include_paths) {
      source_tree.MapPath("", path);
    }
  }
  google::protobuf::compiler::Importer importer(&source_tree, nullptr);

  google::protobuf::FileDescriptorSet fd_set;
  for (const auto& file : proto_files) {
    // Read and print file contents to stderr
    std::ifstream proto_in(file);
    if (!proto_in) {
      std::cerr << "[ERROR] Could not open proto file: '" << file << "'" << std::endl;
    } else {
      std::string line;
      while (std::getline(proto_in, line)) {
        // std::cerr << line << std::endl; // Removed debug logging
      }
    }
    const google::protobuf::FileDescriptor* fd = importer.Import(file.c_str());
    if (!fd) {
      std::cerr << "[ERROR] Failed to import: '" << file << "'" << std::endl;
      return 1;
    }
    auto* proto = fd_set.add_file();
    fd->CopyTo(proto);
  }

  // Write to stdout
  fd_set.SerializeToOstream(&std::cout);
  return 0;
}
