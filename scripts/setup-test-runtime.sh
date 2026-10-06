#!/usr/bin/env bash
set -euo pipefail
runtime="${RUNNER_TEMP:?}/testadmn-sci-runtime"
mkdir -p "$runtime"
for entry in 'kotoba 6ffc07a7006dd0202b0feb0c586d8bd341f086b9' 'org-babashka-nbb 829f0ba11016e925001d80e51f12848c4ad58112' 'text 73bdb13ae7a3d004b44bca08be03a3191157a38f' 'langchain 42ccbf5e17dd7fcfae32c78dcfdcb242ab75e97c'; do
  read -r repo revision <<< "$entry"
  git init -q "$runtime/$repo"
  git -C "$runtime/$repo" fetch --depth 1 "https://github.com/kotoba-lang/$repo.git" "$revision"
  git -C "$runtime/$repo" checkout --detach -q FETCH_HEAD
done
printf '%s\n' '{:paths []}' > "$runtime/empty.edn"
cat > "$runtime/kbb" <<'KBB'
#!/bin/sh
set -eu
runtime="${RUNNER_TEMP:?}/testadmn-sci-runtime"
# Dependencies are explicit immutable classpath entries; do not inherit local stdlib checkouts.
export KBB_NO_STDLIB=1
export KBB_ENGINE="$runtime/org-babashka-nbb/cli.js"
exec sh "$runtime/kotoba/bin/kbb" --backend sci --config "$runtime/empty.edn" --classpath "$PWD/scripts:$PWD/.test-tree:$runtime/langchain/src:$runtime/text/src" "$@"
KBB
chmod +x "$runtime/kbb"
echo "$runtime" >> "${GITHUB_PATH:?}"
