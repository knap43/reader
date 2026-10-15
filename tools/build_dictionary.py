#!/usr/bin/env python3
"""Builds the bundled English dictionary (app/src/main/assets/dictionary/en.db) from WordNet 3.0.

Usage: python3 tools/build_dictionary.py <path to the unpacked WordNet "dict" folder>

The WordNet database files (data.*, index.*, *.exc) are the ones shipped by Princeton or by
NLTK's wordnet corpus. Bump DB_VERSION in Dictionary.kt whenever the output changes, so installed
copies are replaced.
"""
import os
import re
import sqlite3
import sys

POS = {"noun": "n", "verb": "v", "adj": "a", "adv": "r"}
MARKER = re.compile(r"\((?:a|p|ip)\)$")
EXAMPLE = re.compile(r'"([^"]+)"')


def clean(lemma: str) -> str:
    return MARKER.sub("", lemma).replace("_", " ")


def main(src: str, out: str) -> None:
    if os.path.exists(out):
        os.remove(out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    db = sqlite3.connect(out)
    db.executescript(
        """
        PRAGMA page_size = 4096;
        CREATE TABLE synset (id INTEGER PRIMARY KEY, def TEXT NOT NULL, ex TEXT, syn TEXT);
        CREATE TABLE sense (
            word TEXT NOT NULL, pos TEXT NOT NULL, rank INTEGER NOT NULL, tags INTEGER NOT NULL,
            synset INTEGER NOT NULL, PRIMARY KEY (word, pos, rank)
        ) WITHOUT ROWID;
        CREATE TABLE exc (form TEXT NOT NULL, pos TEXT NOT NULL, base TEXT NOT NULL,
            PRIMARY KEY (form, pos, base)) WITHOUT ROWID;
        """
    )

    for i, (name, pos) in enumerate(POS.items()):
        base = (i + 1) * 100_000_000
        rows = []
        with open(os.path.join(src, f"data.{name}"), encoding="latin-1") as f:
            for line in f:
                if line.startswith("  "):
                    continue  # license header
                head, _, gloss = line.partition(" | ")
                parts = head.split()
                offset = int(parts[0])
                count = int(parts[3], 16)
                words = [clean(parts[4 + 2 * k]) for k in range(count)]
                gloss = gloss.strip()
                cut = gloss.find('; "')
                definition = (gloss[:cut] if cut >= 0 else gloss).strip().rstrip(";").strip()
                examples = EXAMPLE.findall(gloss[cut:]) if cut >= 0 else []
                rows.append((
                    base + offset,
                    definition,
                    "\n".join(e.strip() for e in examples) or None,
                    "\n".join(dict.fromkeys(words)),
                ))
        db.executemany("INSERT INTO synset VALUES (?, ?, ?, ?)", rows)

        senses = []
        with open(os.path.join(src, f"index.{name}"), encoding="latin-1") as f:
            for line in f:
                if line.startswith("  "):
                    continue
                parts = line.split()
                word = clean(parts[0])
                synsets = int(parts[2])
                pointers = int(parts[3])
                tags = int(parts[5 + pointers])
                offsets = parts[6 + pointers: 6 + pointers + synsets]
                for rank, off in enumerate(offsets):
                    senses.append((word, pos, rank, tags, base + int(off)))
        db.executemany("INSERT OR IGNORE INTO sense VALUES (?, ?, ?, ?, ?)", senses)

        exc = []
        with open(os.path.join(src, f"{name}.exc"), encoding="latin-1") as f:
            for line in f:
                form, *bases = line.split()
                exc += [(clean(form), pos, clean(b)) for b in bases]
        db.executemany("INSERT OR IGNORE INTO exc VALUES (?, ?, ?)", exc)

    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"{out}: {os.path.getsize(out) / 1e6:.1f} MB")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    main(sys.argv[1], os.path.join(root, "app/src/main/assets/dictionary/en.db"))
