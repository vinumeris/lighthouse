Lighthouse design doc
=====================

Goals
-----

To create an application that allows users to run crowdfunds using only regular bitcoins, significantly cheaper and
with less overhead than traditional crowdfunding solutions like Kickstarter. 

To build a maximally decentralised solution that relies on support from Vinumeris as little as possible, whilst
still retaining a reasonable user experience.

To be useful not only for people who have a website or server they can use, but also for small casual crowdfunds
where no infrastructure is available, e.g. between groups of friends.

To explore a new way of building decentralised P2P apps that provide a user experience competitive with web apps. At
least some of the experience gained and code written should be usable to build other, non-crowdfunding related Bitcoin
apps in future. Avoiding the web lets us improve decentralisation, drive down costs, makes it easier for third parties
to fork the app and experiment (because users can switch by just downloading the new version, there is no data lockin)
and so on.

To keep costs restricted to miners fees. 

Users should be able to pledge money and take the money back at any time before the project has finished fundraising,
without intervention from the project operator.

Non-goals
---------

Lighthouse does not require any alt coins or any intermediate steps beyond acquiring bitcoins.

Lighthouse is not a complete replacement for services like Kickstarter. It is an app, not a company that handles reviews
for project quality or aggregation of projects into a gallery. This service is best provided by independent users who 
have specialised domain knowledge, so can seek out and curate projects. Whilst Vinumeris plans to run a project
aggregation website for projects around Bitcoin and decentralisation, others may choose to run sites for music or arts
projects, hardware design projects, funding other kinds of open source software, local community projects like building
kindergartens, raising funds for charity and so on.

The application will not attempt to provide every feature a crowdfunding platform could have. Some features, like
commenting, distribution of videos, emailing of updates etc are best handled on the web using existing social platforms.

The application does not attempt to handle Bitcoin price volatility. For long term crowdfunds, it's best to ask people
to promise money denominated in a more stable currency. Once enough promises are gathered (e.g. via a web form or forum
posts), the actual Lighthouse crowdfund can begin, to minimise the potential shift in value of the fund.

Lighthouse does not have integrated "signoff groups", in which a quorum of judges decide whether to release the money. 
This functionality is (for now) best handled with a separate app like CoPay, and the destination Bitcoin address of the 
project can then be set to the P2SH address of the group.

Attempting to solve every kind of fraud or threat is an explicit non-goal. Lighthouse can reduce the trust needed in
the project operator by removing the need for them to operate a temporary "bit bank". It still requires you to trust
that the project operator will actually deliver what they say they will. Additionally, Lighthouse does not attempt
to stop a project operator pledging to themselves, borrowing the funds needed to complete the crowdfund, etc. Existing
platforms like Kickstarter have no way to prevent this either, yet are still useful. The security section below goes
into more depth about precisely how Lighthouse affects needed trust between different parties.

Deferred goals
--------------

The following are not goals for the first version but could make sense to add in future:

* Optional deadlines on projects, with automatic revocation of pledges if the project doesn't succeed in time.
* Browsing of project collections via the app.
* Advanced security features beyond passwords for the wallet holding the pledged money (e.g. TREZOR/risk analysis).
* "Scam filtering". If scamming with the app turns out to be prevalent then optional warnings can be shown to the user
  if a project has been reported as fraudulent, in the same manner as a spam/phishing filter.
* Mobile version.
* Digital signing of projects so you can be sure it really is created by who you think it is.
* Optional ID verification of pledgors for cases where auditing of the crowdfund is desirable.

Overview
--------

Briefly:

* _Lighthouse_ is a specialised HD SPV wallet to which money can be sent and retrieved.
* A _project_ is a tx output that describes the goal amount (of money, in satoshis), and the output script containing
  the _project owner's_ key or keys. It also contains a bit of metadata like the name of the project, its
  description and a Facebook-style cover image.
* A _pledge_ is a partial, invalid tx that has a single input signed with SIGHASH_ALL | SIGHASH_ANYONECANPAY and a
  single output as specified by the project. It may also contain some contact details.

Because this is so similar to a BIP 70 payment request, projects are described using a slightly extended form of the
same protocol. This allows us to reuse code as much as possible, and in particular would allow us to have
signed projects in future (though not for v1).

The goal of the Lighthouse design is to keep as much logic _out_ of the server and in the fat client as possible. This
approach may seem strange, because it's the opposite of how much modern software is designed, but in this case we are
aiming for a highly decentralised design in which it's feasible for individuals with no sysadmin ability to create and
run crowdfunding campaigns using nothing more than email, DropBox, AirDrop, Bluetooth or any other way of sending files
around. Whilst a simple dumb server can make the process more fluid for campaigns that are naturally associated with
a web site anyway, it should long term not be a requirement.

