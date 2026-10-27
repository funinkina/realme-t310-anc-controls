# Realme Buds T310 — ANC Mode Control for Linux & macOS

Switch between **Normal / Noise Cancellation / Transparent** modes directly
from Linux or macOS without the Android app, by reverse-engineering the proprietary
Bluetooth control protocol.

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
If Realme Link is installed it holds the SPP channel; the app retries inside the
gap Realme Link leaves after being bumped, so both can coexist.

Android has no public API for putting earbud ANC into Settings or the volume
panel, and the T310 doesn't implement Fast Pair's ANC extension, so a tile is as
native as it gets. See `docs/native-anc-research.md`.

---

## Device

| Property       | Value                                 |
| -------------- | ------------------------------------- |
| Bluetooth      | Classic BT 5.3 (not BLE)              |
| Protocol       | Serial Port Profile (SPP) over RFCOMM |
| RFCOMM channel | **1**                                 |

---

## How this was reverse-engineered

### Phase 1 — Device inspection

The first step was figuring out what Bluetooth protocol the earbuds use for
their control channel. The earbuds expose several BT profiles; the key
question was whether the control path uses Classic BT (RFCOMM) or BLE GATT.

```bash
bluetoothctl info 88:0E:85:C5:FA:87
```

Output showed these UUIDs:

```
UUID: Vendor specific     (0000079a-d102-11e1-9b23-00025b00a5a5)
UUID: Serial Port         (00001101-0000-1000-8000-00805f9b34fb)  ← SPP
UUID: Audio Sink          (0000110b-0000-1000-8000-00805f9b34fb)  ← A2DP
UUID: A/V Remote Control  (0000110e-0000-1000-8000-00805f9b34fb)
UUID: Handsfree           (0000111e-0000-1000-8000-00805f9b34fb)  ← HFP
UUID: Vendor specific     (df21fe2c-2515-4fdb-8886-f12c4d67927c)
```

The **Serial Port Profile (SPP)** UUID `0x1101` was the strong signal — this
is the standard way earbuds expose a proprietary control channel over Classic
BT RFCOMM.

### Phase 2 — Finding the RFCOMM channel via SDP

`sdptool` wasn't available, so the SDP query was done with a raw Python
L2CAP socket connecting to PSM 1 (the SDP service port). A
`ServiceSearchAttributeRequest` PDU was built manually targeting UUID `0x1101`,
and the response was parsed to find the `ProtocolDescriptorList` attribute.

The SDP response contained:

```
L2CAP (UUID 0x0100) → RFCOMM (UUID 0x0003) → channel 1
```

**RFCOMM channel 1** confirmed. The service name was `"SPP"`.

A Python RFCOMM connection to channel 1 succeeded immediately:
```python
sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
sock.connect(("88:0E:85:C5:FA:87", 1))
```

The earbuds connected but sent no data — they wait for the host to initiate.
Several common earbuds probe sequences were tried blindly (`2cdc...`,
`08ee...`, etc.) with no response, confirming the protocol bytes had to be
captured from the real app.

### Phase 3 — Capturing the traffic from Android

The Realme Link app runs on Android and knows the correct protocol. The
standard approach for capturing BT traffic on Android is the **HCI snoop log**:

1. Enable **Developer Options** (tap Build Number 7×)
2. Enable **Bluetooth HCI Snoop Log**
3. Toggle BT off/on to start a fresh log
4. Open Realme Link, connect T310, cycle through all three ANC modes several
   times
5. Toggle BT off to flush the log file to disk

#### Extracting the log (non-trivial on Android 16 / Pixel 9)

Direct `adb pull` from `/data/misc/bluetooth/logs/` is blocked by SELinux on
modern Android:
```
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log
# → Permission denied
```

`adb bugreport` (the standard workaround) also failed:
```
adb: device failed to take a zipped bugreport: Failed to connect to dumpstatez service
```

The working solution was `adb shell bugreportz -p`, which writes the report
as a zip to the shell user's app-private directory — a path ADB _can_ read:

```bash
# The bugreportz -p command prints the destination path as it runs:
# BEGIN:/data/user_de/0/com.android.shell/files/bugreports/bugreport-<build>-<timestamp>.zip

adb pull /data/user_de/0/com.android.shell/files/bugreports/bugreport-tokay-....zip bugreport.zip
```

The bugreport zip contained the snoop log at:
```
FS/data/misc/bluetooth/logs/btsnoop_hci.log   (current session, 174 KB)
FS/data/misc/bluetooth/logs/btsnoop_hci.log.last
```

### Phase 4 — Protocol analysis

`analyze.py` parsed the btsnoop file (btsnoop file format: 8-byte magic +
version + datalink type header, followed by packet records each with a 24-byte
header containing length, flags, dropped count, and a 64-bit timestamp).

