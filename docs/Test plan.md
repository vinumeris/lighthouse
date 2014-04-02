This outlines a simple manual testing matrix. It is not fully fleshed out and only verifies the basic functionality
works. At some point we should use TestFX to automate all of this, but currently TestFX seems a little immature 
(not great docs, etc).

Combinations
------------

1. Unencrypted wallet, serverless mode.
1. Unencrypted wallet, server assisted mode.
1. Encrypted wallet, serverless mode.
1. Encrypted wallet, server assisted mode.

Test plan
---------

 * Start the app three times (alice bob charlie) with three different directories.
 * Create a project and accept the defaults as far as possible, set goal to be 0.2 BTC. Set server as localhost
   if needed.
 * Save project to a new temp directory.
 * Drag project into bob. Check project balance is zero.
 * Send 0.1 BTC to bob.
 * Pledge 0.1 BTC from bob.
 * Verify in alice that the project balance is 0.1
 * Drag project into charlie. Check project balance is 0.1 (shared directory).
 * Send 0.2 BTC to charlie.
 * Pledge 0.1 BTC from charlie (to make a dependency)
 * Verify in alice that the project balance has become 0.2 and the project is now claimable.
 * Claim in alice and check the money was delivered.
