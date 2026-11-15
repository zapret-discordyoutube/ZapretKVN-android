#!/usr/bin/env bash
set -euo pipefail

# Offline tests for the pure helpers of update_hysteria_core.py: the hash length a stored patch was written with and the
# comparison that ignores only the `index` hashes (the latent bug of 2026-11-15: a fixed --abbrev and strict hashes made
# the byte-for-byte proof fail at patch 0001/0002, so no Hysteria update could ever run).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

python3 -B - "$SCRIPT_DIR/update_hysteria_core.py" <<'PY'
import importlib.util
import sys

spec = importlib.util.spec_from_file_location("uhc", sys.argv[1])
module = importlib.util.module_from_spec(spec)
sys.modules["uhc"] = module
spec.loader.exec_module(module)

seven = b"diff --git a/x b/x\nindex f1fb0f0..eecee46 100644\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n"
eight = seven.replace(b"f1fb0f0..eecee46", b"f1fb0f01..eecee462")
created = b"diff --git a/n b/n\nnew file mode 100644\nindex 0000000..081720f\n"
assert module.stored_abbrev(seven) == 7
assert module.stored_abbrev(eight) == 8
assert module.stored_abbrev(created) == 7
assert module.stored_abbrev(b"no index lines") == 8

other_hash = seven.replace(b"eecee46", b"35f653f")
assert module.without_index_hashes(seven) == module.without_index_hashes(other_hash)
assert module.without_index_hashes(seven) == module.without_index_hashes(eight)
changed_hunk = seven.replace(b"+b", b"+c")
assert module.without_index_hashes(seven) != module.without_index_hashes(changed_hunk), "a changed hunk must still fail"
changed_mode = seven.replace(b"100644", b"100755")
assert module.without_index_hashes(seven) != module.without_index_hashes(changed_mode), "a changed file mode must still fail"
upgrades = {
    "github.com/apernet/quic-go v0.63.1-0.2026 // indirect": "github.com/apernet/quic-go v0.64.0-0.2027 // indirect",
    "github.com/apernet/hysteria/core/v2 v2.13.0": "github.com/apernet/hysteria/core/v2 v2.14.0",
}
assert module.version_swaps(upgrades) == [
    ("github.com/apernet/quic-go v0.63.1-0.2026", "github.com/apernet/quic-go v0.64.0-0.2027"),
    ("github.com/apernet/hysteria/core/v2 v2.13.0", "github.com/apernet/hysteria/core/v2 v2.14.0"),
]
# A patch that moves a module from the indirect block to the direct one (0006 does it for quic-go) still gets the new version.
conflict = (
    "<<<<<<< ours\n\tgithub.com/apernet/quic-go v0.64.0-0.2027 // indirect\n=======\n"
    "\tgithub.com/apernet/quic-go v0.63.1-0.2026\n>>>>>>> theirs\n"
)
resolved = module.resolve_go_mod_conflict(conflict, upgrades)
assert resolved == "\tgithub.com/apernet/quic-go v0.64.0-0.2027\n", repr(resolved)
gone = ["-\tgithub.com/cloudflare/circl v1.6.3 // indirect", "-\tgolang.org/x/tools v0.51.0 // indirect"]
assert module.indirect_removals(gone) == ["github.com/cloudflare/circl v1.6.3 // indirect", "golang.org/x/tools v0.51.0 // indirect"]
assert module.indirect_removals([]) is None
assert module.indirect_removals(gone + [""]) == module.indirect_removals(gone), "a blank line is not a change"
assert module.indirect_removals(gone + ["+\tgithub.com/new/module v1.0.0 // indirect"]) is None, "an addition is not mechanical"
assert module.indirect_removals(["-\tgithub.com/direct/module v1.0.0"]) is None, "a direct requirement is not mechanical"
print("update_hysteria_core helpers verified.")
PY