Therefore, in order to create a project, a BIP 70 PaymentRequest message is formatted. The outputs
are specified as normal, and there are only a few differences to the regular payment flow:

1. A title field is added, which is supposed to sum up the project in a few words. This compliments
   memo which is allowed to have a more verbose description.
2. An image field is added, which may contain serialized image bytes that make the project look prettier when
   shown in the UI. Images use the same aspect ratio as Facebook cover photos to make them easy to reuse.
3. If a payment_url is specified, it should speak an extended protocol that allows for querying the status of the
   project i.e. the existing pledges.
4. The Payment message should contain an invalid transaction containing only SIGHASH_ANYONECANPAY signatures. The
   pledge must spend only known, public UTXOs to be considered valid for the project. The refund field may
   not be used. The memo field may contain a message from the user (e.g. words of support for the project). There
   may be an additional field for contact details.

Once formatted, the Payment message may either be POSTd to the payment_url as normal for collection and eventual
combination with other pledges. Or, it may be transferred to the project owner in some other way, like via email.
Regardless of how it reaches the owner, eventually they load it into their Lighthouse client app which provides a GUI
for combining the pledges and transmitting the final transaction to the P2P network.

A project is given an *auth key* which is just a regular Bitcoin secp256k1 key, stored in the users wallet, derived 
from their HD key heirarchy. It's currently just used to prove to the server that the user is the creator of a project.
The auth key could be used in future to do things like provide messages signed by the project creator, establish that 
a newer version of the project file is legitimate and so on.


Project/pledge lifecycle
------------------------

A project starts out in the unclaimed state. Pledges are created and sent to the project owner, either to their server
or to their app. Pledges are checked for validity (see below):

1. when first discovered
2. when the app starts
3. shortly after a new block is broadcast

Once created, a pledge can be *revoked*. A revoked pledge has the output that it signs for spent back to the pledgors
wallet, effectively double spending that output - although from the perspective of the P2P network there was no double
spend. The project owner can notice that a pledge was revoked either by simply rerunning the validity checks as explained
 below on every block, or by populating a Bloom filter with the outpoints being spent and watching to spot the
transaction itself. The first approach is used currently because it was easier to implement, the second approach has
marginally better security and can allow detection of the double spend right away, instead of having to wait for a block
to be found.

The user may pledge any amount up to the smallest of their balance and the total amount needed to complete the project.
If the users wallet does not have an output of the right size (what we call the *stub*), it creates a transaction that 
spends back to itself so such an output does exist, and pledges that. In the serverless case, the dependency transaction
is broadcast onto the P2P network immediately. In the server-assisted case, the dependency is included in the pledge
protocol buffer and uploaded for the server itself to broadcast. This avoids races in which the server may receive
a pledge of outputs that it didn't yet see get created. The assumption is that in the serverless case, there's enough
delay between the pledge being created and received that this doesn't matter. In future, we may wish to expose the
broadcast of the dependency to the user via the UI before the pledge is created at all, in case they are providing some
custom low latency upload system.

The project owner is allowed to *claim* the project when pledges adding up to the goal have been gathered. They cannot
claim less or more. Less is forbidden by the rules of Bitcoin. More is forbidden by the app, because otherwise the excess
would go to miners fees (pointlessly so). In the serverless case this means the user might accidentally end up raising
too much money. In that case they must discard pledges until they have the right amount. In the server-assisted case
the server indicates to the client how much has been raised so far, and the client will prevent the user from pledging
too much.

User interface design
---------------------

Lighthouse is designed to visually resemble a modern webapp. It uses a stylesheet that modifies the default JavaFX skin
(Modena) to make it visually resemble Twitter bootstrap (v2), although we do not attempt to match it pixel for pixel.
There is only one main window. Subwindows are stacked on top of the main UI, again mimicking the design of a web app,
although opening a regular window would be easy. Additionally, in future online updates may work silently again in the
style of a web app. This design decision was taken because:
 
1. Trends in the software industry have trained users what to expect, fashion wise, from a modern app. 
2. An app that is visually similar to a web app should make users think "this is meant for ordinary consumers like me"
   whereas an app that is visually similar to a 1990's style desktop app would make users think "this looks like a
   business app for business users".
