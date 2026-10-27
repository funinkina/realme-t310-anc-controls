# Realme Buds T310 — ANC control without Realme Link

Switch **Normal / Noise Cancellation / Transparent** from Linux, macOS
(CLI + Raycast) or an Android Quick Settings tile, using the earbuds'
reverse-engineered Bluetooth protocol.

## Setup

### 1. Find your MAC address

Pair your earbuds via your system Bluetooth settings, then run:

```bash
bluetoothctl devices                                         # Linux
system_profiler SPBluetoothDataType | grep -A1 -i realme     # macOS
```

Look for your buds in the output:

```
Device 88:0E:85:C5:FA:87 Realme Buds T310
```

To confirm it's connected and showing the right profiles:

```bash
bluetoothctl info <MAC>
```

The output should include `UUID: Serial Port` — that's the SPP channel this tool uses.

### macOS only: install IOBluetooth bindings

macOS Python has no `AF_BLUETOOTH` socket support, so the script talks
RFCOMM through Apple's IOBluetooth framework via PyObjC:

```bash
python3 -m venv .venv
.venv/bin/pip install pyobjc-framework-IOBluetooth
```

Then use `.venv/bin/python` instead of `python3` below. The first run may
trigger a Bluetooth permission prompt for your terminal app — allow it
(System Settings → Privacy & Security → Bluetooth).

### 2. Set your MAC in the script

Open `realme_anc.py` and replace the MAC on line 31:

```python
MAC = "88:0E:85:C5:FA:87"   # ← replace with your device's MAC
```

## Usage

```bash
python3 realme_anc.py normal       # ANC off
python3 realme_anc.py anc          # Noise Cancellation
python3 realme_anc.py transparent  # Transparent mode
```

Optional shell alias:
```bash
alias anc='python3 /path/to/realme_anc.py'
# then: anc anc / anc normal / anc transparent
```

### macOS: Raycast hotkeys

`raycast/` has one script command per mode (they use `.venv` from the macOS
setup above):

1. Raycast → Settings → Extensions → **+** → Add Script Directory → pick `raycast/`
2. Search "Buds:" in Raycast, or give each command a hotkey under
   Settings → Extensions → Realme Buds

A HUD confirms the switch. The first run may ask for Bluetooth permission for
Raycast.

### Android: Quick Settings tile

`android/` is a tiny app (no dependencies) adding a **Buds ANC** tile: tap
cycles Normal → Noise Cancellation → Transparent, long-press opens a Material
You screen with a button per mode and a **Game mode** toggle. The tile goes
unavailable ("Not connected") while the buds are disconnected, and both the tile
and the app read the buds' actual mode when shown, so switching by long-pressing
an earbud is reflected.

GitHub Actions builds it on every push touching `android/`. To install:

```bash
gh run download -n buds-anc-apk        # latest successful build
adb install -r BudsAnc-debug.apk
```

Open **Buds ANC** once to grant the Bluetooth permission, then add the tile
(the app offers the system "add tile" prompt on Android 13+). Builds are signed
with the committed `android/debug.keystore`, so newer builds install as updates.
Realme Link can stay installed; the app works around it holding the channel.
Why this is a tile and not a Settings entry:
[docs/native-anc-research.md](docs/native-anc-research.md).

## Docs

| Doc | What's in it |
| --- | --- |
| [docs/protocol.md](docs/protocol.md) | Frame format and every known command (ANC, mode read, game mode, battery, notifications) |
| [docs/reverse-engineering.md](docs/reverse-engineering.md) | How the protocol was found: SDP, HCI snoop capture, analysis, re-capturing |
| [docs/native-anc-research.md](docs/native-anc-research.md) | Why Android can't show T310 ANC in Settings natively |

## Files

| File | Purpose |
| --- | --- |
| `realme_anc.py` | CLI: switches ANC mode over RFCOMM (Linux sockets / macOS IOBluetooth) |
| `raycast/` | Raycast script commands per mode (macOS) |
| `android/` | Android Quick Settings tile + app (built by GitHub Actions) |
| `analyze.py` | Extracts RFCOMM payloads from a `btsnoop_hci.log` |
| `sniff.py` | Linux RFCOMM probe (connectivity check, manual hex send/recv) |

## Troubleshooting

**Can't connect:** connect the buds first (`bluetoothctl connect <MAC>`, or the
macOS Bluetooth menu) and make sure your terminal has Bluetooth permission.

**Mode doesn't change:** buds in the case refuse ANC changes; put them in. Then
`sniff.py --probe` checks the RFCOMM link and `sniff.py --send <hex>` sends a raw
frame from [docs/protocol.md](docs/protocol.md). If that fails too, the firmware
may have changed; see re-capturing in
[docs/reverse-engineering.md](docs/reverse-engineering.md).
