#!/bin/bash

set -e

# Extract the version number.
ver=$( sed -n 's/^.*final int VERSION = //p' client/src/main/java/lighthouse/Main.java )
ver="${ver:0:${#ver}-1}"
build=updates/builds/processed/$ver.jar

if [ ! -e "$build" ]; then
  echo "Must run package.sh first to generate build"
  exit 1
fi

javapackager -deploy \
    -BappVersion=$ver \
    -Bcategory=Office,Finance \
    -BlicenseType=Apache \
    -BlicenseFile=LICENSE \
    -Bemail=contact@vinumeris.com \
    -Bicon=client/icons/icon.png \
    -native deb \
    -name lighthouse \
    -title "Lighthouse: a peer to peer crowdfunding app that uses Bitcoin" \
    -vendor Vinumeris \
    -outdir deploy \
    -appclass lighthouse.Main \
    -srcfiles $build \
    -srcfiles package/linux/LICENSE \
    -srcfiles package/linux/postinst \
    -srcfiles package/linux/prerm \
    -outfile lighthouse
