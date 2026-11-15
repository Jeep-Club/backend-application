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


def trusted_base(root, reference=None, full=False):
    try:
        head = git(root, 'rev-parse', '--verify', 'HEAD^{commit}')
    except subprocess.CalledProcessError as exc:
        raise Invalid(f'Cannot resolve checked-out HEAD as a Git commit: {exc}') from exc
    default_reference = reference is None
    if default_reference:
        reference = 'origin/develop'
    if default_reference and not full:
        # Local partial runs compare from the shared point, including a branch
        # with no commits of its own (where that point can be HEAD).
        common = subprocess.run(['git', '-C', str(root), 'merge-base', 'HEAD', reference],
                                capture_output=True, text=True)
        if common.returncode != 0 or not common.stdout.strip():
            detail = common.stderr.strip() or f'git exited {common.returncode}'
            raise Invalid(f'Cannot establish a common Git base for HEAD and {reference}: {detail}')
        base = common.stdout.strip()
    else:
        # Explicit baselines and full validation retain the strict ancestor
        # contract. Never replace an invalid explicit ref silently.
        resolved = subprocess.run(['git', '-C', str(root), 'rev-parse', '--verify', f'{reference}^{{commit}}'],
                                  capture_output=True, text=True)
        if resolved.returncode != 0 or not resolved.stdout.strip():
            detail = resolved.stderr.strip() or f'git exited {resolved.returncode}'
            raise Invalid(f'Comparison reference {reference} is missing or does not resolve to a commit: {detail}')
        base = resolved.stdout.strip()
        if base == head:
            raise Invalid(f'Comparison reference {reference} resolves to HEAD; provide a distinct trusted baseline')
        ancestry = subprocess.run(['git', '-C', str(root), 'merge-base', '--is-ancestor', base, head],
                                  capture_output=True, text=True)
        if ancestry.returncode != 0:
            detail = 'not an ancestor of HEAD' if ancestry.returncode == 1 else ancestry.stderr.strip()
            raise Invalid(f'Comparison reference {reference} is not a verifiable ancestor of HEAD: {detail}')
    if full:
        try:
            develop = git(root, 'rev-parse', '--verify', 'origin/develop^{commit}')
            common = git(root, 'merge-base', 'HEAD', develop)
        except subprocess.CalledProcessError as exc:
            raise Invalid(f'Full validation requires a verifiable origin/develop history: {exc}') from exc
        relation = subprocess.run(['git', '-C', str(root), 'merge-base', '--is-ancestor', base, common],
                                  capture_output=True, text=True)
        if relation.returncode != 0:
            raise Invalid('Full validation base must be an ancestor of the fetched origin/develop merge-base')
    return base


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


def test_parents(text, name):
    """Names referenced by the top-level test type, including default-method interfaces."""
    match = re.search(r'\b(?:class|interface)\s+' + re.escape(name) + r'\b([^{};]*)\{', source(text))
    if not match:
        return []
    header = match.group(1)
    while re.search(r'<[^<>]*>', header):
        header = re.sub(r'<[^<>]*>', '', header)
    if '<' in header or '>' in header:
        raise Invalid(f'Cannot resolve generic test inheritance for {name}')
    names = []
    for section in re.finditer(r'\b(?:extends|implements)\s+(.*?)(?=\b(?:extends|implements)\b|$)', header):
        for item in section.group(1).split(','):
            item = item.strip()
            if not re.fullmatch(r'[\w.]+', item):
                raise Invalid(f'Cannot resolve test inheritance for {name}: {item}')
            names.append(item.rsplit('.', 1)[-1])
    return names


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
                            nested=bool(re.search(r'@(?:[\w.]+\.)?Nested\b', text)),
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
    def inherited_methods(text, name, seen):
        methods = set(re.findall(r'\b[\w<>?,.\[\]]+\s+(\w+)\s*\(', text))
        for parent in test_parents(text, name):
            if parent not in seen and len(sources.get(parent, [])) == 1:
                methods |= inherited_methods(sources[parent][0], parent, seen | {parent})
        return methods
    for record in records:
        record['methods'] = sorted(inherited_methods(source((root / record['path']).read_text(encoding='utf-8-sig')), Path(record['path']).stem, set()))
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
            for match in re.finditer(r'\bcom\.jeepclub\.backend\.[\w.*]+', text):
                provider = module(match[0])
                if owner != provider:
                    edges.append(dict(consumer=owner, provider=provider, source=str(path.relative_to(root)),
                                      line=text.count('\n', 0, match.start()) + 1, reference=match[0]))
    return edges


