#!/bin/sh
# Compiles the phone app's own logic (the "core" package, no Android needed) and runs its tests.
#   sh coretest/run.sh                       the logic tests
#   sh coretest/run.sh wty-it-it.zip ...     also imports those dictionaries and looks words up
cd "$(dirname "$0")/.." || exit 1
OUT=$(mktemp -d)
javac -encoding UTF-8 -d "$OUT" app/src/main/java/com/batchmine/pocket/core/*.java coretest/src/*.java || exit 1
java -cp "$OUT" CoreTest || exit 1
if [ $# -gt 0 ]; then java -Xmx256m -cp "$OUT" DictTest "$OUT/dicts" "$@"; fi
