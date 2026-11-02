from __future__ import annotations

from datetime import UTC, datetime

import pytest

from wingman_imessage.timeconv import apple_to_datetime, datetime_to_apple_ns, to_contract_timestamp
from wingman_imessage.typedstream import HEADER, decode_attributed_body, encode_attributed_body_for_fixture


class TestAppleEpoch:
    def test_nanoseconds(self) -> None:
        # 2024-01-01T00:00:00Z = 725760000 s after 2001-01-01.
        assert apple_to_datetime(725_760_000_000_000_000) == datetime(2024, 1, 1, tzinfo=UTC)

    def test_seconds_legacy(self) -> None:
        assert apple_to_datetime(725_760_000) == datetime(2024, 1, 1, tzinfo=UTC)

    def test_zero_and_none_are_unset(self) -> None:
        assert apple_to_datetime(0) is None
        assert apple_to_datetime(None) is None

    def test_round_trip_with_microseconds(self) -> None:
        dt = datetime(2026, 10, 30, 18, 0, 1, 250_000, tzinfo=UTC)
        assert apple_to_datetime(datetime_to_apple_ns(dt)) == dt

    def test_contract_timestamp_format(self) -> None:
        assert to_contract_timestamp(datetime(2026, 1, 2, 3, 4, 5, tzinfo=UTC)) == "2026-01-02T03:04:05Z"
        assert to_contract_timestamp(datetime(2026, 1, 2, 3, 4, 5, 7, tzinfo=UTC)) == "2026-01-02T03:04:05.000007Z"

    def test_naive_rejected(self) -> None:
        with pytest.raises(ValueError):
            datetime_to_apple_ns(datetime(2026, 1, 1))


class TestAttributedBody:
    @pytest.mark.parametrize(
        "text",
        [
            "Hey! Free tonight?",
            "",
            "x" * 127,
            "x" * 128,  # first length that needs the 0x81 form
            "y" * 70_000,  # needs the 0x82 form
            "Emoji \U0001f600 and accents café — 日本",
            'quotes " and backslash \\ and NSString inside text',
            "line1\nline2",
        ],
    )
    def test_round_trip(self, text: str) -> None:
        assert decode_attributed_body(encode_attributed_body_for_fixture(text)) == text

    @pytest.mark.parametrize(
        "blob",
        [
            None,
            b"",
            b"not a typedstream",
            HEADER + b"no class here",
            HEADER + b"NSString\x01\x94\x84\x01",  # no '+'
            HEADER + b"NSString\x01+\x81\xff",  # truncated 2-byte length
            HEADER + b"NSString\x01+\x10short",  # length beyond end
            HEADER + b"NSString\x01+\x05\xff\xfe\xfd\xfc\xfb",  # invalid UTF-8
            HEADER + b"NSString\x01+\x83\x00",  # unknown length tag
        ],
    )
    def test_malformed_returns_none(self, blob: bytes | None) -> None:
        assert decode_attributed_body(blob) is None
