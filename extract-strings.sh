#!/usr/bin/env bash


# Extract translatable strings from Java and Kotlin
find client -name '*.java' -or -name '*.kt' | xargs xgettext --no-location -ktr -cTRANS --language=Java --join-existing --from-code UTF-8 -o i18n/lighthouse.pot --package-name=Lighthouse --copyright-holder="Vinumeris GmbH" --msgid-bugs-address="lighthouse-dev@googlegroups.com"

# Extract translatable strings from FXML
find client/src/main/resources/lighthouse -name '*.fxml' \
   | xargs xml select -t -v '//@*' -n \
   | egrep '^%' \
   | sed 's/^%//g' \
   | while read; do echo "_(\"$REPLY\")"; done \
   | xgettext -k_ -cTRANS --no-location --language=C /dev/stdin -o i18n/lighthouse.pot --join-existing --from-code=UTF-8
