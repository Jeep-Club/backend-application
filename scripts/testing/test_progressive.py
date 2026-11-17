"""Regression tests for progressive Maven test selection."""
import hashlib
import json
import importlib.util
import io
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("progressive.py")
sys.path.insert(0, str(SCRIPT.parent))
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


def mysql_fixture(root):
    rel = 'src/test/java/com/jeepclub/backend/platform/database/MySqlMigrationIT.java'
    java(root, rel, 'package com.jeepclub.backend.platform.database; class MySqlMigrationIT { '
         + ' '.join('@Test void ' + name + '() {}' for name in progressive.SMOKE + ['upgrade'])
         + ' void helper() {} }')
    return dict(record(progressive.MYSQL, 'platform', 'mysql'), methods=progressive.SMOKE + ['upgrade', 'helper'], inherited=False, path=rel)


def git_call(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def init_test_repo(root, source=None):
    git_call(root, 'init', '-q')
    git_call(root, 'config', 'user.name', 'Runner Test')
    git_call(root, 'config', 'user.email', 'runner@example.invalid')
    java(root, 'src/test/java/com/jeepclub/backend/tools/ExistingTest.java', source or
         'package com.jeepclub.backend.tools; class ExistingTest { @Test void old() {} }')
    java(root, 'src/test/java/com/jeepclub/backend/platform/database/ProgressiveMySqlIT.java',
         'package com.jeepclub.backend.platform.database; class ProgressiveMySqlIT { @Test void mysql() {} }')
    java(root, 'pom.xml', '<project/>')
    git_call(root, 'add', '.')
    git_call(root, 'commit', '-qm', 'baseline')
    base = git_call(root, 'rev-parse', 'HEAD')
    git_call(root, 'update-ref', 'refs/remotes/origin/develop', base)
    return base


def inspect_main(testcase, root, *args):
    with mock.patch.object(progressive, 'ROOT', root), \
         mock.patch.object(progressive, 'validate_maven'), \
         mock.patch.object(progressive, 'run', side_effect=AssertionError('inspect must not execute Maven')), \
         redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return progressive.main([*args, '--inspect'])


class ProgressiveTests(unittest.TestCase):
    def test_partial_inspection_accepts_branch_at_develop_and_never_runs_maven(self):
        head = init_test_repo(self.root)
        self.assertEqual(head, git_call(self.root, 'rev-parse', 'HEAD'))
        self.assertEqual(head, progressive.trusted_base(self.root))
        self.assertEqual(0, inspect_main(self, self.root, '--band', 'F1', '--test', 'ExistingTest'))
        self.assertEqual(0, inspect_main(self, self.root, '--band', 'F2', '--module', 'tools'))
        self.assertEqual(0, inspect_main(self, self.root, '--band', 'F3', '--module', 'tools', '--impact-review', 'reviewed test fixture dependencies'))
        self.assertEqual(0, inspect_main(self, self.root, '--band', 'F4', '--module', 'platform'))

    def test_partial_inspection_uses_merge_base_when_develop_advances_in_parallel(self):
        base = init_test_repo(self.root)
        git_call(self.root, 'switch', '-c', 'feature')
        path = self.root / 'src/test/java/com/jeepclub/backend/tools/ExistingTest.java'
        path.write_text('package com.jeepclub.backend.tools; class ExistingTest { @Test void old() {} @Test void featureTest() {} }', encoding='utf-8')
        git_call(self.root, 'add', '.')
        git_call(self.root, 'commit', '-qm', 'feature test')
        git_call(self.root, 'switch', '-c', 'develop', base)
        java(self.root, 'develop-only.txt', 'develop advanced\n')
        git_call(self.root, 'add', '.')
        git_call(self.root, 'commit', '-qm', 'advance develop')
        git_call(self.root, 'update-ref', 'refs/remotes/origin/develop', 'HEAD')
        git_call(self.root, 'switch', 'feature')
        self.assertEqual(base, progressive.trusted_base(self.root))
        self.assertEqual(0, inspect_main(self, self.root, '--band', 'F1', '--test', 'ExistingTest'))
        self.assertEqual(0, inspect_main(self, self.root, '--band', 'F2', '--module', 'tools'))
        records, _ = progressive.inventory(self.root)
        selected = progressive.select(records, 'F2', modules=['tools'])
        self.assertIn('featureTest', [m['method'] for m in progressive.new_test_methods(self.root, base, selected)])

    def test_partial_inspection_discovers_uncommitted_test_method_when_merge_base_is_head(self):
        base = init_test_repo(self.root)
        path = self.root / 'src/test/java/com/jeepclub/backend/tools/ExistingTest.java'
        path.write_text('package com.jeepclub.backend.tools; class ExistingTest { @Test void old() {} @Test void localAdded() {} }', encoding='utf-8')
        self.assertEqual(base, progressive.trusted_base(self.root))
        records, _ = progressive.inventory(self.root)
        selected = progressive.select(records, 'F1', focus=['com.jeepclub.backend.tools.ExistingTest'])
        methods = progressive.new_test_methods(self.root, base, selected)
        self.assertEqual(['localAdded'], [m['method'] for m in methods])
        self.assertEqual(0, inspect_main(self, self.root, '--band', 'F1', '--test', 'ExistingTest'))

    def test_partial_reference_errors_fail_closed_and_explicit_base_is_not_replaced(self):
        init_test_repo(self.root)
        git_call(self.root, 'update-ref', '-d', 'refs/remotes/origin/develop')
        with self.assertRaisesRegex(progressive.Invalid, 'common Git base'):
            progressive.trusted_base(self.root)
        with self.assertRaisesRegex(progressive.Invalid, 'missing or does not resolve'):
            progressive.trusted_base(self.root, 'refs/heads/missing-explicit-base')
        with self.assertRaisesRegex(progressive.Invalid, 'missing or does not resolve'):
            progressive.trusted_base(self.root, '')

    def test_full_bands_keep_strict_baseline_policy(self):
        base = init_test_repo(self.root)
        with self.assertRaisesRegex(progressive.Invalid, 'resolves to HEAD'):
            progressive.trusted_base(self.root, full=True)
        git_call(self.root, 'switch', '-c', 'feature')
        java(self.root, 'feature.txt', 'feature\n')
        git_call(self.root, 'add', '.')
        git_call(self.root, 'commit', '-qm', 'feature commit')
        git_call(self.root, 'switch', '-c', 'develop', base)
        java(self.root, 'develop.txt', 'parallel develop change\n')
        git_call(self.root, 'add', '.')
        git_call(self.root, 'commit', '-qm', 'parallel develop commit')
        git_call(self.root, 'update-ref', 'refs/remotes/origin/develop', 'HEAD')
        git_call(self.root, 'switch', 'feature')
        self.assertEqual(base, progressive.trusted_base(self.root, base, full=True))
        with self.assertRaisesRegex(progressive.Invalid, 'not a verifiable ancestor'):
            progressive.trusted_base(self.root, 'origin/develop', full=True)
        self.assertEqual('project_full', progressive.validation_scope('F5', None))
        self.assertEqual('migration_full', progressive.validation_scope(None, 'M4'))
        self.assertFalse(progressive.validation_scope('F5', None) == 'partial')
        self.assertTrue(progressive.validation_scope(None, 'M4') != 'project_full')

    def test_partial_migration_levels_use_shared_base_and_m3_keeps_risk_gate(self):
        git_call(self.root, 'init', '-q')
        git_call(self.root, 'config', 'user.name', 'Runner Test')
        git_call(self.root, 'config', 'user.email', 'runner@example.invalid')
        fixture_dir = self.root / 'src/test/resources/database/historical'
        fixture_dir.mkdir(parents=True)
        fixture_hashes = []
        for name in ('v1.sql', 'v2.sql', 'v3.sql'):
            content = f'-- immutable {name}\n'.encode()
            (fixture_dir / name).write_bytes(content)
            fixture_hashes.append(f'{hashlib.sha256(content).hexdigest()}  {name}')
        (fixture_dir / 'SHA256SUMS').write_text('\n'.join(fixture_hashes) + '\n', encoding='utf-8')
        migration_dir = self.root / 'src/main/resources/db/migration'
        migration_dir.mkdir(parents=True)
        (migration_dir / 'V1__initial.sql').write_text('select 1;\n', encoding='utf-8')
        mysql_fixture(self.root)
        java(self.root, 'pom.xml', '<project/>')
        git_call(self.root, 'add', '.')
        git_call(self.root, 'commit', '-qm', 'migration baseline')
        base = git_call(self.root, 'rev-parse', 'HEAD')
        git_call(self.root, 'update-ref', 'refs/remotes/origin/develop', base)
        added_migration = migration_dir / 'V2__add_column.sql'
        added_migration.write_text('alter table records add column note varchar(20);\n', encoding='utf-8')
        self.assertEqual(base, progressive.trusted_base(self.root))
        static = progressive.static_migrations(self.root, base)
        self.assertEqual(['src/main/resources/db/migration/V2__add_column.sql'], [m['path'].replace('\\', '/') for m in static['new']])
        self.assertEqual(0, inspect_main(self, self.root, '--migration', 'M1'))
        self.assertEqual(0, inspect_main(self, self.root, '--migration', 'M2'))
        self.assertEqual(2, inspect_main(self, self.root, '--migration', 'M3'))  # New migration still needs risk evidence.
        risk_file = self.root / 'risk.json'
        risk_file.write_text(json.dumps({static['new'][0]['sha256']: {
            'reason': 'upgrade legacy rows and preserve constraints',
            'tests': [progressive.MYSQL + '#upgrade']}}), encoding='utf-8')
        self.assertEqual(0, inspect_main(self, self.root, '--migration', 'M3', '--risk-file', str(risk_file)))

    def test_full_gate_preserves_old_compiled_classes_before_execution(self):
        target = self.root / 'target/test-classes'
        target.mkdir(parents=True)
        (target / 'old.class').write_bytes(b'old')
        out = self.root / 'out'
        out.mkdir()
        row = record('com.jeepclub.backend.tools.ExistingTest', 'tools')
        plan = {'sha': 'head', 'band': 'F5', 'selected': [row], 'commands': [['mvnw', 'verify']]}
        def maven(cmd, cwd, stdout, stderr):
            self.assertFalse((Path(cwd) / 'target/test-classes/old.class').exists())
            report = Path(cwd) / 'target/surefire-reports'
            report.mkdir(parents=True)
            (report / 'TEST-existing.xml').write_text('<testsuite><testcase classname="' + row['name'] + '" name="works"/></testsuite>')
            return mock.Mock(returncode=0)
        with mock.patch.object(progressive, 'git', side_effect=lambda root, *args: 'head' if args[0] == 'rev-parse' else ''), \
             mock.patch.object(progressive.subprocess, 'run', side_effect=maven):
            self.assertEqual(0, progressive.run(self.root, plan, out))
        self.assertEqual(b'old', (out / 'previous-build/test-classes/old.class').read_bytes())

    def test_unicode_test_filename_is_not_omitted_by_git_quoting(self):
        subprocess.run(['git', '-C', str(self.root), 'init', '-q'], check=True)
        path = java(self.root, 'src/test/java/com/jeepclub/backend/tools/CaféTest.java', 'package com.jeepclub.backend.tools; class CaféTest { @Test void old() {} }')
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'base'], check=True)
        base = progressive.git(self.root, 'rev-parse', 'HEAD')
        path.write_text('package com.jeepclub.backend.tools; class CaféTest { @Test void old() {} @Test void added() {} }', encoding='utf-8')
        records, _ = progressive.inventory(self.root)
        methods = progressive.new_test_methods(self.root, base, progressive.select(records, 'F2', modules=['tools']))
        self.assertEqual(['added'], [m['method'] for m in methods])

    def test_focus_cannot_hide_new_method_of_same_class(self):
        row = record('com.jeepclub.backend.tools.ExistingTest', 'tools')
        row['selector'] += '#old1'
        required = [dict(classname=row['name'], method='added', annotation='Test', source='ExistingTest.java')]
        out = self.root / 'out'
        out.mkdir()
        def maven(cmd, cwd, stdout, stderr):
            reports = Path(cwd) / 'target/surefire-reports'
            reports.mkdir(parents=True)
            (reports / 'TEST-existing.xml').write_text('<testsuite><testcase classname="' + row['name'] + '" name="old1"/></testsuite>')
            return mock.Mock(returncode=0)
        plan = {'selected': [row], 'commands': [['mvnw', 'test']], 'new_test_methods': required}
        with mock.patch.object(progressive.subprocess, 'run', side_effect=maven):
            self.assertEqual(1, progressive.run(self.root, plan, out))
        result = json.loads((out / 'result.json').read_text())
        self.assertEqual('not_selected', result['runs'][0]['new_methods'][0]['status'])

    def test_missing_new_method_fails_runner_despite_old_cases(self):
        row = record('com.jeepclub.backend.tools.ExistingTest', 'tools')
        required = [dict(classname=row['name'], method='added', annotation='Test', source='ExistingTest.java')]
        out = self.root / 'out'
        out.mkdir()
        def maven(cmd, cwd, stdout, stderr):
            reports = Path(cwd) / 'target/surefire-reports'
            reports.mkdir(parents=True)
            (reports / 'TEST-existing.xml').write_text('<testsuite><testcase classname="' + row['name'] + '" name="old1"/><testcase classname="' + row['name'] + '" name="old2"/></testsuite>')
            return mock.Mock(returncode=0)
        plan = {'selected': [row], 'commands': [['mvnw', 'test']], 'new_test_methods': required}
        with mock.patch.object(progressive.subprocess, 'run', side_effect=maven):
            self.assertEqual(1, progressive.run(self.root, plan, out))
        result = json.loads((out / 'result.json').read_text())
        self.assertEqual('not_discovered', result['runs'][0]['new_methods'][0]['status'])

    def test_nested_method_moved_to_another_group_fails_closed(self):
        subprocess.run(['git', '-C', str(self.root), 'init', '-q'], check=True)
        path = java(self.root, 'src/test/java/com/jeepclub/backend/tools/GroupsTest.java', 'package com.jeepclub.backend.tools; class GroupsTest { @Nested class A { @Test void same() {} } @Nested class B {} }')
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'base'], check=True)
        base = progressive.git(self.root, 'rev-parse', 'HEAD')
        path.write_text('package com.jeepclub.backend.tools; class GroupsTest { @Nested class A {} @Nested class B { @Test void same() {} } }')
        records, _ = progressive.inventory(self.root)
        with self.assertRaisesRegex(progressive.Invalid, 'Ambiguous new test method'):
            progressive.new_test_methods(self.root, base, progressive.select(records, 'F2', modules=['tools']))

    def test_repeated_dynamic_and_template_methods_are_classified(self):
        methods = progressive.executable_methods('class Example { @RepeatedTest(2) void repeated() {} @TestFactory Object generated() {} @TestTemplate void template() {} @BeforeEach void setup() {} void helper() {} }')
        self.assertEqual({('repeated', 'RepeatedTest'), ('generated', 'TestFactory'), ('template', 'TestTemplate')}, set(methods))
        required = [dict(classname='com.jeepclub.backend.ExampleTest', method=n, annotation=a, source='ExampleTest.java') for n, a in methods]
        cases = [dict(classname='com.jeepclub.backend.ExampleTest', name='repeated()[1]', status='passed')]
        states = {r['method']: r['status'] for r in progressive.reconcile_new_methods(required, cases)}
        self.assertEqual({'repeated': 'executed', 'generated': 'unproven', 'template': 'unproven'}, states)
        ambiguous = dict(required[0], dynamic_ambiguity=True)
        self.assertEqual('unproven', progressive.reconcile_new_methods([ambiguous], cases)[0]['status'])

    def test_full_scope_rejects_base_newer_than_develop(self):
        init_test_repo(self.root)
        with self.assertRaisesRegex(progressive.Invalid, 'resolves to HEAD'):
            progressive.trusted_base(self.root, full=True)

    def test_ci_migration_evidence_requires_matching_sha_and_fresh_reports(self):
        spec = importlib.util.spec_from_file_location('audit_migrations', SCRIPT.with_name('audit_migrations.py'))
        audit = importlib.util.module_from_spec(spec)
        with mock.patch.dict(sys.modules, {'progressive': progressive}):
            spec.loader.exec_module(audit)
        reports = self.root / 'reports'
        reports.mkdir()
        stamp, output = self.root / 'stamp.json', self.root / 'out.json'
        row = mysql_fixture(self.root)
        risk = self.root / 'risk.json'
        risk.write_text(json.dumps({'digest': {'reason': 'legacy data and constraints', 'tests': ['MySqlMigrationIT#upgrade']}}))
        info = {'new': [{'path': 'V99__test.sql', 'sha256': 'digest'}]}
        def fake_git(root, *args):
            return '' if args[0] == 'status' else ('base' if args[0] == 'merge-base' else 'head')
        env = {'GITHUB_RUN_ID': '123', 'GITHUB_JOB': 'mysql', 'GITHUB_SHA': 'head'}
        common = ['audit', '--stamp', str(stamp)]
        with mock.patch.object(audit, 'ROOT', self.root), mock.patch.object(audit, 'git', side_effect=fake_git), \
             mock.patch.object(audit, 'comparison', return_value={'sha': 'base', 'source': 'test event'}), \
             mock.patch.object(audit, 'inventory', return_value=([row], [])), \
             mock.patch.object(audit, 'static_migrations', return_value=info), mock.patch.dict(os.environ, env):
            with mock.patch.object(sys, 'argv', common + ['--start']), mock.patch.object(audit.time, 'time_ns', return_value=1000000000):
                audit.main()
            xml = reports / 'TEST-mysql.xml'
            xml.write_text('<testsuite><testcase classname="' + progressive.MYSQL + '" name="upgrade"/></testsuite>')
            argv = common + ['--reports', str(reports), '--out', str(output), '--risk-file', str(risk)]
            with mock.patch.object(sys, 'argv', argv):
                audit.main()
                other = dict(record('com.jeepclub.backend.tools.OtherMySqlIT', 'tools', 'mysql'), methods=['other'], inherited=False)
                with mock.patch.object(audit, 'inventory', return_value=([row, other], [])), self.assertRaisesRegex(ValueError, 'complete successful profile'):
                    audit.main()
                for changed_identity in ({'GITHUB_SHA': 'other'}, {'GITHUB_RUN_ID': '124'}, {'GITHUB_JOB': 'other'}):
                    with self.subTest(identity=changed_identity), mock.patch.dict(os.environ, changed_identity), \
                         self.assertRaises(ValueError):
                        audit.main()
                os.utime(xml, ns=(1, 1))
                with self.assertRaisesRegex(ValueError, 'older'):
                    audit.main()

    def test_inherited_new_method_is_checked_on_subclass(self):
        subprocess.run(['git', '-C', str(self.root), 'init', '-q'], check=True)
        parent = java(self.root, 'src/test/java/com/jeepclub/backend/tools/Base.java', 'package com.jeepclub.backend.tools; abstract class Base { @Test void old() {} }')
        java(self.root, 'src/test/java/com/jeepclub/backend/tools/ChildTest.java', 'package com.jeepclub.backend.tools; class ChildTest extends Base {}')
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'base'], check=True)
        base = progressive.git(self.root, 'rev-parse', 'HEAD')
        parent.write_text('package com.jeepclub.backend.tools; abstract class Base { @Test void old() {} @Test void added() {} }')
        records, _ = progressive.inventory(self.root)
        selected = progressive.select(records, 'F2', modules=['tools'])
        methods = progressive.new_test_methods(self.root, base, selected)
        self.assertEqual([('com.jeepclub.backend.tools.ChildTest', 'added')], [(m['classname'], m['method']) for m in methods])
        self.assertEqual('not_discovered', progressive.reconcile_new_methods(methods, [])[0]['status'])

    def test_new_default_interface_method_is_checked_and_hierarchy_changes_block(self):
        subprocess.run(['git', '-C', str(self.root), 'init', '-q'], check=True)
        contract = java(self.root, 'src/test/java/com/jeepclub/backend/tools/Contract.java', 'package com.jeepclub.backend.tools; interface Contract { @Test default void old() {} }')
        child = java(self.root, 'src/test/java/com/jeepclub/backend/tools/ChildTest.java', 'package com.jeepclub.backend.tools; class ChildTest implements Contract {}')
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'base'], check=True)
        base = progressive.git(self.root, 'rev-parse', 'HEAD')
        contract.write_text('package com.jeepclub.backend.tools; interface Contract { @Test default void old() {} @Test default void added() {} }')
        records, _ = progressive.inventory(self.root)
        self.assertIn('added', records[0]['methods'])
        selected = progressive.select(records, 'F2', modules=['tools'])
        methods = progressive.new_test_methods(self.root, base, selected)
        self.assertEqual([('com.jeepclub.backend.tools.ChildTest', 'added')], [(m['classname'], m['method']) for m in methods])
        child.write_text('package com.jeepclub.backend.tools; class ChildTest {}')
        with self.assertRaisesRegex(progressive.Invalid, 'Changed test inheritance'):
            progressive.new_test_methods(self.root, base, selected)

    def test_new_migration_requires_hash_review_and_executed_mysql_regression(self):
        mysql = mysql_fixture(self.root)
        info = {'new': [{'path': 'V99__new.sql', 'sha256': 'correct'}]}
        risk_file = self.root / 'risk.json'
        self.assertEqual(({}, []), progressive.migration_risk_selectors({'new': []}, [mysql], risk_file, self.root))
        for payload in ({}, {'wrong': {'reason': 'review', 'tests': ['MySqlMigrationIT#upgrade']}},
                        {'correct': {'reason': 'review', 'tests': ['MySqlMigrationIT']}},
                        {'correct': {'reason': 'review', 'tests': ['MySqlMigrationIT#helper']}},
                        {'correct': {'reason': 'review', 'tests': ['MySqlMigrationIT#' + progressive.SMOKE[0]]}},
                        {'correct': {'reason': 'review', 'tests': ['MySqlMigrationIT#missing']}}):
            risk_file.write_text(json.dumps(payload))
            with self.assertRaises(progressive.Invalid):
                progressive.migration_risk_selectors(info, [mysql], risk_file, self.root)
        risk_file.write_text(json.dumps({'correct': {'reason': 'legacy data and constraints reviewed', 'tests': ['MySqlMigrationIT#upgrade']}}))
        _, required = progressive.migration_risk_selectors(info, [mysql], risk_file, self.root)
        reports = self.root / 'reports'
        reports.mkdir()
        with self.assertRaisesRegex(progressive.Invalid, 'lacks successful'):
            progressive.verify_migration_risks(required, reports)
        (reports / 'TEST-mysql.xml').write_text('<testsuite><testcase classname="' + progressive.MYSQL + '" name="upgrade"><skipped/></testcase></testsuite>')
        with self.assertRaisesRegex(progressive.Invalid, 'lacks successful'):
            progressive.verify_migration_risks(required, reports)
        (reports / 'TEST-mysql.xml').write_text('<testsuite><testcase classname="' + progressive.MYSQL + '" name="upgrade"/></testsuite>')
        self.assertEqual({'passed': 1}, progressive.verify_migration_risks(required, reports)['counts'])
        mysql_source = self.root / mysql['path']
        mysql_source.write_text(mysql_source.read_text().replace('void helper()', '@TestFactory Object generated() { return null; } void helper()'))
        with self.assertRaisesRegex(progressive.Invalid, 'Dynamic risk test attribution'):
            progressive.migration_risk_selectors(info, [mysql], risk_file, self.root)
        postgres = dict(record('com.jeepclub.backend.tools.UpgradePostgresIT', 'tools', 'postgres'), methods=['upgrade'], inherited=False)
        risk_file.write_text(json.dumps({'correct': {'reason': 'review', 'tests': ['UpgradePostgresIT#upgrade']}}))
        with self.assertRaisesRegex(progressive.Invalid, 'MySQL'):
            progressive.migration_risk_selectors(info, [mysql, postgres], risk_file, self.root)

    def test_incremental_methods_require_actual_cases(self):
        subprocess.run(['git', '-C', str(self.root), 'init', '-q'], check=True)
        path = java(self.root, 'src/test/java/com/jeepclub/backend/tools/ExistingTest.java',
                    'package com.jeepclub.backend.tools; class ExistingTest { @Test void old1() {} @Test void old2() {} }')
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'base'], check=True)
        base = progressive.git(self.root, 'rev-parse', 'HEAD')
        path.write_text('package com.jeepclub.backend.tools; class ExistingTest { @Test void old1() {} @Test void old2() {} @Test void added() {} @ParameterizedTest void params(int x) {} @Nested class Group { @Test void nested() {} } @Disabled @Test void disabled() {} void helper() {} }')
        records, _ = progressive.inventory(self.root)
        selected = progressive.select(records, 'F2', modules=['tools'])
        methods = progressive.new_test_methods(self.root, base, selected)
        self.assertEqual({'added', 'params', 'nested', 'disabled'}, {m['method'] for m in methods})
        report = self.root / 'reports'
        report.mkdir()
        name = records[0]['name']
        xml = report / 'TEST-existing.xml'
        xml.write_text('<testsuite name="' + name + '"><testcase classname="' + name + '" name="old1"/><testcase classname="' + name + '" name="old2"/><testcase classname="' + name + '" name="params(int)[1]"/><testcase classname="Nested display" name="disabled"><skipped/></testcase></testsuite>')
        stats = progressive.reconcile(selected, report)
        states = {m['method']: m['status'] for m in progressive.reconcile_new_methods(methods, stats['cases'])}
        self.assertEqual({'added': 'not_discovered', 'params': 'executed', 'nested': 'not_discovered', 'disabled': 'skipped'}, states)
        xml.write_text('<testsuite name="' + name + '"><testcase classname="' + name + '" name="added"/><testcase classname="' + name + '" name="params(int)[1]"/><testcase classname="Nested display" name="nested"/><testcase classname="' + name + '" name="disabled"/></testsuite>')
        states = {m['method']: m['status'] for m in progressive.reconcile_new_methods(methods, progressive.reconcile(selected, report)['cases'])}
        self.assertTrue(all(s == 'executed' for s in states.values()))
        self.assertEqual('unproven', progressive.reconcile_new_methods([dict(methods[0], annotation='TestFactory')], []) [0]['status'])

    def test_migration_full_is_not_project_full(self):
        self.assertEqual('migration_full', progressive.validation_scope(None, 'M4'))
        self.assertEqual('project_full', progressive.validation_scope('F5', None))
        for band in ('F1', 'F2', 'F3', 'F4'):
            self.assertEqual('partial', progressive.validation_scope(band, None))
        for level in ('M1', 'M2', 'M3'):
            self.assertEqual('partial', progressive.validation_scope(None, level))

    def test_whole_class_does_not_hide_a_requested_nonexecuted_method(self):
        row = dict(record('com.jeepclub.backend.tools.UpgradeTest', 'tools'), methods=['helper'], inherited=False)
        selected = progressive.select([row], 'F1', focus=[row['name'], row['name'] + '#helper'])
        self.assertEqual(2, len(selected))
        commands = progressive.commands(selected)
        self.assertEqual(1, len(commands))  # Verify the method in the whole-class XML, without rerunning it.
        out = self.root / 'out'
        out.mkdir()
        def maven(cmd, cwd, stdout, stderr):
            reports = Path(cwd) / 'target/surefire-reports'
            reports.mkdir(parents=True)
            (reports / 'TEST-upgrade.xml').write_text('<testsuite><testcase classname="' + row['name'] + '" name="actualTest"/></testsuite>')
            return mock.Mock(returncode=0)
        with mock.patch.object(progressive.subprocess, 'run', side_effect=maven):
            self.assertEqual(1, progressive.run(self.root, {'selected': selected, 'commands': commands}, out))
        result = json.loads((out / 'result.json').read_text())
        self.assertEqual([row['name'] + '#helper'], result['runs'][0]['not_executed'])

    def test_complete_class_and_method_are_separate_commands_with_per_gate_reconciliation(self):
        full = record('com.jeepclub.backend.tools.NestedTest', 'tools')
        focal = record('com.jeepclub.backend.tools.FocusTest', 'tools')
        focal['selector'] += '#chosen'
        commands = progressive.commands([full, focal])
        self.assertEqual(2, len(commands))
        out = self.root / 'out'
        out.mkdir()
        def maven(cmd, cwd, stdout, stderr):
            selected = full if full['name'] in next(a for a in cmd if a.startswith('-Dtest=')) else focal
            report = Path(cwd) / 'target/surefire-reports'
            report.mkdir(parents=True)
            (report / 'TEST-case.xml').write_text('<testsuite name="' + selected['name'] + '"><testcase classname="' + selected['name'] + '" name="chosen"/></testsuite>')
            return mock.Mock(returncode=0)
        with mock.patch.object(progressive.subprocess, 'run', side_effect=maven):
            self.assertEqual(0, progressive.run(self.root, {'selected': [full, focal], 'commands': commands}, out))
        result = json.loads((out / 'result.json').read_text())
        self.assertEqual([], result['not_run'])
        self.assertEqual([full['selector']], result['runs'][0]['selected_selectors'])
        self.assertEqual([focal['selector']], result['runs'][1]['selected_selectors'])

    def test_nested_display_name_uses_suite_owner_without_hiding_foreign_class(self):
        report = self.root / 'reports'
        report.mkdir()
        row = record('com.jeepclub.backend.tools.NestedTest', 'tools')
        (report / 'TEST-nested.xml').write_text('<testsuite name="' + row['name'] + '"><testcase classname="Creation group" name="works"/><testcase classname="com.jeepclub.backend.tools.OtherTest" name="foreign"/></testsuite>')
        stats = progressive.reconcile([row], report)
        self.assertEqual([], stats['not_executed'])
        self.assertEqual('Creation group', stats['cases'][0]['reported_classname'])
        self.assertEqual(row['name'], stats['cases'][0]['classname'])
        self.assertEqual(['com.jeepclub.backend.tools.OtherTest'], stats['unexpected_classes'])

    def test_sql_probe_audit_preserves_result_with_non_utf8_windows_log(self):
        probe_spec = importlib.util.spec_from_file_location('probe_migrations', SCRIPT.with_name('probe_migrations.py'))
        probe = importlib.util.module_from_spec(probe_spec)
        probe_spec.loader.exec_module(probe)
        run = self.root / 'invalid/run'
        (run / 'gate-1').mkdir(parents=True)
        (run / 'gate-1/execution.log').write_bytes(b'\xbb V99__isolated_runner_probe.sql FlywayMigrateException')
        (run / 'result.json').write_text(json.dumps({'plan': {'migrations': {'new': [{'path': 'db/V99__isolated_runner_probe.sql'}]}},
                                                    'runs': [{'exit_code': 1, 'passed': False, 'counts': {'error': 1}}]}))
        (self.root / 'invalid-risk-block.log').write_text('Uncovered new migration')
        result = probe.audit_probe(self.root, 'invalid', '0' * 40)
        self.assertEqual(1, result['exit_code'])

    def test_method_filter_broadening_is_detected(self):
        report = self.root / 'reports'
        report.mkdir()
        row = record('com.jeepclub.backend.tools.FocusTest', 'tools')
        row['selector'] += '#chosen'
        (report / 'TEST-focus.xml').write_text('<testsuite><testcase classname="' + row['name'] + '" name="chosen"/><testcase classname="' + row['name'] + '" name="unrequested"/></testsuite>')
        stats = progressive.reconcile([row], report)
        self.assertEqual([], stats['not_executed'])
        self.assertEqual([row['name'] + '#unrequested'], stats['unexpected_cases'])

    def test_wholly_skipped_unit_selector_is_not_successful_execution(self):
        report = self.root / 'reports'
        report.mkdir()
        row = record('com.jeepclub.backend.tools.DisabledTest', 'tools')
        (report / 'TEST-disabled.xml').write_text('<testsuite><testcase classname="' + row['name'] + '" name="disabled"><skipped/></testcase></testsuite>')
        stats = progressive.reconcile([row], report)
        self.assertEqual([row['name']], stats['all_skipped_selectors'])
        self.assertEqual([], stats['not_executed'])

    def test_m3_new_migration_requires_review_and_rejects_smoke_only(self):
        subprocess.run(['git', '-C', str(self.root), 'init', '-q'], check=True)
        subprocess.run(['git', '-C', str(self.root), 'config', 'user.name', 'Test'], check=True)
        subprocess.run(['git', '-C', str(self.root), 'config', 'user.email', 'test@example.invalid'], check=True)
        (self.root / 'base.txt').write_text('trusted baseline\n')
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), 'commit', '-qm', 'baseline'], check=True)
        base = subprocess.check_output(['git', '-C', str(self.root), 'rev-parse', 'HEAD'], text=True).strip()
        subprocess.run(['git', '-C', str(self.root), 'update-ref', 'refs/remotes/origin/develop', base], check=True)
        row = mysql_fixture(self.root)
        rel = 'src/test/java/com/jeepclub/backend/platform/NewUpgradeMySqlIT.java'
        java(self.root, rel, 'package com.jeepclub.backend.platform; class NewUpgradeMySqlIT { @Test void upgrade() {} }')
        upgrade = dict(record('com.jeepclub.backend.platform.NewUpgradeMySqlIT', 'platform', 'mysql'), methods=['upgrade'], inherited=False, path=rel)
        java(self.root, 'src/main/resources/db/migration/V99__new.sql', 'select 1;\n')
        subprocess.run(['git', '-C', str(self.root), 'add', '-A'], check=True)
        subprocess.run(['git', '-C', str(self.root), 'commit', '-qm', 'new migration candidate'], check=True)
        risk_file = self.root / 'risk.json'
        new = {'path': 'V99__new.sql', 'sha256': 'digest'}
        args = ['--migration', 'M3', '--risk-file', str(risk_file), '--base', base, '--inspect']
        head = subprocess.check_output(['git', '-C', str(self.root), 'rev-parse', 'HEAD'], text=True).strip()
        def fake_git(root, *parts):
            if parts[0] == 'status':
                return ''
            if parts[0] == 'branch':
                return 'feature/test'
            if parts[0] == 'rev-parse':
                ref = parts[-1].removesuffix('^{commit}')
                return base if ref == base else head
            return ''
        with mock.patch.object(progressive, 'ROOT', self.root), mock.patch.object(progressive, 'validate_maven'), \
             mock.patch.object(progressive, 'git', side_effect=fake_git), \
             mock.patch.object(progressive, 'inventory', return_value=([row, upgrade], [])), \
             mock.patch.object(progressive, 'new_test_methods', return_value=[]), \
             mock.patch.object(progressive, 'relations', return_value=[]), \
             mock.patch.object(progressive, 'static_migrations', return_value={'new': [new]}), \
             mock.patch.object(progressive.platform, 'platform', return_value='test-platform'):
            risk_file.write_text('{}')
            self.assertEqual(2, progressive.main(args))
            risk_file.write_text(json.dumps({'digest': {'reason': 'only smoke', 'tests': [progressive.MYSQL + '#' + progressive.SMOKE[0]]}}))
            self.assertEqual(2, progressive.main(args))
            risk_file.write_text(json.dumps({'digest': {'reason': 'previous-version upgrade and data/constraints preservation', 'tests': [upgrade['name'] + '#upgrade']}}))
            self.assertEqual(0, progressive.main(args))

    def test_partial_report_audit_defaults_to_shared_git_base_with_uncommitted_method(self):
        # HEAD == origin/develop is valid for a partial local audit.
        base = init_test_repo(self.root)
        java(self.root, 'src/test/java/com/jeepclub/backend/tools/ExistingTest.java',
             'package com.jeepclub.backend.tools; class ExistingTest { @Test void old() {} @Test void newLocal() {} }')
        rows, _ = progressive.inventory(self.root)
        row = next(r for r in rows if r['name'].endswith('.ExistingTest'))
        selection = self.root / 'selection.json'
        selection.write_text(json.dumps({'sha': base, 'selected': [dict(row, selector=row['name'])]}), encoding='utf-8')
        reports = self.root / 'reports'
        reports.mkdir()
        (reports / 'TEST-existing.xml').write_text(
            '<testsuite><testcase classname="' + row['name'] + '" name="old"/>'
            '<testcase classname="' + row['name'] + '" name="newLocal"/></testsuite>', encoding='utf-8')
        output = self.root / 'audit.json'
        spec = importlib.util.spec_from_file_location('audit_reports', SCRIPT.with_name('audit_reports.py'))
        audit = importlib.util.module_from_spec(spec)
        with mock.patch.dict(sys.modules, {'progressive': progressive}):
            spec.loader.exec_module(audit)
        argv = ['audit_reports.py', '--kind', 'unit', '--reports', str(reports),
                '--out', str(output), '--selection', str(selection)]
        with mock.patch.dict(os.environ, {k: os.environ[k] for k in ('PATH', 'SYSTEMROOT') if k in os.environ}, clear=True), mock.patch.object(sys, 'argv', argv), \
             mock.patch.object(audit, 'ROOT', self.root), mock.patch.object(audit, 'validate_maven'), \
             redirect_stdout(io.StringIO()):
            self.assertEqual(0, audit.main())
        audited = json.loads(output.read_text(encoding='utf-8'))
        self.assertEqual([('newLocal', 'executed')],
                         [(m['method'], m['status']) for m in audited['new_methods']])
        self.assertTrue(audited['partial'])

    def test_audit_gate_accepts_complete_xml_and_rejects_missing_candidate(self):
        audit_spec = importlib.util.spec_from_file_location('audit_reports', SCRIPT.with_name('audit_reports.py'))
        audit = importlib.util.module_from_spec(audit_spec)
        with mock.patch.dict(sys.modules, {'progressive': progressive}):
            audit_spec.loader.exec_module(audit)
        reports = self.root / 'reports'
        reports.mkdir()
        row = record('com.jeepclub.backend.tools.ToolTest', 'tools')
        (reports / 'TEST-tool.xml').write_text('<testsuite><testcase classname="' + row['name'] + '" name="works"/></testsuite>')
        argv = ['audit_reports.py', '--kind', 'unit', '--reports', str(reports), '--out', str(self.root / 'audit.json')]
        with mock.patch.object(sys, 'argv', argv), mock.patch.dict(os.environ, {}, clear=True), \
             mock.patch.object(audit, 'validate_maven'), \
             mock.patch.object(audit, 'trusted_base', return_value='base'), \
             mock.patch.object(audit, 'new_test_methods', return_value=[]), \
             mock.patch.object(audit, 'inventory', return_value=([row], [])):
            self.assertEqual(0, audit.main())
            (reports / 'TEST-tool.xml').unlink()
            self.assertEqual(1, audit.main())

    def test_large_selection_uses_native_surefire_file_without_losing_entries(self):
        names = ['com.jeepclub.backend.newmodule.VeryLongName' + str(i) + 'Test' for i in range(200)]
        cmds = progressive.commands([record(n, 'newmodule') for n in names])
        actual = progressive.materialize_commands(cmds, self.root)
        prop = next(a for a in actual[0] if a.startswith('-Dsurefire.includesFile='))
        includes = Path(prop.split('=', 1)[1]).read_text(encoding='utf-8').splitlines()
        self.assertEqual(sorted(names), includes)
        self.assertLess(len(subprocess.list2cmdline(actual[0])), 7800)

    def test_unknown_dependency_with_no_tests_fails_closed(self):
        rows = [record('com.jeepclub.backend.tools.OwnerTest', 'tools')]
        with self.assertRaisesRegex(progressive.Invalid, 'Unclassified dependency'):
            progressive.select(rows, 'F3', modules=['tools'], edges=[
                dict(consumer='untested', provider='tools', source='new.java')])

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
        self.assertIn("inherited", child["methods"])
        self.assertEqual("com.jeepclub.backend.identity.ChildMySqlIT#inherited",
                         progressive.select(records, "F1", focus=["ChildMySqlIT#inherited"])[0]["selector"])
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

    def test_a_new_eligible_test_is_added_to_existing_module_selection(self):
        existing = record("com.jeepclub.backend.identity.UserTest", "iam.identity")
        added = record("com.jeepclub.backend.identity.UserProfileTest", "iam.identity")
        before = progressive.select([existing], "F2", modules=["iam.identity"])
        after = progressive.select([existing, added], "F2", modules=["iam.identity"])
        self.assertEqual([existing["name"]], [r["name"] for r in before])
        self.assertEqual({existing["name"], added["name"]}, {r["name"] for r in after})

    def test_f5_requires_both_unit_and_mysql_inventories(self):
        unit = record("com.jeepclub.backend.identity.UserTest", "iam.identity")
        mysql = record("com.jeepclub.backend.identity.UserMySqlIT", "iam.identity", "mysql")
        for rows in ([], [unit], [mysql]):
            with self.subTest(rows=rows), self.assertRaisesRegex(progressive.Invalid, "nonempty Surefire and MySQL"):
                progressive.select(rows, "F5")
        self.assertEqual(2, len(progressive.select([unit, mysql], "F5")))

    def test_b_new_mysql_candidate_routes_to_its_profile(self):
        rows = [record("com.jeepclub.backend.identity.NewMySqlIT", "iam.identity", "mysql")]
        cmds = progressive.commands(rows)
        self.assertIn("-Dit.test=com.jeepclub.backend.identity.NewMySqlIT", cmds[0])

    def test_postgres_test_is_rejected_as_unsupported(self):
        java(self.root, "src/test/java/com/jeepclub/backend/identity/NewPostgresIT.java",
             "package com.jeepclub.backend.identity; class NewPostgresIT { @Test void test() {} }")
        with self.assertRaisesRegex(progressive.Invalid, "Unsupported executable test naming"):
            progressive.inventory(self.root)

    def test_c_new_module_is_known_and_selectable_from_inventory(self):
        java(self.root, "src/test/java/com/jeepclub/backend/tools/ToolsTest.java", """
            package com.jeepclub.backend.tools; class ToolsTest { @Test void works() {} }
        """)
        rows, _ = progressive.inventory(self.root)
        selected = progressive.select(rows, "F2", modules=["tools"])
        self.assertEqual(["com.jeepclub.backend.tools.ToolsTest"], [r["name"] for r in selected])

    def test_d_new_import_expands_f3_before_to_after_selection(self):
        rows = [record("com.jeepclub.backend.identity.UserTest", "iam.identity"),
                record("com.jeepclub.backend.billing.BillingTest", "billing")]
        before = progressive.select(rows, "F3", modules=["iam.identity"])
        java(self.root, "src/main/java/com/jeepclub/backend/billing/api/module/Contract.java", """
            package com.jeepclub.backend.billing.api.module; public interface Contract {}
        """)
        java(self.root, "src/main/java/com/jeepclub/backend/identity/Consumer.java", """
            package com.jeepclub.backend.identity; import com.jeepclub.backend.billing.api.module.Contract;
            class Consumer { Contract contract; }
        """)
        edges = progressive.relations(self.root, {r["module"] for r in rows})
        after = progressive.select(rows, "F3", modules=["iam.identity"], edges=edges)
        self.assertEqual({"iam.identity"}, {r["module"] for r in before})
        self.assertEqual({"iam.identity", "billing"}, {r["module"] for r in after})

    def test_d_f3_rejects_unknown_manual_dependency(self):
        rows = [record("com.jeepclub.backend.identity.UserTest", "iam.identity")]
        with self.assertRaisesRegex(progressive.Invalid, "Unknown module: billing"):
            progressive.select(rows, "F3", modules=["iam.identity"], dependencies=["billing"])

    def test_e_inventory_rejects_unsupported_orphan_test_class(self):
        java(self.root, "src/test/java/com/jeepclub/backend/identity/LegacyCheck.java", """
            package com.jeepclub.backend.identity; class LegacyCheck { @Test void check() {} }
        """)
        with self.assertRaisesRegex(progressive.Invalid, "Unsupported executable test naming"):
            progressive.inventory(self.root)

    def test_e_f1_rejects_obsolete_class_selector(self):
        rows = [record("com.jeepclub.backend.identity.CurrentTest", "iam.identity")]
        with self.assertRaisesRegex(progressive.Invalid, "Unknown, ambiguous or unsupported selector"):
            progressive.select(rows, "F1", focus=["OldTest"])

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
          </profiles></project>"""
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
                    record("com.jeepclub.backend.identity.UserMySqlIT", "iam.identity", "mysql")]
        cmds = progressive.commands(selected)
        self.assertEqual(2, len(cmds))
        self.assertIn("-Dtest=com.jeepclub.backend.identity.UserTest", cmds[0])
        self.assertIn("-Dit.test=com.jeepclub.backend.identity.UserMySqlIT", cmds[1])
        full = progressive.commands([], full=True)
        self.assertEqual(3, len(full))
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

    def test_run_zero_exit_without_xml_is_failure(self):
        out = self.root / "out"
        out.mkdir()
        selected = [record("com.jeepclub.backend.identity.UserTest", "iam.identity")]
        with mock.patch.object(progressive.subprocess, "run", return_value=mock.Mock(returncode=0)):
            status = progressive.run(self.root, {"selected": selected, "commands": [["mvnw", "test"]]}, out)
        self.assertEqual(1, status)
        result = __import__("json").loads((out / "result.json").read_text(encoding="utf-8"))
        self.assertFalse(result["runs"][0]["passed"])
        self.assertEqual([selected[0]["name"]], result["runs"][0]["not_executed"])

    def test_run_success_reconciles_fresh_xml_and_archives_old_reports(self):
        target = self.root / "target"
        report = target / "surefire-reports"
        report.mkdir(parents=True)
        (report / "TEST-old.xml").write_text("""<testsuite><testcase classname="com.jeepclub.backend.identity.ObsoleteTest" name="old"/></testsuite>""")
        out = self.root / "out"
        out.mkdir()
        selected = [record("com.jeepclub.backend.identity.UserTest", "iam.identity")]

        def maven(cmd, cwd, stdout, stderr):
            fresh = Path(cwd) / "target/surefire-reports"
            fresh.mkdir(parents=True)
            (fresh / "TEST-user.xml").write_text("""<testsuite><testcase classname="com.jeepclub.backend.identity.UserTest" name="works"/></testsuite>""")
            return mock.Mock(returncode=0)

        with mock.patch.object(progressive.subprocess, "run", side_effect=maven):
            status = progressive.run(self.root, {"selected": selected, "commands": [["mvnw", "test"]]}, out)
        self.assertEqual(0, status)
        result = __import__("json").loads((out / "result.json").read_text(encoding="utf-8"))
        run = result["runs"][0]
        self.assertTrue(run["passed"])
        self.assertEqual([], run["not_executed"])
        self.assertEqual([], run["unexpected_classes"])
        archive = out / "gate-1/previous-surefire-reports/TEST-old.xml"
        self.assertTrue(archive.exists())
        self.assertFalse((out / "gate-1/reports/TEST-old.xml").exists())
        self.assertTrue((out / "gate-1/reports/TEST-user.xml").exists())

    def test_run_database_skip_is_failure_even_when_xml_case_is_present(self):
        target = self.root / "target/failsafe-reports"
        target.mkdir(parents=True)
        (target / "TEST-db.xml").write_text("""<testsuite><testcase classname="com.jeepclub.backend.identity.DbMySqlIT" name="skipped"><skipped/></testcase></testsuite>""")
        out = self.root / "out"
        out.mkdir()
        selected = [record("com.jeepclub.backend.identity.DbMySqlIT", "iam.identity", "mysql")]
        cmd = ["mvnw", "-Pmysql-migrations", "verify"]
        def maven(cmd, cwd, stdout, stderr):
            if cmd[0] == 'docker':
                return mock.Mock(returncode=0)
            fresh = Path(cwd) / "target/failsafe-reports"
            fresh.mkdir(parents=True)
            (fresh / "TEST-db.xml").write_text("""<testsuite><testcase classname="com.jeepclub.backend.identity.DbMySqlIT" name="skipped"><skipped/></testcase></testsuite>""")
            return mock.Mock(returncode=0)

        with mock.patch.object(progressive.subprocess, "run", side_effect=maven):
            status = progressive.run(self.root, {"selected": selected, "commands": [cmd]}, out)
        self.assertEqual(1, status)
        result = __import__("json").loads((out / "result.json").read_text(encoding="utf-8"))
        self.assertFalse(result["runs"][0]["passed"])
        self.assertEqual({"skipped": 1}, result["runs"][0]["counts"])

    def test_missing_docker_is_failure_without_invoking_maven(self):
        out = self.root / 'out'
        out.mkdir()
        selected = [record('com.jeepclub.backend.tools.DbMySqlIT', 'tools', 'mysql')]
        with mock.patch.object(progressive.subprocess, 'run', return_value=mock.Mock(returncode=1)) as process:
            status = progressive.run(self.root, {'selected': selected, 'commands': [
                ['mvnw', '-Pmysql-migrations', 'verify']]}, out)
        self.assertEqual(1, status)
        self.assertEqual(1, process.call_count)
        self.assertEqual('docker', process.call_args.args[0][0])


if __name__ == "__main__":
    unittest.main()
