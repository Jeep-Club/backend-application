"""GitHub event baseline regressions using isolated temporary repositories."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import ci_base
import progressive
import audit_migrations
import audit_reports


class GitRepo:
    def __init__(self, path):
        self.path = path
        self.path.mkdir(parents=True, exist_ok=True)
        self.git("init", "-q")
        self.git("config", "user.name", "BACK-682 regression")
        self.git("config", "user.email", "back-682@example.invalid")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.path), *args], text=True,
                                       stderr=subprocess.PIPE).strip()

    def write(self, name, content):
        path = self.path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-qm", message)
        return self.git("rev-parse", "HEAD")

    def history(self):
        self.write("src/main/resources/db/migration/V1__baseline.sql", "create table base_row(id int);\n")
        historical = "src/test/resources/database/historical"
        lines = []
        for name in ("v1.sql", "v2.sql", "v3.sql"):
            content = f"-- frozen {name}\nselect 1;\n"
            self.write(f"{historical}/{name}", content)
            lines.append(f"{hashlib.sha256(content.encode()).hexdigest()}  {name}")
        self.write(f"{historical}/SHA256SUMS", "\n".join(lines) + "\n")
        return self.commit("baseline")


class CiBaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="back682-ci-base-")
        self.repo = GitRepo(Path(self.temp.name) / "repo")
        self.base = self.repo.history()

    def tearDown(self):
        self.temp.cleanup()

    def push(self, before=None, ref="refs/heads/develop", after=None):
        head = after or self.repo.git("rev-parse", "HEAD")
        before = before or self.base
        return ci_base.resolve_comparison_base(self.repo.path, "push",
            {"ref": ref, "before": before, "after": head, "commits": []}, head, ref)

    def add_migration(self, name="V2__add_feature.sql"):
        self.repo.write(f"src/main/resources/db/migration/{name}", "alter table base_row add column value int;\n")
        return self.repo.commit("add migration")

    def add_pr_merge(self, change="migration"):
        self.repo.git("checkout", "-qb", "pr-head", self.base)
        if change == "migration":
            self.add_migration()
        else:
            self.repo.write("README-pr.txt", "documentation only\n")
            self.repo.commit("non-migration change")
        pr_head = self.repo.git("rev-parse", "HEAD")
        tree = self.repo.git("rev-parse", "HEAD^{tree}")
        merge = self.repo.git("commit-tree", tree, "-p", self.base, "-p", pr_head, "-m", "synthetic PR merge")
        self.repo.git("checkout", "--detach", merge)
        return pr_head, merge

    def pr(self, base, source, merge):
        payload = {"number": 145, "pull_request": {"base": {"sha": base}, "head": {"sha": source}}}
        return ci_base.resolve_comparison_base(self.repo.path, "pull_request", payload,
                                                merge, "refs/pull/145/merge")

    def info(self, result):
        return progressive.static_migrations(self.repo.path, result["sha"])

    def test_push_develop_uses_before_when_origin_develop_is_the_tested_head(self):
        first = self.add_migration()
        self.repo.write("push-second.txt", "second commit\n")
        head = self.repo.commit("second pushed commit")
        self.repo.git("update-ref", "refs/remotes/origin/develop", head)
        payload = {"ref": "refs/heads/develop", "before": self.base, "after": head, "commits": []}
        event_path = self.repo.path / "event.json"
        event_path.write_text(json.dumps(payload), encoding="utf-8")
        result = audit_migrations.comparison(self.repo.path, environ={
            "GITHUB_EVENT_NAME": "push", "GITHUB_EVENT_PATH": str(event_path),
            "GITHUB_SHA": head, "GITHUB_REF": "refs/heads/develop"})
        info = self.info(result)
        self.assertEqual(self.base, result["sha"])
        self.assertEqual(2, result["pushed_commits"])
        self.assertEqual(["src/main/resources/db/migration/V2__add_feature.sql"],
                         [item["path"].replace("\\", "/") for item in info["new"]])
        self.assertNotEqual(first, head)
        with self.assertRaisesRegex(progressive.Invalid, "Uncovered new migration"):
            progressive.migration_risk_selectors(info, [], self.repo.path / "missing-risk.json", self.repo.path)

    def test_github_actions_without_event_payload_cannot_fall_back(self):
        with self.assertRaisesRegex(ci_base.BaseError, "GITHUB_EVENT_PATH is required"):
            audit_migrations.comparison(self.repo.path, environ={"GITHUB_ACTIONS": "true"})

    def test_mysql_gate_stamp_uses_event_before_even_when_develop_ref_is_head(self):
        self.add_migration()
        head = self.repo.git("rev-parse", "HEAD")
        self.repo.git("update-ref", "refs/remotes/origin/develop", head)
        payload = {"ref": "refs/heads/develop", "before": self.base, "after": head, "commits": []}
        event_path = Path(self.temp.name) / "push-event.json"
        event_path.write_text(json.dumps(payload), encoding="utf-8")
        stamp = Path(self.temp.name) / "mysql-start.json"
        env = {"GITHUB_RUN_ID": "12345", "GITHUB_JOB": "mysql-migrations",
               "GITHUB_EVENT_NAME": "push", "GITHUB_EVENT_PATH": str(event_path),
               "GITHUB_SHA": head, "GITHUB_REF": "refs/heads/develop"}
        with mock.patch.object(audit_migrations, "ROOT", self.repo.path), \
             mock.patch.dict(os.environ, env), \
             mock.patch.object(sys, "argv", ["audit_migrations.py", "--start", "--stamp", str(stamp)]):
            audit_migrations.main()
        recorded = json.loads(stamp.read_text(encoding="utf-8"))
        self.assertEqual(self.base, recorded["identity"]["comparison"]["sha"])
        self.assertEqual("push.before", recorded["identity"]["comparison"]["source"])
        self.assertEqual(1, len(recorded["migrations"]["new"]))

    def test_push_master_detects_new_migration(self):
        self.add_migration()
        result = self.push(self.base, ref="refs/heads/master")
        self.assertEqual("master", result["ref"].split("/")[-1])
        self.assertEqual(1, len(self.info(result)["new"]))

    def test_push_without_migration_is_clean_comparison(self):
        self.repo.write("change.txt", "docs\n")
        self.repo.commit("docs")
        result = self.push()
        self.assertEqual([], self.info(result)["new"])

    def test_pull_request_migration_uses_payload_base_and_requires_review(self):
        source, merge = self.add_pr_merge()
        result = self.pr(self.base, source, merge)
        info = self.info(result)
        self.assertEqual(self.base, result["sha"])
        self.assertEqual(1, len(info["new"]))
        with self.assertRaisesRegex(progressive.Invalid, "Uncovered new migration"):
            progressive.migration_risk_selectors(info, [], self.repo.path / "missing-risk.json", self.repo.path)

    def test_pull_request_without_migration_does_not_block(self):
        source, merge = self.add_pr_merge("docs")
        self.assertEqual([], self.info(self.pr(self.base, source, merge))["new"])

    def test_pull_request_base_advanced_while_open_uses_verified_merge_parent(self):
        self.repo.git("checkout", "-qb", "pr-head", self.base)
        self.add_migration()
        source = self.repo.git("rev-parse", "HEAD")
        self.repo.git("checkout", "-qb", "develop-advanced", self.base)
        self.repo.write("develop-advanced.txt", "target branch advanced\n")
        updated_base = self.repo.commit("advance target branch")
        self.repo.git("merge", "--no-ff", "--no-commit", "pr-head")
        self.repo.git("commit", "-qm", "synthetic pull request merge")
        merge = self.repo.git("rev-parse", "HEAD")
        result = self.pr(self.base, source, merge)
        self.assertEqual(updated_base, result["sha"])
        self.assertEqual(self.base, result["event_base"])
        self.assertEqual("pull_request.merge_base", result["source"])
        self.assertEqual(1, len(self.info(result)["new"]))

    def test_pull_request_updated_base_must_be_ancestor_of_synthetic_parent(self):
        source, merge = self.add_pr_merge("docs")
        with self.assertRaisesRegex(ci_base.BaseError, "not a proven ancestor"):
            self.pr(source, source, merge)

    def test_stale_pull_request_base_or_checkout_ref_fails(self):
        source, merge = self.add_pr_merge()
        with self.assertRaisesRegex(ci_base.BaseError, "not a proven ancestor"):
            self.pr(source, source, merge)
        with self.assertRaisesRegex(ci_base.BaseError, "does not match payload"):
            ci_base.resolve_comparison_base(self.repo.path, "pull_request",
                {"number": 145, "pull_request": {"base": {"sha": self.base}, "head": {"sha": source}}},
                merge, "refs/pull/146/merge")

    def test_push_with_historical_migration_change_is_blocked(self):
        historical = self.repo.path / "src/main/resources/db/migration/V1__baseline.sql"
        historical.write_text("-- unauthorized drift\n", encoding="utf-8")
        self.repo.commit("change historical migration")
        result = self.push()
        with self.assertRaisesRegex(progressive.Invalid, "Historical migration/fixture drift"):
            self.info(result)

    def test_before_equal_head_and_invalid_or_zero_shas_fail(self):
        head = self.repo.git("rev-parse", "HEAD")
        with self.assertRaisesRegex(ci_base.BaseError, "equals tested HEAD"):
            self.push(before=head, after=head)
        for value, message in [(None, "full commit SHA"), ("bad-sha", "full commit SHA"), ("0" * 40, "all zeros")]:
            with self.subTest(value=str(value)[:8]), self.assertRaisesRegex(ci_base.BaseError, message):
                payload = {"ref": "refs/heads/develop", "before": value, "after": head}
                ci_base.resolve_comparison_base(self.repo.path, "push", payload, head, "refs/heads/develop")

    def test_missing_before_commit_fails_diagnostically(self):
        head = self.repo.git("rev-parse", "HEAD")
        absent = "a" * 40
        if absent == head:
            absent = "b" * 40
        with self.assertRaisesRegex(ci_base.BaseError, "Cannot verify Git comparison history"):
            self.push(before=absent, after=head)

    def test_unrelated_force_push_history_is_rejected(self):
        self.repo.git("checkout", "--orphan", "unrelated")
        self.repo.write("independent.txt", "unrelated\n")
        head = self.repo.commit("unrelated history")
        with self.assertRaisesRegex(ci_base.BaseError, "not a proven ancestor"):
            self.push(before=self.base, after=head)

    def test_push_event_refs_and_after_sha_must_match_checkout(self):
        self.repo.write("one.txt", "one\n")
        head = self.repo.commit("one")
        with self.assertRaisesRegex(ci_base.BaseError, "does not match an allowed branch"):
            self.push(ref="refs/heads/feature", after=head)
        with self.assertRaisesRegex(ci_base.BaseError, "does not match event GITHUB_SHA"):
            self.push(after=self.base)

    def test_dispatch_requires_explicit_ancestor_and_never_falls_back(self):
        self.repo.write("dispatch.txt", "one\n")
        head = self.repo.commit("dispatch candidate")
        payload = {"inputs": {"migration_base": self.base}}
        result = ci_base.resolve_comparison_base(self.repo.path, "workflow_dispatch", payload,
                                                  head, "refs/heads/develop")
        self.assertEqual(self.base, result["sha"])
        for value, message in [(None, "full 40- or 64-character"), (head, "equals tested HEAD")]:
            with self.subTest(value=value), self.assertRaisesRegex(ci_base.BaseError, message):
                ci_base.resolve_comparison_base(self.repo.path, "workflow_dispatch",
                    {"inputs": {"migration_base": value}}, head, "refs/heads/develop")
        unrelated = "c" * 40
        with self.assertRaises(ci_base.BaseError):
            ci_base.resolve_comparison_base(self.repo.path, "workflow_dispatch",
                {"inputs": {"migration_base": unrelated}}, head, "refs/heads/develop")

    def test_dispatch_invalid_payload_file_fails(self):
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as event:
            event.write("{")
            event_path = event.name
        try:
            with self.assertRaisesRegex(ci_base.BaseError, "Cannot read GitHub event payload"):
                ci_base.from_environment(self.repo.path, {"GITHUB_EVENT_PATH": event_path})
        finally:
            os.unlink(event_path)

    def test_shallow_checkout_without_before_object_fails(self):
        self.repo.write("second.txt", "second\n")
        head = self.repo.commit("child commit")
        clone = Path(self.temp.name) / "shallow"
        subprocess.run(["git", "clone", "-q", "--depth", "1", self.repo.path.as_uri(), str(clone)],
                       check=True, capture_output=True, text=True)
        result = subprocess.run(["git", "-C", str(clone), "rev-parse", "HEAD"], capture_output=True, text=True)
        self.assertEqual(head, result.stdout.strip())
        payload = {"ref": "refs/heads/develop", "before": self.base, "after": head}
        with self.assertRaisesRegex(ci_base.BaseError, "Cannot verify Git comparison history"):
            ci_base.resolve_comparison_base(clone, "push", payload, head, "refs/heads/develop")

    def test_event_base_finds_new_method_even_if_origin_develop_is_head(self):
        rel = "src/test/java/com/jeepclub/backend/tools/ExistingTest.java"
        # The first pushed commit adds a class, the second adds a method to it.
        self.repo.write("src/test/java/com/jeepclub/backend/tools/ExistingTest.java",
                        "package com.jeepclub.backend.tools; class ExistingTest { @Test void old() {} }")
        self.repo.commit("add old test")
        # A second pushed commit introduces a method to an already discoverable class.
        self.base = self.repo.git("rev-parse", "HEAD")
        self.repo.write(rel, "package com.jeepclub.backend.tools; class ExistingTest { @Test void old() {} @Test void added() {} }")
        head = self.repo.commit("add test method")
        self.repo.git("update-ref", "refs/remotes/origin/develop", head)
        event = {"ref": "refs/heads/develop", "before": self.base, "after": head, "commits": []}
        result = ci_base.resolve_comparison_base(self.repo.path, "push", event, head, "refs/heads/develop")
        selected = [dict(name="com.jeepclub.backend.tools.ExistingTest", kind="unit", path=rel)]
        methods = progressive.new_test_methods(self.repo.path, result["sha"], selected)
        self.assertEqual(["added"], [method["method"] for method in methods])
        reports = Path(self.temp.name) / "reports"
        reports.mkdir()
        (reports / "TEST-existing.xml").write_text(
            '<testsuite><testcase classname="com.jeepclub.backend.tools.ExistingTest" name="old"/>'
            '<testcase classname="com.jeepclub.backend.tools.ExistingTest" name="added"/></testsuite>',
            encoding="utf-8")
        out = Path(self.temp.name) / "audit.json"
        event_path = Path(self.temp.name) / "unit-event.json"
        event_path.write_text(json.dumps(event), encoding="utf-8")
        env = {"GITHUB_EVENT_NAME": "push", "GITHUB_EVENT_PATH": str(event_path),
               "GITHUB_SHA": head, "GITHUB_REF": "refs/heads/develop"}
        with mock.patch.object(audit_reports, "ROOT", self.repo.path), \
             mock.patch.object(audit_reports, "validate_maven"), \
             mock.patch.object(audit_reports, "inventory", return_value=(selected, [])), \
             mock.patch.dict(os.environ, env), \
             mock.patch.object(sys, "argv", ["audit_reports.py", "--kind", "unit", "--reports", str(reports), "--out", str(out)]):
            self.assertEqual(0, audit_reports.main())
        audited = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual("executed", audited["new_methods"][0]["status"])


if __name__ == "__main__":
    unittest.main()
