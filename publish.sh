#!/usr/bin/env bash
# Publishes the daily briefs. Run by the owner, never by an assistant.
#
# The token stays in your environment and never enters a chat, a document or
# a commit. GitHub's secret scanning revokes its own PATs when they appear in
# a public repo, so a committed token would stop working within the minute.
#
#   export FB_GH_TOKEN=github_pat_...
#   ./publish.sh
set -euo pipefail

: "${FB_GH_TOKEN:?set FB_GH_TOKEN first}"
REPO=hschall/fantasy-brief-insights

for L in 1237544639 1325565673; do
  F="daily-$L.json"
  [ -f "$F" ] || { echo "skip $L — no file"; continue; }

  # The gate runs here too. A file that failed earlier cannot sneak through
  # because someone published by hand afterwards.
  if ! python3 validate.py "$F" "league-$L.json"; then
    echo "ABORT $L — validation failed"
    continue
  fi

  SHA=$(curl -s -H "Authorization: Bearer $FB_GH_TOKEN" \
    "https://api.github.com/repos/$REPO/contents/$F" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("sha",""))')

  SHA="$SHA" F="$F" python3 -c '
import base64, json, os
body = {"message": "daily brief", "branch": "main",
        "content": base64.b64encode(open(os.environ["F"], "rb").read()).decode()}
sha = os.environ.get("SHA", "")
if sha:
    body["sha"] = sha          # required to overwrite, omitted on creation
open("/tmp/fb-body.json", "w").write(json.dumps(body))'

  curl -s -X PUT -H "Authorization: Bearer $FB_GH_TOKEN" \
    -d @/tmp/fb-body.json \
    "https://api.github.com/repos/$REPO/contents/$F" \
    | python3 -c '
import json, sys
d = json.load(sys.stdin); c = d.get("content")
print("published", c["name"], c["size"], "bytes") if c else print("FAIL", d.get("message"))'
done
rm -f /tmp/fb-body.json
