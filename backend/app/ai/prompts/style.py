"""Style directives for the reply prompt, from the user's ``StyleProfile`` (WING-007).

Only attributes the user actually set produce a directive. A ``null`` attribute means
"not configured": no constraint is written for it and no user-specific style is
invented. ``response_pace`` is about *when* to send, not wording, so it never reaches
the prompt; the generator turns it into a suggested delay instead.
"""

from app.db.enums import Brevity, EmojiFrequency, Humor, Punctuation, Tone
from app.services.preferences import StyleProfile

__all__ = [
    "BREVITY_MAX_CHARS",
    "EMOJI_MAX",
    "MAX_SAMPLE_CHARS",
    "MAX_SAMPLES",
    "NEUTRAL_STYLE",
    "style_block",
    "style_directives",
]

PUNCTUATION: dict[Punctuation, str] = {
    Punctuation.MINIMAL: (
        "Punctuation: minimal. Lowercase is fine, skip most periods, no exclamation marks."
    ),
    Punctuation.STANDARD: "Punctuation: standard capitalization and punctuation.",
    Punctuation.EXPRESSIVE: (
        "Punctuation: expressive. Exclamation marks and '...' are welcome where they fit."
    ),
}
EMOJI: dict[EmojiFrequency, str] = {
    EmojiFrequency.NONE: "Emoji: none. Do not use any emoji.",
    EmojiFrequency.RARE: "Emoji: rare. At most one emoji, and usually none.",
    EmojiFrequency.OCCASIONAL: "Emoji: occasional. At most two emoji where they fit.",
    EmojiFrequency.FREQUENT: "Emoji: frequent. Use emoji freely where they fit naturally.",
}
# Hard caps the generator checks after generation (``None``: no cap).
EMOJI_MAX: dict[EmojiFrequency, int | None] = {
    EmojiFrequency.NONE: 0,
    EmojiFrequency.RARE: 1,
    EmojiFrequency.OCCASIONAL: 2,
    EmojiFrequency.FREQUENT: None,
}
BREVITY: dict[Brevity, str] = {
    Brevity.VERY_SHORT: "Length: very short. One short line, under 60 characters.",
    Brevity.SHORT: "Length: short. One or two sentences, under 160 characters.",
    Brevity.MEDIUM: "Length: medium. Two to four sentences, under 400 characters.",
    Brevity.LONG: "Length: a longer message is fine when the topic calls for it.",
}
BREVITY_MAX_CHARS: dict[Brevity, int | None] = {
    Brevity.VERY_SHORT: 60,
    Brevity.SHORT: 160,
    Brevity.MEDIUM: 400,
    Brevity.LONG: None,
}
HUMOR: dict[Humor, str] = {
    Humor.NONE: "Humor: none. No jokes.",
    Humor.LIGHT: "Humor: light. A light touch where it is natural.",
    Humor.PLAYFUL: "Humor: playful. Kind, gentle teasing is fine.",
    Humor.WITTY: "Humor: witty. Dry, clever wit.",
}
TONE: dict[Tone, str] = {
    Tone.WARM: "Tone: warm and friendly.",
    Tone.DIRECT: "Tone: direct and to the point.",
    Tone.PLAYFUL: "Tone: playful and light.",
    Tone.FLIRTY: "Tone: flirty, but only as far as the contact has gone.",
    Tone.RESERVED: "Tone: reserved and low-key.",
    Tone.FORMAL: "Tone: polite and formal.",
}

NEUTRAL_STYLE = (
    "No style preferences are set. Write in a neutral, natural texting voice. Do not "
    "invent a persona, slang, or habits for the user."
)
MAX_SAMPLES = 5
MAX_SAMPLE_CHARS = 200


def style_directives(style: StyleProfile) -> list[str]:
    """One line per configured attribute, in a fixed order. Empty if none is set."""
    out: list[str] = []
    if style.punctuation is not None:
        out.append(PUNCTUATION[style.punctuation])
    if style.emoji_frequency is not None:
        out.append(EMOJI[style.emoji_frequency])
    if style.brevity is not None:
        out.append(BREVITY[style.brevity])
    if style.humor is not None:
        out.append(HUMOR[style.humor])
    if style.tone is not None:
        out.append(TONE[style.tone])
    return out


def style_block(style: StyleProfile) -> str:
    lines = style_directives(style)
    samples = [s[:MAX_SAMPLE_CHARS] for s in style.style_samples[:MAX_SAMPLES]]
    if not lines and not samples:
        return NEUTRAL_STYLE
    parts = ["Style settings (the user's own choices):"]
    parts += [f"- {line}" for line in lines]
    if samples:
        parts.append(
            "Messages the user wrote, as examples of their voice (match the voice; do not "
            "copy them and do not reuse any facts in them):"
        )
        parts += [f"  > {s}" for s in samples]
    if not lines:
        parts.append("No other style attribute is set: do not invent one.")
    return "\n".join(parts)
