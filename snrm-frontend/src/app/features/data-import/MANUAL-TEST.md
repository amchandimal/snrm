# Several workbooks in one pass - manual test script (FR-28)

Its own numbering, starting at 1. This covers the **batch** import of FR-28 and, just as
deliberately, the three single-network uploads it must leave alone. Where a step exists only to make
the next one meaningful it says so.

The target of the walk is section F: **four workbooks imported in one pass - one the baseline, two of
them its variants, one independent - drawn in the provenance tree as a fork forest with the fourth
sitting outside it.** Sections G–J are the ways it goes wrong on purpose: a partial batch, a refused
baseline, a file that cannot be named.

## Setup

**S1.** Backend running: `mvnw.cmd spring-boot:run` in `../../../../../snrm-backend`.
→ <http://localhost:8080/actuator/health> returns `{"status":"UP"}`.

**S2.** Frontend running:

```bash
npm start
```

→ Compiles clean; the app serves at <http://localhost:4200>.

**S3.** Sign in and create a project named `Workbook batch`.

**S4.** Open it, press **Products**, add `Gearbox` with unit value `250`.
→ The amber "no products yet" banner clears. (A network with no demand cannot be simulated, and this
walk ends in a lineage, not a run, but the banner is noise otherwise.)

---

## A. Four workbooks to import

There are no `.xlsx` files under `samples/`, by design: the canonical fixtures are CSV and XML, and the
tool's own export is what a folder of workbooks actually looks like in practice. So make them.

**1.** Press **Import network (CSV · Excel · XML)**. Drop the five CSV files from
`../../../../../snrm-backend/samples/minimal-6-node/`, press **Read files**, name the
network `Seed` on step 2, press **Validate**, then **Confirm import**.
→ The wizard reaches step 3 with no errors and `Seed` opens in the editor. **This is the single-file
path and it must be exactly as it always was**: three steps in the stepper, a name field on step 2, a
dry run, a Confirm.

**2.** Go back to the project dashboard. On the `Seed v1` row open the **⋯** menu and choose
**Export XLSX**.
→ One `.xlsx` file downloads.

**3.** In your file manager, make **four copies** of that workbook and name them exactly:

- `Baseline.xlsx`
- `Dual sourcing.xlsx`
- `Extra DC.xlsx`
- `Regional study.xlsx`

→ Four identical workbooks with four different names. Identical is what we want: this walk is about
the batch, and section G is where one of them stops being valid.

**4.** Delete `Seed v1` from the dashboard (row menu → **Delete network**, type the project name).
→ The table is empty. The project now has **no baseline**, which section E depends on.

---

## B. The single-network paths are untouched

Three checks, and they are the regression surface of the whole feature. Each should feel like nothing
changed, because nothing did.

**5.** Open the import wizard and drop **one** file: `Baseline.xlsx`. Do not press anything yet.
→ The heading reads **"Import a network"**, the stepper has **three** steps, and there is no blue
"one network per file" panel. One workbook is one network, as it always was.

**6.** Press **Read files**.
→ Step 2 shows the **Network name** field pre-filled with `Baseline` and the *Make this the project's
baseline* checkbox. Press **Validate**, then **Confirm import**.
→ The network is created and opens in the editor.

**7.** Delete `Baseline v1` again, so the project is empty for section D.

**8.** Open the wizard, drop the single `network.xml` from `samples/xml-6-node/`, and press
**Read files**.
→ Step 2 as before, name pre-filled from the document. Press **Back** rather than importing - the
point of the step was the stepper and the name field.

---

## C. What the batch form refuses, and why

**9.** With the wizard open, drop `Baseline.xlsx` **and** `nodes.csv` from `samples/minimal-6-node/`.
→ A red panel: **"This upload cannot be read."** It names *1 workbook*, *1 other file* and
`nodes.csv`, then states what the batch form accepts. **Read files** is disabled.
→ This is refused because it is *ambiguous*, not because it would fail: a workbook beside a CSV could
be a batch with a stray in it or one network split across two files.

**10.** Remove the two files with the × on each chip, then drop **both** `network.xml` from
`samples/xml-6-node/` and any second `.xml` (copy it and rename it).
→ Refused again, this time naming *2 XML documents* and saying an XML document already carries one
whole network.

**11.** Remove them and drop `network.xml` together with `nodes.csv`.
→ **Not** refused. Nothing about that looks like a batch, so FR-28 introduces no ambiguity there and
the path is exactly as it was. Remove them again.

