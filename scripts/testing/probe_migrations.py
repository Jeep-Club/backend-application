"""Real MySQL runner regression in disposable archives of the committed HEAD.

Run explicitly: python scripts/testing/probe_migrations.py
Requires Docker, Git, Java and Maven dependencies/network. Never uses a configured DB.
"""
import datetime as dt
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def checked(*args, cwd):
    return subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def main():
    sha = checked('git', 'rev-parse', 'HEAD', cwd=ROOT)
    evidence = ROOT / 'target/progressive-probes' / dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    evidence.mkdir(parents=True)
    results = []
    for name, sql in [('valid', 'SELECT 1;\n'), ('invalid', 'THIS IS DELIBERATELY INVALID SQL;\n')]:
        with tempfile.TemporaryDirectory(prefix='back-682-probe-') as tmp:
            tmp = Path(tmp)
            archive, tree = tmp / 'head.tar', tmp / 'tree'
            tree.mkdir()
            checked('git', 'archive', '--format=tar', f'--output={archive}', sha, cwd=ROOT)
            with tarfile.open(archive) as tar:
                # Archive is produced by our own Git HEAD; reject paths escaping extraction root.
                for member in tar.getmembers():
                    if not (tree / member.name).resolve().is_relative_to(tree.resolve()) or member.issym() or member.islnk():
                        raise RuntimeError(f'Unsafe archive entry: {member.name}')
                if sys.version_info >= (3, 12):
                    tar.extractall(tree, filter='data')
                else:
                    tar.extractall(tree)
            checked('git', 'init', '-q', cwd=tree)
            checked('git', 'config', 'core.autocrlf', 'false', cwd=tree)
            checked('git', 'add', '.', cwd=tree)
            checked('git', '-c', 'user.name=BACK-682 probe', '-c', 'user.email=probe@example.invalid',
                    'commit', '-qm', 'Isolated committed baseline', cwd=tree)
            migrations = tree / 'src/main/resources/db/migration'
            version = max(int(re.match(r'V(\d+)', p.name)[1]) for p in migrations.glob('V*__*.sql')) + 1
            migration = migrations / f'V{version}__isolated_runner_probe.sql'
            migration.write_text(sql, encoding='utf-8')
            command = [sys.executable, '-u', 'scripts/testing/progressive.py', '--base', 'HEAD']
            blocked = subprocess.run(command + ['--migration', 'M3', '--inspect'], cwd=tree,
                                     capture_output=True, text=True)
            (evidence / f'{name}-risk-block.log').write_text(blocked.stdout + blocked.stderr, encoding='utf-8')
            if blocked.returncode != 2 or 'Uncovered new migration' not in blocked.stderr:
                raise RuntimeError('New migration did not require an explicit historical risk review')
            with (evidence / f'{name}.log').open('w', encoding='utf-8') as log:
                proc = subprocess.run(command + ['--migration', 'M2'], cwd=tree, stdout=log, stderr=subprocess.STDOUT)
            shutil.copytree(tree / 'target/progressive', evidence / name)
            reports = list((evidence / name).glob('*/result.json'))
            if len(reports) != 1:
                raise RuntimeError('Missing unique execution report for schema probe')
            report = json.loads(reports[0].read_text(encoding='utf-8'))
            logs = '\n'.join(p.read_text(encoding='utf-8') for p in (evidence / name).glob('*/gate-*/execution.log'))
            if name == 'valid':
                if proc.returncode or report['runs'][0]['counts'].get('passed') != 2:
                    raise RuntimeError(f'Valid new migration failed; see {evidence}')
            elif not proc.returncode or migration.name not in logs or 'FlywayMigrateException' not in logs:
                raise RuntimeError(f'Invalid SQL was not rejected by real Flyway/MySQL; see {evidence}')
            results.append(dict(probe=name, source_sha=sha, expected_exit='zero' if name == 'valid' else 'nonzero',
                                exit_code=proc.returncode, runs=report['runs']))
            print(f'{name}: expected result confirmed; logs: {evidence}', flush=True)
    (evidence / 'summary.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
    return 0


if __name__ == '__main__':
    sys.exit(main())