The file contained **2706 HCI packets**. The analysis pipeline:

1. **Pass 1 — build handle→MAC map** from `HCI Connection Complete` events
   (event code `0x03`), mapping the 12-bit connection handle to the earbud's
   BD_ADDR. Handle `0x000B` → `88:0E:85:C5:FA:87`.

2. **Pass 2 — extract RFCOMM UIH frames** from HCI ACL packets on that handle.
   ACL packets carry L2CAP frames (CID ≥ `0x0040` for dynamically allocated
   channels). Inside L2CAP, the RFCOMM multiplexer (TS 07.10) runs multiple
   virtual channels (DLCIs) over the single RFCOMM channel 1 connection:

   | DLCI | Purpose                                                     |
   | ---- | ----------------------------------------------------------- |
   | 0    | RFCOMM multiplexer control (SABM/UA/DM frames)              |
   | 2    | **Proprietary Realme `aa`-frame control protocol**          |
   | 4    | HFP AT command channel (`AT+BRSF`, `AT+BAC`, `+CIEV`, etc.) |
   | 10   | Secondary channel (version/identity exchange)               |

   The RFCOMM UIH frame (control byte `0xEF` / `0xFF`) carries actual data.
   Some frames had a leading credit byte (RFCOMM UIH+credit, used for flow
   control), which was accounted for in parsing.

#### The `aa`-frame protocol (DLCI 2)

All proprietary control frames start with `0xaa` and follow this structure:

```
┌──────┬───────────────┬───────┬─────────┬──────┬──────────────┬──────────────────┐
│  aa  │  len_field    │ flags │   cmd   │ seq  │   data_len   │      data        │
│ 1 B  │   2 B (LE)    │  1 B  │  2 B    │  1 B │   2 B (LE)   │  data_len bytes  │
└──────┴───────────────┴───────┴─────────┴──────┴──────────────┴──────────────────┘
```

- **`len_field`** (little-endian uint16): equals `total_frame_bytes − 2`.
  Empirically: `len_field = payload_size + 1` where payload_size is the byte
  count of everything after the 3-byte header (`aa` + 2 len bytes).
- **`flags`**: always `0x00` for host-sent frames.
- **`cmd`**: 2-byte command identifier. For responses from earbuds, the high
  byte has bit 7 set (e.g. request `04 04` → response `04 84`).
- **`seq`**: 1-byte sequence number, used to match responses to requests.
  Increments monotonically within a session. The earbuds echo it back in ACKs.
  Starting with `0x01` in a fresh connection works fine.
- **`data_len`**: little-endian uint16 byte count of the data field.

#### HFP channel (DLCI 4)

The DLCI 4 frames are standard HFP negotiation — the earbuds act as a
Hands-Free Unit (HF) and send AT commands to the Android phone acting as
Audio Gateway (AG):

```
AT+BRSF=703    (Bluetooth Retrieve Supported Features)
AT+BAC=1,2     (Bluetooth Available Codecs: CVSD, mSBC)
AT+CIND=?      (indicator capability query)
AT+CIND?       (indicator value query)
AT+CMER=3,0,0,1 (event reporting)
```

Android responds with `+CIEV` indicator events (call state, battery, etc.).
These frames are for audio/call functionality and are unrelated to ANC.

#### Identifying the ANC mode command

After the session handshake (frames 75–170, which synchronise device state via
a series of `cmd=0x01XX` attribute queries), the ANC mode switch commands
appeared starting at frame 172. The pattern was unmistakable — nine frames
with identical structure differing only in the last byte, cycling through
exactly three values each time a mode was switched:

```
Frame 172 (NC):    aa 0a 00 | 00 04 04 30 03 00 01 01 02
Frame 178 (Normal):aa 0a 00 | 00 04 04 33 03 00 01 01 01
Frame 184 (Transp):aa 0a 00 | 00 04 04 36 03 00 01 01 08
Frame 190 (NC):    aa 0a 00 | 00 04 04 39 03 00 01 01 02
Frame 196 (Transp):aa 0a 00 | 00 04 04 3c 03 00 01 01 08
Frame 220 (Normal):aa 0a 00 | 00 04 04 48 03 00 01 01 01
```

Parsed:
- `cmd` = `04 04` — ANC mode set command
- `seq` = `30`, `33`, `36`… (incrementing by 3; each mode switch sends 3
  sequential frames: the mode command + two per-earbud state queries)
- `data_len` = `03 00` = 3 bytes
- `data` = `01 01 [MODE]`

**MODE values:**
| Value  | Mode               |
| ------ | ------------------ |
| `0x01` | Normal (ANC off)   |
| `0x02` | Transparent        |
| `0x08` | Noise Cancellation |

