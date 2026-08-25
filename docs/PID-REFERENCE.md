# PID reference

Everything here is Mode 01 (current data) unless stated otherwise. The app never assumes a PID is
present: the supported set is discovered at connect time by walking the `0100` / `0120` / `0140`
support bitmasks, and anything that answers `NO DATA` three times is dropped from the poll rotation
for the rest of the session.

Decoders live in [`ObdPid.kt`](../app/src/main/java/com/mohid/obd2dash/obd/ObdPid.kt).

## The four on the main dial

| Metric | PID | Formula | Range shown |
|---|---|---|---|
| Engine RPM | `010C` | `(256A + B) / 4` | 0 to 8000 rpm |
| Vehicle speed | `010D` | `A` | 0 to 180 km/h |
| Coolant temperature | `0105` | `A - 40` | -40 to 130 °C |
| **Boost** | derived | `MAP - barometric` | -1.0 to +1.5 bar |

Boost is not a PID. It is computed from manifold absolute pressure minus ambient, so it reads
negative under vacuum and positive under boost. Barometric comes from `0133` where the ECU supports
it, and falls back to 101 kPa at sea level where it does not. A fallback is a fixed offset error
rather than a wrong shape, which is the right failure mode for a gauge.

## Core engine and air

| Metric | PID | Formula | Unit |
|---|---|---|---|
| Calculated engine load | `0104` | `A × 100 / 255` | % |
| Manifold absolute pressure | `010B` | `A` | kPa |
| Timing advance | `010E` | `A / 2 - 64` | ° |
| Intake air temperature | `010F` | `A - 40` | °C |
| MAF air flow rate | `0110` | `(256A + B) / 100` | g/s |
| Throttle position | `0111` | `A × 100 / 255` | % |
| Barometric pressure | `0133` | `A` | kPa |
| Absolute load | `0143` | `(256A + B) × 100 / 255` | % |
| Relative throttle | `0145` | `A × 100 / 255` | % |
| Ambient air temperature | `0146` | `A - 40` | °C |
| Engine oil temperature | `015C` | `A - 40` | °C |

## Fuel and mixture

| Metric | PID | Formula | Unit |
|---|---|---|---|
| Short term fuel trim, bank 1 | `0106` | `(A - 128) × 100 / 128` | % |
| Long term fuel trim, bank 1 | `0107` | same | % |
| Short term fuel trim, bank 2 | `0108` | same | % |
| Long term fuel trim, bank 2 | `0109` | same | % |
| Fuel pressure | `010A` | `A × 3` | kPa |
| Fuel rail gauge pressure | `0123` | `(256A + B) × 10` | kPa |
| Commanded equivalence ratio | `0144` | `(256A + B) / 32768` | lambda |
| Fuel tank level | `012F` | `A × 100 / 255` | % |
| Engine fuel rate | `015E` | `(256A + B) / 20` | L/h |

Fuel trim is worth watching on a turbo. Sustained long term trim past ±10% usually means a leak, a
tired MAF, or an injector that is no longer flowing what the ECU thinks it is.

## Oxygen sensors

| Metric | PIDs | Formula |
|---|---|---|
| Narrow band voltage, sensors 1 to 8 | `0114` to `011B` | `A / 200` V |
| Wide band lambda, sensors 1 to 8 | `0124` to `012B` | `(256A + B) / 32768` |

## Electrical

| Metric | PID | Formula | Unit |
|---|---|---|---|
| Control module voltage | `0142` | `(256A + B) / 1000` | V |

A healthy charging system sits around 13.8 to 14.4 V with the engine running. The default alert
bounds are set just outside that.

## Emissions and counters

| Metric | PID | Formula |
|---|---|---|
| Monitor status and MIL | `0101` | bit 7 of A is the lamp, `A & 0x7F` is the stored code count |
| Commanded EGR | `012C` | `A × 100 / 255` |
| EGR error | `012D` | `(A - 128) × 100 / 128` |
| Commanded evaporative purge | `012E` | `A × 100 / 255` |
| Catalyst temperature, 4 positions | `013C` to `013F` | `(256A + B) / 10 - 40` |
| Run time since engine start | `011F` | `256A + B` seconds |
| Distance with MIL on | `0121` | `256A + B` km |
| Warm-ups since codes cleared | `0130` | `A` |
| Distance since codes cleared | `0131` | `256A + B` km |
| Run time with MIL on | `014D` | `256A + B` minutes |
| Time since codes cleared | `014E` | `256A + B` minutes |

## Diagnostic trouble codes

| Mode | Meaning |
|---|---|
| `03` | Stored codes |
| `07` | Pending codes, seen once but not yet confirmed |
| `0A` | Permanent codes, cannot be cleared by a scan tool |
| `04` | Clear stored codes and turn the MIL off |
| `02` | Freeze frame, the snapshot taken when a code set |

The app polls monitor status every 30 seconds during a trip and only asks for the full code list when
the lamp is on or the stored count is non zero.

Codes decode from two bytes. The top two bits pick the system letter, and the remaining fourteen bits
are four hex digits:

```
byte A          byte B
7 6 5 4 3 2 1 0 7 6 5 4 3 2 1 0
└┬┘ └┬┘ └───┬─┘ └───┬─┘ └───┬─┘
 │   │      │       │       └── digit 4
 │   │      │       └────────── digit 3
 │   │      └────────────────── digit 2
 │   └───────────────────────── digit 1
 └───────────────────────────── 00 = P, 01 = C, 10 = B, 11 = U
```

So `0x01 0x33` becomes `P0133`, the classic slow O2 sensor response.

One real world wrinkle: CAN ECUs usually insert a code count byte after the `43` echo and older
K-line units do not. An odd number of trailing bytes can only be explained by that count byte, which
is the tell the decoder uses.

## Adapter setup

The handshake the app runs, in order:

| Command | Purpose |
|---|---|
| `ATZ` | Reset. Slow, needs about a second to settle. |
| `ATE0` | Echo off, so replies are not doubled up |
| `ATL0` | No linefeeds |
| `ATS0` | No spaces in hex, which halves the bytes to parse |
| `ATH0` | No CAN headers |
| `ATAT1` | Adaptive timing |
| `ATST32` | Roughly 200 ms ceiling per request |
| `ATSP0` | Automatic protocol detection |
| `0100` | First real request, this is what actually triggers detection |
| `ATDP` | Report the protocol that was negotiated |

Requests are sent with a frame count hint appended (`010C1` rather than `010C`). That tells the
adapter to return as soon as one frame arrives instead of waiting out its full timeout, which roughly
doubles the achievable sample rate. It needs ELM327 v1.3 or later, and there is a settings toggle to
turn it off for a clone that mishandles it.