3. It mostly avoids us having to match the visual style of each host OS, which vary considerably. Matching things like
   scrollbars may still be worthwhile but mostly, having a distinct visual style reduces work.

The main Lighthouse UI consists of:

1. A control strip at the top, where the app and wallet functionality is controlled.
2. The main area. In overview mode this presents a tile for each project loaded into the app and when clicked, it
   becomes filled with the project view.
   
The users current address is on screen at all times. This will likely be removed in future so it only shows up if the
user requests it (in which case the QRcode would be shown simultaneously). For now it's kept to reinforce in the users
mind that this is in fact a separate, standalone wallet. We do this because the "specialised p2p wallet" paradigm is
somewhat new to the Bitcoin community and may provoke a small amount of initial confusion if we did not call it out. 
   
To import a project into the app, the user can click the import button or drag and drop the project onto the main 
window. Pledges are at present not imported directly. In the server-assisted case, they are downloaded and uploaded
automatically in the background. In serverless mode, their files must be dropped next to the project file that was
imported. The app watches the directories containing imported project files and will notice the pledge if it's valid.
This latter approach is/was designed to work well with shared folders but has proven confusing for the
(probably more common?) case where pledges are exchanged over IM or email. This part of the UI may need to be redesigned
so pledges can be imported directly as well, which is what some users will expect.

Edit controls perform real time validation as the user types. If there's a problem they turn red. Currently no 
explanation of *why* the input is unacceptable is given; this would be a useful upgrade to add.

Projects have a cover image associated with them. A default cover image is provided that's randomly recoloured to ensure
people who don't want to select or create a real cover image still have an acceptably unique looking project. Hosting
web sites could use this image in their own user interfaces if they wanted also.

The project view shows a list of pledges, some stats on how much money was raised, and a pie chart that visualises
the pledges and their sizes.

Subwindows are provided for basic wallet functions like sending money out, viewing wallet words, encrypting the wallet,
restoring from backed up wallet words, and so on.

Server functionality
--------------------

The server speaks a simple RESTful protocol via HTTPS. POSTing to the payment URL defined in the project file
is how you upload a new pledge. GETing the project URL serves a status message which contains *scrubbed* copies of
each pledge. A scrubbed pledge has the transaction data removed, and replaced with the hash of the pledge. This
is done so in the server-assisted case, the clients can show a pie chart and list of pledges gathered so far without
the pledgors being able to trigger claiming of the project. For example the project creator may not be ready to take
people's money at the exact instant the last coin is raised, and it's at any rate unintuitive that users would be
able to do this. The hash of the pledge is included so the client can identify which one is theirs in the UI. 

For the serverless case, claiming of a project (funds to the project owner) would be technically 
possible if any copy of the app can see all gathered pledges (and there is sufficient gathered funds), but to avoid 
people accidentally triggering claims the claim button is disabled if the project was not created by the app. 
This is only a UI level restriction though and a specialised tool could be created to forcibly close the contract.
Again, this would not allow anyone to steal money, just result in it moving earlier than the project owner may have
chosen.

Although the server could easily close the project when given a special command, instead we keep the server dumb and
have the claim logic in the app (where it is anyway needed for the serverless case). The auth key is used to sign a 
message which is then sent to the server in URL parameters when requesting a project status,
and the server then returns an unscrubbed copy of the project status. The app then behaves as if it was in the 
serverless mode, building the claim/contract transaction and broadcasting it. The server notices the claim being
broadcast and starts vending status messages telling clients they can no longer pledge, along with the tx hash of the
claim. The app then puts the project into the claimed state, which has a button that allows viewing of the transaction
and a greyed out cover image. The pledges themselves are still visible.

The server does not insist on serving SSL itself, but the client app does require the server uses SSL. This allows
the server to be put behind a regular httpd frontend that handles SSL itself and just acts as a forwarding proxy.

The server shares the same backend code with the app, and thus stores pledges and projects on disk locally. See the 
code design section to learn more. To configure the server, the admin just drops a project file into its directory. That
project will begin serving automatically.

Verifying pledge validity
-------------------------

It is useful to know whether a pledge could really be spent or not, before attempting to do so. Otherwise someone
could tamper with the process by submitting a pledge that doesn't connect to anything, and then you'd have to do a
full combination search to find the set of inputs the network would accept.  Also it'd be impossible to know if
someone has (quite legitimately) double spent away the output they pledged, because they changed their mind or because
there were several competing projects and the user wanted their money to be taken by whichever project reached the
finishing line first.

