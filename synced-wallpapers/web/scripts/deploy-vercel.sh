#!/usr/bin/env bash
# Builds the web app and deploys web/dist to the Vercel project "livrun" (production).
# Needs a Vercel token in VERCEL_TOKEN (never commit it). Usage: scripts/deploy-vercel.sh
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERCEL_TOKEN:?set VERCEL_TOKEN}"
npm run build
cp vercel.json dist/vercel.json
python3 - > .deploy.json <<'PY'
import os, json, base64
files = []
for d, _, fs in os.walk("dist"):
    for f in fs:
        p = os.path.join(d, f); rel = os.path.relpath(p, "dist")
        if rel != "_redirects":
            files.append({"file": rel, "data": base64.b64encode(open(p, "rb").read()).decode(), "encoding": "base64"})
print(json.dumps({"name": "livrun", "target": "production", "files": files,
    "projectSettings": {"framework": None, "buildCommand": None, "installCommand": None, "outputDirectory": None}}))
PY
curl -sS -X POST "https://api.vercel.com/v13/deployments?skipAutoDetectionConfirmation=1" \
  -H "Authorization: Bearer $VERCEL_TOKEN" -H "Content-Type: application/json" --data-binary @.deploy.json \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("error") or "Deploying: https://" + d["url"] + "  (production: https://livrun.vercel.app)")'
rm -f .deploy.json
