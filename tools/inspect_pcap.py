"""Print DLNA HTTP/SSDP payloads from a pktmon pcapng capture."""

from __future__ import annotations

import struct
import sys


def blocks(data: bytes):
    offset = 0
    while offset + 12 <= len(data):
        block_type, length = struct.unpack_from("<II", data, offset)
        if length < 12 or offset + length > len(data):
            break
        yield block_type, data[offset + 8 : offset + length - 4]
        offset += length


def packet_payload(packet: bytes):
    # Ethernet II; handle optional one or two VLAN tags.
    if len(packet) < 14:
        return None
    ethertype = struct.unpack_from("!H", packet, 12)[0]
    pos = 14
    while ethertype in (0x8100, 0x88A8) and len(packet) >= pos + 4:
        ethertype = struct.unpack_from("!H", packet, pos + 2)[0]
        pos += 4
    if ethertype != 0x0800 or len(packet) < pos + 20:
        return None
    ihl = (packet[pos] & 0x0F) * 4
    if len(packet) < pos + ihl:
        return None
    src = ".".join(str(x) for x in packet[pos + 12 : pos + 16])
    dst = ".".join(str(x) for x in packet[pos + 16 : pos + 20])
    proto = packet[pos + 9]
    if proto == 17:
        if len(packet) < pos + ihl + 8:
            return None
        sport, dport, length, _ = struct.unpack_from("!HHHH", packet, pos + ihl)
        payload = packet[pos + ihl + 8 : pos + ihl + length]
    elif proto == 6:
        if len(packet) < pos + ihl + 20:
            return None
        sport, dport = struct.unpack_from("!HH", packet, pos + ihl)
        offset = (packet[pos + ihl + 12] >> 4) * 4
        payload = packet[pos + ihl + offset :]
    else:
        return None
    if not payload:
        return None
    if sport not in (8765, 1900) and dport not in (8765, 1900):
        return None
    return src, sport, dst, dport, payload


def main(path: str) -> None:
    data = open(path, "rb").read()
    for block_type, body in blocks(data):
        # Enhanced Packet Block.
        if block_type != 0x00000006 or len(body) < 20:
            continue
        captured = struct.unpack_from("<I", body, 12)[0]
        packet = body[20 : 20 + captured]
        parsed = packet_payload(packet)
        if not parsed:
            continue
        src, sport, dst, dport, payload = parsed
        text = payload.decode("utf-8", errors="replace")
        if any(x in text for x in ("GET ", "POST ", "SUBSCRIBE", "NOTIFY", "M-SEARCH", "HTTP/")):
            print(f"{src}:{sport} -> {dst}:{dport} {text[:1200]!r}")


if __name__ == "__main__":
    main(sys.argv[1])
