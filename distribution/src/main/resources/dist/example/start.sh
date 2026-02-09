#!/bin/bash -e
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# ManifoldCF start script (Maven-built distribution)
# Uses wildcard classpath instead of explicit JAR enumeration

if [ -f ./properties.xml ] ; then
    # Build classpath from all JARs in ../lib/
    LIB_DIR="../lib"
    CLASSPATH="."
    for jar in "$LIB_DIR"/*.jar; do
        CLASSPATH="$CLASSPATH:$jar"
    done

    JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx512m}"
    MCF_CONFIG="-Dorg.apache.manifoldcf.configfile=./properties.xml"
    MCF_SHUTDOWN_TOKEN="-Dorg.apache.manifoldcf.jettyshutdowntoken=secret_token"
    JAAS_CONFIG="-Djava.security.auth.login.config="

    if [ -n "$JAVA_HOME" ] && [ -e "$JAVA_HOME/bin/java" ] ; then
        JAVA_CMD="$JAVA_HOME/bin/java"
    else
        JAVA_CMD="java"
    fi

    echo "Starting ManifoldCF..."
    exec "$JAVA_CMD" $JAVA_OPTS $MCF_CONFIG $MCF_SHUTDOWN_TOKEN $JAAS_CONFIG \
        -cp "$CLASSPATH" \
        org.apache.manifoldcf.jettyrunner.ManifoldCFJettyRunner
else
    echo "Working directory contains no properties.xml file." 1>&2
    exit 1
fi
