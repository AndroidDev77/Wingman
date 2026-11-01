"""Analysis is advisory: the analyzer and stage rules import nothing that can send.

Checked two ways: statically (every import statement, including the transitive closure
inside ``app``) and at runtime (importing the modules in a fresh interpreter loads no
connector, dispatcher or automation module).
"""

import ast
import subprocess
import sys
from pathlib import Path

import app.ai.analyzer as analyzer_module
import app.ai.stages as stages_module

BACKEND = Path(__file__).resolve().parents[3]
APP = BACKEND / "app"

# Anything that sends, or that could reach a send path.
FORBIDDEN_PREFIXES = (
    "app.connectors",
    "app.api.demo_connector",
    "app.services.dispatcher",
    "app.services.automation",
    "app.services.dispatch",
    "httpx",
)
FORBIDDEN_NAMES = {"send", "dispatch", "Connector", "MockConnector", "Dispatcher"}


def _imports(path: Path) -> list[tuple[str, list[str]]]:
    tree = ast.parse(path.read_text())
    found: list[tuple[str, list[str]]] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            found += [(a.name, []) for a in node.names]
        elif isinstance(node, ast.ImportFrom) and node.module:
            found.append((node.module, [a.name for a in node.names]))
    return found


def _module_path(module: str) -> Path | None:
    rel = Path(*module.split("."))
    for candidate in (BACKEND / rel.with_suffix(".py"), BACKEND / rel / "__init__.py"):
        if candidate.is_file():
            return candidate
    return None


def _closure(start: Path) -> dict[str, Path]:
    """Every ``app.*`` module reachable by import statements from ``start``."""
    seen: dict[str, Path] = {}
    todo = [start]
    while todo:
        for module, _ in _imports(todo.pop()):
            if not module.startswith("app") or module in seen:
                continue
            path = _module_path(module)
            if path is not None:
                seen[module] = path
                todo.append(path)
    return seen


def _forbidden(module: str) -> bool:
    return any(module == p or module.startswith(p + ".") for p in FORBIDDEN_PREFIXES)


def test_analyzer_and_stages_import_no_send_path_directly() -> None:
    for mod in (analyzer_module, stages_module):
        assert mod.__file__ is not None
        for module, names in _imports(Path(mod.__file__)):
            assert not _forbidden(module), f"{mod.__name__} imports {module}"
            assert not FORBIDDEN_NAMES.intersection(names), f"{mod.__name__}: {names}"


def test_no_send_path_in_transitive_app_imports() -> None:
    for mod in (analyzer_module, stages_module):
        assert mod.__file__ is not None
        reachable = _closure(Path(mod.__file__))
        bad = sorted(m for m in reachable if _forbidden(m))
        assert bad == [], f"{mod.__name__} reaches {bad}"
    # Sanity: the closure really walks into the dependencies it should.
    assert "app.ai.llm_gateway" in _closure(Path(analyzer_module.__file__ or ""))


def test_importing_analyzer_loads_no_send_module_at_runtime() -> None:
    code = (
        "import sys\n"
        "import app.ai.analyzer, app.ai.stages\n"
        f"bad = [m for m in sys.modules if m.startswith({FORBIDDEN_PREFIXES[:5]!r})]\n"
        "print(','.join(sorted(bad)))\n"
    )
    out = subprocess.run(  # noqa: S603 - fixed interpreter and code
        [sys.executable, "-c", code],
        cwd=BACKEND,
        capture_output=True,
        text=True,
        check=True,
        timeout=60,
    )
    assert out.stdout.strip() == "", out.stdout


def test_analysis_output_cannot_carry_send_instructions() -> None:
    from app.ai.analyzer import AnalysisOutput, ConversationAnalysis

    for model in (AnalysisOutput, ConversationAnalysis):
        fields = set(model.model_fields)
        assert not {f for f in fields if "send" in f or "recipient" in f or "approv" in f}
        assert model.model_config.get("extra") == "forbid"
    assert APP.is_dir()
