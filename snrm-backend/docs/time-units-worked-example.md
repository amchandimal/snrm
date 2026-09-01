# Time units — a worked example

*Every number below is derived by hand from the conversion formulas and the validation
rules; nothing here was produced by running the application.*

`api-tests.http` requests 1–24 build exactly this network, so the responses can be checked against
the findings stated here.

---

## 1. The network

Four nodes, three links, one product, on a network whose clock is **1 DAY / 52 periods / NEAREST**.

```text
   SUP-1 ──6 h──▶ PLANT-1 ──36 h──▶ DC-1 ──2 weeks──▶ CUST-1
 (supplier)        (plant)          (DC)             (customer)
   dwell 0        dwell 12 h      dwell 4 h
```

| Element | Attribute | Declared |
|---|---|---|
| Network | period length | 1 DAY |
| | horizon | 52 periods |
| | rounding policy | NEAREST |
| SUP-1 | capacity | 500 per DAY |
| | processing time | *(omitted → 0 DAY)* |
| PLANT-1 | capacity | 400 per WEEK |
| | processing time | 12 HOUR |
| DC-1 | capacity | 250 per DAY |
| | processing time | 4 HOUR |
| CUST-1 | capacity | *(omitted → unconstrained)* |
| SUP-1 → PLANT-1 | lead time | 6 HOUR |
| PLANT-1 → DC-1 | lead time | 36 HOUR |
| DC-1 → CUST-1 | lead time | 2 WEEK |
| CUST-1 / Gearbox | demand | 120 per WEEK |
| | holding cost | 0.4 per DAY |

*(`api-tests.http` also demonstrates the editor's bulk PATCH on this network, which leaves SUP-1 at
750 per DAY and CUST-1 renamed. Neither touches a duration, so neither changes a finding below.)*

The two shapes are spelled differently on the wire, following the column names —
a duration's denominator is `unit`, a rate's is `timeUnit`:

```json
"leadTime": { "value": 36,  "unit":     "HOUR" }
"capacity": { "value": 400, "timeUnit": "WEEK" }
```

---

## 2. The conversion

Everything reduces to seconds first, which is what makes an hour comparable with a week without a
conversion table. `MONTH` is always 30 days and `YEAR` always 365 — fixed lengths, not calendar
arithmetic.

```text
SECOND 1   MINUTE 60   HOUR 3 600   DAY 86 400   WEEK 604 800
```

### 2.1 The period

```text
periodSeconds = period_length_value × secondsOf(period_length_unit)
              = 1 × 86 400
              = 86 400
```

### 2.2 Durations

```text
durationPeriods = round( duration_seconds / periodSeconds , rounding_policy )
```

`NEAREST` is `Math.round`, which is half-**up**: 0.5 → 1, 1.5 → 2, 0.4 → 0.

| Element · attribute | Declared | Seconds | ÷ 86 400 | NEAREST | Signed error |
|---|---|---|---|---|---|
| SUP-1 · processing time | 0 DAY | 0 | 0 | **0** | — *(exact, not reported)* |
| PLANT-1 · processing time | 12 HOUR | 43 200 | 0.5 | **1** | (1 − 0.5) / 0.5 = **+100 %** |
| DC-1 · processing time | 4 HOUR | 14 400 | 0.1666… | **0** | (0 − 0.1666…) / 0.1666… = **−100 %** |
| SUP-1 → PLANT-1 · lead time | 6 HOUR | 21 600 | 0.25 | **0** | (0 − 0.25) / 0.25 = **−100 %** |
| PLANT-1 → DC-1 · lead time | 36 HOUR | 129 600 | 1.5 | **2** | (2 − 1.5) / 1.5 = **+33.3 %** |
| DC-1 → CUST-1 · lead time | 2 WEEK | 1 209 600 | 14.0 | **14** | **0 %** |

Read the error column as what the *engine* will do relative to what the *user said*: the plant's
12-hour changeover becomes a full day, twice the dwell that was entered; the 6-hour truck leg and
the 4-hour cross-dock disappear.

Three things worth noticing:

- **The two-week leg is exact.** 1 209 600 / 86 400 = 14 with no remainder, so it produces no
  finding at all. A clean conversion is the normal case and the point of stating units in the first
  place — the ocean leg is not the problem; the truck leg is.
- **The sign matters more than the magnitude.** Both −100 % errors *shorten* a delay, and a network
  whose transit times vanish looks more resilient than it is. That asymmetry is why the check reports
  at all rather than rounding quietly.
