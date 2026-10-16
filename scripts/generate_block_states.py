#!/usr/bin/env python3
"""Write the block-state table that the registry loader reads.

The input is the blocks.json that the official server writes with --reports.
The output has one line per block state, with four tab-separated columns:

    namespaced key, state ID, name=value pairs, default flag

The pairs are sorted by name and empty for a block without properties. Lines
are grouped by block key, and the states of a block are in ascending ID order.
"""

import argparse
import json
import sys
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT = REPOSITORY / "llama" / "src" / "main" / "resources" / "minecraft" / "26.3" / "blocks.tsv"
FORBIDDEN_IN_VALUES = ("=", ",", "\t", "\n")


def table_rows(blocks):
    rows = []
    owners = {}

    for key in sorted(blocks):
        block = blocks[key]
        if ":" not in key:
            raise ValueError(f"{key}: block key has no namespace")

        allowed = block.get("properties", {})
        names = sorted(allowed)
        defaults = 0

        for state in sorted(block["states"], key=lambda entry: entry["id"]):
            state_id = state["id"]
            if state_id in owners:
                raise ValueError(f"state ID {state_id} belongs to both {owners[state_id]} and {key}")
            owners[state_id] = key

            values = state.get("properties", {})
            if sorted(values) != names:
                raise ValueError(f"{key} state {state_id}: properties {sorted(values)} differ from {names}")

            pairs = []
            for name in names:
                value = values[name]
                if value not in allowed[name]:
                    raise ValueError(f"{key} state {state_id}: {name}={value} is not a declared value")
                if any(character in value or character in name for character in FORBIDDEN_IN_VALUES):
                    raise ValueError(f"{key} state {state_id}: {name}={value} contains a reserved character")
                pairs.append(f"{name}={value}")

            is_default = bool(state.get("default", False))
            defaults += is_default
            default_column = "true" if is_default else "false"
            rows.append(f"{key}\t{state_id}\t{','.join(pairs)}\t{default_column}")

        if defaults != 1:
            raise ValueError(f"{key}: expected exactly one default state, found {defaults}")

    if not rows:
        raise ValueError("blocks.json contains no block states")

    return rows, owners


def main(argv):
    parser = argparse.ArgumentParser(description="Write the 26.3 block-state table from blocks.json.")
    parser.add_argument("blocks_json", type=Path, help="blocks.json written by the official server report")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="target file (default: %(default)s)")
    arguments = parser.parse_args(argv)

    try:
        blocks = json.loads(arguments.blocks_json.read_text(encoding="utf-8"))
        rows, owners = table_rows(blocks)
    except (OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    with open(arguments.output, "w", encoding="utf-8", newline="\n") as output:
        output.write("".join(row + "\n" for row in rows))

    print(f"wrote {len(rows)} states for {len(blocks)} blocks to {arguments.output}")
    print(f"highest state ID {max(owners)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
