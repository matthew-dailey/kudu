# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Use pkgconfig to configure the build regarding liboauth. This allows
# to extract info on include and library paths, etc. The liboauth library
# is installed at alternative location on MacOS X.
find_package(PkgConfig)
if (NOT PKG_CONFIG_FOUND)
  message(WARNING "pkgconfig not found. Skipping twitter demo.")
else()
  pkg_search_module(LIBOAUTH oauth)
  if(NOT LIBOAUTH_FOUND)
    message(WARNING "liboauth not found. Skipping twitter demo.")
  else()
    include_directories(SYSTEM ${LIBOAUTH_INCLUDE_DIRS})
    link_directories(${LIBOAUTH_LIBRARY_DIRS})
    add_library(twitter_demo
      oauth.cc
      parser.cc
      insert_consumer.cc
      twitter_streamer.cc)

    target_link_libraries(twitter_demo
      gutil
      kudu_util
      kudu_test_util)

    target_link_libraries(twitter_demo
      kudu_client
      ${LIBOAUTH_LIBRARIES}
      ${CURL_LIBRARIES}
      ${KUDU_BASE_LIBS})

    # Require that the tserver protobuf code is generated first
    add_dependencies(twitter_demo
      tserver_proto)

    add_executable(ingest_firehose ingest_firehose.cc)
    target_link_libraries(ingest_firehose
      twitter_demo)

    # Tests
    ADD_KUDU_TEST(oauth-test)
    # parser-test relies on symlinked data files which we can't currently copy correctly
    # to the cluster.
    ADD_KUDU_TEST(parser-test LABELS no_dist_test)
    if(NOT "${NO_TESTS}")
      target_link_libraries(oauth-test
        twitter_demo)
      target_link_libraries(parser-test
        twitter_demo)
      execute_process(COMMAND ln -sf ${CMAKE_CURRENT_SOURCE_DIR}/example-tweets.txt
        ${EXECUTABLE_OUTPUT_PATH})
      execute_process(COMMAND ln -sf ${CMAKE_CURRENT_SOURCE_DIR}/example-deletes.txt
        ${EXECUTABLE_OUTPUT_PATH})
    endif()

  endif() # if(NOT LIBOAUTH_LIBRARY) ... else ...
endif() # if (NOT PKG_CONFIG_FOUND) ... else ...
