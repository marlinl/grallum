#!/bin/sh
# Validate all Java sources and tests against the repository's Maven lint rules.
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec mvn -B -ntp -f "$project_root/pom.xml" validate "$@"
