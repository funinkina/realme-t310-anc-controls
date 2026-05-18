#!/usr/bin/env python3
"""
sniff.py — Linux-side RFCOMM monitor for Realme Buds T310.

Connects to the earbuds' SPP (RFCOMM channel 1) and records every byte
that flows in/out. Run this while doing mode changes via the Realme Link
app on Android — BUT NOTE: the earbuds can only be connected to one host
at a time, so you'll need the Android HCI snoop approach instead.

This script is useful for:
  1. Verifying connectivity to RFCOMM channel 1
  2. Monitoring once you know the init sequence (sending it first, then
     recording what the earbuds report back)
  3. Long-running ANC state polling once the protocol is known

Usage (verify connectivity):
    python3 sniff.py --probe

Usage (send a specific hex command and record response):
    python3 sniff.py --send "2c dc 09 00 01 00"
"""

import socket
import sys
import time
import argparse

MAC     = "88:0E:85:C5:FA:87"
CHANNEL = 1


def connect() -> socket.socket:
    sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    sock.settimeout(10)
    sock.connect((MAC, CHANNEL))
    return sock


def recv_all(sock: socket.socket, timeout: float = 3.0) -> bytes:
    sock.settimeout(timeout)
    buf = b""
    try:
        while True:
            chunk = sock.recv(512)
            if not chunk:
                break
            buf += chunk
            sock.settimeout(0.5)  # shorter timeout after first bytes arrive
    except socket.timeout:
        pass
    return buf


def probe():
    print(f"Connecting to {MAC} RFCOMM ch{CHANNEL}...")
    try:
        sock = connect()
    except Exception as e:
        print(f"Connection failed: {e}")
        sys.exit(1)

    print("Connected. Listening for 5 seconds (earbuds may send status)...")
    data = recv_all(sock, timeout=5.0)
    if data:
        print(f"Received {len(data)} bytes: {data.hex()}")
    else:
        print("No data received (earbuds wait for host to send first).")
        print("Use --send to send an init/command byte sequence.")
    sock.close()


def send_hex(hex_str: str):
    payload = bytes.fromhex(hex_str.replace(" ", ""))
    print(f"Connecting to {MAC} RFCOMM ch{CHANNEL}...")
    sock = connect()
    print(f"Sending ({len(payload)}B): {payload.hex()}")
    sock.send(payload)
    data = recv_all(sock, timeout=4.0)
    if data:
        print(f"Response ({len(data)}B): {data.hex()}")
    else:
        print("No response.")
    sock.close()


def send_and_monitor(hex_commands: list[str]):
    """Send multiple commands with a delay and print all responses."""
    print(f"Connecting to {MAC} RFCOMM ch{CHANNEL}...")
    sock = connect()
    sock.settimeout(2.0)

    for hex_str in hex_commands:
        payload = bytes.fromhex(hex_str.replace(" ", ""))
        print(f"\nSending: {payload.hex()}")
        sock.send(payload)
        time.sleep(0.3)
        data = recv_all(sock, timeout=2.0)
        if data:
            print(f"Response: {data.hex()}")
        else:
            print("No response.")

    sock.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Realme Buds T310 RFCOMM monitor")
    parser.add_argument("--mac",     default=MAC,     help="Earbud MAC address")
    parser.add_argument("--channel", default=CHANNEL, type=int, help="RFCOMM channel")
    parser.add_argument("--probe",   action="store_true", help="Connect and listen only")
    parser.add_argument("--send",    help="Hex bytes to send, e.g. '2c dc 09 00'")
    args = parser.parse_args()

    MAC     = args.mac
    CHANNEL = args.channel

    if args.probe:
        probe()
    elif args.send:
        send_hex(args.send)
    else:
        parser.print_help()
