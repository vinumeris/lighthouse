These instructions are only really meant for Mike.

To release a new version via online update:

* Remember to fill out the update-description.txt file
* Run ./package.sh to compile the app, calculate an update and new signed index.
* Upload the patch via the AWS console
* Sync the index to the website
* Bump the version number in Main.java
* Run "mvn release:update-versions -DautoVersionSubmodules=true"
* Make a commit with "start version whatever" as the commit message

For important updates:

* Run the platform specific scripts in their VMs to generate new installers
* Remember to sign the Windows installer EXE
* Upload installers/packages and adjust website to point to new URLs