For this purpose, we extend the Bitcoin P2P protocol with a new "getutxos" message. It takes in a list of COutPoint
messages and a boolean stating whether to check the mempool or not, and returns a "utxos" message that contains a bitmap
of found/not found statuses for each outpoint, along with the CTxOut data for each found UTXO. In this way the client
can query remote nodes to quickly identify revoked or invalid pledges. Multiple peers are queried in parallel and their
results are cross-checked against each other. The returned scripts are checked to ensure they're of a standard form
(i.e. are not just OP_TRUE) and then executed along with the data in the pledged input. In this way, we verify that the
provider of the pledge is actually capable of signing for the pledged output.

Of course, this design is racy: the answer is only partly authenticated, and might become invalid the moment after you 
asked. Nevertheless it's anticipated that this technique is good enough to provide a satisfying UI and work well enough 
in practice.

As extending the Bitcoin protocol via changes to Core is no longer feasible, a modified version of Core will be
released under a new name that users can download and run if they would like to contribute simultanteously to both
the Bitcoin P2P network and Lighthouse users.

Code design
-----------

### Backend

Both the fat client and the server share the same code for handling of projects and pledges. The `LighthouseBackend`
class is responsible for:

 * Via the `DiskManager` class, loading, saving and monitoring of project and pledge files, along with the directories
   containing watched project files, and in the server-side case the servers private directory. The user can load 
   pledges into the app by dropping them into the same directory as the project file resides in.
 * Matching up pledges to projects, verifying pledges, and tracking whether they're "open" pledges (can be claimed),
   revoked, or claimed. Verifying pledges involves doing UTXO lookups, running scripts and so on.
 * Watching the P2P network for claim transactions, or broadcasting them and watching to ensure they propagate.
 * Fetching project status messages from the server.
 * Processing pledge messages uploaded to the server.
 * Exposing observable data structures that reflect the current state of the system, like which projects are known,
   what pledges are open vs claimed, whether there's currently any kind of status lookup going on and so on.
   
The LighthouseBackend class is entirely thread safe, and runs the bulk of all logic in its own dedicated thread. This
serializes all operations by default and ensures that operations happen in a sane order, e.g. we do not attempt to
start processing a new pledge whilst in the middle of doing network lookups for another. Almost all operations the 
backend does are blocking for this reason, with non-blocking operations being carefully called out so the potential
re-entrancy is clear. This is especially important when performing UTXO lookups because the protocol allows only one
outstanding query at once. Thus we cannot overlap queries.

### Core data structures

A `Project` object is an immutable representation of a project, and can be initialised either from a protocol buffer
or from a `ProjectModel`, which is designed to be used only in the GUI. `Project` knows how to verify the validity of
a pledge at the individual level (this is insufficient to fully check a pledge is valid overall), and how to request
a status from the project server.

A pledge is always represented by an `LHProtos.Pledge` object, which is just the raw protocol buffer. A pledge is
identified by a hash, which can either by the raw hash of the transaction bytes, or whatever the pledge asserts its
tx hash was if the tx data itself is missing.

Note that both objects are immutable and thus safe for use as keys in maps.

### Mirrors 

Because the backend class may become busy for several seconds at a time whilst waiting for a remote server or peer, it
is not suitable for direct access from the GUI client, which must remain responsive at all times. Additionally we would
like a GUI that animates fluidly in response to changes in the state of the system, with the animations being useful and
indicating the nature of the change.

To resolve these two requirements we introduce the notion of a *mirrored* data structure. JavaFX provides us with the 
following data structures:

* Observable maps
* Observable lists
* Observable sets
* Observable generified/templated value classes 

Change listeners can be registered on these objects to receive compact, well encoded deltas. For example the change
objects delivered to the observable list listeners understand the difference between moving items around, adding them,
removing them or permuting them.

Although technically a part of JavaFX, these classes do not have any GUI system dependencies and can also be used on the
server side. But when paired with the JavaFX GUI library they can be directly bound to list views, pie charts, or
any property of any control, and many of those controls know how to use the contents of the delta objects to generate
correct looking animations.

A mirrored collection can be created using a static method on the `ObservableMirrors` class. The collection to mirror
is provided, along with an *executor*, which is simply any object that accepts closures and runs them at some point in
the future. The mirrored collection registers a change listener on the target collection. Those listeners naturally 
execute in the thread that performed the mutation. They then re-perform the same change via the given executor, thus
marshalling the change onto the target thread. The result is that the mirrored collection contains the same data as
the target collection, but may lag behind and is guaranteed not to change as long as the linked executor is busy.