- **12 h and 4 h round in opposite directions** despite both being under a period, because
  `NEAREST` splits at half a period. Under `DOWN` both would vanish; under `UP` both would become a
  full day.

### 2.3 Rates

```text
ratePerPeriod = rate_value × ( periodSeconds / secondsOf(rate_time_unit) )
```

Rates are rescaled, never rounded, so nothing is lost and there is nothing here to check.

| Element · attribute | Declared | Arithmetic | Per period |
|---|---|---|---|
| SUP-1 · capacity | 500 per DAY | 500 × (86 400 / 86 400) | 500 |
| PLANT-1 · capacity | 400 per WEEK | 400 × (86 400 / 604 800) | 57.142857… |
| DC-1 · capacity | 250 per DAY | 250 × (86 400 / 86 400) | 250 |
| CUST-1 · capacity | *unconstrained* | — | *null* |
| CUST-1 / Gearbox · demand | 120 per WEEK | 120 × (86 400 / 604 800) | 17.142857… |
| CUST-1 / Gearbox · holding cost | 0.4 per DAY | 0.4 × (86 400 / 86 400) | 0.4 |

`initialInventory` and `safetyStock` carry no unit and cross unchanged: a stock level is a quantity
at an instant, not a flow over time.

---

## 3. What the engine actually receives

`NetworkGraphFactory` converts once, when the snapshot is built, and the `NetworkGraph` beneath it
holds no units at all:

| Snapshot field | Value at a 1-day period |
|---|---|
| `timeBasis.periodSeconds` | 86 400 |
| `timeBasis.roundingPolicy` | NEAREST |
| `horizonPeriods` | 52 |
| SUP-1 `capacityPerPeriod` / `processingPeriods` | 500 / 0 |
| PLANT-1 `capacityPerPeriod` / `processingPeriods` | 57.142857… / 1 |
| DC-1 `capacityPerPeriod` / `processingPeriods` | 250 / 0 |
| CUST-1 `capacityPerPeriod` / `processingPeriods` | null / 0 |
| SUP-1 → PLANT-1 `leadTimePeriods` | **0** |
| PLANT-1 → DC-1 `leadTimePeriods` | **2** |
| DC-1 → CUST-1 `leadTimePeriods` | **14** |
| CUST-1 / Gearbox `demandPerPeriod` | 17.142857… |

The first arc is the one to look at. `leadTimePeriods = 0` means material shipped from SUP-1 is
available at PLANT-1 within the same period — the engine's pipeline inventory never holds anything on
that arc, and a disruption at SUP-1 propagates to PLANT-1 instantly instead of six hours later. No
metric can recover that; it is gone before the metric engine sees the network.

---

## 4. The findings the API returns

### 4.1 `GET /api/v1/networks/{id}/time-validation`

Default context is `EDITOR`. Ids below assume nodes 1–4 and links 1–3.

Findings sort worst-severity-first, then `NODE` before `LINK`, then by id — so with four warnings
the order is by element:

```json
{
  "networkId": 1,
  "context": "EDITOR",
  "periodLength": { "value": 1.0, "unit": "DAY" },
  "roundingPolicy": "NEAREST",
  "horizonPeriods": 52,
  "suggestedPeriod": { "value": 2.0, "unit": "HOUR" },
  "errorCount": 0,
  "warningCount": 4,
  "findings": [
    {
      "elementType": "NODE",
      "elementId": 2,
      "elementName": "PLANT-1",
      "field": "processingTime",
      "code": "DURATION_ROUNDING_ERROR",
      "severity": "WARNING",
      "declaredValue": { "value": 12.0, "unit": "HOUR" },
      "convertedPeriods": 1,
      "errorPercent": 100.0,
      "message": "Processing time 12.0 HOUR rounds to 1 period (1.0 DAY, +100%). Consider a period of 2.0 HOUR."
    },
    {
      "elementType": "NODE",
      "elementId": 3,
      "elementName": "DC-1",
      "field": "processingTime",
      "code": "DURATION_ROUNDS_TO_ZERO",
      "severity": "WARNING",
      "declaredValue": { "value": 4.0, "unit": "HOUR" },
      "convertedPeriods": 0,
      "errorPercent": -100.0,
      "message": "Processing time 4.0 HOUR is shorter than half a period (1.0 DAY) and will be treated as instantaneous. Use a finer period, or accept no dwell. Consider a period of 2.0 HOUR."
    },
    {
      "elementType": "LINK",
      "elementId": 1,
      "elementName": "SUP-1 → PLANT-1",
      "field": "leadTime",
      "code": "DURATION_ROUNDS_TO_ZERO",
      "severity": "WARNING",
      "declaredValue": { "value": 6.0, "unit": "HOUR" },
      "convertedPeriods": 0,
      "errorPercent": -100.0,
      "message": "Lead time 6.0 HOUR is shorter than half a period (1.0 DAY) and will be treated as instantaneous. Use a finer period, or accept zero transit. Consider a period of 2.0 HOUR."
    },
    {
      "elementType": "LINK",
      "elementId": 2,
      "elementName": "PLANT-1 → DC-1",
      "field": "leadTime",
      "code": "DURATION_ROUNDING_ERROR",
      "severity": "WARNING",
      "declaredValue": { "value": 36.0, "unit": "HOUR" },
      "convertedPeriods": 2,
      "errorPercent": 33.33333333333333,
      "message": "Lead time 36.0 HOUR rounds to 2 periods (2.0 DAY, +33%). Consider a period of 2.0 HOUR."
    }
  ]
}
```

