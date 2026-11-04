#!/usr/bin/env python3
"""Read-only mobile Markdown work board; Python standard library only.

Adapted from the sibling spofify-go board. No claims, writes or runtime role.
"""

import argparse
import json
from pathlib import Path
import re
import sys


ID = re.compile(r"(?:FEAT|BUG)-[0-9]{3}\Z")
STATUSES = {"todo", "in-progress", "review", "blocked", "done"}
PHASES = {"discovery", "implementation"}
REQUIRED = {
    "ID", "Status", "Phase", "Spec readiness", "Owner", "Branch", "Worktree",
    "Depends on", "Touchpoints", "Next action",
}
UNASSIGNED = {"unassigned", "none", "tbd", ""}


def dependencies(value):
    if value == "none":
        return []
    return [part.strip() for part in value.split(",")]


def load_specs(root):
    specs, errors = [], []
    directory = root / "docs" / "specs"
    if not directory.is_dir():
        return [], [f"missing spec directory: {directory}"]
    for path in sorted(directory.glob("*.md")):
        if path.name == "README.md" or path.name.startswith("_"):
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            errors.append(f"{path.name}: cannot read: {exc}")
            continue
        if not re.match(r"(?:FEAT|BUG)-[0-9]{3}-.+\.md\Z", path.name):
            errors.append(f"{path.name}: use ID-short-name.md (notes belong in research/)")
        headings = list(re.finditer(
            r"^ {0,3}#{1,6}[ \t]+Tracking(?:[ \t]+#+)?[ \t]*$", content, re.M))
        if not headings:
            errors.append(f"{path.name}: missing ## Tracking section")
            continue
        if len(headings) != 1:
            errors.append(f"{path.name}: duplicate Tracking sections")
            continue
        if headings[0][0].rstrip() != "## Tracking":
            errors.append(f"{path.name}: use exactly '## Tracking' for metadata")
            continue
        tracking = re.split(r"^#{1,2}[ \t]+", content[headings[0].end():],
                            maxsplit=1, flags=re.M)[0]
        fields = {}
        for line in tracking.splitlines():
            if not line.strip():
                continue
            field = re.fullmatch(r"- ([^:\n]+):[ \t]*(.*)", line)
            if not field:
                errors.append(f"{path.name}: malformed tracking line {line!r}")
                continue
            name, value = field.groups()
            if name not in REQUIRED | {"Discovery depends on"}:
                errors.append(f"{path.name}: unknown tracking field {name!r}")
            if name in fields:
                errors.append(f"{path.name}: duplicate tracking field {name}")
            fields[name] = value.strip()
        missing = sorted(REQUIRED - fields.keys())
        if missing:
            errors.append(f"{path.name}: missing tracking fields: {', '.join(missing)}")
            continue
        heading = content.splitlines()[0] if content.splitlines() else ""
        fields["title"] = re.sub(r"^# \S+\s+[—-]\s+", "", heading)
        fields["path"] = str(path.relative_to(root))
        if not heading.startswith(f"# {fields['ID']} — "):
            errors.append(f"{path.name}: heading must be '# ID — Title'")
        specs.append(fields)
    if not specs and not errors:
        errors.append(f"no task specs in: {directory}")
    return specs, errors


