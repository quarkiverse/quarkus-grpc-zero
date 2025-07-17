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
      include_paths.push_back(arg.substr(3));
    } else if (!arg.empty() && arg[0] != '-') {
      proto_files.push_back(arg);
    } else {
      std::cerr << "Unknown argument: " << arg << std::endl;
      return 1;
    }
  }

  if (proto_files.empty()) {
    std::cerr << "No .proto files specified." << std::endl;
    return 1;
  }

  // Set up the importer
  google::protobuf::compiler::DiskSourceTree source_tree;
  if (include_paths.empty()) {
    source_tree.MapPath("", "."); // Default to current directory
  } else {
    for (const auto& path : include_paths) {
      source_tree.MapPath("", path);
    }
  }
  google::protobuf::compiler::Importer importer(&source_tree, nullptr);

  google::protobuf::FileDescriptorSet fd_set;
  for (const auto& file : proto_files) {
    const google::protobuf::FileDescriptor* fd = importer.Import(file.c_str());
    if (!fd) {
      std::cerr << "Failed to import: " << file << std::endl;
      return 1;
    }
    auto* proto = fd_set.add_file();
    fd->CopyTo(proto);
  }

  // Write to stdout
  fd_set.SerializeToOstream(&std::cout);
  return 0;
}