Notes on the shape:

- **`scenarioId` is absent.** No scenario was named, so no `EVENT_EXCEEDS_HORIZON` finding can
  appear (§6 below). Null members are dropped —
  `spring.jackson.default-property-inclusion=non_null`.
- **`errorPercent` is a raw double.** `33.33333333333333` is 1/3 as IEEE-754 prints it; the exact
  digits are an artefact of binary floating point, not part of the contract. The message rounds it
  to `+33%` for display.
- **The 2-week leg contributes nothing.** Exact conversions are silent.
- **Three lead times were declared and only two are reported**, which is the whole intent: the
  banner lists what changed meaning, not what exists.

### 4.2 The same network during import

`GET /api/v1/networks/{id}/time-validation?context=IMPORT`

A duration that converts to zero periods is an error on import and a warning in the editor.
Nothing else changes — same four findings, same numbers, same messages:

| Element · attribute | Code | EDITOR | IMPORT |
|---|---|---|---|
| DC-1 · processing time | `DURATION_ROUNDS_TO_ZERO` | WARNING | **ERROR** |
| SUP-1 → PLANT-1 · lead time | `DURATION_ROUNDS_TO_ZERO` | WARNING | **ERROR** |
| PLANT-1 · processing time | `DURATION_ROUNDING_ERROR` | WARNING | WARNING |
| PLANT-1 → DC-1 · lead time | `DURATION_ROUNDING_ERROR` | WARNING | WARNING |

so `errorCount` becomes 2, `warningCount` 2, and the two errors sort to the top of the list. The
import refuses the file outright; the editor shows a dismissible banner and lets the
researcher carry on.

---

## 5. "Suggest period"

> the coarsest period that keeps every duration within the 10% tolerance — usually the greatest
> common divisor of the declared durations, capped at the smallest one

The five positive durations, in seconds:

```text
12 h = 43 200     4 h = 14 400     6 h = 21 600     36 h = 129 600     2 wk = 1 209 600
```

**Greatest common divisor**, by Euclid:

```text
gcd(43 200, 14 400)      = 14 400
gcd(14 400, 21 600)      =  7 200
gcd(7 200, 129 600)      =  7 200      (129 600 = 18 × 7 200)
gcd(7 200, 1 209 600)    =  7 200      (1 209 600 = 168 × 7 200)
```

7 200 s. **Cap**: the smallest declared duration is 4 h = 14 400 s, so no candidate above that is
considered — a period longer than the shortest duration cannot represent it however it rounds.

Every candidate from the ladder at or below the cap is then tested against the 10 % tolerance, under
this network's own rounding policy, and the coarsest survivor wins:

| Candidate | 6 h | 4 h | 12 h | 36 h | 2 wk | Verdict |
|---|---|---|---|---|---|---|
| **4 HOUR** (14 400) | 1.5 → 2, **+33 %** | 1 | 3 | 9 | 84 | ✗ over tolerance |
| **3 HOUR** (10 800) | 2 | 1.333 → 1, **−25 %** | 4 | 12 | 112 | ✗ over tolerance |
| **2 HOUR** (7 200) | 3 | 2 | 6 | 18 | 168 | ✓ every duration exact |

**Suggested period: 2 HOUR** — reported as `{ "value": 2.0, "unit": "HOUR" }`, spelled in the
coarsest unit that measures 7 200 s whole (2 hours, not 120 minutes).

