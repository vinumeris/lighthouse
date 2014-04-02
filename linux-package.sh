#!/bin/bash

set -e

# Extract the version number.
ver=$( sed -n 's/^.*final int VERSION = //p' client/src/main/java/lighthouse/Main.java )
ver="${ver:0:${#ver}-1}"
build=updates/builds/$ver.jar

if [ ! -e "$build" ]; then
  echo "Must run package.sh first to generate build"
  exit 1
fi

javapackager -deploy \
    -BappVersion=1 \
    -Bcategory=Office,Finance \
    -BlicenseType=Apache \
    -Bemail=contact@vinumeris.com \
    -Bicon=client/icons/icon.png \
    -native deb \
    -name lighthouse \
    -title Lighthouse \
    -vendor Vinumeris \
    -outdir deploy \
    -appclass lighthouse.Main \
    -srcfiles updates/builds/$ver.jar \
    -outfile lighthouse

# TODO: Figure out where LICENSE file goes so distros don't complain about "low quality" packages.
