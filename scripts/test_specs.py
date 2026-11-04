"""Synthetic tests: python3 -m unittest discover -s scripts -p 'test_specs.py'."""

from contextlib import redirect_stdout, redirect_stderr
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import specs


def task(key="FEAT-001", **changes):
    result = {
        "ID": key, "Status": "todo", "Phase": "discovery", "Spec readiness": "draft",
        "Owner": "unassigned", "Branch": "unassigned", "Worktree": "unassigned",
        "Depends on": "none", "Discovery depends on": "none", "Touchpoints": "test fixtures",
        "Next action": "investigate", "title": "Synthetic feature",
        "path": f"docs/specs/{key}-synthetic.md",
    }
    result.update(changes)
    return result


def markdown(value):
    return f"# {value['ID']} — {value['title']}\n\n## Tracking\n\n" + "\n".join(
        f"- {key}: {text}" for key, text in value.items()
        if key not in {"title", "path"}
    ) + "\n\n## User problem\n\nSynthetic fixture only.\n"


class SpecTests(unittest.TestCase):
    def test_valid_graph_and_discovery_ignores_implementation_prerequisites(self):
        first = task()
        second = task("FEAT-002", **{"Depends on": "FEAT-001"})
        self.assertEqual(specs.validate([first, second]), [])
        by_id = {s["ID"]: s for s in (first, second)}
        self.assertTrue(specs.runnable(second, by_id))
        second.update({"Phase": "implementation", "Spec readiness": "ready"})
        self.assertFalse(specs.runnable(second, by_id))
        first.update({"Status": "done", "Phase": "implementation", "Spec readiness": "ready"})
        self.assertTrue(specs.runnable(second, by_id))
        self.assertEqual(specs.validate([first, second]), [])

    def test_discovery_dependency_waits(self):
        first = task()
        second = task("FEAT-002", **{"Discovery depends on": "FEAT-001"})
        self.assertEqual(specs.waits(second, {s["ID"]: s for s in (first, second)}),
                         ["wait for FEAT-001"])

    def test_unready_implementation_not_runnable(self):
        value = task(**{"Phase": "implementation"})
        self.assertFalse(specs.runnable(value, {value["ID"]: value}))
        self.assertEqual(specs.waits(value, {value["ID"]: value}), ["spec not ready"])

    def test_active_review_blocked_and_done_not_runnable(self):
        for status in ("in-progress", "review", "blocked", "done"):
            value = task(Status=status)
            self.assertFalse(specs.runnable(value, {value["ID"]: value}))

    def test_active_work_needs_ownership(self):
        errors = specs.validate([task(Status="in-progress")])
        for field in ("Owner", "Branch", "Worktree"):
            self.assertTrue(any(f"needs {field}" in error for error in errors))

    def test_active_implementation_needs_ready_spec(self):
        value = task(Status="review", Phase="implementation", Owner="lead", Branch="spec/test", Worktree="/test")
        self.assertTrue(any("accepted ready spec" in error for error in specs.validate([value])))

    def test_discovery_cannot_be_done(self):
        self.assertTrue(any("not a done feature" in error for error in specs.validate([task(Status="done")])))

    def test_bad_state_and_empty_value(self):
        errors = specs.validate([task(Status="running", Phase="research", **{"Next action": ""})])
        self.assertEqual(len(errors), 3)

    def test_missing_duplicate_and_invalid_dependencies(self):
        value = task(**{"Depends on": "FEAT-999, invalid, FEAT-999"})
        errors = specs.validate([value])
        for phrase in ("missing dependency", "invalid dependency", "duplicate dependency"):
            self.assertTrue(any(phrase in error for error in errors))

    def test_self_and_multi_node_cycles(self):
        self.assertTrue(any("self-dependency" in error for error in
                            specs.validate([task(**{"Depends on": "FEAT-001"})])))
        values = [task(**{"Depends on": "FEAT-002"}),
                  task("FEAT-002", **{"Discovery depends on": "FEAT-003"}),
                  task("FEAT-003", **{"Depends on": "FEAT-001"})]
        self.assertTrue(any("dependency cycle" in error for error in specs.validate(values)))

    def test_duplicate_id_and_filename_mismatch(self):
        errors = specs.validate([task(), task(path="docs/specs/FEAT-002-wrong.md")])
        self.assertTrue(any("duplicate ID" in error for error in errors))
        self.assertTrue(any("filename does not match" in error for error in errors))

    def test_parser_is_scoped_to_tracking_and_ignores_template_research(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = root / "docs/specs"
            directory.mkdir(parents=True)
            (directory / "README.md").write_text("board docs")
            (directory / "_template.md").write_text("template")
            (directory / "research").mkdir()
            (directory / "research/note.md").write_text("a research note")
            value = task()
            (root / value["path"]).write_text(markdown(value) + "\n- Status: bad outside Tracking\n")
            values, errors = specs.load_specs(root)
            self.assertEqual(errors, [])
            self.assertEqual(len(values), 1)
            self.assertEqual(values[0]["Status"], "todo")
            self.assertEqual(specs.validate(values), [])

    def test_bad_or_missing_metadata_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = root / "docs/specs"
            directory.mkdir(parents=True)
            (directory / "FEAT-001-test.md").write_text("# Feature\n")
            (directory / "FEAT-002-test.md").write_text("# Feature\n## Tracking\n- ID: FEAT-002\n")
            (directory / "FEAT-003-test.md").write_text(markdown(task("FEAT-003")).replace(
                "- Status: todo", "- Status: todo\n- Status: review"))
            _, errors = specs.load_specs(root)
            for phrase in ("missing ## Tracking", "missing tracking fields", "duplicate tracking field"):
                self.assertTrue(any(phrase in error for error in errors))
            output = io.StringIO()
            with redirect_stdout(output):
                result = specs.main(["ready", "--root", str(root), "--json"])
            self.assertEqual(result, 1)
            self.assertIn("errors", json.loads(output.getvalue()))

    def test_malformed_optional_dependency_cannot_advertise_runnable_work(self):
        canonical = "- Discovery depends on: FEAT-001"
        variants = (
            " " + canonical,
            "- Discovery depends on FEAT-001",
            "- Discovery depends on : FEAT-001",
            "- Discovery dependencies: FEAT-001",
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs/specs").mkdir(parents=True)
            first = task(Status="blocked")
            second = task("FEAT-002", **{"Discovery depends on": "FEAT-001"})
            (root / first["path"]).write_text(markdown(first))
            path = root / second["path"]
            for variant in variants:
                with self.subTest(line=variant):
                    path.write_text(markdown(second).replace(canonical, variant))
                    for command in ("check", "board", "ready"):
                        output = io.StringIO()
                        with redirect_stdout(output):
                            result = specs.main([command, "--root", str(root), "--json"])
                        self.assertEqual(result, 1)
                        report = json.loads(output.getvalue())
                        self.assertIn("errors", report)
                        self.assertNotIn("specs", report)

    def test_duplicate_tracking_sections_fail_closed(self):
        for spaces in range(4):
            for heading in ("## Tracking", "### Tracking", "## Tracking ##"):
                heading = " " * spaces + heading
                with self.subTest(heading=heading), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    (root / "docs/specs").mkdir(parents=True)
                    first = task(Status="blocked")
                    value = task("FEAT-002")
                    (root / first["path"]).write_text(markdown(first))
                    # Append after an ordinary section, outside the first metadata block.
                    (root / value["path"]).write_text(markdown(value) +
                        f"\n{heading}\n- Status: blocked\n- Discovery depends on: FEAT-001\n")
                    for command in ("check", "board", "ready"):
                        output = io.StringIO()
                        with redirect_stdout(output):
                            result = specs.main([command, "--root", str(root), "--json"])
                        self.assertEqual(result, 1)
                        report = json.loads(output.getvalue())
                        self.assertTrue(any("duplicate Tracking" in e for e in report["errors"]))
                        self.assertNotIn("specs", report)

    def test_nested_tracking_content_is_not_silently_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs/specs").mkdir(parents=True)
            value = task()
            (root / value["path"]).write_text(markdown(value).replace(
                "- Depends on: none", "### Prerequisites\n- Depends on: none"))
            _, errors = specs.load_specs(root)
            self.assertTrue(any("malformed tracking line" in e for e in errors))

    def test_cli_json_ready_and_read_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = root / "docs/specs"
            directory.mkdir(parents=True)
            for value in (task(), task("BUG-001", Status="blocked"),
                          task("FEAT-002", Phase="implementation")):
                (root / value["path"]).write_text(markdown(value))
            before = {p: p.read_bytes() for p in directory.glob("*.md")}
            output = io.StringIO()
            with redirect_stdout(output):
                result = specs.main(["ready", "--root", str(root), "--json"])
            self.assertEqual(result, 0)
            report = json.loads(output.getvalue())
            self.assertEqual([s["ID"] for s in report["specs"]], ["FEAT-001"])
            self.assertEqual(before, {p: p.read_bytes() for p in directory.glob("*.md")})

    def test_missing_directory_reports_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            with redirect_stderr(io.StringIO()):
                self.assertEqual(specs.main(["check", "--root", tmp]), 1)

    def test_invalid_filename_title_unknown_and_empty_metadata(self):
        variants = (
            ("notes.md", markdown(task()), "use ID-short-name"),
            ("FEAT-001-test.md", markdown(task()).replace("# FEAT-001 —", "# Wrong —"),
             "heading must be"),
            ("FEAT-001-test.md", markdown(task()).replace("- Owner: unassigned",
             "- Owner: unassigned\n- Surprise: ignored?"), "unknown tracking field"),
            ("FEAT-001-test.md", markdown(task()).replace("- Depends on: none",
             "- Depends on: "), "empty Depends on"),
        )
        for filename, content, message in variants:
            with self.subTest(message=message), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / "docs/specs").mkdir(parents=True)
                (root / "docs/specs" / filename).write_text(content, encoding="utf-8")
                values, errors = specs.load_specs(root)
                errors += specs.validate(values)
                self.assertTrue(any(message in e for e in errors), errors)

    def test_optional_discovery_dependency_defaults_only_when_absent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs/specs").mkdir(parents=True)
            path = root / task()["path"]
            path.write_text(markdown(task()).replace("- Discovery depends on: none\n", ""))
            values, errors = specs.load_specs(root)
            self.assertEqual(errors + specs.validate(values), [])
            self.assertTrue(specs.runnable(values[0], {values[0]["ID"]: values[0]}))
            path.write_text(markdown(task()).replace("- Discovery depends on: none",
                                                    "- Discovery depends on:"))
            values, errors = specs.load_specs(root)
            self.assertTrue(any("invalid dependency" in e for e in errors + specs.validate(values)))

    def test_unreadable_encoding_is_an_inventory_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs/specs").mkdir(parents=True)
            (root / task()["path"]).write_bytes(bytes([255]))
            values, errors = specs.load_specs(root)
            self.assertEqual(values, [])
            self.assertTrue(any("cannot read" in e for e in errors))

    def test_empty_inventory_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs/specs").mkdir(parents=True)
            (root / "docs/specs/_template.md").write_text("not a task")
            output = io.StringIO()
            with redirect_stdout(output):
                result = specs.main(["ready", "--root", tmp, "--json"])
            self.assertEqual(result, 1)
            self.assertIn("no task specs", output.getvalue())

    def test_active_work_requires_topic_and_absolute_worktree(self):
        errors = specs.validate([task(Status="in-progress", Owner="lead",
                                      Branch="main", Worktree="relative")])
        self.assertTrue(any("dedicated topic" in e for e in errors))
        self.assertTrue(any("must be absolute" in e for e in errors))

    def test_subprocess_commands_from_other_cwd_are_read_only_and_deterministic(self):
        script = Path(specs.__file__).resolve()
        with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryDirectory() as cwd:
            root = Path(tmp)
            directory = root / "docs/specs"
            directory.mkdir(parents=True)
            for value in (task("FEAT-002"), task(), task("BUG-001", Status="blocked")):
                (root / value["path"]).write_text(markdown(value), encoding="utf-8")
            def snapshot():
                return {str(p.relative_to(root)): (p.read_bytes(), p.stat().st_mode,
                                                   p.stat().st_mtime_ns)
                        for p in root.rglob("*") if p.is_file()}
            for path in directory.iterdir():
                path.chmod(0o444)
            directory.chmod(0o555)
            before = snapshot()
            try:
                for command in ("board", "ready", "check"):
                    for json_flag in ([], ["--json"]):
                        args = [sys.executable, str(script), command, "--root", str(root)] + json_flag
                        first = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
                        second = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
                        self.assertEqual(first.returncode, 0, first.stderr)
                        self.assertEqual(first.stdout, second.stdout)
                        if json_flag:
                            report = json.loads(first.stdout)
                            ids = [s["ID"] for s in report["specs"]]
                            self.assertEqual(ids, ["FEAT-001", "FEAT-002"] if command == "ready"
                                             else ["BUG-001", "FEAT-001", "FEAT-002"])
                self.assertEqual(before, snapshot())
            finally:
                directory.chmod(0o755)
                for path in directory.iterdir():
                    path.chmod(0o644)
            # Default root is script-relative, never cwd-relative.
            result = subprocess.run([sys.executable, str(script), "check", "--json"],
                                    cwd=cwd, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr or result.stdout)
            self.assertGreater(json.loads(result.stdout)["count"], 0)

    def test_subprocess_invalid_inventory_never_returns_partial_board(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs/specs").mkdir(parents=True)
            first = task()
            second = task("FEAT-002", **{"Discovery depends on": "FEAT-999"})
            for value in (first, second):
                (root / value["path"]).write_text(markdown(value), encoding="utf-8")
            for command in ("board", "ready", "check"):
                result = subprocess.run([sys.executable, str(Path(specs.__file__).resolve()),
                                         command, "--root", tmp, "--json"],
                                        capture_output=True, text=True, env={**os.environ,
                                        "PYTHONDONTWRITEBYTECODE": "1"})
                self.assertEqual(result.returncode, 1)
                report = json.loads(result.stdout)
                self.assertIn("errors", report)
                self.assertNotIn("specs", report)

    def test_board_escapes_table_cells(self):
        self.assertEqual(specs.cell("one|two\nthree"), "one\\|two three")


if __name__ == "__main__":
    unittest.main()
