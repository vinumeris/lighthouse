#!/bin/bash

set -e

# Extract the version number.
ver=$( sed -n 's/^.*final int VERSION = //p' client/src/main/java/lighthouse/Main.java )
ver="${ver:0:${#ver}-1}"

echo "Building version $ver..."
mvn -q -U clean package -DskipTests
[ ! -e updates ] && mkdir -p updates/builds

dest=updates/builds/$ver.jar

#echo "Decompressing JAR for smaller updates"
#mkdir client/target/repack
#cd client/target/repack
#unzip -q ../client-0.1-SNAPSHOT-bundled.jar
#jar c0fm uncompressed.jar META-INF/MANIFEST.MF *
#cd - >/dev/null
#cp client/target/repack/uncompressed.jar $dest
#rm -r client/target/repack

cp client/target/client-0.1-SNAPSHOT-bundled.jar $dest
echo "Copied build as version $ver to $dest"

# TODO: Find a way to canonicalise timestamps to make small patches small.

echo "Generating online update site"
rm updates/site/* || true
java -jar tools/updatefx.jar --url=https://s3-eu-west-1.amazonaws.com/vinumeris/lighthouse/updates updates

if [[ $1 == "--package" ]]; then
    if [ -e /Library ]; then
        ./mac-package.sh
    elif [ -e /proc ]; then
        ./linux-package.sh
    fi
fi