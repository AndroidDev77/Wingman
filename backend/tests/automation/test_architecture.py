"""Architecture rules (principle 2, ADR 0006), checked statically over every module in
``app/``:

* only ``app.services.dispatcher`` calls ``.send_message(`` on a connector (the demo
  router is the WING-006 dev-only simulator outside ``/v1`` and the connector SDK
  defines the method);
* only ``app.services.dispatcher`` calls ``create_instruction`` (no API operation can
  create an outbox instruction) and ``create_outbound`` (no outbound message bypasses
  the dispatcher);
* the AI layer (``app.ai``) imports nothing from connectors, dispatcher or controller.
"""

import ast
from pathlib import Path

APP = Path(__file__).resolve().parents[2] / "app"

# Where these method names may appear as a *call*.
ALLOWED = {
    "send_message": {
        "app/services/dispatcher.py",
        "app/connectors/base.py",  # BaseConnector.send_message -> _do_send_message
        "app/api/demo_connector.py",  # WING-006 dev-only simulator, never mounted in prod
    },
    "create_instruction": {"app/services/dispatcher.py"},
    "create_outbound": {"app/services/dispatcher.py"},
}


def _calls(path: Path) -> list[tuple[str, int]]:
    tree = ast.parse(path.read_text())
    out = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            fn = node.func
            name = (
                fn.attr
                if isinstance(fn, ast.Attribute)
                else fn.id
                if isinstance(fn, ast.Name)
                else None
            )
            if name in ALLOWED:
                out.append((name, node.lineno))
    return out


def _modules() -> list[Path]:
    return sorted(p for p in APP.rglob("*.py") if "__pycache__" not in p.parts)


def test_only_the_dispatcher_sends_or_creates_instructions() -> None:
    offenders = []
    for path in _modules():
        rel = path.relative_to(APP.parent).as_posix()
        for name, line in _calls(path):
            if rel not in ALLOWED[name]:
                offenders.append(f"{rel}:{line} calls {name}()")
    assert offenders == [], offenders


def test_the_dispatcher_really_does_the_sending() -> None:
    names = {n for n, _ in _calls(APP / "services" / "dispatcher.py")}
    assert names == {"send_message", "create_instruction", "create_outbound"}


def test_no_raw_outbox_or_outbound_inserts_outside_repositories_and_dispatcher() -> None:
    """Nothing builds an OutboxInstruction / outbound Message row directly."""
    allowed = {
        "app/db/repositories/automation.py",
        "app/db/repositories/conversations.py",
        "app/db/models.py",
        "app/services/dispatcher.py",
    }
    offenders = []
    for path in _modules():
        rel = path.relative_to(APP.parent).as_posix()
        if rel in allowed:
            continue
        tree = ast.parse(path.read_text())
        for node in ast.walk(tree):
            if isinstance(node, ast.Call):
                fn = node.func
                name = (
                    fn.id
                    if isinstance(fn, ast.Name)
                    else fn.attr
                    if isinstance(fn, ast.Attribute)
                    else ""
                )
                if name == "OutboxInstruction":
                    offenders.append(f"{rel}:{node.lineno}")
                if name in ("insert", "Message") and any(
                    isinstance(a, ast.Name) and a.id in ("OutboxInstruction",) for a in node.args
                ):
                    offenders.append(f"{rel}:{node.lineno}")
    assert offenders == [], offenders


def _imports(path: Path) -> set[str]:
    out = set()
    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, ast.Import):
            out |= {a.name for a in node.names}
        elif isinstance(node, ast.ImportFrom) and node.module:
            out.add(node.module)
    return out


def test_ai_layer_cannot_reach_the_action_layer() -> None:
    forbidden = (
        "app.connectors",
        "app.services.dispatcher",
        "app.services.automation",
        "app.services.outbox",
        "app.services.ingest",
        "app.api",
    )
    offenders = []
    for path in (APP / "ai").rglob("*.py"):
        for module in _imports(path):
            if module.startswith(forbidden):
                offenders.append(f"{path.relative_to(APP.parent)} imports {module}")
    assert offenders == [], offenders


def test_policy_reads_no_model_text() -> None:
    """``PolicyInput`` carries no draft text, rationale or confidence."""
    from dataclasses import fields

    from app.services.automation import PolicyInput

    names = {f.name for f in fields(PolicyInput)}
    assert not {"text", "rationale", "confidence", "question_for_user"} & names
    assert "draft_decision" in names and "content_reasons" in names
