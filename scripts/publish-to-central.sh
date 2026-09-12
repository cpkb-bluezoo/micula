#!/usr/bin/env bash
# Builds, signs, bundles, and uploads a release to Maven Central's Publisher
# API. The single canonical implementation of this process - both the
# release-to-maven-central.yml workflow and a maintainer running this by
# hand locally call this same script, so there is exactly one place that
# knows how to do this, not two copies that can drift apart.
#
# Usage:
#   GPG_KEY_ID=27196849F242508A \
#   GPG_PASSPHRASE=... \
#   CENTRAL_TOKEN_USERNAME=... \
#   CENTRAL_TOKEN_PASSWORD=... \
#   ./scripts/publish-to-central.sh
#
# Required environment variables:
#   GPG_KEY_ID               - key ID (or fingerprint) to sign with; must
#                               already be in the local/CI GPG keyring
#   GPG_PASSPHRASE            - passphrase for that key
#   CENTRAL_TOKEN_USERNAME   - Sonatype Central user token username
#   CENTRAL_TOKEN_PASSWORD   - Sonatype Central user token password
#
# Optional:
#   VERSION                  - the release version, e.g. 1.0 - if unset,
#                               auto-detected from pom.xml's own <version>.
#                               Either way (given or auto-detected), it is
#                               cross-checked against pom.xml and build.xml's
#                               own version strings before building anything -
#                               see the mismatch check below.
#   PUBLISHING_TYPE          - AUTOMATIC or USER_MANAGED (default:
#                               AUTOMATIC - goes live on Central as soon as
#                               validation passes, no manual "Publish" click
#                               needed. A released version can never be
#                               deleted or overwritten, so only rely on this
#                               once the pipeline is trusted; set
#                               PUBLISHING_TYPE=USER_MANAGED to fall back to
#                               reviewing and clicking Publish by hand at
#                               central.sonatype.com)
#
# This script does not commit, tag, or push anything - it only builds
# whatever is currently checked out and publishes it under the given
# VERSION. Run it from the repository root.

set -euo pipefail

for var in GPG_KEY_ID GPG_PASSPHRASE CENTRAL_TOKEN_USERNAME CENTRAL_TOKEN_PASSWORD; do
    if [ -z "${!var:-}" ]; then
        echo "error: $var must be set" >&2
        exit 1
    fi
done

POM_VERSION=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
if [ -z "$POM_VERSION" ]; then
    echo "error: could not read <version> from pom.xml" >&2
    exit 1
fi

if [ -z "${VERSION:-}" ]; then
    VERSION="$POM_VERSION"
    echo "==> Auto-detected VERSION=$VERSION from pom.xml"
fi

# pom.xml is copied as-is into the bundle (just renamed to
# micula-$VERSION.pom) - if its own <version> disagrees with $VERSION,
# the published POM's declared version would mismatch its Maven coordinate.
# build.xml's "release" property is a third, independent source of the same
# number (used as the default for its release/release-sources/release-javadoc
# targets). If any of these have drifted apart, that almost certainly means
# one was bumped without the others, so refuse to publish rather than risk
# shipping the wrong artifact under the wrong coordinate.
if [ "$POM_VERSION" != "$VERSION" ]; then
    echo "error: version mismatch - requested VERSION=$VERSION, but pom.xml's <version> says $POM_VERSION" >&2
    echo "       fix whichever one is stale before publishing" >&2
    exit 1
fi

BUILD_XML_VERSION=$(grep -m1 'name="release"' build.xml | sed -E 's/.*value="([^"]*)".*/\1/')
if [ -n "$BUILD_XML_VERSION" ] && [ "$BUILD_XML_VERSION" != "$VERSION" ]; then
    echo "error: version mismatch - VERSION=$VERSION, but build.xml's 'release' property says $BUILD_XML_VERSION" >&2
    echo "       fix whichever one is stale before publishing" >&2
    exit 1
fi

PUBLISHING_TYPE="${PUBLISHING_TYPE:-AUTOMATIC}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo "==> Building release artifacts (version $VERSION)"
ant release release-sources release-javadoc -Drelease="$VERSION"

echo "==> Assembling and signing Central bundle"
BUNDLE_DIR="$WORKDIR/bundle/org/bluezoo/micula/$VERSION"
mkdir -p "$BUNDLE_DIR"

cp pom.xml "$BUNDLE_DIR/micula-$VERSION.pom"
cp "dist/micula-$VERSION.jar" "$BUNDLE_DIR/"
cp "dist/micula-$VERSION-sources.jar" "$BUNDLE_DIR/"
cp "dist/micula-$VERSION-javadoc.jar" "$BUNDLE_DIR/"

(
    cd "$BUNDLE_DIR"
    for f in *.jar *.pom; do
        gpg --batch --local-user "$GPG_KEY_ID" --pinentry-mode loopback \
            --passphrase "$GPG_PASSPHRASE" -ab "$f"
        md5sum "$f" | cut -d' ' -f1 > "$f.md5"
        shasum -a 1 "$f" | cut -d' ' -f1 > "$f.sha1"
    done
)

BUNDLE_ZIP="$WORKDIR/central-bundle.zip"
(cd "$WORKDIR/bundle" && zip -qr "$BUNDLE_ZIP" .)
echo "Bundle assembled at $BUNDLE_ZIP:"
unzip -l "$BUNDLE_ZIP"

# Optional: preserve a copy of the bundle outside the temp workdir (e.g. so
# CI can upload it as an inspectable artifact) before the EXIT trap deletes
# the workdir.
if [ -n "${KEEP_BUNDLE_AT:-}" ]; then
    cp "$BUNDLE_ZIP" "$KEEP_BUNDLE_AT"
fi

echo "==> Uploading to Maven Central (publishingType=$PUBLISHING_TYPE)"
TOKEN=$(printf '%s:%s' "$CENTRAL_TOKEN_USERNAME" "$CENTRAL_TOKEN_PASSWORD" | base64 | tr -d '\n')
DEPLOYMENT_ID=$(curl --fail --request POST \
    -H "Authorization: Bearer $TOKEN" \
    --form bundle=@"$BUNDLE_ZIP" \
    "https://central.sonatype.com/api/v1/publisher/upload?publishingType=$PUBLISHING_TYPE")

echo
echo "==> Uploaded. Deployment ID: $DEPLOYMENT_ID"
if [ "$PUBLISHING_TYPE" = "USER_MANAGED" ]; then
    echo "This will NOT go live until you review and click Publish at:"
    echo "  https://central.sonatype.com/publishing/deployments"
fi
