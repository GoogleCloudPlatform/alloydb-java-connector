#!/bin/bash
# Copyright 2019 Google LLC
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

set -eo pipefail

## Get the directory of the build script
scriptDir=$(realpath $(dirname "${BASH_SOURCE[0]}"))
## cd to the parent directory, i.e. the root of the git repo
cd ${scriptDir}/..

# include common functions
source ${scriptDir}/common.sh

# Print out Maven & Java version
./mvnw -version
echo ${JOB_TYPE}

# attempt to install 3 times with exponential backoff (starting with 10 seconds)
retry_with_backoff 3 10 \
  ./mvnw install -B -V -ntp \
    -DskipTests=true \
    -Dclirr.skip=true \
    -Denforcer.skip=true \
    -Dmaven.javadoc.skip=true \
    -Dgcloud.download.skip=true \
    -T 1C

RETURN_CODE=0
set +e

case ${JOB_TYPE} in
test)
    ./mvnw test -B -ntp -Pcoverage -Dclirr.skip=true -Denforcer.skip=true
    RETURN_CODE=$?
    ;;
lint)
    ./mvnw com.coveo:fmt-maven-plugin:check -B -ntp
    RETURN_CODE=$?
    ;;
javadoc)
    ./mvnw javadoc:javadoc javadoc:test-javadoc -B -ntp
    RETURN_CODE=$?
    ;;
integration)
    ./mvnw -B ${INTEGRATION_TEST_ARGS} \
      -ntp \
      -Penable-integration-tests \
      -DtrimStackTrace=false \
      -Dclirr.skip=true \
      -Denforcer.skip=true \
      -fae \
      verify
    RETURN_CODE=$?
    ;;
graalvm)
    # Run Unit and Integration Tests with Native Image
    ./mvnw -B ${INTEGRATION_TEST_ARGS} -ntp -Pnative test
    RETURN_CODE=$?
    ;;
samples)
    SAMPLES_DIR=samples
    # only run ITs in snapshot/ on presubmit PRs. run ITs in all 3 samples/ subdirectories otherwise.
    if [[ ! -z ${KOKORO_GITHUB_PULL_REQUEST_NUMBER} ]]
    then
      SAMPLES_DIR=samples/snapshot
    fi

    if [[ -f ${SAMPLES_DIR}/pom.xml ]]
    then
        for FILE in ${KOKORO_GFILE_DIR}/secret_manager/*-samples-secrets; do
          [[ -f "$FILE" ]] || continue
          source "$FILE"
        done

        pushd ${SAMPLES_DIR}
        ./mvnw -B \
          -ntp \
          -DtrimStackTrace=false \
          -Dclirr.skip=true \
          -Denforcer.skip=true \
          -fae \
          verify
        RETURN_CODE=$?
        popd
    else
        echo "no sample pom.xml found - skipping sample tests"
    fi
    ;;
clirr)
    ./mvnw -B -ntp -Denforcer.skip=true clirr:check
    RETURN_CODE=$?
    ;;
*)
    ;;
esac

echo "exiting with ${RETURN_CODE}"
exit ${RETURN_CODE}
