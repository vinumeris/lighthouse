#!/bin/bash

set -e

if grep 'String UPDATES_BASE_URL = null' client/src/main/java/lighthouse/Main.java >/dev/null; then
    echo "Don't use this for Crowdfunding App"
    exit 1
fi
# Extract the version number.
ver=$( sed -n 's/^.*final int VERSION = //p' client/src/main/java/lighthouse/Main.java )
ver="${ver:0:${#ver}-1}"

echo "Building version $ver..."
mvn -q -U clean package -DskipTests
[ ! -e updates ] && mkdir -p updates/builds

dest=updates/builds/$ver.jar

echo "Running ProGuard to delete dead code and shrink JAR ..."
java -jar ~/.m2/repository/net/sf/proguard/proguard-base/5.0/proguard-base-5.0.jar @client/proguard.pro

du -h client/target/shaded.jar client/target/lighthouse.jar

cp client/target/lighthouse.jar $dest
echo "Copied build as version $ver to $dest"

echo "Generating online update site"
rm updates/site/* || true
java -jar tools/updatefx.jar --url=https://s3-eu-west-1.amazonaws.com/vinumeris/lighthouse/updates --gzip-from=7 updates

echo "Generating Windows build script"
echo "& 'C:\Program Files\Java\stripped8u20\bin\javapackager.exe' -deploy -BappVersion=$ver -native exe -name Lighthouse -title Lighthouse -vendor Vinumeris -outdir deploy -appclass lighthouse.Main -srcfiles .\\updates\\builds\\processed\\$ver.jar -outfile Lighthouse -Bruntime='c:\Program Files\Java\stripped8u20\jre'" >win-build.ps1

if [[ $1 == "--package" ]]; then
    if [ -e /Library ]; then
        ./mac-package.sh
    elif [ -e /proc ]; then
        ./linux-package.sh
    fi
fi