"""Compare stable CheckRelease results from the CLI and the native Jenkins step."""
import hashlib
import json
from pathlib import Path

root = Path('.comparison')
cli = json.loads((root / 'cli.json').read_text())
native = json.loads((root / 'plugin.json').read_text())
# Object field ordering differs between protojson and JSON-lib; array order is meaningful.
cli_result = cli.get('checkResults')
native_result = native.get('checkResults')
report = {
    'sqlSha256': hashlib.sha256((root / 'V3__add_source_system.sql').read_bytes()).hexdigest(),
    'cliExitCode': int((root / 'cli-exit-code.txt').read_text().strip()),
    'pluginStatus': (root / 'plugin-status.txt').read_text().strip(),
    'bothHaveCheckResults': cli_result is not None and native_result is not None,
    'identicalCheckResults': cli_result is not None and cli_result == native_result,
    'metadataHttpStatus': {key: int((root / f'rpc-{key}-http.txt').read_text().strip())
                           for key in ('identity', 'database', 'project', 'instance')},
}
if (root / 'legacy-preflight-exit-code.txt').exists():
    report['legacyPreflightExitCode'] = int((root / 'legacy-preflight-exit-code.txt').read_text().strip())
for label, result in [('cli', cli_result), ('plugin', native_result)]:
    counts = {'ERROR': 0, 'WARNING': 0}
    for item in (result or {}).get('results', []):
        for advice in item.get('advices', []):
            if advice.get('status') in counts:
                counts[advice['status']] += 1
    report[label + 'Advices'] = counts
(root / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
if report['cliExitCode'] != 0 or report['pluginStatus'] != 'SUCCESS' or not report['identicalCheckResults']:
    raise SystemExit('CLI/plugin parity was not established; inspect the archived responses')
