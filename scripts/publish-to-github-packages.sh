#!/usr/bin/env bash
# Uploads release JARs to GitHub Packages (Maven registry). Expects
# dist/micula-$VERSION.{jar,-sources.jar,-javadoc.jar} and pom.xml to
# already exist (normally after the Ant build in publish-to-central.sh).
#
# Deploys the main jar, POM, sources, and javadoc in one Maven
# deploy:deploy-file call. Separate calls each re-upload the POM, which
# GitHub Packages rejects with HTTP 409 after the first artifact.
#
# Required:
#   VERSION           - release version (same as pom.xml)
#   GITHUB_TOKEN      - token with packages:write (GITHUB_TOKEN in Actions)
#
# Optional:
#   GITHUB_REPOSITORY - owner/repo (default cpkb-bluezoo/micula)
#   GITHUB_ACTOR      - username for Maven auth (default: x-access-token)
#
# Skips with exit 0 when GITHUB_TOKEN is unset (local Central-only publish).

set -euo pipefail

if [ -z "${GITHUB_TOKEN:-}" ]; then
    echo "==> Skipping GitHub Packages (GITHUB_TOKEN not set)"
    exit 0
fi

if [ -z "${VERSION:-}" ]; then
    echo "error: VERSION must be set" >&2
    exit 1
fi

if ! command -v mvn >/dev/null 2>&1; then
    echo "error: mvn is required to publish to GitHub Packages" >&2
    exit 1
fi

REPO="${GITHUB_REPOSITORY:-cpkb-bluezoo/micula}"
ACTOR="${GITHUB_ACTOR:-x-access-token}"
GROUP_ID=$(grep -m1 '<groupId>' pom.xml | sed -E 's/.*<groupId>(.*)<\/groupId>.*/\1/')
ARTIFACT_ID=$(grep -m1 '<artifactId>' pom.xml | sed -E 's/.*<artifactId>(.*)<\/artifactId>.*/\1/')

MAIN_JAR="dist/micula-$VERSION.jar"
SOURCES_JAR="dist/micula-$VERSION-sources.jar"
JAVADOC_JAR="dist/micula-$VERSION-javadoc.jar"

for f in "$MAIN_JAR" "$SOURCES_JAR" "$JAVADOC_JAR"; do
    if [ ! -f "$f" ]; then
        echo "error: missing $f (run ant release first)" >&2
        exit 1
    fi
done

SETTINGS="$(mktemp)"
trap 'rm -f "$SETTINGS"' EXIT

cat > "$SETTINGS" <<EOF
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <servers>
    <server>
      <id>github</id>
      <username>${ACTOR}</username>
      <password>${GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
EOF

URL="https://maven.pkg.github.com/${REPO}"

echo "==> Publishing to GitHub Packages ($URL)"
# One call so the POM is uploaded once. Classifiers alone with -DpomFile
# each attempt another POM write and GitHub Packages returns 409 Conflict.
mvn --batch-mode -q -s "$SETTINGS" deploy:deploy-file \
    -DgroupId="$GROUP_ID" \
    -DartifactId="$ARTIFACT_ID" \
    -Dversion="$VERSION" \
    -Dpackaging=jar \
    -Dfile="$MAIN_JAR" \
    -DpomFile=pom.xml \
    -Dsources="$SOURCES_JAR" \
    -Djavadoc="$JAVADOC_JAR" \
    -DrepositoryId=github \
    -Durl="$URL"
echo "==> GitHub Packages upload complete"
