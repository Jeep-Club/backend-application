"""Progressive Maven selection. Python 3.10+, standard library only."""
import argparse
from collections import Counter
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'com.jeepclub.backend.'
ANNOTATION = re.compile(r'@(?:[\w.]+\.)?(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b')
MYSQL = 'com.jeepclub.backend.platform.database.MySqlMigrationIT'
SMOKE = ['emptyDatabaseMatchesEveryJpaMappingAndIsIdempotent',
         'springBootMigratesBeforeHibernateValidateUsingApplicationConfiguration']


class Invalid(ValueError):
    pass


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def digest(data):
    return hashlib.sha256(data.replace(b'\r\n', b'\n')).hexdigest()


def source(text):
    # Strip comments and literals before inspecting declarations/imports/annotations.
    return re.sub(r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda m: '\n' * m[0].count('\n') + ' ', text, flags=re.S)


def module(name):
    parts = name.removeprefix(PREFIX).split('.')
    if parts[0][:1].isupper():
        return 'platform'  # Root application/composition tests.
    if parts[0] in ('identity', 'authentication', 'authorization'):
        return 'iam.' + parts[0]  # Legacy test packages still present in this checkout.
    return '.'.join(parts[:2]) if parts[0] == 'iam' and len(parts) > 1 else parts[0]


def inventory(root):
    records, helpers, errors = [], [], []
    paths = sorted((root / 'src/test/java').rglob('*.java'))
    for path in paths:
        raw = path.read_text(encoding='utf-8-sig')
        text = source(raw)
        pkg = re.search(r'\bpackage\s+([\w.]+)\s*;', text)
        decl = re.search(r'\b(?:(abstract)\s+)?class\s+' + re.escape(path.stem) + r'\b', text)
        has_test = bool(ANNOTATION.search(text))
        if not decl or decl[1]:
            helpers.append(str(path.relative_to(root)))
            continue
        name = (pkg[1] + '.' if pkg else '') + path.stem
        if not name.startswith(PREFIX):
            if has_test:
                errors.append(f'Unclassified test package: {name}')
            continue
        kind = ('mysql' if path.stem.endswith('MySqlIT') or path.stem == 'MySqlMigrationIT'
                else 'postgres' if path.stem.endswith('PostgresIT')
                else 'unit' if re.fullmatch(r'Test.*|.*Tests?|.*TestCase', path.stem) else None)
        if not kind:
            if has_test:
                errors.append(f'Unsupported executable test naming: {name}')
            helpers.append(str(path.relative_to(root)))
            continue
        # Eligible names remain candidates even with inherited/composed tests. JUnit XML
        # is the execution oracle; a candidate producing no cases fails reconciliation.
        records.append(dict(name=name, module=module(name), kind=kind,
                            path=str(path.relative_to(root)), disabled='@Disabled' in text,
                            declared_tests=has_test, inherited=bool(re.search(r'\bextends\b', text)),
                            methods=sorted(set(re.findall(r'\bvoid\s+(\w+)\s*\(', text)))))
    if errors:
        raise Invalid('\n'.join(errors))
    # Resolve inherited methods from source as well as directly declared methods.
    # Ambiguous/unavailable parent sources remain candidates for class execution,
    # but cannot justify a method selector.
    sources = {}
    for path in paths:
        sources.setdefault(path.stem, []).append(source(path.read_text(encoding='utf-8-sig')))
    def inherited_methods(text, seen):
        methods = set(re.findall(r'\b[\w<>?,.\[\]]+\s+(\w+)\s*\(', text))
        parent = re.search(r'\bextends\s+(\w+)', text)
        if parent and parent[1] not in seen and len(sources.get(parent[1], [])) == 1:
            methods |= inherited_methods(sources[parent[1]][0], seen | {parent[1]})
        return methods
    for record in records:
        record['methods'] = sorted(inherited_methods(source((root / record['path']).read_text(encoding='utf-8-sig')), set()))
    return records, helpers


def relations(root, modules):
    edges = []
    for folder in ('src/main/java', 'src/test/java'):
        for path in sorted((root / folder).rglob('*.java')):
            text = source(path.read_text(encoding='utf-8-sig'))
            pkg = re.search(r'\bpackage\s+([\w.]+)', text)
            if not pkg or not pkg[1].startswith(PREFIX):
                continue
            owner = module(pkg[1])
            for match in re.finditer(r'\bimport\s+(?:static\s+)?(com\.jeepclub\.backend\.[\w.*]+)', text):
                provider = module(match[1])
                if owner != provider and owner in modules and provider in modules:
                    edges.append(dict(consumer=owner, provider=provider, source=str(path.relative_to(root))))
    return edges


