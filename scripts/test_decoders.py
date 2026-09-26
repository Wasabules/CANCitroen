#!/usr/bin/env python3
"""Vérifie les décodeurs de bridge.py sur les trames de référence partagées
avec l'app Android (fixtures/can_decode_cases.tsv).

Usage :
    python3 -m unittest scripts/test_decoders.py      # depuis la racine du projet
    ./scripts/test_decoders.py
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bridge  # noqa: E402

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures" / "can_decode_cases.tsv"


def load_cases():
    for n, line in enumerate(FIXTURES.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        can_id, data, _kotlin, py_key, expected, source = line.split("\t")
        if py_key != "-":
            yield n, can_id, bytes.fromhex(data), py_key, expected, source


def parse_expected(raw: str):
    if raw == "null":
        return None
    if raw in ("true", "false"):
        return raw == "true"
    try:
        return float(raw)
    except ValueError:
        return raw


class DecoderFixtures(unittest.TestCase):
    def test_fixtures(self):
        cases = list(load_cases())
        self.assertTrue(cases, "aucune trame de référence chargée")
        for n, can_id, data, key, raw_expected, source in cases:
            with self.subTest(ligne=n, id=can_id, champ=key):
                getattr(bridge, f"decode_{can_id}")(data)
                value = bridge.STATE
                for part in key.split("."):
                    value = value[part]
                expected = parse_expected(raw_expected)
                if isinstance(expected, float) and not isinstance(value, bool):
                    self.assertIsNotNone(value, source)
                    self.assertAlmostEqual(float(value), expected, places=6, msg=source)
                else:
                    self.assertEqual(value, expected, source)


if __name__ == "__main__":
    unittest.main()
