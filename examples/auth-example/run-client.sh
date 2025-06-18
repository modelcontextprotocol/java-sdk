#!/bin/bash
cd "$(dirname "$0")"
mvn exec:java -Dexec.mainClass="io.modelcontextprotocol.examples.auth.client.SimpleAuthClient"