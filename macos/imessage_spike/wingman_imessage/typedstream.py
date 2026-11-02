"""Best-effort plain-text recovery from ``message.attributedBody``.

Since macOS 13 (Ventura) ``message.text`` is often NULL and the body exists only in
``attributedBody``: an ``NSMutableAttributedString`` archived with the legacy NeXT
``typedstream`` format (header ``\\x04\\x0bstreamtyped``). Apple does not document the
format. Community decoders (imessage-exporter's ``typedstream`` module, GPL-3.0, and
several MIT-licensed readers) agree on this layout for the leading string:

    ... "NSString" <class version byte> ... 0x2B ('+') <length> <UTF-8 bytes> ...

where ``<length>`` uses the typedstream integer encoding: one byte for 0..127 (as an
unsigned byte below 0x81 in practice), ``0x81`` followed by a 2-byte little-endian
integer, or ``0x82`` followed by a 4-byte little-endian integer.

This module is a clean-room reimplementation of that published description, not a
copy of any decoder. It extracts only the first ``NSString`` (the message text) and
ignores attributes (mentions, links, formatting). A full decoder in WING-030 should
walk the object graph instead of pattern-matching.

Status: VERIFIED only against blobs this repo synthesizes from the same description
(``tests/fixture_db.py``). Decoding of REAL Messages blobs is UNVERIFIED.
"""

from __future__ import annotations

HEADER = b"\x04\x0bstreamtyped"
_NSSTRING = b"NSString"
_PLUS = 0x2B
_MAX_SCAN_AFTER_CLASS = 24
_MAX_TEXT_BYTES = 1_000_000


class TypedStreamError(ValueError):
    """The blob is not a recognisable attributedBody."""


def decode_attributed_body(blob: bytes | None) -> str | None:
    """Return the message text, or None when the blob is absent or not decodable.

    Never raises on malformed input: a reader must keep going past one bad row.
    """
    if not blob:
        return None
    try:
        return _decode(bytes(blob))
    except (TypedStreamError, UnicodeDecodeError, IndexError):
        return None


def _decode(blob: bytes) -> str:
    if not blob.startswith(HEADER):
        raise TypedStreamError("missing streamtyped header")
    cls = blob.find(_NSSTRING, len(HEADER))
    if cls < 0:
        raise TypedStreamError("no NSString in stream")
    start = cls + len(_NSSTRING)
    window = blob[start : start + _MAX_SCAN_AFTER_CLASS]
    plus = window.find(bytes([_PLUS]))
    if plus < 0:
        raise TypedStreamError("no '+' marker after NSString")
    pos = start + plus + 1
    length, pos = _read_length(blob, pos)
    if length < 0 or length > _MAX_TEXT_BYTES or pos + length > len(blob):
        raise TypedStreamError("string length out of range")
    return blob[pos : pos + length].decode("utf-8")


def _read_length(blob: bytes, pos: int) -> tuple[int, int]:
    tag = blob[pos]
    if tag == 0x81:
        return int.from_bytes(blob[pos + 1 : pos + 3], "little", signed=False), pos + 3
    if tag == 0x82:
        return int.from_bytes(blob[pos + 1 : pos + 5], "little", signed=False), pos + 5
    if tag >= 0x80:
        raise TypedStreamError(f"unsupported length tag 0x{tag:02x}")
    return tag, pos + 1


def encode_attributed_body_for_fixture(text: str) -> bytes:
    """Build a SYNTHETIC attributedBody with the documented layout (tests only).

    The surrounding bytes mimic the commonly published prefix
    (NSAttributedString / NSObject / NSString class chain) closely enough that the
    decoder must find the string by structure rather than by offset.
    """
    data = text.encode("utf-8")
    if len(data) < 0x80:
        length = bytes([len(data)])
    elif len(data) <= 0xFFFF:
        length = b"\x81" + len(data).to_bytes(2, "little")
    else:
        length = b"\x82" + len(data).to_bytes(4, "little")
    prefix = (
        HEADER
        + b"\x81\xe8\x03\x84\x01@\x84\x84\x84\x12NSAttributedString\x00"
        + b"\x84\x84\x08NSObject\x00\x85\x92\x84\x84\x84\x08NSString\x01\x94\x84\x01+"
    )
    suffix = (
        b"\x86\x84\x02iI\x01\x05\x92\x84\x84\x84\x0cNSDictionary\x00\x94\x84\x01i\x01\x92\x84\x96\x96"
        + b"\x1d__kIMMessagePartAttributeName\x86\x92\x84\x84\x84\x08NSNumber\x00"
        + b"\x84\x84\x07NSValue\x00\x94\x84\x01*\x84\x99\x99\x00\x86\x86\x86"
    )
    return prefix + length + data + suffix
