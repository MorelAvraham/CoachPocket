# Pod BLE protocol, v1 (draft for review)

Contract between `firmware/` (ESP32-S3 pod) and `app/`. Reference implementation and tests:
`app/core/src/main/kotlin/com/coachpocket/core/protocol/`. `SimulatedPod` in `app/core/.../sim/`
emits byte-exact packets and can be used as a golden reference when writing the firmware.

**Status: proposed by the app side. Not frozen until the firmware owner has reviewed it.**

## GATT

| | UUID | Properties |
|---|---|---|
| Service | `c0ac0001-9f6b-4a3e-8d57-1e2b3c4d5e6f` | advertised |
| IMU data | `c0ac0002-9f6b-4a3e-8d57-1e2b3c4d5e6f` | notify |
| Time beacon | `c0ac0003-9f6b-4a3e-8d57-1e2b3c4d5e6f` | write without response, notify |

The pod must put the service UUID in its advertising data; the phone scans with a filter on it.

Link parameters the phone requests after connecting: ATT MTU 247, LE 2M PHY, high connection
priority (11.25 to 15 ms interval on Android). The pod should accept all three.

## IMU data packet

Little-endian. One notification per packet.

| Offset | Type | Field |
|---|---|---|
| 0 | u16 | `seq`: packet counter, +1 per packet, wraps at 65536 |
| 2 | u8 | `n`: samples in this packet, 1 to 19 |
| 3 | u8 | `flags`: bits 0-1 accel full scale, bits 2-3 gyro full scale, bits 4-7 protocol version (= 1) |
| 4 | u32 | `t0`: pod clock at the **first** sample, microseconds, wraps at 2^32 (71.6 min) |
| 8 | u16 | `period`: sample period, microseconds (1000 at 1 kHz) |
| 10 | i16 × 6 × n | `ax ay az gx gy gz` raw counts per sample, full scale = 32768 counts |

Full-scale codes: accel 0/1/2/3 = ±2/4/8/16 g, gyro 0/1/2/3 = ±250/500/1000/2000 °/s.

Size: 10 + 19 × 12 = 238 bytes, inside the 244-byte notification payload at MTU 247.
At 1 kHz that is about 53 packets/s and about 100 kbit/s.

Rules for the firmware:

- `t0` is taken from a free-running microsecond timer at the moment the sample is read (ideally
  latched on the IMU data-ready interrupt), **not** at the moment the packet is sent.
- `seq` increments for every packet built, including ones the BLE stack then fails to send. That is
  how the phone measures loss (test U1).
- Samples within a packet are evenly spaced by `period`. If a sample is missed, close the packet
  early and start a new one with a correct `t0`.
- Lowering the rate (on-pod downsampling) only changes `period`; the phone adapts.

## Time beacon (optional but recommended)

The phone aligns the clocks from the data packets alone, by tracking the smallest observed
(receive time − pod time). That estimate is late by the minimum transport delay. The beacon
measures and removes that bias.

Once a second the phone writes 8 bytes: `u64 t1` (phone clock, µs).
The pod replies with a 16-byte notification:

| Offset | Type | Field |
|---|---|---|
| 0 | u64 | `t1`, echoed unchanged |
| 8 | u32 | `t2`: pod clock when the write was received, µs |
| 12 | u32 | `t3`: pod clock when this reply was queued, µs |

Same pod timer as `t0`. The phone works without the beacon and shows "no time beacon".

## Open questions for the firmware owner

1. Can the ESP32-S3 BLE stack (NimBLE) sustain 53 notifications/s of 238 bytes on 2M PHY to an
   Android central? This is test U1; if not, drop `period` to 2000 µs (500 Hz) first.
2. Where is `t2` best latched: in the GATT write callback, or lower in the stack?
3. Is a battery level / status characteristic wanted in v1?
