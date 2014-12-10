File formats and protocols
==========================

The Lighthouse protocols and file formats are based on protocol buffers, and are mostly an extension of BIP 70.
You can use the [schema file from the Lighthouse source](https://github.com/vinumeris/lighthouse/blob/master/common/src/main/extended-bip70.proto)
along with a protobuf compiler for your language to work with the data formats.

A .lighthouse-project file is a length prefixed (delimited) "Project" message. This message is defined to have an
identical layout to a BIP70 PaymentRequest message so code that knows how to parse these messages can be reused, e.g.
if at some point [project files start being signable](https://github.com/vinumeris/lighthouse/issues/54).

The "ProjectDetails" message is mostly self explanatory: the outputs a project wishes to collect the money on are
defined, a description of the project can be put in the memo field, the timestamp must be set but the expiry time is
currently ignored (in future if deadlines are supported, it would go here). The payment_url field, if set, points to
a Lighthouse server endpoint that responds to GET and POST requests for that project. The merchant_data field, if set,
contains opaque data meaningful only to the project creator themselves. Today that means it may contain an index into
the wallet's HD key hierarchy for the auth key (see below).

There is also a "ProjectExtraDetails" message that contains:

* A short title for the project
* A cover image (serialized JPEG or PNG bytes)
* An "auth key", a secp256k1 public key that can be used to sign server requests or messages to prove ownership of the
  project.
* A minimum pledge size, which can be configured by the project owner.

A .lighthouse-pledge file is a length-prefixed (delimited) "Pledge" message. It contains a transaction fragment that
pays to the outputs specified in the project, and has inputs adding up to the amount of pledged money. The inputs must
be signed using SIGHASH_ANYONECANPAY.

The Lighthouse HTTP protocol is simply:

* GET to retrieve a binary ProjectStatus message.
* POST to upload a binary Pledge message.
* GET with ?msg=abc&sig=def where "abc" is any arbitrary string and "def" is a base64 encoded secp256k1 signature over
  abc with the project auth key, which returns a ProjectStatus message with tx data included in the pledges.

