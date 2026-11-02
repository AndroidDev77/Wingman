"""Build (never run) the Apple-event command that would send one iMessage reply.

Design: **no message content is ever spliced into AppleScript source.** The script is
a constant; the chat GUID and the text are passed as ``argv`` to the script's
``on run argv`` handler (``man osascript``: "Any arguments following the script will
be passed as a list of strings to the direct parameter of the 'run' handler"). The
argv vector goes to ``execve`` directly, with no shell, so neither AppleScript nor a
shell ever parses user text. In a Swift WING-030 the equivalent is an
``NSAppleScript``/``OSAScript`` compiled once from the same constant source and run
with a ``run`` Apple event whose direct parameter is a list descriptor of the two
strings.

``applescript_string_literal`` exists for the cases where a literal is unavoidable
(diagnostics, a "copy this into Script Editor" manual test). It follows the AppleScript
Language Guide rules for text literals: ``\\`` and ``"`` must be escaped with a
backslash; ``\\n``, ``\\r``, ``\\t`` are recognised escapes. Other control characters
are rejected rather than guessed at.

Only sending into an EXISTING chat (``chat id``) is supported. Starting a new
conversation, attachments, reactions and edits are out of scope (UNSUPPORTED).

Everything here is VERIFIED on Linux only as string/argv construction. Whether
Messages accepts the command on a given macOS version is UNVERIFIED.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

OSASCRIPT = "/usr/bin/osascript"
MAX_TEXT_CHARS = 4000  # OutboxInstruction.text maxLength

# Constant script. Do not format user data into it.
SEND_TO_CHAT_SCRIPT: tuple[str, ...] = (
    "on run argv",
    'if (count of argv) is not 2 then error "wingman: expected 2 arguments" number 1001',
    "set chatGuid to item 1 of argv",
    "set messageText to item 2 of argv",
    'tell application "Messages"',
    "set targetChat to chat id chatGuid",
    "send messageText to targetChat",
    "end tell",
    'return "ok"',
    "end run",
)

# <service>;-;<address> (1:1) or <service>;+;<id> (group). Services seen in the wild:
# iMessage, SMS, RCS, and "any" (reported for macOS 26 by the imessage-apple crate).
_CHAT_GUID = re.compile(r"^(iMessage|SMS|RCS|any);[-+];[A-Za-z0-9@._+%:\-]{1,200}$")
_ALLOWED_CONTROL = {"\n", "\r", "\t"}


class SendCommandError(ValueError):
    """The instruction cannot be turned into a safe command."""


@dataclass(frozen=True, slots=True)
class SendCommand:
    argv: tuple[str, ...]
    chat_guid: str
    is_group: bool

    def describe(self) -> str:
        """Redacted description for logs: never includes the text or the address."""
        kind = "group" if self.is_group else "1:1"
        service = self.chat_guid.split(";", 1)[0]
        return f"osascript send ({service}, {kind}, {len(self.argv[-1])} chars)"


def validate_chat_guid(chat_guid: str) -> str:
    if not _CHAT_GUID.fullmatch(chat_guid):
        raise SendCommandError("chat GUID has an unexpected shape")
    return chat_guid


def validate_text(text: str) -> str:
    if not text or not text.strip():
        raise SendCommandError("empty text")
    if len(text) > MAX_TEXT_CHARS:
        raise SendCommandError("text too long")
    for ch in text:
        if ch == "\x00":
            raise SendCommandError("NUL byte in text")
        if (ord(ch) < 0x20 or ord(ch) == 0x7F) and ch not in _ALLOWED_CONTROL:
            raise SendCommandError("control character in text")
        if 0xD800 <= ord(ch) <= 0xDFFF:
            raise SendCommandError("lone surrogate in text")
    return text


def build_send_command(chat_guid: str, text: str) -> SendCommand:
    """Return the argv for one send. The caller decides whether and when to run it."""
    guid = validate_chat_guid(chat_guid)
    body = validate_text(text)
    argv: list[str] = [OSASCRIPT]
    for line in SEND_TO_CHAT_SCRIPT:
        argv += ["-e", line]
    # The GUID never starts with '-', so osascript cannot read it as an option.
    argv += [guid, body]
    return SendCommand(argv=tuple(argv), chat_guid=guid, is_group=";+;" in guid)


def applescript_string_literal(value: str) -> str:
    """Quote ``value`` as an AppleScript text literal. Rejects unsafe control chars."""
    out = ['"']
    for ch in value:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ord(ch) < 0x20 or ord(ch) == 0x7F or 0xD800 <= ord(ch) <= 0xDFFF:
            raise SendCommandError("unsupported character for an AppleScript literal")
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def render_manual_script(chat_guid: str, text: str) -> str:
    """A standalone script for the MANUAL Mac test (paste into Script Editor).

    Uses literals, so it goes through ``applescript_string_literal``. Production code
    uses ``build_send_command`` instead.
    """
    guid = validate_chat_guid(chat_guid)
    body = validate_text(text)
    return (
        'tell application "Messages"\n'
        f"\tset targetChat to chat id {applescript_string_literal(guid)}\n"
        f"\tsend {applescript_string_literal(body)} to targetChat\n"
        "end tell\n"
    )