Mirrored collections are used in several places in Lighthouse:

1. The frontend code mirrors collections maintained by the backend and then binds them directly to the UI, sometimes
   after applying various lazy functional transforms.
2. The server mirrors collections maintained by the backend into its HTTP processing thread. This ensures that
   it can always serve answers fast, even if the backend is busy recalculating new answers.

It's important to note the following things about them:

1. Mirrored collections are read only: the reflection is one way only.
2. The act of requesting a mirrored collection, almost by definition, happens on a thread that does not own the target
   collection. Attempting to initialise the mirror by copying the target list would therefore not be thread safe. To
   resolve this, creating a mirrored collection is done only via public methods on the owning object (e.g. the backend)
   and internally this performs a cross-thread method call to obtain a consistent snapshot of the target. Thus, creating
   a mirror can block.

### Pledging wallet

The `PledgingWallet` class extends the standard bitcoinj `Wallet` class with new functionality needed for creating
and revoking pledges. A wallet extension is registered which stores pledges created by the wallet and the projects they
were created for. Note that pledges are stored even after being revoked, although in such a way that we know about the
revocation. This is important because even though a revoked pledge may become invisible in the UI (at least today),
it may take time for the project server to notice the revocation, and thus the UI would still see the pledge coming
back in the status message. By remembering which pledges we revoked, we can reliably remember that the project is
(for us) back in the open/pledgable state even if the server didn't catch up yet. Additionally it may prove useful
for rendering an activity log in the UI in future.

The pledging wallet uses a custom coin selector to ensure that we don't attempt to spend coins that have been used
in a pledge, even though from the perspective of the block chain they are available for use.

The wallet knows how to spot pledge revocations (i.e. spends of the stub outputs) by other copies of Lighthouse sharing
its keys.

Lighthouse uses an HD wallet that, currently, uses the regular BIP32 recommended heirarchy. It is likely to switch to
a separate heirarchy using a dedicated BIP43 purpose field, to ensure that importing the seed words into another wallet
does not reveal any funds. This is a tradeoff: on one hand, being able to extract all the money put into a Lighthouse
wallet using another app would be useful for disaster recovery. On the other hand, being able to share funds in this
way is likely to mislead users into thinking it actually works: it would not be intuitive that sharing wallet words 
between Lighthouse and another BIP32-implementing wallet would result in pledges being randomly revoked with no warning.

### Frontend

The GUI code in the app uses the JavaFX UI toolkit, which is probably the worlds newest widget library. We make full
use of FXML and CSS for fast user interface iteration and Lighthouse can be set up to act like a web browser, loading
UI resources from disk with a hot reload button so visual tweaks can be applied without having to restart the app. The
--resdir command line switch can be used to do this.

Most UI subwindows are placed in-frame, in the style of web apps. The `Main` class implements a simple UI stacking
framework that understands how to animate subwindows in, out and between them. Most UI logic is bound directly to the
underlying data structures.

Encrypted wallets pose a special challenge. When private key material is needed, the frontend checks if the wallet
is encrypted. If it is, the "enter password" subwindow is activated, with a callback being provided that runs when
the user gets the password right. This replaces the current subwindow, if there is one. The callback must store as much
data as needed to get the original UI back into the right state.


Online update framework
-----------------------

Lighthouse uses a custom online update engine called UpdateFX. This has been designed specifically to meet the needs
of the Bitcoin community. It features:

 * Binary delta updates that can be downloaded and applied to the app whilst it's running.
 * Threshold signing using Bitcoin-compatible secp256k1 keys, that reuses the Bitcoin message signing
   standard (although UpdateFX does not depend on bitcoinj). This means a group of developers can require agreement
   amongst themselves in order to push updates, and their signing keys can be protected in any way a regular Bitcoin
   key can be.
 * No administrator access required to apply updates. New versions of the app jar are stored in the apps private data
   directory.
 * Update files can be served from a CDN or any other arbitrary site, even without SSL, as their hashes are checked
   against the signed index.
   
Additionally the following features are very easy to add:

 * Pinning of the app at a particular version to prevent upgrades, or even perform downgrades. Each version of the app 
   is stored independently on disk so pinning just requires a UI widget for the user to interact with, and recording
   the version that we want to pin to.
 * Updates that are either entirely silent (for things that don't matter much), entirely blocking (security critical
   updates) or anything in between. The user should be able to configure this so updates are e.g. always noisy.
   
Lighthouse keeps track of its own version using a wallet tag. When it notices its been upgraded, it can choose whether
to show release notes or not depending on the importance of the upgrade.

