#!/usr/bin/env jjs -scripting

// Tool to extract strings from source code and merge them into the pot file. We use this to automate execution of
// the xgettext tool and to properly handle strings in FXML.
//
// This is written in Nashorn-flavoured Javascript. Nashorn is a Javascript engine that ships with JDK 8.
//
// Useful language extensions we get in this mode include:
//
//  - bash style backtick callouts to the shell
//  - shorter lambda forms
//  - for each loops
//  - use of Java APIs

"use strict";

var Files = java.nio.file.Files;
var Paths = java.nio.file.Paths;

var files = `find client/src/main/resources/lighthouse -name *.fxml`.split("\n").filter(function(i) i != "");
var output = "";

for each (var file in files) {
    var xml = new org.xml.sax.InputSource(file);
    var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
    var results = xpath.evaluate("//@*[starts-with(., '%')]", xml, javax.xml.xpath.XPathConstants.NODESET);
    for (var i = 0; i < results.getLength(); i++) {
        output += "_(\"" + results.item(i).value.replace(/\n/g, "\\n").substr(1) + "\")";
        output += "\n";
    }
}

$EXEC("xgettext -k_ -cTRANS --no-location --language=C /dev/stdin -o i18n/lighthouse.pot --join-existing --from-code=UTF-8", output);
$EXEC("xargs xgettext -ktr -cTRANS --no-location --language=Java -o i18n/lighthouse.pot --join-existing --from-code=UTF-8", `find client -name *.java`);
// We need this disgusting hack because Kotlin treats $s in strings specially and requires escaping to avoid it.
// This doesn't play nicely with positional parameters. So pre-process each .kt file here. See KT-7258.
for each (var kt in `find client -name *.kt`.split("\n")) {
    var processedKT = $EXEC("cat " + kt).replace(/\\\$/g, "$");
    $EXEC("xgettext -ktr -cTRANS --no-location --language=Java -o i18n/lighthouse.pot --join-existing --from-code=UTF-8 /dev/stdin", processedKT);
}