def select(records, band, modules=(), focus=(), dependencies=(), edges=()):
    known = {r['module'] for r in records}
    for value in [*modules, *dependencies]:
        if value not in known:
            raise Invalid(f'Unknown module: {value}; known: {sorted(known)}')
    names = {r['name']: r for r in records}
    if band == 'F1':
        if not focus:
            raise Invalid('F1 requires --test Class[#method]')
        resolved = []
        for item in focus:
            cls, _, method = item.partition('#')
            matches = [n for n in names if n == cls or n.rsplit('.', 1)[-1] == cls]
            if len(matches) != 1 or ('#' in item and not re.fullmatch(r'\w+', method)):
                raise Invalid(f'Unknown, ambiguous or unsupported selector: {item}')
            if method and method not in names[matches[0]].get('methods', []):
                raise Invalid(f'Unknown method selector: {item}')
            resolved.append(dict(names[matches[0]], selector=matches[0] + ('#' + method if method else '')))
        return resolved
    if focus:
        raise Invalid('--test is only supported with F1')
    if band == 'F5':
        if modules or dependencies:
            raise Invalid('F5 cannot be narrowed')
        return [dict(r, selector=r['name']) for r in records]
    if not modules:
        raise Invalid(f'{band} requires --module')
    chosen = set(modules) | set(dependencies)
    if band == 'F3':
        # Conservative undirected transitive closure includes providers and consumers.
        while True:
            expanded = chosen | {e[k] for e in edges for k in ('consumer', 'provider')
                                 if e['consumer'] in chosen or e['provider'] in chosen}
            if expanded == chosen:
                break
            chosen = expanded
        chosen |= known & {'platform', 'shared', 'architecture', 'lifecycle'}
    selected = [dict(r, selector=r['name']) for r in records
                if r['module'] in chosen and ((r['kind'] != 'unit') if band == 'F4' else r['kind'] == 'unit')]
    if not selected:
        raise Invalid(f'Empty mandatory selection: {band} {sorted(chosen)}')
    return selected


def static_migrations(root, base):
    folder = 'src/main/resources/db/migration'
    historical = 'src/test/resources/database/historical'
    old = git(root, 'ls-tree', '-r', '--name-only', base, '--', folder, historical).splitlines()
    for name in old:
        expected = subprocess.check_output(['git', '-C', str(root), 'show', f'{base}:{name}'])
        path = root / name
        if not path.is_file() or digest(path.read_bytes()) != digest(expected):
            raise Invalid(f'Historical migration/fixture drift: {name} (base {base})')
    seen, scripts = set(), []
    for path in sorted((root / folder).iterdir()):
        match = re.fullmatch(r'V([1-9]\d*(?:[._]\d+)*)__([a-zA-Z0-9_]+)\.sql', path.name)
        if not match or not path.read_text(encoding='utf-8-sig').strip():
            raise Invalid(f'Invalid migration naming/content: {path.name}')
        version = tuple(int(v) for v in re.split(r'[._]', match[1]))
        while len(version) > 1 and version[-1] == 0:
            version = version[:-1]
        if version in seen:
            raise Invalid(f'Duplicate migration version: {path.name}')
        seen.add(version)
        scripts.append(dict(path=str(path.relative_to(root)), sha256=digest(path.read_bytes()), version=version))
    for line in (root / historical / 'SHA256SUMS').read_text().splitlines():
        checksum, name = line.split()
        if name not in ('v1.sql', 'v2.sql', 'v3.sql') or digest((root / historical / name).read_bytes()) != checksum:
            raise Invalid(f'Historical fixture checksum invalid: {name}')
    new = [s for s in scripts if s['path'].replace('\\', '/') not in old]
    if not scripts:
        raise Invalid('No migrations discovered')
    return dict(base=base, scripts=scripts, new=new, sql_executed=False,
                requires_historical_validation=bool(new))


def validate_maven(root):
    """Fail closed when the selection contract drifts; never override exclusions silently."""
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    pom = ET.parse(root / 'pom.xml').getroot()
    for plugin in pom.findall('.//m:plugin', ns):
        artifact = plugin.findtext('m:artifactId', namespaces=ns)
        if artifact == 'maven-surefire-plugin':
            for tag in ('includes', 'excludes', 'groups', 'excludedGroups', 'suiteXmlFiles'):
                if plugin.find('.//m:' + tag, ns) is not None:
                    raise Invalid(f'Surefire selection configuration changed: {tag}; review discovery contract')
        if artifact == 'maven-failsafe-plugin' and plugin.find('.//m:excludes', ns) is not None:
            raise Invalid('Failsafe exclusions require explicit discovery review')
    for profile, expected in [('mysql-migrations', ['**/*MySqlIT.java', '**/MySqlMigrationIT.java']),
                              ('publications-postgresql', ['**/*PostgresIT.java'])]:
        node = next((p for p in pom.findall('m:profiles/m:profile', ns)
                     if p.findtext('m:id', namespaces=ns) == profile), None)
        if node is None:
            raise Invalid(f'Missing integration profile: {profile}')
        plugin = next((p for p in node.findall('.//m:plugin', ns)
                       if p.findtext('m:artifactId', namespaces=ns) == 'maven-failsafe-plugin'), None)
        actual = [] if plugin is None else [p.text for p in plugin.findall('.//m:include', ns)]
        if actual != expected:
            raise Invalid(f'Failsafe discovery changed for {profile}: {actual}; review discovery contract')