This design is intended to strike a balance between the needs of the average user who wants a web-like behaviour where
the app does not bother them unless it's really necessary, and the desires of the pro user who may wish to exercise
precise control over what version of the software he/she is using. For example, perhaps he wishes to get confirmation
the update is safe from other users first, or perhaps she simply does not like a UI redesign and wants to stick with
what they have.

Security
--------

Lighthouse is intended to avoid the need for crowdfunders to accept deposits and manually process withdrawals during
the time of the fundraise. This solves several problems:

* Deposits in a liquid wallet can be lost or hacked.
* Deposits in a cold/threshold wallet would take a long time to be returned to the user, especially if the project
  is run by one person who becomes sick, goes on holiday etc.
* There is clarity over who owns the money: pledged money is still controlled by the pledgor until the instant the
  contract is broadcast, at which point it becomes unquestionably the project owners. Deposits do not have this
  property.
* It avoids regulatory overhead and risk that may be associated with being a (temporary, ad hoc) financial institution.

Lighthouse does not guarantee that a crowdfund meets any particular conditions. For example it's OK for a project
creator to finish off a fundraise with their own money. If this isn't desirable for some reason, a project server
can reject pledges that do not contain some kind of auditable information (email address, registration token, etc) and
the data can be released to convince people that the pledgors were independent. In practice this requirement is unlikely
to be common: when pledging to a project the important thing is usually not where the money comes from but what is done
with it.

When used with a project server, the project creators app trusts the server. It is assumed to be controlled either by
the project operator directly, or someone they trust. The server knows how to speak SSL directly when given a key
and certificate, but mostly we anticipate it will be used behind a reverse proxy or in a Java app server. 
 
Neither the app nor the server trust pledgors and we assume a pledge may be malicious in some way. Lighthouse performs 
a variety of checks:

1. A pledge should be for money that is unspent.
2. A pledge must be correctly signed so it can claim the connected output, it must be signed with SIGHASH_ANYONECANPAY.
3. Different pledges should not attempt to sign for the same output. Individual pledges are allowed to have multiple
   inputs but they must not try to spend the same outputs either.
4. Once finalized the claim must correctly propagate.
5. The pledge transactions match the project output.
6. The pledge does not violate a variety of other misc rules, like having a coinbase style input.

The app and server use SPV mode when interacting with the Bitcoin network. Both app and server probe the Bitcoin P2P 
port on localhost and will use any node that's found there. So, users can choose the performance/security tradeoff
for themselves simply by installing and running the Bitcoin Core application themselves, no configuration is required.

Projects are parsed using protocol buffers and Java so in general should be safe to load, although loading a project
into the app indicates an intention to send money to the project creator, so if they are malicious then you're probably
about to get scammed anyway. The main risk of loading a project is the cover image, which could try to exploit bugs
in the image parsing code (which is unfortunately written in C/C++ and not Java). No such bugs have been found for a
while. In the event one is found we'd have to push a security update that parsed the images using Java implementations
of the image standards instead.

The app figures out if an output is spent by querying P2P nodes that support the getutxo extension. In the case of a
local Bitcoin node this is equivalent to full security. When one isn't present (presumed to be common for the end user
case), we cross-check the answers given by each peer against each other. As the P2P network is unauthenticated a man
in the middle capable of controlling all your traffic can give semi-bogus answers. For example, they could claim an
output is unspent when it fact it was spent, making you believe you collected more money than you actually did. This
confusion would be revealed when attempting to claim the project and noticing the transaction does not confirm. Practical
experience from over 2.5 years of SPV wallets suggests that MITM attacks against SPV wallets must be rare, as none have
ever been reported. A future version of the Bitcoin protocol might add authentication/encryption, in which case we'd
be able to use that.

Tor mode
--------

Lighthouse has a bundled Tor client, thus the user doesn't need to install any extra software to use it. In the first
version support will be only accessible via a command line flag. The reason is that in the first release it will
only apply to P2P traffic and will not have been well tested: a future version can make it apply to all traffic
including update checks and server calls, at which point we can potentially decide to enable it by default (although
this would come with a startup time penalty).

Internationalization
--------------------

It is valid and accepted that a project is language specific. Allowing projects to be simultaneously run in multiple
languages is not just about the wire formats, but would require project owners to be able to email people in different
languages etc. It is the subject of future upgrades.

For v1 the app itself is not internationalized. In future, it would make sense to use the Java version of GNU gettext.
