#!/usr/bin/env python3
"""
analyze.py — Parse an Android btsnoop_hci.log and extract RFCOMM payloads
sent to/from Realme Buds T310.

Usage:
    python3 analyze.py btsnoop_hci.log [--mac 88:0E:85:C5:FA:87]

The script prints every RFCOMM data frame exchanged with the earbuds,
grouped by direction. Look for 3 payloads that differ by one byte — those
are the ANC mode commands. Copy them into realme_anc.py.
"""

import sys
import struct
import argparse
from pathlib import Path

BTSNOOP_MAGIC = b"btsnoop\x00"
HCI_COMMAND   = 0x01
HCI_ACL       = 0x02
HCI_SCO       = 0x03
HCI_EVENT     = 0x04

# btsnoop packet flags
FLAG_RECV = 0x01   # 0 = host→controller (sent), 1 = controller→host (received)
FLAG_CMD  = 0x02   # 0 = data, 1 = command/event


def parse_btsnoop(path: Path):
    """Yield (timestamp_us, flags, data) for every packet in the file."""
    raw = path.read_bytes()
    if not raw.startswith(BTSNOOP_MAGIC):
        raise ValueError("Not a btsnoop file")

    # 8 magic + 4 version + 4 datalink
    offset = 16
    while offset + 24 <= len(raw):
        orig_len, incl_len, flags, drops = struct.unpack_from(">IIII", raw, offset)
        ts_high, ts_low = struct.unpack_from(">II", raw, offset + 16)
        timestamp_us = (ts_high << 32 | ts_low) - 0x00E03AB44A676000  # epoch offset
        offset += 24
        data = raw[offset: offset + incl_len]
        offset += incl_len
        yield timestamp_us, flags, data


def mac_bytes(mac_str: str) -> bytes:
    """'AA:BB:CC:DD:EE:FF' → b'\\xaa\\xbb\\xcc\\xdd\\xee\\xff'"""
    return bytes(int(x, 16) for x in mac_str.split(":"))


def mac_reversed(mac_str: str) -> bytes:
    """BT address is stored little-endian in HCI packets."""
    return mac_bytes(mac_str)[::-1]


