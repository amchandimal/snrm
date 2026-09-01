# `data-import` - the import wizard

Three steps for **one** network: **upload** CSV files, an Excel workbook or an XML document → **map
columns** onto the canonical schema → **review the validation report and confirm**. On
success the imported network opens in the editor (FR-02).

Four steps for a **batch** of workbooks (FR-28): **upload** several `.xlsx` → **map columns** once →
**assign roles** (which file is the baseline, and what each of the others is) → **the per-file
report**. A batch creates several networks, so it opens no editor.

Route: `/projects/:projectId/import`, reached from the "Import from CSV/Excel" button on the project
dashboard.

## Files

| File | Role |
|---|---|
| `data-import.store.ts` | All wizard state. The files, the mapping, the confirmed time base, the report - and, for a batch, the roles, the plan and the per-file outcomes. |
| `file-names.ts` | Pure, specced. The network name a file gives (extension stripped, trimmed) and what shape an upload is: one network, a batch, or neither. |
| `batch-plan.ts` | Pure, specced. Roles, the import order, the two baselines, and the verdict at the top of the report. |
| `import-wizard.component.*` | The frame: stepper, error banner, navigation, and the redirect into the editor. |
| `upload-step/` | Step 1. Wraps `shared/file-drop`; collects files, shows the names a batch will use, and asks the server what they are. |
| `mapping-step/` | Step 2. Column mapping per sheet, plus the network name and the required time-base confirmation. |
| `roles-step/` | Step 3, **batch only**. One row per file: the derived name, one baseline choice, and variant-or-independent for each of the others. |
| `report-step/` | The last step. One report and Confirm, or one report per file. |
| `report-step/import-report-view.component.*` | One two-stage report, drawn. Used once for a single import and once per file for a batch. |
| `network-export.service.ts` | `GET /networks/{id}/export?format=xlsx\|csv\|xml` - the other half of the round trip. |

## Formats

Three, all validated identically because the server turns each into the same intermediate tables:

- **CSV** - the five canonical files. Delimiter auto-detected.
- **XLSX** - one workbook with the same five as sheets. **Several of them are several networks** - see
  below.
- **XML** - the interchange document: one self-describing file carrying everything,
  including `posX`/`posY` and the network's own name. It needs no column mapping, so the wizard's step
  2 is a confirmation of the time base and nothing more, and the name field arrives pre-filled from
  `preview.declaredName`.

## Three decisions worth knowing before changing anything

**The server parses; the browser never does.** Both the preview and the import post the files and the
server reads them. A CSV or XLSX parser in the client would be a second implementation, and
"the delimiter was detected differently in the browser" a class of bug that is very hard to see.

**Nothing is stored between the two requests.** The files stay in the store and are posted twice - once
to `/networks/import/preview` for the headers, once to `/networks/import` with the confirmed mapping.
So there is no temporary upload to expire or authorise again, and no way for the confirmed import to run
against a different file than the one on screen.

**The step is derived from what has been fetched**, not tracked. No preview means step 1; a preview but
no report means step 2; a report means step 3. Going back therefore discards what came after, which is
correct: a report is about a mapping the user is now changing. FR-28's roles step extends that rule
rather than breaking it - see below.

## The dry run

Step 3 shows the result of `validateOnly=true` - validation with nothing created. Confirming re-posts
the same request with it false. Imports are transactional: one error anywhere and no network
exists, so `valid` (nothing blocks it) and `committed` (it happened) are genuinely different questions
and the store exposes both.

**A batch has no dry run**, and that is the same argument reaching the opposite conclusion. A single
import is all-or-nothing, so the dry run exists to let the user confirm a decision they have been
shown. A batch is not all-or-nothing: each file is its own transaction, "and a file that fails leaves
the others imported and itself named" (FR-28). A refused file therefore creates nothing either way,
which leaves a batch dry run nothing to be a rehearsal of - and the variants could not be rehearsed at
all, since `baseNetworkId` names a network a dry run does not create. So the roles step's button *is*
the import, and the report step is what happened.

