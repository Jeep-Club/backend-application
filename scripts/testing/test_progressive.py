"""Regression tests for progressive Maven test selection."""
import hashlib
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("progressive.py")
SPEC = importlib.util.spec_from_file_location("progressive", SCRIPT)
progressive = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(progressive)


def java(root, rel, content):
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def record(name, module, kind="unit"):
    return dict(name=name, module=module, kind=kind, selector=name)


class ProgressiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_a_inventory_counts_inherited_and_parameterized_candidates_and_helpers(self):
        java(self.root, "src/test/java/com/jeepclub/backend/identity/ParentIntegrationTest.java", """
            package com.jeepclub.backend.identity;
            abstract class ParentIntegrationTest { @Test void inherited() {} }
        """)
        java(self.root, "src/test/java/com/jeepclub/backend/identity/ChildMySqlIT.java", """
            package com.jeepclub.backend.identity;
            class ChildMySqlIT extends ParentIntegrationTest { }
        """)
        java(self.root, "src/test/java/com/jeepclub/backend/identity/ParameterizedTest.java", """
            package com.jeepclub.backend.identity;
            class ParameterizedTest { @ParameterizedTest void cases() {} }
        """)
        java(self.root, "src/test/java/com/jeepclub/backend/identity/NestedTest.java", """
            package com.jeepclub.backend.identity;
            class NestedTest { @Nested class NestedScenario { @Test void nested() {} } }
        """)
        java(self.root, "src/test/java/com/jeepclub/backend/identity/AbstractContract.java", """
            package com.jeepclub.backend.identity;
            abstract class AbstractContract { @Test void inheritedContract() {} }
        """)
        records, helpers = progressive.inventory(self.root)
        by_name = {r["name"]: r for r in records}
        self.assertEqual({"iam.identity"}, {r["module"] for r in records})
        child = by_name["com.jeepclub.backend.identity.ChildMySqlIT"]
        self.assertEqual("mysql", child["kind"])
        self.assertTrue(child["inherited"])
        self.assertTrue(by_name["com.jeepclub.backend.identity.ParameterizedTest"]["declared_tests"])
        portable_helpers = {p.replace("\\", "/") for p in helpers}
        self.assertIn("src/test/java/com/jeepclub/backend/identity/AbstractContract.java", portable_helpers)
        self.assertNotIn("src/test/java/com/jeepclub/backend/identity/NestedTest.java", portable_helpers)

    def test_b_selection_rejects_unknown_and_empty_modules(self):
        rows = [record("com.jeepclub.backend.identity.UserTest", "iam.identity")]
        with self.assertRaisesRegex(progressive.Invalid, "Unknown module"):
            progressive.select(rows, "F2", modules=["iam.unknown"])
        with self.assertRaisesRegex(progressive.Invalid, "Empty mandatory selection"):
            progressive.select(rows, "F4", modules=["iam.identity"])
        with self.assertRaisesRegex(progressive.Invalid, "requires --module"):
            progressive.select(rows, "F2")

    def test_f1_rejects_unknown_methods_but_allows_inherited_method_resolution(self):
        rows = [dict(record("com.jeepclub.backend.identity.UserTest", "iam.identity"),
                     methods=["exists"], inherited=False),
                dict(record("com.jeepclub.backend.identity.InheritedTest", "iam.identity"),
                     methods=["baseCase"], inherited=True)]
        self.assertEqual("com.jeepclub.backend.identity.UserTest#exists",
                         progressive.select(rows, "F1", focus=["UserTest#exists"])[0]["selector"])
        with self.assertRaisesRegex(progressive.Invalid, "Unknown method selector"):
            progressive.select(rows, "F1", focus=["UserTest#missing"])
        self.assertEqual("com.jeepclub.backend.identity.InheritedTest#baseCase",
                         progressive.select(rows, "F1", focus=["InheritedTest#baseCase"])[0]["selector"])

    def test_validate_maven_fails_closed_on_selection_contract_drift(self):
        pom = self.root / "pom.xml"
        good = """<project xmlns="http://maven.apache.org/POM/4.0.0"><build><plugins>
          <plugin><artifactId>maven-surefire-plugin</artifactId></plugin></plugins></build><profiles>
          <profile><id>mysql-migrations</id><build><plugins><plugin><artifactId>maven-failsafe-plugin</artifactId>
          <configuration><includes><include>**/*MySqlIT.java</include><include>**/MySqlMigrationIT.java</include>
          </includes></configuration></plugin></plugins></build></profile>
          <profile><id>publications-postgresql</id><build><plugins><plugin><artifactId>maven-failsafe-plugin</artifactId>
          <configuration><includes><include>**/*PostgresIT.java</include></includes></configuration>
          </plugin></plugins></build></profile></profiles></project>"""
        pom.write_text(good, encoding="utf-8")
        progressive.validate_maven(self.root)
        pom.write_text(good.replace("<artifactId>maven-surefire-plugin</artifactId>",
                                    "<artifactId>maven-surefire-plugin</artifactId><configuration><excludes><exclude>**/*IT.java</exclude></excludes></configuration>"),
                       encoding="utf-8")
        with self.assertRaisesRegex(progressive.Invalid, "Surefire selection configuration changed"):
            progressive.validate_maven(self.root)
        pom.write_text(good.replace("**/*MySqlIT.java", "**/*IntegrationTest.java"), encoding="utf-8")
        with self.assertRaisesRegex(progressive.Invalid, "Failsafe discovery changed"):
            progressive.validate_maven(self.root)

    def test_c_f3_closure_includes_new_consumer_and_shared_baseline(self):
        rows = [record("com.jeepclub.backend.identity.IdentityTest", "iam.identity"),
                record("com.jeepclub.backend.billing.BillingTest", "billing"),
                record("com.jeepclub.backend.vehicles.VehicleTest", "vehicles"),
                record("com.jeepclub.backend.platform.PlatformTest", "platform")]
        edges = [dict(consumer="iam.identity", provider="billing", source="new-consumer.java"),
                 dict(consumer="billing", provider="vehicles", source="bridge.java")]
        selected = progressive.select(rows, "F3", modules=["iam.identity"], edges=edges)
        self.assertEqual({"iam.identity", "billing", "vehicles", "platform"},
                         {r["module"] for r in selected})
        self.assertEqual(["iam.identity"], [r["module"] for r in progressive.select(
            rows, "F2", modules=["iam.identity"])])

        java(self.root, "src/main/java/com/jeepclub/backend/billing/api/module/Contract.java", """
            package com.jeepclub.backend.billing.api.module; public interface Contract {}
        """)
        java(self.root, "src/main/java/com/jeepclub/backend/identity/service/NewConsumer.java", """
            package com.jeepclub.backend.identity.service;
            import com.jeepclub.backend.billing.api.module.Contract;
            class NewConsumer { Contract contract; }
        """)
        actual_edges = progressive.relations(self.root, {"iam.identity", "billing"})
        self.assertTrue(any(e["consumer"] == "iam.identity" and e["provider"] == "billing"
                            and e["source"].endswith("NewConsumer.java") for e in actual_edges))

    def test_d_static_migrations_uses_isolated_git_history_and_checks_fixture(self):
        (self.root / ".git").mkdir()
        subprocess.run(["git", "-C", str(self.root), "init", "-q"], check=True)
        migrations = self.root / "src/main/resources/db/migration"
        historical = self.root / "src/test/resources/database/historical"
        migrations.mkdir(parents=True)
        historical.mkdir(parents=True)
        (migrations / "V1__baseline.sql").write_text("create table baseline(id int);\n", encoding="utf-8")
        fixture = b"select 1;\n"
        (historical / "v1.sql").write_bytes(fixture)
        checksum = hashlib.sha256(fixture).hexdigest()
        (historical / "SHA256SUMS").write_text(f"{checksum}  v1.sql\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=Test", "-c",
                        "user.email=test@example.test", "commit", "-qm", "baseline"], check=True)
        base = subprocess.check_output(["git", "-C", str(self.root), "rev-parse", "HEAD"], text=True).strip()
        (migrations / "V2__new_change.sql").write_text("alter table baseline add column value int;\n", encoding="utf-8")
        info = progressive.static_migrations(self.root, base)
        self.assertEqual(2, len(info["scripts"]))
        self.assertEqual(["src/main/resources/db/migration/V2__new_change.sql"],
                         [s["path"].replace("\\", "/") for s in info["new"]])
        self.assertTrue(info["requires_historical_validation"])
        (migrations / "V1__baseline.sql").write_text("-- drift\n", encoding="utf-8")
        with self.assertRaisesRegex(progressive.Invalid, "Historical migration/fixture drift"):
            progressive.static_migrations(self.root, base)

    def test_commands_map_unit_and_database_gates_and_full_archive(self):
        selected = [record("com.jeepclub.backend.identity.UserTest", "iam.identity"),
                    record("com.jeepclub.backend.identity.UserMySqlIT", "iam.identity", "mysql"),
                    record("com.jeepclub.backend.identity.UserPostgresIT", "iam.identity", "postgres")]
        cmds = progressive.commands(selected)
        self.assertEqual(3, len(cmds))
        self.assertIn("-Dtest=com.jeepclub.backend.identity.UserTest", cmds[0])
        self.assertIn("-Dit.test=com.jeepclub.backend.identity.UserMySqlIT", cmds[1])
        self.assertIn("-Dit.test=com.jeepclub.backend.identity.UserPostgresIT", cmds[2])
        full = progressive.commands([], full=True)
        self.assertEqual(4, len(full))
        self.assertIn("verify", full[0])
        self.assertEqual(["bash", "deploy/test-future-migration.sh"], full[-1])

    def test_reconcile_matches_nested_class_and_method_or_reports_missing_xml(self):
        report = self.root / "reports"
        report.mkdir()
        (report / "TEST-sample.xml").write_text("""<testsuite tests="2">
            <testcase classname="com.jeepclub.backend.identity.UserTest$Cases" name="works[1]"/>
            <testcase classname="com.jeepclub.backend.identity.UserTest" name="skips"><skipped/></testcase>
        </testsuite>""", encoding="utf-8")
        result = progressive.reconcile([
            dict(record("com.jeepclub.backend.identity.UserTest", "iam.identity"),
                 selector="com.jeepclub.backend.identity.UserTest#works"),
            record("com.jeepclub.backend.identity.MissingTest", "iam.identity")], report)
        self.assertEqual(2, len(result["cases"]))
        self.assertEqual(["com.jeepclub.backend.identity.MissingTest"], result["not_executed"])
        self.assertEqual({"passed": 1, "skipped": 1}, result["counts"])

    def test_run_records_subprocess_exit_and_fails_when_xml_is_missing(self):
        target = self.root / "target"
        out = self.root / "out"
        out.mkdir()
        (target / "surefire-reports").mkdir(parents=True)
        selected = [record("com.jeepclub.backend.identity.UserTest", "iam.identity")]
        plan = {"selected": selected, "commands": [["mvnw", "test"]]}
        with mock.patch.object(progressive.subprocess, "run", return_value=mock.Mock(returncode=3)):
            status = progressive.run(self.root, plan, out)
        self.assertEqual(3, status)
        result = __import__("json").loads((out / "result.json").read_text(encoding="utf-8"))
        self.assertEqual(3, result["runs"][0]["exit_code"])
        self.assertFalse(result["runs"][0]["passed"])
        self.assertEqual([selected[0]["name"]], result["runs"][0]["not_executed"])


if __name__ == "__main__":
    unittest.main()