def extract_rfcomm_payloads(path: Path, target_mac: str):
    """
    Walk every HCI ACL packet in the btsnoop file.
    Filter for ACL connections that carry L2CAP RFCOMM traffic to/from target_mac.
    Print every RFCOMM UIH frame (actual data payload).
    """
    target_le = mac_reversed(target_mac)  # little-endian as in HCI

    # We need to track: connection_handle → MAC (from HCI Connection Complete events)
    handle_to_mac: dict[int, str] = {}

    packets = list(parse_btsnoop(path))
    print(f"Loaded {len(packets)} HCI packets from {path.name}\n")

    # Pass 1: build handle→MAC map from HCI Connection Complete events (0x03)
    for ts, flags, data in packets:
        if not data:
            continue
        pkt_type = data[0]
        if pkt_type == HCI_EVENT and len(data) >= 3:
            evt_code = data[1]
            if evt_code == 0x03 and len(data) >= 14:  # Connection Complete
                status = data[3]
                handle = struct.unpack_from("<H", data, 4)[0] & 0x0FFF
                addr   = data[6:12]  # 6-byte BD_ADDR little-endian
                addr_str = ":".join(f"{b:02X}" for b in reversed(addr))
                if status == 0:
                    handle_to_mac[handle] = addr_str

    if not handle_to_mac:
        print("WARNING: No HCI Connection Complete events found.")
        print("  The capture may not include the initial connection.")
        print("  Try re-capturing with Bluetooth toggled off then on.\n")
    else:
        for h, m in handle_to_mac.items():
            marker = " ← TARGET" if m.upper() == target_mac.upper() else ""
            print(f"  Handle 0x{h:04X} → {m}{marker}")
        print()

    target_handles = {
        h for h, m in handle_to_mac.items()
        if m.upper() == target_mac.upper()
    }

    # Pass 2: extract L2CAP/RFCOMM payload from ACL packets on target handles
    rfcomm_frames = []
    for ts, flags, data in packets:
        if not data or data[0] != HCI_ACL:
            continue
        if len(data) < 5:
            continue

        handle_flags = struct.unpack_from("<H", data, 1)[0]
        handle = handle_flags & 0x0FFF

        if target_handles and handle not in target_handles:
            continue

        # If we have no handle map, show all ACL that contain RFCOMM-like data
        direction = "HOST→CTRL" if (flags & FLAG_RECV) == 0 else "CTRL→HOST"
        sent_by_app = (flags & FLAG_RECV) == 0  # app sent = host→controller

        acl_payload = data[5:]   # skip HCI ACL header (4 bytes) + packet_type (1)
        if len(acl_payload) < 8:
            continue

        # L2CAP header: 2-byte length, 2-byte CID
        l2cap_len = struct.unpack_from("<H", acl_payload, 0)[0]
        l2cap_cid = struct.unpack_from("<H", acl_payload, 2)[0]
        l2cap_payload = acl_payload[4: 4 + l2cap_len]

        # RFCOMM uses CID >= 0x0040 (dynamically assigned)
        # CID 0x0001 = signaling, skip it
        if l2cap_cid < 0x0040 or len(l2cap_payload) < 4:
            continue

        # RFCOMM frame: address(1), control(1), length(1 or 2), [data], fcs(1)
        rfcomm_addr    = l2cap_payload[0]
        rfcomm_ctrl    = l2cap_payload[1]
        rfcomm_dlci    = (rfcomm_addr >> 2) & 0x3F

        # UIH frame (control = 0xEF or 0xFF) carries actual data
        is_uih = (rfcomm_ctrl & 0xEF) == 0xEF
        if not is_uih:
            continue

        # Length field: EA bit in bit0 of byte 2
        length_byte = l2cap_payload[2]
        if length_byte & 0x01:  # EA=1, single byte length
            rfcomm_data_offset = 3
            rfcomm_data_len    = length_byte >> 1
        else:                   # EA=0, two-byte length
            rfcomm_data_offset = 4
            rfcomm_data_len    = ((l2cap_payload[3] << 7) | (length_byte >> 1))

        rfcomm_data = l2cap_payload[rfcomm_data_offset: rfcomm_data_offset + rfcomm_data_len]

        if rfcomm_data:
            rfcomm_frames.append((ts, direction, sent_by_app, rfcomm_dlci, rfcomm_data))

    if not rfcomm_frames:
        print("No RFCOMM UIH frames found for the target MAC.")
        print("Possible reasons:")
        print("  1. Capture didn't include the RFCOMM session (toggle BT off/on before capture)")
        print("  2. Wrong MAC address (check with: bluetoothctl devices)")
        print("  3. The app uses BLE GATT instead of RFCOMM")
        print()
        _dump_all_acl(packets)
        return

    print(f"Found {len(rfcomm_frames)} RFCOMM data frames:\n")
    print(f"{'#':>4}  {'Direction':12}  {'DLCI':>4}  {'Hex payload'}")
    print("-" * 80)

    # Group consecutive identical payloads
    prev_hex = None
    for i, (ts, direction, sent_by_app, dlci, payload) in enumerate(rfcomm_frames):
        hex_str = payload.hex()
        marker = " ← SENT BY APP" if sent_by_app else ""
        if hex_str != prev_hex:
            print(f"{i:>4}  {direction:12}  {dlci:>4}  {hex_str}{marker}")
        prev_hex = hex_str

    print()
    _suggest_mode_bytes(rfcomm_frames)


def _suggest_mode_bytes(frames):
    """
    Look for triplets of app-sent frames that share a common prefix/suffix
    but differ by exactly one byte — that one byte is likely the mode value.
    """
    sent = [payload for (_, _, sent_by_app, _, payload) in frames if sent_by_app]
    if len(sent) < 3:
        return

    # Find frames of the same length that differ in exactly 1 byte position
    candidates = {}
    for p in sent:
        key = len(p)
        candidates.setdefault(key, []).append(p)

    for length, group in candidates.items():
        if len(group) < 2:
            continue
        # Find byte positions that vary
        varying = [i for i in range(length)
                   if len({p[i] for p in group}) > 1]
        fixed   = [i for i in range(length)
                   if len({p[i] for p in group}) == 1]
        if len(varying) <= 2 and len(fixed) >= 3:
            print(f"Likely ANC command pattern (length={length}):")
            print(f"  Varying byte position(s): {varying}")
            for p in group:
                vals = " ".join(f"{p[i]:02x}[{p[i]}]" if i in varying
                                else f"{p[i]:02x}"
                                for i in range(length))
                print(f"  {vals}")
            print()


def _dump_all_acl(packets):
    """Fallback: dump all ACL payloads so the user can inspect manually."""
    print("Fallback: showing all ACL data payloads (first 20):\n")
    count = 0
    for ts, flags, data in packets:
        if not data or data[0] != HCI_ACL:
            continue
        direction = "→" if (flags & FLAG_RECV) == 0 else "←"
        print(f"  {direction} {data.hex()}")
        count += 1
        if count >= 20:
            break


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Analyze btsnoop_hci.log for Realme Buds T310 ANC commands")
    parser.add_argument("log", type=Path, help="Path to btsnoop_hci.log")
    parser.add_argument("--mac", default="88:0E:85:C5:FA:87",
                        help="Earbud MAC address (default: 88:0E:85:C5:FA:87)")
    args = parser.parse_args()

    if not args.log.exists():
        print(f"File not found: {args.log}")
        sys.exit(1)

    extract_rfcomm_payloads(args.log, args.mac)
