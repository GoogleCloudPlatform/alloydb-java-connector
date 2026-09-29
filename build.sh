#!/usr/bin/env bash

# Copyright 2026 Google LLC.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Set SCRIPT_DIR to the current directory of this file.
SCRIPT_DIR=$(cd -P "$(dirname "$0")" >/dev/null 2>&1 && pwd)
SCRIPT_FILE="${SCRIPT_DIR}/$(basename "$0")"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]]; then
    export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
    export PATH="$JAVA_HOME/bin:$PATH"
  elif [[ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]]; then
    export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

if [[ -f "$SCRIPT_DIR/mvnw" ]]; then
  mvn_cmd="$SCRIPT_DIR/mvnw"
else
  mvn_cmd="mvn"
fi

##
## Local Development
##
## These functions should be used to run the local development process
##

## clean - Cleans the build output
function clean() {
  $mvn_cmd clean "$@"
}

## build - Builds the project without running tests.
function build() {
   $mvn_cmd install -DskipTests=true "$@"
}

## test - Runs local unit tests.
function test() {
  if [[ "$(uname -s)" == "Darwin" ]]; then
    echo "macOS detected. Setting up IP aliases for tests."
    echo "You may be prompted for your password to run sudo."
    if ! ifconfig lo0 | grep -q 127.0.0.2 ; then
      sudo ifconfig lo0 alias 127.0.0.2 up
    fi
    if ! ifconfig lo0 | grep -q 127.0.0.3 ; then
      sudo ifconfig lo0 alias 127.0.0.3 up
    fi
  fi
  scripts/test_units.sh "$@"
}

## e2e - Runs end-to-end integration tests.
function e2e() {
  if [[ ! -f .envrc ]] ; then
    write_e2e_env .envrc
  fi
  source .envrc
  scripts/test_system.sh "$@"
}

## e2e_graalvm - Runs end-to-end integration tests with GraalVM native image.
function e2e_graalvm() {
  if [[ ! -f .envrc ]] ; then
    write_e2e_env .envrc
  fi
  source .envrc
  scripts/test_graalvm.sh "$@"
}

## fix - Fixes java code format.
function fix() {
  scripts/format.sh "$@"
}

## lint - runs the java lint
function lint() {
  scripts/lint.sh "$@"
}

## check_dependencies - Checks for unused or undeclared dependencies
function check_dependencies() {
  scripts/check_dependencies.sh "$@"
}

## check_clirr - Checks binary compatibility with previous release
function check_clirr() {
  scripts/check_clirr.sh "$@"
}

## deps - updates dependencies to the latest version
function deps() {
  $mvn_cmd versions:use-latest-versions "$@"
  find . -name 'pom.xml.versionsBackup' -print0 | xargs -0 rm -f
}

# write_e2e_env - Loads secrets from the gcloud project and writes
#     them to target/e2e.env to run e2e tests.
#
function write_e2e_env(){
  # Set the default to .envrc file if no argument is passed
  outfile="${1:-.envrc}"
  secret_vars=(
    ALLOYDB_INSTANCE_NAME=ALLOYDB_INSTANCE_URI
    ALLOYDB_PASS=ALLOYDB_CLUSTER_PASS
    ALLOYDB_IAM_USER=ALLOYDB_JAVA_IAM_USER
    ALLOYDB_INSTANCE_IP=ALLOYDB_INSTANCE_IP
    ALLOYDB_IMPERSONATED_USER=IMPERSONATED_USER
    ALLOYDB_PSC_INSTANCE_URI=ALLOYDB_PSC_INSTANCE_URI
  )

  if [[ -z "${TEST_PROJECT:-}" ]] ; then
    echo "Set TEST_PROJECT environment variable to the project containing"
    echo "the e2e test suite secrets."
    exit 1
  fi

  echo "Getting test secrets from $TEST_PROJECT into $outfile"
  {
  echo "export ALLOYDB_DB='postgres'"
  echo "export ALLOYDB_USER='postgres'"
  for env_name in "${secret_vars[@]}" ; do
    env_var_name="${env_name%%=*}"
    secret_name="${env_name##*=}"
    set -x
    val=$(gcloud secrets versions access latest --project "$TEST_PROJECT" --secret="$secret_name")
    echo "export $env_var_name='$val'"
  done
  } > "$outfile"
}

## help - prints the help details
##
function help() {
   # Note: This will print the comments beginning with ## above each function
   # in this file.

   echo "build.sh <command> <arguments>"
   echo
   echo "Commands to assist with local development and CI builds."
   echo
   echo "Commands:"
   echo
   grep -e '^##' "$SCRIPT_FILE" | sed -e 's/##/ /'
}

set -euo pipefail

# Check CLI Arguments
if [[ "$#" -lt 1 ]] ; then
  help
  exit 1
fi

cd "$SCRIPT_DIR"

"$@"
