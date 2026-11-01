"""Guard: the connector SDK contains no live dating-app integration."""

from __future__ import annotations

import re
from pathlib import Path

import app.api.demo_connector as demo_module
import app.connectors as connectors_pkg

SOURCES = [
    *Path(connectors_pkg.__file__).parent.glob("*.py"),
    Path(demo_module.__file__),
]
FORBIDDEN = re.compile(r"tinder|bumble|hinge|gotinder|https?://(?!wingman\.invalid)", re.I)


def test_no_dating_app_endpoints_tokens_or_urls() -> None:
    offenders = [
        f"{path.name}:{lineno}: {line.strip()}"
        for path in SOURCES
        for lineno, line in enumerate(path.read_text().splitlines(), start=1)
        if FORBIDDEN.search(line)
    ]
    assert offenders == []