def select(records, band, modules=(), focus=(), dependencies=(), edges=()):
    known = {r['module'] for r in records}
    for value in [*modules, *dependencies]:
        if value not in known:
            raise Invalid(f'Unknown module: {value}; known: {sorted(known)}')
    names = {r['name']: r for r in records}
    if band == 'F1':
        if modules or dependencies:
            raise Invalid('F1 requires explicit tests only; module/dependency would be ignored')
        if not focus:
            raise Invalid('F1 requires --test Class[#method]')
        resolved = []
        for item in focus:
            if not isinstance(item, str):
                raise Invalid('Test selectors must be strings')
            cls, _, method = item.partition('#')
            matches = [n for n in names if n == cls or n.rsplit('.', 1)[-1] == cls]
            if len(matches) != 1 or ('#' in item and not re.fullmatch(r'\w+', method)):
                raise Invalid(f'Unknown, ambiguous or unsupported selector: {item}')
            if method and method not in names[matches[0]].get('methods', []):
                raise Invalid(f'Unknown method selector: {item}')
            resolved.append(dict(names[matches[0]], selector=matches[0] + ('#' + method if method else '')))
        return list({r['selector']: r for r in resolved}.values())
    if focus:
        raise Invalid('--test is only supported with F1')
    if band == 'F5':
        if modules or dependencies:
            raise Invalid('F5 cannot be narrowed')
        if {r['kind'] for r in records} != {'unit', 'mysql', 'postgres'}:
            raise Invalid('F5 requires nonempty Surefire, MySQL and PostgreSQL inventories')
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
        if chosen - known:
            raise Invalid(f'Unclassified dependency modules without discovered tests: {sorted(chosen - known)}')
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
    existing = [s for s in scripts if s['path'].replace('\\', '/') in old]
    if existing and any(s['version'] <= max(e['version'] for e in existing) for s in new):
        raise Invalid('New migration must append a version after immutable history')
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
            for tag in ('includes', 'excludes', 'includesFile', 'excludesFile', 'groups',
                        'excludedGroups', 'suiteXmlFiles', 'includeJUnit5Engines', 'excludeJUnit5Engines'):
                if plugin.find('.//m:' + tag, ns) is not None:
                    raise Invalid(f'Surefire selection configuration changed: {tag}; review discovery contract')
        if artifact == 'maven-failsafe-plugin':
            for tag in ('excludes', 'includesFile', 'excludesFile', 'groups', 'excludedGroups',
                        'suiteXmlFiles', 'includeJUnit5Engines', 'excludeJUnit5Engines'):
                if plugin.find('.//m:' + tag, ns) is not None:
                    raise Invalid(f'Failsafe filtering ({tag}) requires explicit discovery review')
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


def executable_methods(text, with_scopes=False):
    """Conservatively identify directly annotated Jupiter methods, excluding helpers."""
    text = source(text)
    method = re.compile(r'\b(?:void|[\w.$<>\[\],?]+)\s+(\w+)\s*\([^;{}]*\)\s*(?:throws\s+[\w.,\s]+)?\s*[{;]')
    found = []
    for annotation in ANNOTATION.finditer(text):
        # Stop at a different executable annotation or class declaration; never
        # guess across an unsupported declaration.
        tail = text[annotation.end():]
        match = method.search(tail)
        boundary = re.search(r'\b(?:class|interface|record|enum)\s+\w+|@(?:[\w.]+\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b', tail)
        if not match or (boundary and boundary.start() < match.start()):
            raise Invalid('Unrecognized annotated test declaration; manual verification required')
        scopes, pending = [], None
        for token in re.finditer(r'\b(?:class|interface|record|enum)\s+(\w+)|[{}]', text[:annotation.start()]):
            if token.group(1):
                pending = token.group(1)
            elif token.group() == '{':
                scopes.append(pending)
                pending = None
            elif scopes:
                scopes.pop()
        owner = '.'.join(s for s in scopes if s)
        if not owner:
            raise Invalid('Cannot determine test method owner; manual verification required')
        found.append((owner, match.group(1), annotation.group(1)) if with_scopes else (match.group(1), annotation.group(1)))
    return found


