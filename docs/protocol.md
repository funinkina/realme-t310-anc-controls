# Realme `aa` protocol reference (Buds T310)

SPP over RFCOMM channel 1. How it was found: [reverse-engineering.md](reverse-engineering.md).

## Frame structure

```
aa [len: 2B LE] [00] [cmd: 2B] [seq: 1B] [data_len: 2B LE] [data: N bytes]
```

`len` = total frame size − 2 = `7 + N` (where N = data byte count).

## ANC mode command

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

## ACK structure (from earbuds)

```
aa 08 00 00 04 84 [seq] 01 00 [status]
```

`status = 0x00` means success. The `seq` byte mirrors the request.

## More commands (from a second Realme Link capture)

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
