"""Server-side UUIDv7 generation (ADR 0001).

Python 3.12 has no ``uuid.uuid7()`` (added in 3.14) and PostgreSQL 16 has no
``uuidv7()`` (added in 18), so IDs are generated here per RFC 9562 section 5.7:
48-bit big-endian Unix milliseconds, version 7, then 74 random bits.

Within one process, IDs are strictly increasing: if the clock has not advanced
(or went backwards) since the previous ID, the previous ID's 74 random bits are
incremented instead (RFC 9562 section 6.2, method 2), which keeps B-tree
inserts append-mostly. Clients must still treat IDs as opaque.
"""

import secrets
import threading
import time
import uuid

_RAND_BITS = 74
_RAND_MASK = (1 << _RAND_BITS) - 1
_lock = threading.Lock()
_last_ms = -1
_last_rand = 0


def _build(ms: int, rand: int) -> uuid.UUID:
    rand_a = rand >> 62  # 12 bits
    rand_b = rand & ((1 << 62) - 1)  # 62 bits
    value = (ms & ((1 << 48) - 1)) << 80
    value |= 0x7 << 76  # version
    value |= rand_a << 64
    value |= 0b10 << 62  # RFC 4122/9562 variant
    value |= rand_b
    return uuid.UUID(int=value)


def uuid7() -> uuid.UUID:
    """Return a new, process-monotonic UUIDv7."""
    global _last_ms, _last_rand
    with _lock:
        ms = time.time_ns() // 1_000_000
        if ms > _last_ms:
            rand = secrets.randbits(_RAND_BITS)
        else:
            ms = _last_ms
            rand = _last_rand + 1
            if rand > _RAND_MASK:  # counter exhausted within one ms: borrow the next ms
                ms += 1
                rand = secrets.randbits(_RAND_BITS)
        _last_ms, _last_rand = ms, rand
        return _build(ms, rand)
