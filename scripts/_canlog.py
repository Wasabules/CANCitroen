"""Parser pour les logs candump format `-L` (rejouables avec canplayer).

Format d'une ligne:
    (1700000000.123456) slcan0 1A0#0102030405060708
    (1700000000.123457) slcan0 18FEF100#0102030405060708        # 29-bit ID
    (1700000000.123458) slcan0 1A0#R8                           # remote frame, ignored
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

_LINE = re.compile(
    r"^\((?P<ts>\d+\.\d+)\)\s+(?P<iface>\S+)\s+(?P<id>[0-9A-Fa-f]+)#(?P<data>[0-9A-Fa-f]*)\s*$"
)


@dataclass(frozen=True)
class Frame:
    ts: float
    iface: str
    can_id: int
    extended: bool
    data: bytes

    @property
    def hex_id(self) -> str:
        return f"{self.can_id:08X}" if self.extended else f"{self.can_id:03X}"

    @property
    def hex_data(self) -> str:
        return self.data.hex(" ").upper()


def iter_frames(path: str | Path) -> Iterator[Frame]:
    with open(path, "r") as fh:
        for line in fh:
            m = _LINE.match(line)
            if not m:
                continue
            id_hex = m["id"]
            extended = len(id_hex) > 3
            try:
                can_id = int(id_hex, 16)
                data = bytes.fromhex(m["data"])
            except ValueError:
                continue
            yield Frame(
                ts=float(m["ts"]),
                iface=m["iface"],
                can_id=can_id,
                extended=extended,
                data=data,
            )