---

## D. Four workbooks: names, one mapping, one clock

**12.** Drop all four workbooks: `Baseline.xlsx`, `Dual sourcing.xlsx`, `Extra DC.xlsx`,
`Regional study.xlsx`.
→ The heading becomes **"Import 4 networks"** and the stepper grows a **fourth** step, *Roles*. A blue
panel lists the name each file will give:

```text
Baseline.xlsx        → Baseline
Dual sourcing.xlsx   → Dual sourcing
Extra DC.xlsx        → Extra DC
Regional study.xlsx  → Regional study
```

**13.** Press **Read files**.
→ Step 2, with **no name field and no baseline checkbox** - a batch takes its names from its files.
In their place, a sentence saying so and pointing at FR-29 for renaming later.

**14.** Read the blue panel above the mapping table.
→ **"One mapping for the whole batch"**, naming `Baseline.xlsx` as the file it was read
from, and telling you to import heterogeneous files one at a time. Check the network tab of your
browser's dev tools if you like: `POST /networks/import/preview` carried **one** file, not four.

**15.** Leave the mapping alone (every column is already canonical - it is the tool's own export) and
press **Continue to roles**.

---

## E. The roles step, and the two baselines

**16.** Read the table.
→ Four rows, each with its file name, its derived **network name**, a **Baseline** radio and a
*Variant of the baseline* / *Independent network* pair. `Baseline.xlsx` is the baseline by default and
every other row is a **variant**, because a folder of workbooks from one study is
usually one configuration and its alternatives.

**17.** Read the info panel above the table.
→ **"This project has no baseline yet"**, so the chosen file will be marked as the project's baseline
*as well as* being the base of the batch's edges.

**18.** Set `Regional study.xlsx` to **Independent network**.
→ The *What this will do* list below updates: **2** files recorded as a `CONFIGURATION_VARIANT`, **1**
with no edge at all, and 4 requests with `Baseline` first.

**19.** Click the **Baseline** radio on the `Extra DC.xlsx` row, then click it back on `Baseline.xlsx`.
→ Exactly one baseline at any moment, and the row that was the baseline drops back to *variant*.

---

## F. The import itself - the target of this walk

**20.** Press **Import 4 network(s)**.
→ The wizard moves to step 4 immediately and the report **fills in as it goes**: four cards, each
*Waiting* with a spinner, settling one at a time from the top. The order is `Baseline` first - the
baseline goes first because a variant edge needs its base's id.

**21.** When it settles, read the top.
→ A **green** panel: **"All 4 files imported."** and the counts `4 created · 0 refused · 0 not
attempted`.

**22.** Read the cards.
→ Each names the network it created with its version: **"Created as Baseline v1 (#N)"**, **"Created as
Dual sourcing v1 (#N)"**, and so on. The two variants say they are recorded as a
`CONFIGURATION_VARIANT` of the baseline; `Regional study` says *Independent network*. Each card has a
disclosure holding that file's own two-stage report, closed, showing 0 errors.

**23.** Press **Back to the project**.
→ The dashboard lists **four** networks, all `v1`, each named after its file. `Baseline v1` carries
the **Baseline** badge.

**24.** Read the **lineage** panel beneath the table. **This is what the feature is for.**
→ A **fork forest**:

```text
Baseline v1                 ← the batch's baseline, and the project's
  ├─ Dual sourcing v1
  └─ Extra DC v1
Regional study v1           ← its own root, outside the fork
```

Two variants hang under the baseline; the independent file is a root of its own beside it. Neither
carries a lever note, because nothing diffs two networks and the wizard invented none.

**25.** Rename one of them: on the `Extra DC v1` row, rename it to `Extra DC - north`.
→ FR-29's rename from the project table. (**If the table offers no rename control, this feature has not
landed yet** - the wizard's promise on the roles step is to FR-29, which is a separate task. Note it
and move on.)

---

## G. A partial batch reads as a partial batch

**26.** Open `Extra DC.xlsx` in Excel or LibreOffice, go to the `links` sheet and change one
`source` cell to a node name that does not exist - `PLANT-99`. Save it as `.xlsx`.

**27.** Import the same four workbooks again, same roles as before (`Regional study` independent).
→ Note that the names now clash with the four networks already in the project. **That is fine and is
the point**: the server takes the next version number, so this batch produces `v2` of each.

**28.** Read the top of the report.
→ An **amber** panel: **"Partly imported - 3 of 4 files created a network."** and, in the message,
*"This is neither a success nor a failure… the networks marked Created below are in the project
already - the remedy is to fix the named files and import those files again, not to import the batch
again."**
→ It is neither green nor red, which is the whole requirement.

**29.** Read the `Extra DC.xlsx` card.
→ **Refused**, with its own report **already open**, listing the row error against the `links` sheet
with a line number. The other three are *Created*, at `v2`.

**30.** Check the dashboard.
→ Seven networks: the original four, plus `Baseline v2`, `Dual sourcing v2` and `Regional study v2`.
`Extra DC` is still at `v1` alone. The seven exist; the one bad row cost one file, not the batch.

**31.** *Back to roles* on the report step.
→ **Disabled**, with a tooltip saying networks were created and the remedy is to import the fixed
files. Re-running the batch would make `v3` of everything rather than replacing anything.

---

## H. A refused baseline takes only its variants

**32.** Repair `Extra DC.xlsx` (`PLANT-99` back to what it was) and instead break **`Baseline.xlsx`**
the same way.

**33.** Import all four again, roles unchanged.
→ Top: **amber**, **"Partly imported - 1 of 4 files created a network."** Not red, and this is the
check: `Regional study` is *independent*, so it never needed the baseline and is created regardless.
A failed baseline takes its **variants** with it and nothing else.

**34.** Read the four cards in order.
→ `Baseline.xlsx` - **Refused**, its rows shown. `Dual sourcing.xlsx` and `Extra DC.xlsx` - **Not
attempted**, each saying *"a configuration-variant edge needs its base network's id… Fix the
baseline's file and import this one again, or import it as an independent network."* Nothing was sent
for them. `Regional study.xlsx` - **Created** as `v3`.
→ This is the one way a file's failure reaches another file, and it is stated rather than inferred.

**35.** Repair `Baseline.xlsx`.

---

## I. A file that cannot be named refuses itself

**36.** Copy `Dual sourcing.xlsx` and rename the copy to exactly `.xlsx` (no stem). If your file
manager refuses, name it `   .xlsx` - three spaces.

**37.** Drop it together with `Baseline.xlsx` and `Extra DC.xlsx`, and press **Read files**.
→ On the **upload** step, before anything is posted, the blue panel already says the file has *no name
left once the extension is stripped, so this file will be refused rather than given an invented one*.

**38.** Continue to the roles step.
→ Its row is highlighted, its name cell reads **"Cannot be named"**, its **Baseline** radio is
disabled and it offers no role. An amber note says 1 file will be refused before anything is sent, and
the button reads **Import 2 network(s)** rather than 3.

**39.** Import.
→ Two cards *Created*, and the unnamed file at the **end** of the list, **Not attempted**, with the
sentence about renaming it. Nothing was sent for it.

---

## J. The project's baseline flag does not move

**40.** The project now has a baseline (`Baseline v1`, from step 23). Import `Baseline.xlsx` and
`Dual sourcing.xlsx` as a batch and reach the **roles** step.
→ The info panel now reads **"This project already has a baseline - Baseline v1, and it stays where it
is… the file you choose below is used only as the base of this batch's variant edges"**.

**41.** Read *What this will do*.
→ **"The project's baseline flag is not touched"**.

**42.** Import.
→ Both created. On the dashboard, **`Baseline v1` still carries the Baseline badge** and the new
`Baseline v4` does not - and the lineage shows the new `Dual sourcing` under the new `Baseline`. The
flag stayed; the edges were written. Had the wizard sent `baseline=true` here, the *first* request
would have come back 409 and taken the variant with it.

**43.** Stop the backend, open the wizard, drop two workbooks and reach the roles step.
→ An **amber** panel: the project's networks could not be read, so the flag is left alone and the
variant edges are unaffected, with a **Retry** button. Start the backend and press **Retry**.
→ The panel turns blue and states what the project's baseline actually is.

---

## What this walk does not cover

- The **metric and simulation** consequences of the imported networks: they are ordinary networks, and
  `features/simulations/MANUAL-TEST.md` covers running one.
- **FR-29's rename** beyond step 25 - it is its own feature and its own script.
- The **archive** round trip of the resulting project (`features/projects/MANUAL-TEST.md`), though a
  batch-built project with a fork forest is a good input for it: variant edges whose both ends are
  selected travel with a subset export (FR-24).
