#!/bin/bash

if [ ! -e javafx-src.zip ]; then
    echo "Not in the right directory"
    exit 1
fi
rm javafx-src.zip COPYRIGHT LICENSE README.html THIRDPARTYLICENSEREADME-JAVAFX.txt THIRDPARTYLICENSEREADME.txt
rm jre/lib/libjfxwebkit.dylib jre/lib/libgstreamer-lite.dylib jre/lib/libglib-lite.dylib jre/lib/libAppleScriptEngine.dylib jre/lib/plugin.jar jre/lib/ext/nashorn.jar

packages="apple/laf com/apple/laf com/oracle/webservices com/sun/corba com/sun/java/swing com/sun/media/sound"
packages="$packages com/sun/jndi com/sun/org/glassfish com/sun/org/omg com/sun/xml/internal/ws java/rmi javax/activation"
packages="$packages javax/management javax/naming javax/rmi javax/smartcardio javax/sound javax/swing"
packages="$packages javax/xml/ws org/omg sun/applet sun/corba sun/management sun/rmi sun/swing"

args=""
for p in $packages; do
    args="$args $p/*"
done
zip -d jre/lib/rt.jar $args