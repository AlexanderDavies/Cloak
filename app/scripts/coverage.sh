#!/usr/bin/env bash
# Runs the Cloak test suite with coverage and fails if line coverage on
# meaningful code is below the threshold. Only the app's own sources under Cloak/ count;
# third-party packages (AppAuth, …) and pure views / app entry / generated code are excluded
# (root CLAUDE.md > quality gates; guide §14.5).
set -euo pipefail

export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
THRESHOLD=${COVERAGE_THRESHOLD:-90}
SIM=${SIM:-iPhone 17}
# Excluded from the denominator (not meaningful unit-test surface, guide §14.5):
#  - pure SwiftUI views (*View.swift), the app entry, generated code;
#  - platform-edge adapters that only delegate to Apple/third-party SDKs and are verified by manual
#    E2E, not units — the real WebSocket transport and the AppAuth client. (The iOS app can't
#    integration-test these the way the server tests adapters on Testcontainers.)
# The transport's *pure* logic (envelope encode/decode) lives on MessageEnvelope, NOT in the excluded
# adapter, so it IS counted; its error-surfacing contract is covered via the mock at the view-model
# level. Only the URLSession plumbing (connect/send/receiveLoop) stays excluded here.
EXCLUDE_REGEX='(View\.swift|CloakApp\.swift|WebSocketMessageTransport\.swift|AuthService\.swift|URLSessionHTTPRunner\.swift|\.generated\.swift)$'
DERIVED=build/derived
RESULT="$DERIVED/TestResults.xcresult"

# Boot the simulator under Xcode's toolchain before testing. If the system active developer dir is
# Command Line Tools (xcode-select -p), xcodebuild's auto-boot launch helper resolves `simctl`
# against it and fails preflight ("Application failed preflight checks"); pre-booting avoids that.
xcrun simctl boot "$SIM" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$SIM" -b >/dev/null 2>&1 || true

rm -rf "$RESULT"   # deterministic, single result bundle
xcodebuild test \
  -workspace Cloak.xcworkspace \
  -scheme Cloak \
  -destination "platform=iOS Simulator,name=$SIM" \
  -derivedDataPath "$DERIVED" \
  -resultBundlePath "$RESULT" \
  -enableCodeCoverage YES \
  -quiet

REPORT_FILE="$DERIVED/coverage.json"
xcrun xccov view --report --json "$RESULT" > "$REPORT_FILE"

# Count only the app's own sources (path under /app/Cloak/), excluding third-party package
# checkouts and the view/app-entry/generated files matched by EXCLUDE_REGEX.
python3 - "$REPORT_FILE" "$EXCLUDE_REGEX" "$THRESHOLD" <<'PY'
import json, re, sys
report = json.load(open(sys.argv[1])); pattern = re.compile(sys.argv[2]); threshold = float(sys.argv[3])
covered = executable = 0
counted = []
for target in report.get("targets", []):
    for f in target.get("files", []):
        path = f.get("path", "")
        if "/app/Cloak/" not in path:        # app target sources only (not SourcePackages/checkouts)
            continue
        if pattern.search(f["name"]):         # exclude views / app entry / generated
            continue
        covered += f["coveredLines"]; executable += f["executableLines"]
        counted.append(f'{f["name"]}: {f["coveredLines"]}/{f["executableLines"]}')
pct = (covered / executable * 100) if executable else 100.0
print("Counted files:"); [print("  " + c) for c in counted]
print(f"Coverage (meaningful files): {pct:.1f}% ({covered}/{executable})")
if pct < threshold:
    print(f"FAIL: below {threshold}%"); sys.exit(1)
print("PASS")
PY