def validate(specs):
    errors, by_id = [], {}
    for spec in specs:
        label = spec["path"]
        key = spec["ID"]
        if not ID.fullmatch(key):
            errors.append(f"{label}: invalid ID {key!r}")
        if not Path(label).name.startswith(key + "-"):
            errors.append(f"{label}: filename does not match ID {key}")
        if key in by_id:
            errors.append(f"{label}: duplicate ID {key}")
        by_id[key] = spec
        for field, allowed in (("Status", STATUSES), ("Phase", PHASES),
                               ("Spec readiness", {"draft", "ready"})):
            if spec[field] not in allowed:
                errors.append(f"{key}: invalid {field}: {spec[field]!r}")
        for field in sorted(REQUIRED):
            if not spec[field]:
                errors.append(f"{key}: empty {field}")
        if spec["Status"] in {"in-progress", "review"}:
            for field in ("Owner", "Branch", "Worktree"):
                if spec[field].lower() in UNASSIGNED:
                    errors.append(f"{key}: active work needs {field}")
            if spec["Branch"] == "main":
                errors.append(f"{key}: active work needs a dedicated topic branch")
            if (spec["Worktree"].lower() not in UNASSIGNED and
                    not Path(spec["Worktree"]).is_absolute()):
                errors.append(f"{key}: active Worktree must be absolute")
        if (spec["Phase"] == "implementation" and
                spec["Status"] in {"in-progress", "review", "done"} and
                spec["Spec readiness"] != "ready"):
            errors.append(f"{key}: implementation needs an accepted ready spec")
        if spec["Status"] == "done" and spec["Phase"] != "implementation":
            errors.append(f"{key}: discovery is not a done feature")

    graph = {}
    for key, spec in by_id.items():
        graph[key] = []
        for field in ("Depends on", "Discovery depends on"):
            refs = dependencies(spec.get(field, "none"))
            if len(refs) != len(set(refs)):
                errors.append(f"{key}: duplicate dependency in {field}")
            for dep in refs:
                if not ID.fullmatch(dep):
                    errors.append(f"{key}: invalid dependency {dep!r} in {field}")
                elif dep == key:
                    errors.append(f"{key}: self-dependency")
                elif dep not in by_id:
                    errors.append(f"{key}: missing dependency {dep}")
                else:
                    graph[key].append(dep)

    visited, active = set(), []

    def visit(key):
        if key in active:
            errors.append("dependency cycle: " + " -> ".join(active[active.index(key):] + [key]))
            return
        if key in visited:
            return
        active.append(key)
        for dep in graph[key]:
            visit(dep)
        active.pop()
        visited.add(key)

    for key in graph:
        visit(key)
    return errors


def waits(spec, by_id):
    field = "Discovery depends on" if spec["Phase"] == "discovery" else "Depends on"
    reasons = [f"wait for {dep}" for dep in dependencies(spec.get(field, "none"))
               if by_id[dep]["Status"] != "done"]
    if spec["Phase"] == "implementation" and spec["Spec readiness"] != "ready":
        reasons.append("spec not ready")
    if spec["Status"] == "blocked":
        reasons.append("blocked: " + spec["Next action"])
    return reasons


def runnable(spec, by_id):
    return spec["Status"] == "todo" and not waits(spec, by_id)


def cell(value):
    return value.replace("|", "\\|").replace("\n", " ")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("board", "ready", "check"), nargs="?", default="board")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    args = parser.parse_args(argv)
    try:
        specs, errors = load_specs(args.root.resolve())
    except OSError as exc:
        parser.exit(2, f"cannot read specs: {exc}\n")
    errors += validate(specs)
    if errors:
        if args.json:
            print(json.dumps({"errors": errors}, indent=2))
        else:
            print("Spec check failed:\n- " + "\n- ".join(errors), file=sys.stderr)
        return 1
    by_id = {spec["ID"]: spec for spec in specs}
    rows = specs if args.command != "ready" else [s for s in specs if runnable(s, by_id)]
    if args.json:
        print(json.dumps({"count": len(rows), "specs": [
            {**spec, "runnable": runnable(spec, by_id), "waiting": waits(spec, by_id)}
            for spec in rows]}, indent=2))
    elif args.command == "check":
        print(f"OK: {len(specs)} specs; tracking and dependency graph valid")
    elif not rows:
        print("No runnable todo tasks." if args.command == "ready" else "No feature specs yet.")
    else:
        print("| Task | Status / phase | Owner | Startability |")
        print("| --- | --- | --- | --- |")
        for spec in rows:
            reasons = waits(spec, by_id)
            state = "; ".join(reasons) if reasons else (
                "ready to assign" if runnable(spec, by_id) else spec["Status"])
            print("| " + " | ".join(cell(value) for value in (
                f"{spec['ID']} {spec['title']}", f"{spec['Status']} / {spec['Phase']}",
                spec["Owner"], state)) + " |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
