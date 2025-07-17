// Protocol Buffers - Google's data interchange format
// Copyright 2008 Google Inc.  All rights reserved.
//
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file or at
// https://developers.google.com/open-source/licenses/bsd

#include "{{INCLUDE_PATH}}"
#include "google/protobuf/compiler/plugin.h"

int main(int argc, char *argv[]) {
  {{GENERATOR_TYPE}} generator;
#ifdef GOOGLE_PROTOBUF_RUNTIME_INCLUDE_BASE
  generator.set_opensource_runtime(true);
  generator.set_runtime_include_base(GOOGLE_PROTOBUF_RUNTIME_INCLUDE_BASE);
#endif
  return ::google::protobuf::compiler::PluginMain(argc, argv, &generator);
}