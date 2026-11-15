"""Compare an original Maven gate's fresh XML reports with current eligible sources."""
import argparse
import json
import os
from pathlib import Path
import sys

from progressive import ROOT, compact, inventory, new_test_methods, reconcile, reconcile_new_methods, trusted_base, validate_maven
from ci_base import from_environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=['unit', 'mysql', 'postgres'], required=True)
    parser.add_argument('--reports', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--selection', type=Path, help='Reconcile a saved partial selection instead of the full inventory')
    parser.add_argument('--base', default='origin/develop')
    args = parser.parse_args()
    validate_maven(ROOT)
    rows, helpers = inventory(ROOT)
    manifest = json.loads(args.selection.read_text(encoding='utf-8')) if args.selection else None
    selected = ([r for r in manifest['selected'] if r['kind'] == args.kind] if manifest else
                [dict(r, selector=r['name']) for r in rows if r['kind'] == args.kind])
    stats = reconcile(selected, args.reports)
    base = from_environment(ROOT)['sha'] if os.environ.get('GITHUB_ACTIONS') == 'true' or os.environ.get('GITHUB_EVENT_NAME') else trusted_base(ROOT, args.base, full=not bool(manifest))
    stats['new_methods'] = reconcile_new_methods(new_test_methods(ROOT, base, selected), stats['cases'], selected)
    passed = bool(selected) and not (stats['not_executed'] or stats['all_skipped_selectors'] or stats['unexpected_cases'] or any(m['status'] != 'executed' for m in stats['new_methods']) or
                                    stats['counts'].get('failure') or stats['counts'].get('error') or
                                    (args.kind != 'unit' and stats['counts'].get('skipped')))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(dict(kind=args.kind, selected=selected, helpers=helpers,
                                        partial=bool(manifest), source_manifest=str(args.selection) if manifest else None,
                                        source_sha=manifest.get('sha') if manifest else None,
                                        passed=passed, **stats), indent=2), encoding='utf-8')
    print(json.dumps(dict(kind=args.kind, partial=bool(manifest), eligible_classes=len(selected), passed=passed,
                         **compact(stats))))
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