Each command was immediately ACK'd by the earbuds:
```
aa 08 00 | 00 04 84 [seq] 01 00 [00]
```
`04 84` = `04 04` with bit 7 set on the high byte = success response.
Status byte `0x00` = OK.

### Phase 5 — Verification

A direct Python RFCOMM connection to channel 1 with a fresh sequence number
(`seq=0x01`) proved the session handshake is not required for mode commands —
the earbuds accept the ANC command standalone:

```python
sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
sock.connect(("88:0E:85:C5:FA:87", 1))
sock.sendall(bytes.fromhex("aa0a00000404010300010102"))  # ANC on
ack = sock.recv(64)
# → aa080000048401010000  (ACK, status=0x00)
```

All three modes tested and confirmed working.

---

## Protocol reference

### Frame structure

```
aa [len: 2B LE] [00] [cmd: 2B] [seq: 1B] [data_len: 2B LE] [data: N bytes]
```

`len` = total frame size − 2 = `7 + N` (where N = data byte count).

### ANC mode command

```
cmd      = 0x04 0x04
data     = 0x01 0x01 [MODE]
data_len = 0x03 0x00
```

Complete frames (seq=0x01):

| Mode        | Hex                        |
| ----------- | -------------------------- |
| Normal      | `aa0a00000404010300010101` |
| Transparent | `aa0a00000404010300010102` |
| ANC         | `aa0a00000404010300010108` |

### ACK structure (from earbuds)

```
aa 08 00 00 04 84 [seq] 01 00 [status]
```

`status = 0x00` means success. The `seq` byte mirrors the request.

### More commands (from a second Realme Link capture)

The first `cmd` byte is the command, the second is the type: `01` read,
`04` write, `02` notification; replies set bit 7 (`81`, `84`, `82`). Replies
start with a status byte (`00` = OK). Unsolicited frames from the earbuds have
flags `08 00` instead of `00 00`, so a reader must skip them while waiting for
its reply.

| What                 | Request (`cmd type` / data)  | Reply data                 |
| -------------------- | ---------------------------- | -------------------------- |
| Read ANC mode        | `0c 01` / `01 01`            | `00 01 01 [MODE]`          |
| Read feature         | `0d 01` / `01 [id]`          | `00 01 [id] [0/1]`         |
| Set feature          | `03 04` / `[id] [0/1]`       | `00`                       |
| Battery              | `06 01` / –                  | `00 03 02 [R] 01 [L] 03 [case]` |
| Firmware             | `05 01` / –                  | ASCII, e.g. `1,2,1.1.0.45,2,2,1.1.0.45` |

Feature `0x06` is game mode (low latency). Notifications (`04 02`, flags
`08 00`): `03 01 [MODE]` when the mode changes on the buds (long-press),
`01 03 ...` battery, `06 ...` connected hosts by name.

---

## Files

| File                   | Purpose                                                            |
| ---------------------- | ------------------------------------------------------------------ |
| `realme_anc.py`        | CLI tool — switches ANC mode via RFCOMM (Linux sockets / macOS IOBluetooth) |
| `raycast/`             | Raycast script commands for each mode (macOS)                      |
| `android/`             | Android Quick Settings tile app (built by GitHub Actions)          |
| `docs/native-anc-research.md` | Why Android can't show T310 ANC natively (APIs, OEMs, Fast Pair test) |
| `analyze.py`           | Parses a btsnoop_hci.log and extracts/decodes RFCOMM UIH payloads  |
| `sniff.py`             | Linux-side RFCOMM probe (connectivity check, manual hex send/recv) |
| `btsnoop_hci.log`      | Captured Android HCI log used for analysis                         |
| `btsnoop_hci.log.last` | Previous BT session log (from before the capture session)          |
| `bugreport.zip`        | Android bugreport used to extract the snoop log                    |

---

## Troubleshooting

**`realme_anc.py` can't connect**
```bash
bluetoothctl connect 88:0E:85:C5:FA:87
```
On macOS, connect the buds from the Bluetooth menu, and check your terminal
has Bluetooth permission. `sniff.py` is Linux-only.

**Mode doesn't change (but no error)**  
Try running `sniff.py --probe` to confirm the RFCOMM connection is live, then
manually send the raw bytes with `sniff.py --send "aa0a00000404010300010108"`.
If that also fails, the earbuds firmware may have changed and a new capture
will be needed.

**Re-capturing on a different Android device**  
If `adb pull /data/misc/bluetooth/logs/btsnoop_hci.log` is denied, use:
```bash
adb shell bugreportz -p
# note the BEGIN: path printed, then:
adb pull /data/user_de/0/com.android.shell/files/bugreports/<filename>.zip bugreport.zip
unzip bugreport.zip "FS/data/misc/bluetooth/logs/btsnoop_hci.log"
python3 analyze.py btsnoop_hci.log
```
