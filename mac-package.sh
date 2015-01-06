#!/bin/bash

set -e

# Extract the version number.
ver=$( sed -n 's/^.*final int VERSION = //p' client/src/main/java/lighthouse/Main.java )
ver="${ver:0:${#ver}-1}"
build=updates/builds/processed/$ver.jar

# Generate the plist from the template
sed "s|JAR_NAME_STRING_GOES_HERE|<string>$ver.jar</string>|" package/macosx/Info.template.plist >package/macosx/Info.plist

if [ ! -e "$build" ]; then
  echo "Must run package.sh first to generate build"
  exit 1
fi

jh=$(/usr/libexec/java_home -v 1.8)
if [ -e ../min-jdk ]; then
  jh=`pwd`/../min-jdk/Contents/Home
fi
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
    -srcfiles $build \
    -outfile Lighthouse