Two consequences the dialog should say out loud:

- **The horizon does not follow automatically.** 52 periods of 1 day is 52 days; 52 periods of
  2 hours is 4 days and 8 hours. Covering the same span needs `horizonPeriods` 624 — which is what
  `api-tests.http` request 22 sends, and why `PUT /time-base` takes all three fields together.
- **Changing the period on a network with completed runs is refused** (`NETWORK_IMMUTABLE`, 409).
  Results are stated in periods, so redefining the period turns "TTR = 14" from fourteen days into
  fourteen hours without altering a stored number. Clone the network and set the time
  base on the variant.

---

## 6. The two checks this network does not trigger

### 6.1 `PERIOD_TOO_FINE` — period more than 1000× finer than the longest duration

Set the same network to a **1 MINUTE** period (`api-tests.http` request 23):

```text
periodSeconds = 60
longest declared duration = 2 WEEK = 1 209 600 s
1 209 600 / 60 = 20 160  >  1000
```

Every duration now converts exactly (6 h → 360, 36 h → 2 160, 12 h → 720, 4 h → 240, 2 wk → 20 160),
so the rounding checks are silent and exactly one finding comes back:

```json
{
  "elementType": "LINK",
  "elementId": 3,
  "elementName": "DC-1 → CUST-1",
  "field": "leadTime",
  "code": "PERIOD_TOO_FINE",
  "severity": "WARNING",
  "declaredValue": { "value": 2.0, "unit": "WEEK" },
  "convertedPeriods": 20160,
  "message": "Horizon of 20160 periods needed to span a lead time of 2.0 WEEK at a period of 1.0 MINUTE; simulation may be slow."
}
```

Nothing is wrong with the model — this is a cost warning. 20 160 steps × 100 replications is
two million period evaluations to watch one shipment cross an ocean.

### 6.2 `EVENT_EXCEEDS_HORIZON` — event window outruns the run

Needs a scenario, which is why the check takes one as a query parameter:
`GET /api/v1/networks/{id}/time-validation?scenarioId={scenarioId}`. Scenarios are project-scoped so
one can be replayed against every variant, and the variants need not share a horizon — so
whether an event outruns the run is a question about the *pair*, not about either alone.

Back on the 1-day period, with a scenario holding one event against DC-1:

| | Declared | Periods |
|---|---|---|
| start offset | 30 DAY | 30 |
| duration | 4 WEEK | 28 |
| **end** | | **58** |
| horizon | | **52** |

The offset and the window are discretised separately, because that is what the engine does with
them — an offset becomes the index of the step the event fires in, a duration a count of steps it
lasts. Both convert exactly here, so the only finding is the horizon one:

```json
{
  "elementType": "DISRUPTION_EVENT",
  "elementId": 7,
  "elementName": "NODE 3",
  "field": "window",
  "code": "EVENT_EXCEEDS_HORIZON",
  "severity": "ERROR",
  "declaredValue": { "value": 4.0, "unit": "WEEK" },
  "convertedPeriods": 58,
  "message": "Event starts at period 30 and lasts 28, so it ends at period 58 — after the horizon of 52. Its recovery is never observed, and any metric over it measures the truncation; extend horizon_periods."
}
```

An error rather than a warning: a recovery metric computed over an event that has not finished
measures where the run stopped, not how the network recovered.

**This one cannot be exercised yet** — `CRUD /projects/{id}/scenarios` and `/scenarios/{id}/events`
are not built. The check is implemented and reachable the moment they are;
`api-tests.http` request 25 carries the request to send.

---

## 7. Summary

| # | Check | Severity | Fired by this network at 1 DAY |
|---|---|---|---|
| 1 | Duration converts to 0 periods | ERROR on import, WARNING in editor | 4 h dwell, 6 h lead time |
| 2 | Relative rounding error > 10 % | WARNING | 12 h dwell (+100 %), 36 h lead time (+33 %) |
| 3 | Period > 1000× finer than longest duration | WARNING | no — needs a 1-minute period |
| 4 | Event window ends after the horizon | ERROR | no — needs a scenario |

| Question | Answer for this network |
|---|---|
| Coarsest period keeping every duration within 10 % | **2 HOUR** |
| Horizon needed at that period to cover 52 days | **624** |
| Durations lost entirely at 1 DAY | 2 of 5 |
| Rates distorted by conversion | 0 — rates rescale exactly |
