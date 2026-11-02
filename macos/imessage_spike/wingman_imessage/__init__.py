"""WING-020 feasibility spike: pure-logic model of a macOS iMessage companion.

Nothing in this package touches a real Mac, a real ``chat.db``, Apple events or the
network. It is the executable reference for WING-030 (see
``docs/integrations/imessage.md``):

* ``timeconv``     Apple epoch (2001-01-01) conversion
* ``typedstream``  best-effort text recovery from ``message.attributedBody``
* ``chatdb``       read-only, incremental (ROWID cursor) reader over a chat.db-shaped file
* ``envelope``     normalization into ``connector_event.v1`` envelopes (platform ``imessage``)
* ``state``        durable cursor + dedup + envelope outbox (survives restarts)
* ``connector``    one poll round: read -> normalize -> dedup -> enqueue -> advance cursor
* ``applescript``  injection-safe send-command builder that NEVER executes anything
* ``sendplan``     outbox-instruction -> send plan, one attempt per key, lease deadline,
                   and correlation of the sent row for an honest acknowledgement
* ``capabilities`` the capability document the companion would claim (all UNVERIFIED or
                   UNSUPPORTED)

Every Mac-specific behaviour is UNVERIFIED until the manual script in
``macos/imessage_spike/manual/`` has been run on a real Mac and recorded.
"""

CONNECTOR_VERSION = "0.1.0-spike"
PLATFORM = "imessage"
