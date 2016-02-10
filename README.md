Lighthouse
==========

### Please note: this project is no longer maintained.

Lighthouse is a decentralised, peer to peer crowdfunding application that uses the smart contracts features of the
Bitcoin protocol. It lets you create projects and pledge to those projects.

# get involved

Mailing lists:

* [User discussion and questions](https://groups.google.com/forum/#!forum/lighthouse-discuss)
* [Development talk](https://groups.google.com/forum/#!forum/lighthouse-dev)

Or sign up for occasional email newsletters on [vinumeris.com](https://www.vinumeris.com).

You can chat to us in the #lighthouse chatroom on [Freenode IRC](http://webchat.freenode.net/).

If you're a developer, build instructions are at the end of this README.

# how to tweak the user interface

You can do some work on the UI without being a Java developer. If you have web design experience modifying the Lighthouse
UI will seem quite familiar. Ask for a binary build if you don't want to compile the app yourself, and then run 
Lighthouse with the --resdir=/path/to/ui/files flag. It should point to the client/src/main/resources/lighthouse
directory from this git repository. You can use the Shortcut+S key combination to reload the UI whilst the app
is running. Shortcut is the Cmd key on a Mac and Control key on Windows/Linux. The UI is defined by the CSS files
and FXML files (similar to HTML but different language).

Documentation:

* [JavaFX CSS](http://docs.oracle.com/javase/8/javafx/api/javafx/scene/doc-files/cssref.html)
* FXML is best learned by poking around in the existing code, or reviewing [the JavaFX docs](http://docs.oracle.com/javase/8/javase-clienttechnologies.htm) in general.

You can also use the [Scene Builder](http://www.oracle.com/technetwork/java/javase/downloads/sb2download-2177776.html) 
tool to do visual UI design. It can't load main.fxml but everything else should open just fine.

# license and legal stuff

The Lighthouse *code* is under the Apache license, which allows for commercial derivatives.

However the Lighthouse logo and name are not open. In future the logo images and name may be taken
out of this repository and put into a proprietary branch. This would be a similar arrangement to Firefox and Chrome.
The purpose of this is to ensure that if someone downloads an app called "Lighthouse" they know what they are getting
and that it is not, for example, a fork or patched version of the app that may be broken in some way. If someone were
to distribute a fork of the app, they would have to create a new name and logo so users can tell the fork apart from
the original.

Thus to avoid confusion if you'd like to distribute a version of the project that doesn't match the upstream sources, 
please invent a new name and logo for it first. Thanks.

# building from source

Building Lighthouse from source requires the [Maven](http://maven.apache.org/) build tool and the [bitcoinj](http://bitcoinj.github.io/) library.

The latest version of bitcoinj should be [installed from source](https://github.com/bitcoinj/bitcoinj):

```
$ git clone https://github.com/bitcoinj/bitcoinj
$ cd bitcoinj
$ mvn clean install
```

Compile Lighthouse with:

```
$ git clone https://github.com/vinumeris/lighthouse
$ cd lighthouse
$ mvn clean package
```

Run Lighthouse with:

```
$ java -jar client/target/shaded.jar
```
