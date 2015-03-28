#!/usr/bin/env bash

# Uses GNU gettext to extract translatable strings into a "pot" template file.

find client -name '*.java' -or -name '*.kt' | xargs xgettext -k_ -cTRANS --language=Java --join-existing --from-code UTF-8 -o i18n/lighthouse.pot --package-name=Lighthouse --copyright-holder="Vinumeris GmbH" --msgid-bugs-address="lighthouse-dev@googlegroups.com"
