"""Compare an original Maven gate's fresh XML reports with current eligible sources."""
import argparse
import json
from pathlib import Path
import sys

from progressive import ROOT, inventory, reconcile, validate_maven


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=['unit', 'mysql', 'postgres'], required=True)
    parser.add_argument('--reports', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    validate_maven(ROOT)
    rows, helpers = inventory(ROOT)
    selected = [dict(r, selector=r['name']) for r in rows if r['kind'] == args.kind]
    stats = reconcile(selected, args.reports)
    passed = bool(selected) and not (stats['not_executed'] or stats['unexpected_classes'] or
                                    stats['counts'].get('failure') or stats['counts'].get('error') or
                                    (args.kind != 'unit' and stats['counts'].get('skipped')))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(dict(kind=args.kind, selected=selected, helpers=helpers,
                                        passed=passed, **stats), indent=2), encoding='utf-8')
    print(json.dumps(dict(kind=args.kind, eligible_classes=len(selected), passed=passed,
                         **{k: v for k, v in stats.items() if k != 'cases'})))
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
