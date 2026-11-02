"""The send-command builder: argv only, constant script, hostile input never parsed."""

from __future__ import annotations

import pytest

from wingman_imessage.applescript import (
    OSASCRIPT,
    SEND_TO_CHAT_SCRIPT,
    SendCommandError,
    applescript_string_literal,
    build_send_command,
    render_manual_script,
)

DM = "iMessage;-;+15550100001"
GROUP = "iMessage;+;chat123456789012345"

HOSTILE = [
    '" & (do shell script "touch /tmp/pwned") & "',
    '"\nend tell\ndo shell script "rm -rf ~"\ntell application "Messages"\n--',
    '\\" & quit & \\"',
    "$(touch /tmp/pwned) `id` ; rm -rf / | cat",
    "-e 'do shell script \"id\"'",
    "«» “smart quotes” ¬not ≠",  # chevrons/smart quotes have AppleScript meaning
    "-- comment (* block *) #hash",
]


def script_part(argv: tuple[str, ...]) -> list[str]:
    return [argv[i + 1] for i, a in enumerate(argv[:-2]) if a == "-e"]


def test_argv_shape() -> None:
    cmd = build_send_command(DM, "Hey! 8pm?")
    assert cmd.argv[0] == OSASCRIPT
    assert script_part(cmd.argv) == list(SEND_TO_CHAT_SCRIPT)
    assert cmd.argv[-2:] == (DM, "Hey! 8pm?")
    assert not cmd.is_group


def test_group_chat_is_flagged() -> None:
    assert build_send_command(GROUP, "hi all").is_group


@pytest.mark.parametrize("text", HOSTILE)
def test_hostile_text_never_reaches_script_source(text: str) -> None:
    cmd = build_send_command(DM, text)
    assert script_part(cmd.argv) == list(SEND_TO_CHAT_SCRIPT)  # script unchanged by input
    assert cmd.argv[-1] == text  # passed verbatim as one argv element
    assert all(text not in line for line in script_part(cmd.argv))
    assert len(cmd.argv) == 2 + 2 * len(SEND_TO_CHAT_SCRIPT) + 1


def test_script_has_no_shell_or_formatting_hooks() -> None:
    src = "\n".join(SEND_TO_CHAT_SCRIPT)
    assert "do shell script" not in src
    assert "run script" not in src
    assert "{" not in src and "%" not in src
    assert "argv" in src


@pytest.mark.parametrize(
    "guid",
    [
        "",
        "iMessage;-;",
        'iMessage;-;+1555" & quit & "',
        "iMessage;-;a b",
        "iMessage;-;x\nend tell",
        "-e;-;+15550100001",
        "Telegram;-;+15550100001",
        "iMessage;*;+15550100001",
        "iMessage;-;" + "1" * 201,
    ],
)
def test_bad_chat_guid_rejected(guid: str) -> None:
    with pytest.raises(SendCommandError):
        build_send_command(guid, "hi")


@pytest.mark.parametrize("guid", [DM, GROUP, "SMS;-;+15550100004", "any;-;bob@example.com", "RCS;-;+15550100009"])
def test_good_chat_guids(guid: str) -> None:
    assert build_send_command(guid, "hi").chat_guid == guid


@pytest.mark.parametrize(
    "text", ["", "   ", "\n", "a\x00b", "bell\x07", "esc\x1b[31m", "del\x7f", "x" * 4001, "\ud800"]
)
def test_bad_text_rejected(text: str) -> None:
    with pytest.raises(SendCommandError):
        build_send_command(DM, text)


def test_text_limits_and_allowed_whitespace() -> None:
    assert build_send_command(DM, "x" * 4000).argv[-1] == "x" * 4000
    assert build_send_command(DM, "a\nb\tc\r\nd").argv[-1] == "a\nb\tc\r\nd"
    assert build_send_command(DM, "emoji \U0001f525").argv[-1].endswith("\U0001f525")


def test_describe_is_redacted() -> None:
    d = build_send_command(DM, "secret text").describe()
    assert "secret" not in d and "+1555" not in d
    assert d == "osascript send (iMessage, 1:1, 11 chars)"


@pytest.mark.parametrize(
    ("raw", "literal"),
    [
        ("plain", '"plain"'),
        ('say "hi"', '"say \\"hi\\""'),
        ("back\\slash", '"back\\\\slash"'),
        ("a\nb\tc\rd", '"a\\nb\\tc\\rd"'),
        ('\\" & quit & "', '"\\\\\\" & quit & \\""'),
        ("«data»", '"«data»"'),
    ],
)
def test_string_literal_escaping(raw: str, literal: str) -> None:
    assert applescript_string_literal(raw) == literal


@pytest.mark.parametrize("text", HOSTILE)
def test_literal_cannot_be_broken_out_of(text: str) -> None:
    lit = applescript_string_literal(text)
    inner = lit[1:-1]
    # Every '"' inside must be escaped, i.e. preceded by an odd number of backslashes.
    i = 0
    while i < len(inner):
        if inner[i] == "\\":
            assert inner[i + 1] in '\\"nrt'
            i += 2
            continue
        assert inner[i] not in '"\n\r'
        i += 1


@pytest.mark.parametrize("ch", ["\x00", "\x01", "\x1b", "\x7f", "\ud83d"])
def test_literal_rejects_control_chars(ch: str) -> None:
    with pytest.raises(SendCommandError):
        applescript_string_literal(f"a{ch}b")


def test_manual_script_is_escaped() -> None:
    src = render_manual_script(DM, 'He said "hi" \\ bye')
    assert src.splitlines()[2] == '\tsend "He said \\"hi\\" \\\\ bye" to targetChat'
    with pytest.raises(SendCommandError):
        render_manual_script("bad", "x")
