#!/usr/bin/env bash
# Package a locally built dist/pages (after pack-cloudflare-assets.py) as a GitHub Release asset,
# then pin its tag and SHA-256 in web/site-release.json. Commit that file to let Cloudflare deploy it.
set -euo pipefail

cd "$(dirname "$0")/.."
test -f dist/pages/index.html || { echo "dist/pages is missing; run web/build-site.sh first" >&2; exit 1; }
test -z "$(git status --porcelain -- web 2>/dev/null)" || {
    echo "Commit web/ changes before publishing so the release matches a commit" >&2
    exit 1
}

commit=$(git rev-parse HEAD)
short=$(git rev-parse --short HEAD)
tag="site-$(date -u +%Y%m%d%H%M)-$short"
out=artifacts/release
mkdir -p "$out"
asset="$out/zerocon-site.tar.gz"
tar -czf "$asset" -C dist/pages .
sha=$(sha256sum "$asset" | cut -d' ' -f1)
bytes=$(stat -c %s "$asset")

# --latest=false keeps the app/model "latest" release lookups unchanged.
gh release create "$tag" "$asset" --latest=false --target "$commit" \
    --title "$tag" \
    --notes "Locally built ZeroConvert IME web site for Cloudflare Pages (commit $commit)."

node -e '
const fs = require("node:fs");
const [tag, sha, bytes, commit] = process.argv.slice(1);
const pin = JSON.parse(fs.readFileSync("web/site-release.json", "utf8"));
Object.assign(pin, { tag, sha256: sha, bytes: Number(bytes), commit });
fs.writeFileSync("web/site-release.json", JSON.stringify(pin, null, 2) + "\n");
' "$tag" "$sha" "$bytes" "$commit"

rm -f "$asset"
echo "Pinned $tag ($bytes bytes, sha256 $sha) in web/site-release.json"
echo "Next: commit web/site-release.json and push to main."
