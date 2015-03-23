# Translating Lighthouse

Lighthouse now supports localization using GNU GetText. It's an open source tool, and you will need it for adding or modifying translations.

On a Ubuntu / Debian based Linux distro, you can install it with `sudo apt-get install gettext`

To extract strings for translation from source code, go to the root folder of Lighthouse and issue this command:

    find client/ -name '*.java' -or -name '*.kt'  | xargs xgettext -k_ -cTRANS --language=Java --from-code UTF-8 -o i18n/lighthouse.pot


How to add a new language:

1. Create the language file. Let's say you want to translate it to Spanish, so the language code is 'es'.

    `msginit -l es -i i18n/lighthouse.pot -o i18n/locale_es.po`

2. Open the file in a .po translation editor

3. Translate the strings and save

4. Use this to compile your translation, don't forget to put your language code after `-l`

    `msgfmt --java i18n/locale_es.po -d client/src/main/resources/ -l es -r lighthouse.locale.lighthouse`

5. Rebuild the Lighthouse app using

    `mvn clean package -Dmaven.test.skip=true`

If you now change your locale to this language and launch Lighthouse, it will use your translations.

**Good news:** this is the hard way. An on-line translation project will be created soon, making translation contributions easy and user friendly. A link to the project will be added here.
