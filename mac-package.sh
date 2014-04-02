#!/bin/bash

set -e

# Extract the version number.
# ver=$( sed -n 's/^.*final int VERSION = //p' client/src/main/java/lighthouse/Main.java )
# ver="${ver:0:${#ver}-1}"
ver=1
build=updates/builds/$ver.jar

if [ ! -e "$build" ]; then
  echo "Must run package.sh first to generate build"
  exit 1
fi

jh=/Library/Java/JavaVirtualMachines/jdk1.8.0_20.jdk/Contents/Home/
$jh/bin/javapackager -deploy \
    -BappVersion=$ver \
    -Bmac.CFBundleIdentifier=com.vinumeris.lighthouse \
    -Bmac.CFBundleName=Lighthouse \
    -Bicon=client/icons/mac.icns \
    -Bruntime="$jh/../../" \
    -native dmg \
    -name Lighthouse \
    -title Lighthouse \
    -vendor Vinumeris \
    -outdir deploy \
    -appclass lighthouse.Main \
    -srcfiles updates/builds/$ver.jar \
    -outfile Lighthouse

# TODO: Gatekeeper signing