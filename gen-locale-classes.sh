#!/usr/bin/env bash
tx pull -a
pushd i18n
for f in *.po; do
    echo "Processing $f"
    msgfmt --java $f -d ../client/src/main/resources/ -l ${f/.po/} -r lighthouse.locale.lighthouse
done
popd