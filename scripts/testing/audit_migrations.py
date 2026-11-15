"""Audit new migration declarations against the just-completed full MySQL gate."""
import argparse
import json
import os
import time
from pathlib import Path
from progressive import ROOT, git, inventory, migration_risk_selectors, reconcile, static_migrations, trusted_base, verify_migration_risks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='origin/develop')
    parser.add_argument('--risk-file', type=Path, default=ROOT / 'docs/testing/migration-risk.json')
    parser.add_argument('--reports', type=Path)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--stamp', type=Path, default=ROOT / 'target/progressive-ci-mysql-start.json')
    parser.add_argument('--start', action='store_true')
    args = parser.parse_args()
    sha = git(ROOT, 'rev-parse', 'HEAD')
    if git(ROOT, 'status', '--porcelain'):
        raise ValueError('Migration gate requires a clean committed checkout')
    identity = dict(sha=sha, run=os.environ.get('GITHUB_RUN_ID'), job=os.environ.get('GITHUB_JOB'),
                    github_sha=os.environ.get('GITHUB_SHA'))
    if not identity['run'] or not identity['job'] or identity['github_sha'] != sha:
        raise ValueError('CI run, job and checkout SHA are required for full gate evidence')
    if args.start:
        args.stamp.parent.mkdir(parents=True, exist_ok=True)
        args.stamp.write_text(json.dumps(dict(identity=identity, started_ns=time.time_ns())), encoding='utf-8')
        return
    if not args.reports or not args.out:
        parser.error('--reports and --out are required for audit')
    stamp = json.loads(args.stamp.read_text(encoding='utf-8'))
    if stamp['identity'] != identity:
        raise ValueError('Full MySQL evidence belongs to a different SHA or CI run')
    xmls = list(args.reports.glob('TEST-*.xml'))
    if not xmls or any(p.stat().st_mtime_ns < stamp['started_ns'] for p in xmls):
        raise ValueError('Full MySQL reports missing or older than the current gate')
    base = trusted_base(ROOT, args.base, full=True)
    info = static_migrations(ROOT, base)
    records, _ = inventory(ROOT)
    full = [dict(r, selector=r['name']) for r in records if r['kind'] == 'mysql']
    full_stats = reconcile(full, args.reports)
    if not full or full_stats['not_executed'] or full_stats['all_skipped_selectors'] or full_stats['unexpected_cases'] or any(full_stats['counts'].get(s) for s in ('failure', 'error', 'skipped')):
        raise ValueError('MySQL evidence is not the complete successful profile')
    risks, required = migration_risk_selectors(info, records, args.risk_file, ROOT)
    evidence = verify_migration_risks(required, args.reports)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(dict(sha=sha, base=base, gate_identity=identity,
                                        migrations=info, risks=risks, required=required,
                                        evidence=evidence, full_profile=full_stats, semantic_review_required=bool(required)), indent=2), encoding='utf-8')
    print(f'Migration risk: {len(info["new"])} new; {len(required)} regression selectors verified; semantic review remains required')


if __name__ == '__main__':
    main()
