#!/usr/bin/env bash
# Keep the Robolectric test project's compile classpath in lock-step with the
# APK build: installs build/classes + every libs/*.jar into the local Maven repo
# and regenerates the dependency block in robolectric-test/pom.xml.
#
# Run this after build_apk.sh and before `mvn test` (verify_apk.sh does both).
set -euo pipefail

ROOT="/root/moonbear-k4-auto"
POM="$ROOT/robolectric-test/pom.xml"

echo "[1/3] packaging app classes"
jar cf /tmp/k4auto-app.jar -C "$ROOT/build/classes" .
mvn -q install:install-file \
  -Dfile=/tmp/k4auto-app.jar \
  -DgroupId=dev.moonbear -DartifactId=k4auto-app -Dversion=1.2.0 -Dpackaging=jar

echo "[2/3] installing bundled jars"
deps=""
for jar in "$ROOT"/libs/*.jar; do
  name="$(basename "$jar" .jar)"
  mvn -q install:install-file \
    -Dfile="$jar" \
    -DgroupId=dev.androidx.bundle -DartifactId="$name" -Dversion=1.0 -Dpackaging=jar
  deps="${deps}    <dependency>\n      <groupId>dev.androidx.bundle</groupId>\n      <artifactId>${name}</artifactId>\n      <version>1.0</version>\n    </dependency>\n"
done

echo "[3/3] regenerating pom dependency block"
python3 - "$POM" "$deps" <<'PYEOF'
import sys, io
pom, deps = sys.argv[1], sys.argv[2].encode().decode("unicode_escape")
src = io.open(pom, encoding="utf-8").read()
header = """    <!-- BEGIN GENERATED DEPS (sync_test_classpath.sh) -->
    <dependency>
      <groupId>dev.moonbear</groupId>
      <artifactId>k4auto-app</artifactId>
      <version>1.2.0</version>
    </dependency>
"""
footer = "    <!-- END GENERATED DEPS -->\n"
start = src.index("    <!-- BEGIN GENERATED DEPS")
end = src.index("    <!-- END GENERATED DEPS -->") + len(footer)
io.open(pom, "w", encoding="utf-8").write(src[:start] + header + deps + footer + src[end:])
print("pom dependency block regenerated")
PYEOF

echo "done"
