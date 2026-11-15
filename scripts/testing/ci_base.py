"""Resolve a trusted Git comparison commit from a GitHub Actions event."""
import json
import re
import subprocess
from pathlib import Path


class BaseError(ValueError):
    """The event does not provide a provable comparison baseline."""


SHA = re.compile(r"^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$")
ZERO = re.compile(r"^0+$")


def _git(root, *args):
    try:
        return subprocess.check_output(["git", "-C", str(root), *args], text=True,
                                       stderr=subprocess.PIPE).strip()
    except (subprocess.CalledProcessError, OSError) as exc:
        detail = exc.stderr.strip() if isinstance(exc, subprocess.CalledProcessError) and exc.stderr else str(exc)
        raise BaseError(f"Cannot verify Git comparison history: {detail}") from exc


def _commit(root, value, label):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise BaseError(f"{label} must be a full 40- or 64-character commit SHA")
    if ZERO.fullmatch(value):
        raise BaseError(f"{label} is the all-zero SHA; no previous baseline exists")
    resolved = _git(root, "rev-parse", "--verify", f"{value}^{{commit}}")
    if resolved.lower() != value.lower():
        raise BaseError(f"{label} does not identify the supplied commit object")
    return resolved


def _ancestor(root, base, head, label):
    result = subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", base, head],
                            text=True, capture_output=True)
    if result.returncode == 1:
        shallow = _git(root, "rev-parse", "--is-shallow-repository") == "true"
        detail = "; checkout is shallow so ancestry is incomplete" if shallow else ""
        raise BaseError(f"{label} {base} is not a proven ancestor of tested HEAD {head}; force-push or unrelated history is unsupported{detail}")
    if result.returncode != 0:
        raise BaseError(f"Cannot establish ancestry for {label} {base}: {result.stderr.strip()}")


def resolve_comparison_base(root, event_name, payload, github_sha, github_ref):
    """Return the exact base commit and provenance; never infer from moving branch refs."""
    head = _commit(root, github_sha, "GITHUB_SHA")
    checked_out = _git(root, "rev-parse", "HEAD")
    if checked_out != head:
        raise BaseError(f"Checkout HEAD {checked_out} does not match event GITHUB_SHA {head}")

    if event_name == "pull_request":
        pr = payload.get("pull_request") or {}
        number = payload.get("number")
        base = _commit(root, (pr.get("base") or {}).get("sha"), "pull_request.base.sha")
        source = _commit(root, (pr.get("head") or {}).get("sha"), "pull_request.head.sha")
        expected_ref = f"refs/pull/{number}/merge"
        if not isinstance(number, int) or github_ref != expected_ref:
            raise BaseError(f"PR event ref {github_ref!r} does not match payload merge ref {expected_ref!r}")
        parents = _git(root, "rev-list", "--parents", "-n", "1", head).split()[1:]
        if len(parents) != 2 or set(parents) != {base, source}:
            raise BaseError("Tested pull_request merge commit does not have the exact base/head SHAs from its event payload")
        return dict(sha=base, source="pull_request.base.sha", event=event_name, ref=github_ref,
                    head=head, pr_head=source)

    if event_name == "push":
        ref = payload.get("ref")
        if ref != github_ref or ref not in ("refs/heads/develop", "refs/heads/master"):
            raise BaseError(f"Push payload ref {ref!r} does not match an allowed branch ref {github_ref!r}")
        after = _commit(root, payload.get("after"), "push.after")
        before_value = payload.get("before")
        if not isinstance(before_value, str) or not SHA.fullmatch(before_value):
            raise BaseError("push.before must be a full commit SHA")
        if ZERO.fullmatch(before_value):
            raise BaseError("push.before is all zeros (new branch); there is no trusted previous baseline")
        before = _commit(root, before_value, "push.before")
        if after != head:
            raise BaseError(f"Push after SHA {after} does not match tested GITHUB_SHA {head}")
        if before == head:
            raise BaseError("push.before equals tested HEAD; refusing an empty/self comparison baseline")
        _ancestor(root, before, head, "push.before")
        count = int(_git(root, "rev-list", "--count", f"{before}..{head}"))
        if count < 1:
            raise BaseError("Push range contains no commits")
        return dict(sha=before, source="push.before", event=event_name, ref=ref,
                    head=head, pushed_commits=count)

    if event_name == "workflow_dispatch":
        inputs = payload.get("inputs") or payload.get("workflow_dispatch", {}).get("inputs") or {}
        baseline_value = inputs.get("migration_base") or (payload.get("github", {}).get("event", {}).get("inputs") or {}).get("migration_base")
        baseline = _commit(root, baseline_value, "workflow_dispatch.inputs.migration_base")
        if baseline == head:
            raise BaseError("workflow_dispatch migration_base equals tested HEAD")
        _ancestor(root, baseline, head, "workflow_dispatch migration_base")
        return dict(sha=baseline, source="workflow_dispatch.inputs.migration_base",
                    event=event_name, ref=github_ref, head=head)

    raise BaseError(f"Unsupported GitHub event for migration comparison: {event_name!r}")


def from_environment(root, environ=None):
    import os
    env = os.environ if environ is None else environ
    event_path = env.get("GITHUB_EVENT_PATH")
    if not event_path:
        raise BaseError("GITHUB_EVENT_PATH is required to establish a CI comparison baseline")
    try:
        payload = json.loads(Path(event_path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise BaseError(f"Cannot read GitHub event payload {event_path}: {exc}") from exc
    return resolve_comparison_base(root, env.get("GITHUB_EVENT_NAME"), payload,
                                   env.get("GITHUB_SHA"), env.get("GITHUB_REF"))


def validate_explicit_base(root, value):
    """Validate an explicit local baseline as a full commit ancestor of HEAD."""
    head = _git(root, "rev-parse", "HEAD")
    base = _commit(root, value, "--base")
    if base == head:
        raise BaseError("Explicit comparison base equals HEAD")
    _ancestor(root, base, head, "Explicit comparison base")
    return dict(sha=base, source="explicit --base", event="local", ref=None, head=head)
