#!/usr/bin/env bash
# Runs the official 26.3 data generator and writes its reports to a directory.
#
# Usage: scripts/generate_reports.sh OUTPUT_DIR
#        LLAMA_REPORTS_DIR=/some/dir scripts/generate_reports.sh
#
# The server jar is downloaded from piston-meta into a temporary directory, checked against the
# SHA-1 in the version metadata, and removed on exit. No jar is kept, and nothing is written into
# the repository except what OUTPUT_DIR points at. The block table is then produced from
# OUTPUT_DIR/reports/blocks.json by scripts/generate_block_states.py.

set -euo pipefail

VERSION_ID="26.3"
MANIFEST_URL="https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

output_arg="${1:-${LLAMA_REPORTS_DIR:-}}"
if [ -z "$output_arg" ]; then
    echo "usage: $0 OUTPUT_DIR   (or set LLAMA_REPORTS_DIR)" >&2
    exit 2
fi

mkdir -p "$output_arg"
output="$(cd "$output_arg" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

curl -fsSL "$MANIFEST_URL" -o "$work/manifest.json"
version_url="$(python3 -I -c "
import json, sys
versions = [entry for entry in json.load(open(sys.argv[1], encoding='utf-8'))['versions'] if entry['id'] == sys.argv[2]]
if len(versions) != 1:
    sys.exit('version ' + sys.argv[2] + ' is not in the manifest')
print(versions[0]['url'])
" "$work/manifest.json" "$VERSION_ID")"

curl -fsSL "$version_url" -o "$work/version.json"
read -r server_url server_sha1 <<< "$(python3 -I -c "
import json, sys
server = json.load(open(sys.argv[1], encoding='utf-8'))['downloads']['server']
print(server['url'], server['sha1'])
" "$work/version.json")"

curl -fsSL "$server_url" -o "$work/server.jar"
echo "$server_sha1  $work/server.jar" | sha1sum -c --quiet -

(cd "$work" && java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --output "$output")
echo "reports written to $output/reports"