---

## Several workbooks in one pass (FR-28)

> "One `.xlsx` carries one network, so several carry several, and the wizard accepts them together -
> the shape a project arrives in when a collaborator sends a folder, or when the tool's own per-network
> exports are gathered back up."

Two workbooks or more is a **batch**. Everything else - one workbook, a CSV set, an XML document - is
read exactly as it was before FR-28, through the same code, to the same requests. This is an added
path, not a replacement, and `file-names.spec.ts` pins that as its first expectation.

### A batch is workbooks and nothing else

`uploadShape` refuses a workbook mixed with anything, and refuses two or more XML documents. Neither is
refused because it would fail; both are refused because the wizard cannot say what it would do. Once a
second workbook means a second network, an upload holding a workbook *and* a `nodes.csv` has two
readings - a batch with a stray in it, or one network split across two files - and several XML
documents look like a batch while being defined as several whole networks. The refusal names
the count that is wrong and then states what the batch form accepts.

An XML document beside CSV files is **not** refused: nothing about it looks like a batch, so FR-28
introduces no ambiguity there and the path is untouched.

### Names come from file names, and nothing else

`networkNameFromFileName` strips the final `.xlsx`/`.xlsm` and trims. `Baseline.v2.xlsx` is
`Baseline.v2` - only the format's dot goes, because the others are the researcher's. A file whose name
is empty afterwards (`.xlsx`) **refuses its own file** rather than being given an invented one: a
generated stand-in looks like a decision, and the network it produced would then have to be identified
by its contents.

`suggestName`, which has guessed a lone workbook's name since the wizard was written, now calls the
same function. One derivation, two callers - a workbook imported alone and the same workbook imported
in a batch must not end up named two different things.

**Nothing checks the names against the project**, deliberately. A name already used in the
project takes the next version number exactly as any other network of that name would. That is the
server's `findMaxVersion(name) + 1`, so a client-side check would either pre-empt the established way of
adding a variant or duplicate it and disagree with it. The roles step says the rule instead.

**There is no name field on the roles step either.** FR-29 is the other half of that decision: a name
taken from a file name is a starting point rather than a decision, so it is edited from the project's
network table afterwards, where the researcher has seen what the network contains. A column of text
inputs on the roles step would be that feature built twice, at the moment it is least useful.

### One mapping for the batch

The preview posts **the first file only** and the mapping it produces applies to all of them. That is
a deliberate rule rather than an optimisation - files imported together are files of one shape, which
is why they are being imported together - and posting all of them would be wrong on its own terms, since
the preview endpoint reads one network's worth of sheets and four workbooks would arrive as four
`nodes` sheets.

A file whose headers do not match is **not** detected early. It is sent like any other and reports its
missing columns as its own row-level errors, which is exactly the intended behaviour and is why nothing
here inspects a file's contents. The mapping step says on screen that heterogeneous files should be
imported one at a time.

### The two baselines, kept apart

This is the distinction that matters most, because conflating them is how the wrong thing gets marked:

| | What it is | On the wire |
|---|---|---|
| The **project's baseline flag** | `NETWORK.is_baseline`, at most one per project. What the comparison view measures against. | `baseline=true` |
| The **base of this batch's variant edges** | The network the others are recorded as `CONFIGURATION_VARIANT`s of. | `baseNetworkId=<id>` |

The roles step asks one question and it feeds both - but only when the project has none. When the
project already has a baseline, `importPlan` leaves the flag exactly where it is and uses the chosen
file as the edge base alone, and the step **says so before anything is sent**. That matters more than
it looks: a second baseline is a 409 on the *baseline* request, which is the first one, so it would
take every variant behind it down with it.

