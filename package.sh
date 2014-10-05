#!/bin/bash

set -e

# Extract the version number.
ver=$( sed -n 's/^.*final int VERSION = //p' client/src/main/java/lighthouse/Main.java )
ver="${ver:0:${#ver}-1}"

echo "Building version $ver..."
mvn -q -U clean package -DskipTests
[ ! -e updates ] && mkdir -p updates/builds

dest=updates/builds/$ver.jar

cp client/target/client-0.1-SNAPSHOT-bundled.jar $dest
echo "Copied build as version $ver to $dest"

echo "Generating online update site"
rm updates/site/* || true
java -jar tools/updatefx.jar --url=https://s3-eu-west-1.amazonaws.com/vinumeris/lighthouse/updates --gzip-from=7 updates

if [[ $1 == "--package" ]]; then
    if [ -e /Library ]; then
        ./mac-package.sh
    elif [ -e /proc ]; then
        ./linux-package.sh
    fi
fi