def commands(selected, full=False, migration=None):
    mvn = ['mvnw.cmd' if os.name == 'nt' else './mvnw', '--batch-mode', '--no-transfer-progress']
    if full or migration == 'M4':
        gates = ([mvn + ['verify']] if full else [])
        gates += [mvn + ['-Pmysql-migrations', '-DskipUnitTests=true', 'verify']]
        if full:
            gates += [mvn + ['-Ppublications-postgresql', '-DskipUnitTests=true', 'verify']]
        return gates + [['bash', 'deploy/test-future-migration.sh']]
    result = []
    for kind, profile in [('unit', None), ('mysql', 'mysql-migrations'), ('postgres', 'publications-postgresql')]:
        selectors = sorted({r['selector'] for r in selected if r['kind'] == kind})
        if not selectors:
            continue
        args = ['-Dtest=' + ','.join(selectors), '-DfailIfNoTests=true', 'test'] if not profile else [
            '-P' + profile, '-DskipUnitTests=true', '-Dit.test=' + ','.join(selectors),
            '-Dfailsafe.failIfNoSpecifiedTests=true', 'verify']
        result.append(mvn + args)
    return result


def reconcile(selected, report_dir):
    cases = []
    for path in sorted(report_dir.glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        for case in suite.iter('testcase'):
            state = next((s for s in ('failure', 'error', 'skipped') if case.find(s) is not None), 'passed')
            cases.append(dict(classname=case.get('classname', ''), name=case.get('name', ''), status=state))
    missing = []
    for r in selected:
        cls, _, method = r['selector'].partition('#')
        matching = [c for c in cases if (c['classname'] == cls or c['classname'].startswith(cls + '$'))
                    and (not method or c['name'] == method or c['name'].startswith(method + '(')
                         or c['name'].startswith(method + '['))]
        if not matching:
            missing.append(r['selector'])
    unexpected = sorted({c['classname'] for c in cases if not any(
        c['classname'] == r['name'] or c['classname'].startswith(r['name'] + '$') for r in selected)})
    return dict(cases=cases, counts=dict(Counter(c['status'] for c in cases)), not_executed=missing,
                unexpected_classes=unexpected)


def run(root, plan, out):
    runs = []
    if not plan['commands']:
        (out / 'result.json').write_text(json.dumps(dict(plan=plan, runs=[], static_passed=True), indent=2), encoding='utf-8')
    for index, cmd in enumerate(plan['commands']):
        folder = out / f'gate-{index + 1}'
        folder.mkdir()
        # Archive prior reports instead of mixing or deleting evidence from other runs.
        for name in ('surefire-reports', 'failsafe-reports'):
            previous = root / 'target' / name
            if previous.exists():
                shutil.move(str(previous), str(folder / ('previous-' + name)))
        if cmd[0] == 'bash' and (root / 'target/forward-migration-evidence').exists():
            shutil.move(str(root / 'target/forward-migration-evidence'), str(folder / 'previous-forward-migration-evidence'))
        started = time.monotonic()
        log = folder / 'execution.log'
        relevant = [r for r in plan['selected'] if r['kind'] == (
            'mysql' if '-Pmysql-migrations' in cmd or cmd[0] == 'bash' else
            'postgres' if '-Ppublications-postgresql' in cmd else 'unit')]
        try:
            with log.open('w', encoding='utf-8') as stream:
                proc = subprocess.run(cmd, cwd=root, stdout=stream, stderr=subprocess.STDOUT)
            code = proc.returncode
        except OSError as exc:
            log.write_text(str(exc), encoding='utf-8')
            code = 127
        report = root / 'target' / ('failsafe-reports' if any(r['kind'] != 'unit' for r in relevant) else 'surefire-reports')
        if cmd[0] == 'bash':
            report = root / 'target/forward-migration-evidence/failsafe-reports'
        stats = reconcile(relevant, report)
        if report.exists():
            shutil.copytree(report, folder / 'reports')
        skipped = stats['counts'].get('skipped', 0)
        ok = not (code or stats['not_executed'] or stats['unexpected_classes'] or stats['counts'].get('failure') or stats['counts'].get('error')
                  or (skipped and any(r['kind'] != 'unit' for r in relevant)))
        runs.append(dict(command=cmd, exit_code=code, passed=ok, seconds=round(time.monotonic()-started, 3),
                         log=str(log), **stats))
        (out / 'result.json').write_text(json.dumps(dict(plan=plan, runs=runs), indent=2), encoding='utf-8')
        print(json.dumps({k: v for k, v in runs[-1].items() if k not in ('cases', 'command')}, ensure_ascii=False))
        if not ok:
            return code or 1
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--band', choices=['F1', 'F2', 'F3', 'F4', 'F5'])
    parser.add_argument('--migration', choices=['M1', 'M2', 'M3', 'M4'])
    parser.add_argument('--module', action='append', default=[])
    parser.add_argument('--test', action='append', default=[])
    parser.add_argument('--dependency', action='append', default=[])
    parser.add_argument('--impact-review', help='Required F3 semantic review rationale; imports cannot prove semantic completeness')
    parser.add_argument('--risk-file', type=Path, help='JSON: new migration sha256 -> {reason, tests: [Class#method]}')
    parser.add_argument('--base', default='origin/develop', help='Trusted immutable-history comparison ref')
    parser.add_argument('--inspect', action='store_true')
    args = parser.parse_args(argv)
    root = ROOT
    try:
        if bool(args.band) == bool(args.migration):
            raise Invalid('Choose exactly one of --band or --migration')
        validate_maven(root)
        records, helpers = inventory(root)
        edges = relations(root, {r['module'] for r in records})
        base = git(root, 'merge-base', 'HEAD', args.base)
        static = static_migrations(root, base) if args.migration else None
        if args.migration and (args.module or args.test or args.dependency):
            raise Invalid('Migration levels cannot be narrowed with functional selectors')
        if args.band == 'F3' and not args.impact_review:
            raise Invalid('F3 requires --impact-review: review semantic/runtime dependencies and declare --dependency as needed')
        if args.migration:
            selected = [dict(r, selector=r['name']) for r in records if r['kind'] == 'mysql']
            if args.migration == 'M1':
                selected = []
            if args.migration in ('M2', 'M3'):
                selectors = [MYSQL + '#' + m for m in SMOKE] if args.migration == 'M2' else [MYSQL]
                if args.migration == 'M3' and static['new']:
                    risks = json.loads(args.risk_file.read_text(encoding='utf-8')) if args.risk_file else {}
                    for new in static['new']:
                        risk = risks.get(new['sha256'], {})
                        if not risk.get('reason') or not risk.get('tests'):
                            raise Invalid(f'Uncovered new migration {new["path"]}: require sha256-bound risk review and upgrade/constraint tests in --risk-file')
                        selectors.extend(risk['tests'])
                selected = select(records, 'F1', focus=selectors)
                if any(r['kind'] != 'mysql' for r in selected):
                    raise Invalid('Migrations require MySQL integration selectors')
        else:
            selected = select(records, args.band, args.module, args.test, args.dependency, edges)
        cmds = [] if args.migration == 'M1' else commands(selected, args.band == 'F5', args.migration)
        plan = dict(sha=git(root, 'rev-parse', 'HEAD'), branch=git(root, 'branch', '--show-current'),
                    dirty=bool(git(root, 'status', '--porcelain')), band=args.band, migration=args.migration,
                    partial=args.band != 'F5' and args.migration != 'M4', environment=dict(
                        os=platform.platform(), python=platform.python_version(), java_home=os.environ.get('JAVA_HOME'),
                        java=subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java', '-version'],
                                            capture_output=True, text=True).stderr.strip()),
                    inventory=records, helpers=helpers, selected=selected,
                    not_selected=sorted({r['name'] for r in records}-{r['name'] for r in selected}),
                    relations=edges if args.band == 'F3' else [], impact_review=args.impact_review,
                    migrations=static, commands=cmds,
                    limitations=['Source candidates are not JUnit case counts; XML reconciles execution.',
                                 'Import closure cannot infer reflection, events, configuration or external consumers.',
                                 'Partial execution is not full validation or JaCoCo coverage.'])
        out = root / 'target/progressive' / (dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
        out.mkdir(parents=True)
        (out / 'selection.json').write_text(json.dumps(plan, indent=2), encoding='utf-8')
        print(f'{args.band or args.migration}: {len(selected)} selectors; {"PARTIAL" if plan["partial"] else "FULL gates"}; manifest: {out / "selection.json"}')
        if args.inspect:
            for cmd in cmds:
                print(subprocess.list2cmdline(cmd))
            return 0
        if (args.band == 'F5' or args.migration == 'M4') and plan['dirty']:
            raise Invalid('Full archive gate requires committed clean HEAD (git archive HEAD)')
        return run(root, plan, out)
    except (Invalid, subprocess.CalledProcessError, OSError, ValueError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
