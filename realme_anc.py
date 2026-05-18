#!/usr/bin/env python3
"""
realme_anc.py — Switch ANC mode on Realme Buds T310 from Linux.

Usage:
    python3 realme_anc.py normal
    python3 realme_anc.py anc
    python3 realme_anc.py transparent

Requires:
    - Earbuds paired and connected (check: bluetoothctl info 88:0E:85:C5:FA:87)
    - Python 3.10+

Protocol (confirmed by HCI snoop analysis):
    Classic BT → RFCOMM channel 1 (SPP) → proprietary 'aa' frame protocol.

    Frame format:
        aa [total_len-2: 2B LE] [flags=00] [cmd: 2B] [seq: 1B] [data_len: 2B LE] [data]

    ANC command: cmd=0x0404, data=[0x01, 0x01, MODE]
        MODE 0x01 = Normal (ANC off)
        MODE 0x02 = Transparent
        MODE 0x08 = Noise Cancellation
"""

import socket
import sys

# ── Device ────────────────────────────────────────────────────────────────────
MAC = "88:0E:85:C5:FA:87"
CHANNEL = 1  # SPP RFCOMM channel (confirmed via SDP query)

# ── Protocol ──────────────────────────────────────────────────────────────────
# Confirmed by btsnoop_hci.log analysis (frames 172, 178, 184 in the capture).
# Sequence byte (position 6) doesn't need to match previous session values.


def _make_cmd(mode_byte: int, seq: int = 0x01) -> bytes:
    """Build a Realme 'aa' ANC mode command frame."""
    data = bytes([0x01, 0x01, mode_byte])
    data_len = len(data)  # 3
    # payload = flags(1) + cmd(2) + seq(1) + data_len_field(2) + data(3) = 9 bytes
    # len_field = payload_size + 1  (protocol quirk confirmed by capture)
    payload_size = 1 + 2 + 1 + 2 + data_len  # = 9
    len_field = payload_size + 1  # = 10 = 0x0a
    return (
        bytes(
            [
                0xAA,
                len_field & 0xFF,
                (len_field >> 8) & 0xFF,
                0x00,  # flags
                0x04,
                0x04,  # command ID
                seq & 0xFF,  # sequence number
                data_len & 0xFF,
                0x00,  # data length LE
            ]
        )
        + data
    )


MODES: dict[str, bytes] = {
    "normal": _make_cmd(0x01),  # ANC off
    "anc": _make_cmd(0x08),  # Noise Cancellation
    "transparent": _make_cmd(0x02),  # Transparent mode
}

TIMEOUT = 5  # seconds


def set_mode(mode: str):
    if mode not in MODES:
        print(f"Unknown mode '{mode}'. Choose from: {', '.join(MODES)}")
        sys.exit(1)

    payload = MODES[mode]
    print(f"Connecting to {MAC} (RFCOMM ch{CHANNEL})...")

    try:
        sock = socket.socket(
            socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM
        )
        sock.settimeout(TIMEOUT)
        sock.connect((MAC, CHANNEL))
    except OSError as e:
        print(f"Connection failed: {e}")
        print("Is the device connected?  Check: bluetoothctl info 88:0E:85:C5:FA:87")
        sys.exit(1)

    print(f"Sending mode={mode!r} ({len(payload)}B): {payload.hex()}")
    sock.sendall(payload)

    # Read optional ACK from earbuds
    sock.settimeout(2.0)
    try:
        ack = sock.recv(64)
        if ack:
            print(f"ACK from earbuds ({len(ack)}B): {ack.hex()}")
    except socket.timeout:
        print(
            "No ACK (earbuds may require full handshake — see README if mode didn't change)"
        )

    sock.close()
    print("Done.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    set_mode(sys.argv[1].lower())
