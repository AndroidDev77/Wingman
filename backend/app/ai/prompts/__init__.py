"""Prompt templates for the reply generator (WING-010).

Prompts live here, never in API handlers or controllers. ``modes`` holds per-intent
guidance (serious, casual_dating, casual_physical, open, custom), ``style`` turns a
``StyleProfile`` into directives, and ``reply`` assembles the full message list.
"""

from app.ai.prompts.modes import MODE_GUIDANCE, ModeGuidance, mode_block
from app.ai.prompts.reply import (
    GUIDANCE_REF,
    INTENT_REF,
    REPLY_SYSTEM_PROMPT,
    AnalysisView,
    MemoryLine,
    PromptMessage,
    ReplyPromptContext,
    build_reply_messages,
)
from app.ai.prompts.style import (
    BREVITY_MAX_CHARS,
    EMOJI_MAX,
    NEUTRAL_STYLE,
    style_block,
    style_directives,
)

__all__ = [
    "BREVITY_MAX_CHARS",
    "EMOJI_MAX",
    "GUIDANCE_REF",
    "INTENT_REF",
    "MODE_GUIDANCE",
    "NEUTRAL_STYLE",
    "REPLY_SYSTEM_PROMPT",
    "AnalysisView",
    "MemoryLine",
    "ModeGuidance",
    "PromptMessage",
    "ReplyPromptContext",
    "build_reply_messages",
    "mode_block",
    "style_block",
    "style_directives",
]