`DataImportStore.probeBaseline` is the one read that answers this - `GET /projects/{id}/networks`, kept
for one boolean and one name. It is read here rather than through `NetworksStore` for the reason
`SideBySideStore` gives: that store owns the dashboard's selection and its `deleteMany`, and a wizard
should not have either one injection away. **A failed read degrades to `UNKNOWN` and sets no flag**,
which is the asymmetry that matters - an unset flag costs a checkbox later, a wrongly set one costs the
batch - and the step says so with a Retry beside it.

### Sequenced, baseline first, stopping at nothing

`importBatch` is a loop, not a batch endpoint. A batch of files is *N* of these
requests, sequenced by the wizard rather than a batch endpoint: the two-stage report is per network and
so is the transaction. A batch endpoint would still validate each file on its own, still commit each
on its own, and still answer with N reports - and it would put the sequencing rule on the far side of
an HTTP call, where the wizard could no longer show it happening.

The baseline goes first because `baseNetworkId` names a network that has to exist. The edge is written
inside the same transaction that creates the network, so a variant is never briefly a network
with no edge - which is why this loop has nothing to clean up after a failure, and why the provenance
tree can be read while the batch is still running.

Nothing stops the sequence. A refused file is **content, not an error** - `_error` is never written
here, exactly as `NetworksStore.deleteMany` never writes its own - and the run carries on. The only way
one file reaches another is `attemptable`: a **variant** whose baseline was not created has nothing to
point at and is *not attempted*, rather than imported bare as an independent network, because a network
that arrived as a root is indistinguishable from a file the wizard was told was independent.
Independent files are unaffected and still import.

Every file is seeded `PENDING` before the first request and rewritten the moment its answer arrives, so
the report is accurate at every moment rather than only at the end - `deleteMany`'s discipline, for the
same reason.

### The report reads as what it is

`batchSummary` has three settled verdicts and `PARTIAL` is one of them, because a batch that
created some networks and refused others says so at the top rather than reading as either
a success or a failure. Green would hide the file that has to be fixed; red would suggest the ones
that worked have to be imported again, which is the lottery a batch-wide transaction was rejected
to avoid.

Below it, one card per file with its status, the name and version of the network it created, and its
own two-stage report behind a disclosure - open for a refused file, because the rows are the remedy.
That report is `ImportReportViewComponent`, which is the single import's own rendering extracted
unchanged; the **verdict** above it is projected in through `<ng-content />`, since that sentence is the
one part the two callers genuinely differ about. A second implementation would have been a second place
for the two to drift, in the direction that matters most: a refused file's rows are the only account it
gets.

*Back to roles* is offered only while nothing was created. Re-running a batch that already made
networks would make them **again** under the next version number rather than replacing them; the remedy
the report names is to fix the named files and import those files.

### The derived step, extended

The roles step is derived from `roles()` - the assignment, which is the thing the step is *about*,
exactly as the preview is what the mapping step is about - **and** from `batch()`, which is a function
of the files. So a roles step with no roles to assign is unrepresentable, and so is one over an upload
that is not a batch. A stored step index would have made "on the roles step with one workbook" a state
to guard against in three places.

---

## Where the units show up

- Step 2 confirms the period, horizon and rounding policy. When the upload had no `network_meta` sheet
  this is a **default** the wizard has to ask about, because every value in a column without a
  `*_unit` column is read in that period's unit. For a batch it applies to every file, like the mapping.
- The report lists the resolution findings twice: inline in the row tables, addressed to
  the line that caused them, and in their own section with the declared value, the converted periods and
  the **suggested period** - the remedy, which belongs next to the arithmetic.

## Where the layout shows up

`pos_x`/`pos_y` (`posX`/`posY` in XML) travel with every format, so a network arranged
by hand on the canvas comes back arranged. A node the editor never positioned exports empty and
re-imports as "no layout", which the editor answers with auto-layout - not as `0,0`, which would stack
the network on the origin and look like a layout rather than the absence of one.

Sample datasets to exercise all of this, and what each should report, are in
`../../../../../snrm-backend/samples/README.md`. `MANUAL-TEST.md` beside this file walks
a four-workbook batch end to end, including the fork forest it should draw.