def new_test_methods(root, base, selected):
    """Compare existing test sources with a verified base; inheritance is conservative."""
    if not any(row.get('path') for row in selected):
        return []
    paths = sorted((root / 'src/test/java').rglob('*.java'))
    by_stem = {}
    for path in paths:
        by_stem.setdefault(path.stem, []).append(path)
    def git_paths(*args):
        data = subprocess.check_output(['git', '-C', str(root), '-c', 'core.quotepath=false', *args])
        return {path.decode('utf-8') for path in data.split(b'\0') if path}
    changed = git_paths('diff', '--name-only', '-z', base, '--', 'src/test/java')
    changed.update(git_paths('ls-files', '--others', '--exclude-standard', '-z', '--', 'src/test/java'))
    if not changed:
        return []
    result = []
    for row in selected:
        if 'path' not in row:
            continue
        initial = root / row['path']
        initial_rel = initial.relative_to(root).as_posix()
        initial_exists = subprocess.run(['git', '-C', str(root), 'cat-file', '-e', f'{base}:{initial_rel}'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        chain, seen, pending = [], set(), [initial]
        while pending:
            path = pending.pop()
            if path in seen:
                continue
            seen.add(path)
            text = path.read_text(encoding='utf-8-sig')
            chain.append((path, text))
            for parent in test_parents(text, path.stem):
                candidates = by_stem.get(parent, [])
                if len(candidates) > 1 and (not initial_exists or any(p.relative_to(root).as_posix() in changed for p in candidates)):
                    raise Invalid(f'Cannot resolve inherited tests for {row["name"]}: {parent}')
                if len(candidates) == 1:
                    pending.append(candidates[0])
        for path, current in chain:
            rel = path.relative_to(root).as_posix()
            if rel not in changed and initial_exists:
                continue
            exists = subprocess.run(['git', '-C', str(root), 'cat-file', '-e', f'{base}:{rel}'],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
            old = subprocess.check_output(['git', '-C', str(root), 'show', f'{base}:{rel}']).decode('utf-8-sig') if exists else ''
            if exists and test_parents(old, path.stem) != test_parents(current, path.stem):
                raise Invalid(f'Changed test inheritance in {rel}; manual verification required')
            if not initial_exists:
                old = ''  # A new subclass must prove every inherited executable method too.
            before = set(executable_methods(old, with_scopes=True))
            now = executable_methods(current, with_scopes=True)
            for owner, name, annotation in now:
                if (owner, name, annotation) not in before:
                    if sum(n == name for _, n, _ in now) != 1 or any(n == name and prior_owner != owner for prior_owner, n, _ in before):
                        raise Invalid(f'Ambiguous new test method {rel}#{name}; manual verification required')
                    if any(name in {n for n, _ in executable_methods(other_text)} for other_path, other_text in chain if other_path != path):
                        raise Invalid(f'Overridden or ambiguous inherited test {rel}#{name}; manual verification required')
                    dynamic = any(a == 'TestFactory' for _, chain_text in chain for _, a in executable_methods(chain_text))
                    result.append(dict(classname=row['name'], method=name, annotation=annotation, source=rel,
                                       owner=owner, dynamic_ambiguity=dynamic))
    return list({(x['classname'], x['method'], x['source']): x for x in result}.values())


def reconcile_new_methods(methods, cases, selected=None):
    results = []
    for item in methods:
        cls, name = item['classname'], item['method']
        matching = [c for c in cases if (c['classname'] == cls or c['classname'].startswith(cls + '$'))
                    and (c['name'] == name or c['name'].startswith(name + '(') or c['name'].startswith(name + '['))]
        scoped = [r for r in selected or [] if r['name'] == cls]
        if selected is not None and not any('#' not in r['selector'] or r['selector'].partition('#')[2] == name for r in scoped):
            state = 'not_selected'
        elif item['annotation'] == 'TestFactory' or item.get('dynamic_ambiguity'):
            state = 'unproven'  # Dynamic testcase names do not prove their factory identity.
        elif not matching:
            state = 'unproven' if item['annotation'] == 'TestTemplate' else 'not_discovered'
        elif any(c['status'] in ('failure', 'error') for c in matching):
            state = 'failed'
        elif any(c['status'] == 'skipped' for c in matching):
            state = 'skipped'
        else:
            state = 'executed'
        results.append(dict(item, status=state))
    return results


def migration_risk_selectors(info, records, risk_file, root=None):
    root = root or ROOT
    if not info['new']:
        return {}, []
    risks = json.loads(risk_file.read_text(encoding='utf-8')) if risk_file and risk_file.is_file() else {}
    if not isinstance(risks, dict):
        raise Invalid('Risk file must contain a JSON object keyed by migration sha256')
    selectors = []
    for migration in info['new']:
        risk = risks.get(migration['sha256'], {})
        if not isinstance(risk, dict) or not isinstance(risk.get('reason'), str) or not risk['reason'].strip() or not isinstance(risk.get('tests'), list) or not risk['tests']:
            raise Invalid(f'Uncovered new migration {migration["path"]}: require sha256-bound risk review and upgrade/constraint tests in --risk-file')
        chosen = select(records, 'F1', focus=risk['tests'])
        if any('#' not in r['selector'] for r in chosen):
            raise Invalid('Migration risk review requires explicit regression methods, not whole classes')
        sources = {}
        for path in (root / 'src/test/java').rglob('*.java'):
            sources.setdefault(path.stem, []).append(path)
        for row in chosen:
            if row['kind'] != 'mysql' or not row.get('path'):
                raise Invalid('Migrations require MySQL integration selectors with source evidence')
            pending, seen, declared = [root / row['path']], set(), []
            while pending:
                path = pending.pop()
                if path in seen:
                    continue
                seen.add(path)
                text = path.read_text(encoding='utf-8-sig')
                declared.extend(executable_methods(text))
                for parent in test_parents(text, path.stem):
                    matches = sources.get(parent, [])
                    if len(matches) > 1:
                        raise Invalid(f'Ambiguous inherited risk test in {row["selector"]}')
                    if matches:
                        pending.append(matches[0])
            method = row['selector'].partition('#')[2]
            if any(annotation == 'TestFactory' for _, annotation in declared):
                raise Invalid(f'Dynamic risk test attribution is unproven: {row["selector"]}')
            if sum(name == method for name, _ in declared) != 1:
                raise Invalid(f'Risk selector is not an unambiguous annotated JUnit test: {row["selector"]}')
        if all(r['selector'] in [MYSQL + '#' + m for m in SMOKE] for r in chosen):
            raise Invalid(f'New migration {migration["path"]}: schema smoke alone cannot prove historical compatibility')
        selectors.extend(r['selector'] for r in chosen)
    selected = select(records, 'F1', focus=selectors)
    if any(r['kind'] != 'mysql' for r in selected):
        raise Invalid('Migrations require MySQL integration selectors')
    return risks, selected


def verify_migration_risks(required, report):
    if not required:
        return None
    result = reconcile(required, report)
    if result['not_executed'] or result['all_skipped_selectors'] or result['counts'].get('failure') or result['counts'].get('error') or result['counts'].get('skipped'):
        raise Invalid('Migration risk regression lacks successful MySQL execution: ' + str(result['not_executed'] or result['all_skipped_selectors']))
    cases = [c for c in result['cases'] if any(
        (c['classname'] == r['name'] or c['classname'].startswith(r['name'] + '$')) and
        (c['name'] == r['selector'].partition('#')[2] or c['name'].startswith(r['selector'].partition('#')[2] + '(') or c['name'].startswith(r['selector'].partition('#')[2] + '['))
        for r in required)]
    return dict(selectors=[r['selector'] for r in required], cases=cases,
                counts=dict(Counter(c['status'] for c in cases)))


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
        # A global Maven method filter can exclude nested cases of a different
        # whole-class selector. Keep complete classes and methods in separate runs.
        whole = {s for s in selectors if '#' not in s}
        for group in (sorted(whole), [s for s in selectors if '#' in s and s.split('#')[0] not in whole]):
            if not group:
                continue
            patterns = set(group)
            for r in selected:
                if r['selector'] in group and r.get('nested') and '#' in r['selector']:
                    cls, method = r['selector'].split('#')
                    patterns.add(cls + '$*#' + method)
            args = ['-Dtest=' + ','.join(sorted(patterns)), '-DfailIfNoTests=true', 'test'] if not profile else [
                '-P' + profile, '-DskipUnitTests=true', '-Dit.test=' + ','.join(sorted(patterns)),
                '-Dfailsafe.failIfNoSpecifiedTests=true', 'verify']
            result.append(mvn + args)
    return result


def materialize_commands(cmds, out):
    """Use Surefire's native include file when a Windows cmd selector is too long.

    Explicit Failsafe includes in this POM append to includesFile, so never use
    that property to narrow an IT selection (it would broaden the requested scope).
    """
    result = []
    for index, cmd in enumerate(cmds):
        cmd = list(cmd)
        for pos, arg in enumerate(cmd):
            if arg.startswith('-Dtest=') and len(arg) > 6000:
                path = out / f'gate-{index + 1}-includes.txt'
                path.write_text('\n'.join(arg[len('-Dtest='):].split(',')) + '\n', encoding='utf-8')
                cmd[pos] = '-Dsurefire.includesFile=' + str(path)
        if os.name == 'nt' and len(subprocess.list2cmdline(cmd)) > 7800:
            raise Invalid('Selection exceeds Windows cmd limit; split explicit focal IT selection or use the full profile')
        result.append(cmd)
    return result


def reconcile(selected, report_dir):
    cases = []
    for path in sorted(report_dir.glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        for case in suite.iter('testcase'):
            state = next((s for s in ('failure', 'error', 'skipped') if case.find(s) is not None), 'passed')
            reported = case.get('classname', '')
            # Existing precedent: docs/testing/final-hardening/measure.py. Jupiter
            # nested DisplayNames may replace classname; suite retains source owner.
            owner = suite.get('name', '')
            canonical = owner if not reported.startswith(PREFIX) and owner.startswith(PREFIX) else reported
            cases.append(dict(classname=canonical, reported_classname=reported, suite=owner,
                              report=path.name, name=case.get('name', ''), status=state))
    def matches(record, case):
        cls, _, method = record['selector'].partition('#')
        return (case['classname'] == cls or case['classname'].startswith(cls + '$')) and (
            not method or case['name'] == method or case['name'].startswith(method + '(')
            or case['name'].startswith(method + '['))
    missing, all_skipped = [], []
    for r in selected:
        matching = [c for c in cases if matches(r, c)]
        if not matching:
            missing.append(r['selector'])
        elif all(c['status'] == 'skipped' for c in matching):
            all_skipped.append(r['selector'])
    unexpected = sorted({c['classname'] for c in cases if not any(
        c['classname'] == r['name'] or c['classname'].startswith(r['name'] + '$') for r in selected)})
    unexpected_cases = [c['classname'] + '#' + c['name'] for c in cases
                        if not any(matches(r, c) for r in selected)]
    return dict(cases=cases, counts=dict(Counter(c['status'] for c in cases)), not_executed=missing,
                all_skipped_selectors=all_skipped, unexpected_classes=unexpected, unexpected_cases=unexpected_cases)


def compact(stats):
    """Bound console output; complete identities remain in the JSON evidence."""
    result = {'counts': stats['counts']}
    samples = {}
    for key in ('not_executed', 'all_skipped_selectors', 'unexpected_classes', 'unexpected_cases'):
        values = stats.get(key, [])
        result[key] = len(values)
        if values:
            samples[key] = values[:3]
    if samples:
        result['diagnostic_sample'] = samples
    pending = [m for m in stats.get('new_methods', []) if m['status'] != 'executed']
    if pending:
        result['new_methods_pending'] = len(pending)
        result['new_methods_sample'] = pending[:3]
    return result


def run(root, plan, out):
    runs = []
    if plan.get('band') == 'F5' or plan.get('migration') == 'M4':
        # Keep prior build outputs as evidence, but compile the committed sources
        # afresh before treating a full gate as evidence for this HEAD.
        old_build = out / 'previous-build'
        for name in ('classes', 'test-classes', 'generated-sources', 'generated-test-sources', 'maven-status'):
            previous = root / 'target' / name
            if previous.exists():
                old_build.mkdir(exist_ok=True)
                shutil.move(str(previous), str(old_build / name))
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
        requested = next((a.split('=', 1)[1].split(',') for a in cmd if a.startswith(('-Dtest=', '-Dit.test='))), None)
        include_file = next((a.split('=', 1)[1] for a in cmd if a.startswith('-Dsurefire.includesFile=')), None)
        if include_file:
            requested = Path(include_file).read_text(encoding='utf-8').splitlines()
        if requested is not None:
            relevant = [r for r in relevant if r['selector'] in requested or
                        ('#' in r['selector'] and r['name'] in requested)]
        if not relevant:
            raise Invalid(f'Command has no resolved mandatory selectors: {cmd}')
        try:
            with log.open('w', encoding='utf-8') as stream:
                docker = any(r['kind'] != 'unit' for r in relevant)
                proc = subprocess.run(['docker', 'info', '--format', '{{.ServerVersion}}'],
                                      cwd=root, stdout=stream, stderr=subprocess.STDOUT) if docker else None
                if proc is None or proc.returncode == 0:
                    proc = subprocess.run(cmd, cwd=root, stdout=stream, stderr=subprocess.STDOUT)
            code = proc.returncode
        except OSError as exc:
            log.write_text(str(exc), encoding='utf-8')
            code = 127
        report = root / 'target' / ('failsafe-reports' if any(r['kind'] != 'unit' for r in relevant) else 'surefire-reports')
        if cmd[0] == 'bash':
            report = root / 'target/forward-migration-evidence/failsafe-reports'
        stats = reconcile(relevant, report)
        required = [m for m in plan.get('new_test_methods', []) if any(r['name'] == m['classname'] for r in relevant)]
        stats['new_methods'] = reconcile_new_methods(required, stats['cases'], relevant)
        risk_error = None
        integrity_error = None
        if plan.get('band') == 'F5' or plan.get('migration') == 'M4':
            if git(root, 'rev-parse', 'HEAD') != plan['sha'] or git(root, 'status', '--porcelain'):
                integrity_error = 'Full gate requires the same clean committed HEAD throughout execution'
        if '-Pmysql-migrations' in cmd and (plan.get('band') == 'F5' or plan.get('migration') == 'M4') and plan.get('risk_selectors'):
            try:
                verify_migration_risks(plan['risk_selectors'], report)
            except Invalid as exc:
                risk_error = str(exc)
        stats['migration_risk_error'] = risk_error
        stats['gate_integrity_error'] = integrity_error
        if report.exists():
            shutil.copytree(report, folder / 'reports')
        skipped = stats['counts'].get('skipped', 0)
        ok = not (code or risk_error or integrity_error or stats['not_executed'] or stats['all_skipped_selectors'] or stats['unexpected_cases'] or any(m['status'] != 'executed' for m in stats['new_methods']) or stats['counts'].get('failure') or stats['counts'].get('error')
                  or (skipped and any(r['kind'] != 'unit' for r in relevant)))
        runs.append(dict(command=cmd, selected_selectors=[r['selector'] for r in relevant], exit_code=code, passed=ok, seconds=round(time.monotonic()-started, 3),
                         log=str(log), **stats))
        reported = {c['classname'].split('$')[0] for run in runs for c in run['cases']}
        (out / 'result.json').write_text(json.dumps(dict(plan=plan, runs=runs,
            remaining_commands=plan['commands'][index + 1:],
            not_run=sorted({r['name'] for r in plan['selected']} - reported)), indent=2), encoding='utf-8')
        print(json.dumps(dict(exit_code=code, passed=ok, seconds=runs[-1]['seconds'],
                              log=str(log), migration_risk_error=risk_error, gate_integrity_error=integrity_error, **compact(stats)), ensure_ascii=False))
        if not ok:
            return code or 1
    return 0


def validation_scope(band, migration):
    return 'project_full' if band == 'F5' else 'migration_full' if migration == 'M4' else 'partial'


def main(argv=None):
    started = time.monotonic()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--band', choices=['F1', 'F2', 'F3', 'F4', 'F5'])
    parser.add_argument('--migration', choices=['M1', 'M2', 'M3', 'M4'])
    parser.add_argument('--module', action='append', default=[])
    parser.add_argument('--test', action='append', default=[])
    parser.add_argument('--dependency', action='append', default=[])
    parser.add_argument('--impact-review', help='Required F3 semantic review rationale; imports cannot prove semantic completeness')
    parser.add_argument('--risk-file', type=Path, help='JSON: new migration sha256 -> {reason, tests: [Class#method]}')
    parser.add_argument('--base', help='Explicit comparison ref; must resolve to a distinct ancestor commit (use its SHA for reproducibility). By default partial levels compare from merge-base(HEAD, origin/develop).')
    parser.add_argument('--inspect', action='store_true')
    args = parser.parse_args(argv)
    root = ROOT
    try:
        if bool(args.band) == bool(args.migration):
            raise Invalid('Choose exactly one of --band or --migration')
        validate_maven(root)
        records, helpers = inventory(root)
        risks = {}
        edges = relations(root, {r['module'] for r in records})
        base = trusted_base(root, args.base, args.band == 'F5' or args.migration == 'M4')
        static = static_migrations(root, base) if args.migration or args.band == 'F5' else None
        risk_selectors = []
        if args.migration in ('M3', 'M4') or args.band == 'F5':
            risk_path = args.risk_file or root / 'docs/testing/migration-risk.json'
            risks, risk_selectors = migration_risk_selectors(static, records, risk_path, root)
        if args.migration and (args.module or args.test or args.dependency):
            raise Invalid('Migration levels cannot be narrowed with functional selectors')
        if args.band == 'F3' and not (args.impact_review and args.impact_review.strip()):
            raise Invalid('F3 requires --impact-review: review semantic/runtime dependencies and declare --dependency as needed')
        if args.migration:
            selected = [dict(r, selector=r['name']) for r in records if r['kind'] == 'mysql']
            if not selected:
                raise Invalid('Empty mandatory MySQL inventory')
            if args.migration == 'M1':
                selected = []
            if args.migration in ('M2', 'M3'):
                selectors = [MYSQL + '#' + m for m in SMOKE] if args.migration == 'M2' else [MYSQL]
                if args.migration == 'M3':
                    selectors.extend(r['selector'] for r in risk_selectors)
                selected = select(records, 'F1', focus=selectors)
                if any(r['kind'] != 'mysql' for r in selected):
                    raise Invalid('Migrations require MySQL integration selectors')
        else:
            selected = select(records, args.band, args.module, args.test, args.dependency, edges)
        cmds = [] if args.migration == 'M1' else commands(selected, args.band == 'F5', args.migration)
        new_methods = new_test_methods(root, base, selected)
        plan = dict(sha=git(root, 'rev-parse', 'HEAD'), branch=git(root, 'branch', '--show-current'),
                    dirty=bool(git(root, 'status', '--porcelain')), band=args.band, migration=args.migration,
                    partial=args.band != 'F5', validation_scope=validation_scope(args.band, args.migration), environment=dict(
                        os=platform.platform(), python=platform.python_version(), java_home=os.environ.get('JAVA_HOME'),
                        java=subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java', '-version'],
                                            capture_output=True, text=True).stderr.strip()),
                    inventory=records, helpers=helpers, selected=selected,
                    not_selected=sorted({r['name'] for r in records}-{r['name'] for r in selected}),
                    relations=edges if args.band == 'F3' else [], impact_review=args.impact_review,
                    migrations=static, commands=cmds,
                    request=dict(modules=args.module, tests=args.test, dependencies=args.dependency, base=args.base),
                    risk_review=risks, risk_selectors=risk_selectors,
                    semantic_review_required=bool(risk_selectors),
                    new_test_methods=new_methods, comparison_base=base,
                    limitations=['Source candidates are not JUnit case counts; XML reconciles execution.',
                                 'Import closure cannot infer reflection, events, configuration or external consumers.',
                                 'Partial execution is not full validation or JaCoCo coverage.'])
        out = root / 'target/progressive' / (dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
        out.mkdir(parents=True)
        plan['commands'] = materialize_commands(cmds, out)
        plan['selection_seconds'] = round(time.monotonic() - started, 3)
        (out / 'selection.json').write_text(json.dumps(plan, indent=2), encoding='utf-8')
        print(f'{args.band or args.migration}: {len(selected)} selectors; {plan["validation_scope"]}; manifest: {out / "selection.json"}')
        if args.inspect:
            for cmd in plan['commands']:
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
