Running a Lighthouse server
===========================

Although optional, Lighthouse servers perform the following useful functions for end users:

* They accept uploads of pledges via HTTPS, saving backers the hassle of emailing or uploading a pledge file manually.
* They serve status messages to clients so they can immediately see how much money has been pledged, and by who.
  This helps avoid accidental over-collection of pledges.
* They verify pledges as the block chain changes in the same way as the app, so the project status is always up to date.
* They hide transaction data from clients, unless those clients can sign with the project auth key. This means only
  the server operator and project creator can trigger claiming of the money. This is useful for dispute mediated
  projects.

A Lighthouse server does not have any dependencies beyond Java and is easy to administer. So if you'd like to help
with the further decentralisation of Lighthouse, running one is a good way to do so!

Once set up, the only work required is:

1. Keep up with new versions
2. Do some basic review of projects that are submitted to you, so you don't end up accidentally donating your resources
   to scammers, con artists or people doing other things you don't approve of. Lighthouse servers do not accept
   arbitrary uploads of random projects and they do not touch any money flows, so you can rest easy knowing you
   won't end up with any legal hassles.

Dependencies
------------

You will need the Oracle Java 8 runtime. Note that OpenJDK will not work as for some reason it doesn't include all the
same open source components that ship with regular Java. Hopefully in future this will be resolved.

You will also need an SSL certificate. In future Lighthouse might start letting project creators specify server public
keys directly, and then we could [allow servers to use self signed certificates](https://github.com/vinumeris/lighthouse/issues/93).
But for now you need a CA cert.

Although not strictly necessary, a local Bitcoin node running Bitcoin XT is a really good idea. By running a Bitcoin node
you improve security and reduce your level of trust in the Bitcoin P2P network. For the same reasons you should run a
local node if you're running a merchant, you should run a node if you're running a Lighthouse server. You need to use
Bitcoin XT and not Bitcoin Core because the server needs the getutxo protocol feature to help it check pledges.

And that's it. The Lighthouse server does not require anything else.

Converting your SSL certificate
-------------------------------

Although future Java versions will not have this problem, current versions use their own key store format that isn't
the same as what OpenSSL uses. So you will need to convert your SSL key and certificate.

Don't worry if you already have a web server running on the same machine. You could set up reverse proxying, but an
easier approach is just to run the Lighthouse server on a different port.

The procedure to do the conversion is arcane, but well documented in [this StackOverflow answer](http://stackoverflow.com/questions/906402/importing-an-existing-x509-certificate-and-private-key-in-java-keystore-to-use-i/8224863#8224863).
Note that the password MUST be "changeit" (yes really).

Running the server
------------------

Create a directory for it to use, then:

```
java -jar lighthouse-server.jar --dir=lhserver --keystore=path/to/server.keystore --local-node
```

If you don't have a local node running, leave off the last flag. It will print lots of debugging spew to the console.
Most likely therefore, you want something like this:

```
java -jar lighthouse-server.jar --dir=lhserver --keystore=path/to/server.keystore --local-node &>lhserver/log.txt &
```

This puts it into the background and sends all the logging output to a file in the server directory. The --net=test
flag will run it on the testnet instead.

By default it will listen on port 13765, and project creators must specify the name of your server like this: "example.com:13765".
You can set up reverse proxying if you don't want this.

You can obtain the lighthouse-server.jar file from the github releases page.

Adding and removing projects
----------------------------

The server watches the lhserver directory. To add a project, just drop the project file into it. To remove it, just
delete the project file. The directory is also used to hold pledges, which are named after the hash of their contents.
Removing a project file doesn't delete the associated pledges, but they will be ignored. A future version of the server
might clean up or archive old pledges in some way.

You can review a project file that's been sent to you by just loading it into Lighthouse, and then clicking the
details link at the bottom of the project view.

Getting informed about updates
------------------------------

Please join the [lighthouse-discuss mailing list](https://groups.google.com/forum/#!forum/lighthouse-discuss) so you
know when new server versions are available.

Future improvements
-------------------

It would be nice to have an apt-get repository for the server components and/or a Docker/Snappy package.

A way for the server admin to upload a project via HTTPS would also be nice.