# Networks side by side - manual test script (FR-25, FR-27, FR-31)

Its own numbering, starting at 1. This covers the **structural** comparison of `features/comparison`
- the window of FR-25, the collapse of FR-27 and the metric filter of FR-31 - not the metric matrix
beside it (FR-10), which the matrix's own screen covers. Where a step exists only to make the next
one meaningful it says so.

The target of the walk is the reading FR-25 exists for: **four variants of one network open in a
2 × 2, clicking `DC-1` lighting it up in three panes and reporting its absence in the fourth** - and
then the reading the raised cap exists for: **twelve networks opening as twelve miniatures, and one
press filling them in.** Sections A–B build the first; C is the grid rule; D is the shared selection;
E is the collapse (FR-27); **E2 is the metric filter (FR-31)**; F is the twelve-pane window, the cap
and the URL; G is the read-only contract and the link to the matrix; H is the degraded cases.

> **Run this maximised.** At twelve panes the grid is four columns, and four columns of miniature on
> a half-width window is the thing the cap exists to keep out. Below a tablet the grid honestly drops
> to one column (step 47).

## Setup

**S1.** Backend running: `mvnw.cmd spring-boot:run` in `../snrm-backend`.
→ <http://localhost:8080/actuator/health> returns `{"status":"UP"}`.

**S2.** Frontend running:

```bash
npm start
```

→ Compiles clean; the app serves at <http://localhost:4200>.

**S3.** Sign in and create a project named `Side by side`.

---

## A. Four variants of one network

The view compares *configurations*, so it needs several networks that share most of their element
names. Forking is how those come to exist, which is also why the panes end up named
`Baseline v1 … v4`.

**1.** Open the project, press **Products**, add `Gearbox` with unit value `250`.
→ The amber "no products yet" banner clears.

**2.** Press **Import network (CSV · Excel · XML)** and import a sample from
`../snrm-backend/samples/four-echelon-playback/`, naming it `Baseline`.
→ The wizard reaches step 3 with no errors and the network opens in the editor. Note the node names
- one of them is `DC-1`.

**3.** In the editor's run panel, run a **Baseline** run (no scenario) and let it finish.
→ The report loads in place, and the network is now **frozen**. This step exists for two
reasons: the freeze is what raises the fork prompt in the next step, and it is what puts a *Frozen*
badge on one pane in section F.

**4.** Try to move a node on the canvas.
→ The fork prompt appears. Choose **Fork a variant**, note `+capacity at PLANT-1`, and confirm.
→ The editor moves to `Baseline v2`.

**5.** Navigate back to `Baseline v1` (project dashboard → its row → **Open**), try an edit again and
fork twice more, noting anything you like.
→ The dashboard now lists `Baseline` **v1, v2, v3 and v4**. Only v1 carries *Frozen*.

**6.** Open **`Baseline v3`** in the editor, click the node named `DC-1`, press **Delete** and
confirm.
→ `DC-1` and its links are gone from v3. This is the structural difference the whole walk is about.

---

## B. Opening the window

**7.** On the project dashboard, tick the checkboxes of all four `Baseline` rows.
→ The actions menu enables and reads **Actions · 4 selected**.

**8.** Open the menu without clicking anything.
→ Three entries: **Delete…**, **Export as a project…**, **Compare side by side…**. Under the third,
a sentence stating the cap: *"Up to 12 networks at once - 12 fills a grid of four columns and three
rows, so the panes are small and the last row scrolls, and a thirteenth would take a fourth row.
Above 6 panes each one opens as a title and a miniature, with its structural metrics collapsed and
one click away."* **The cap is stated here, before the window exists** (FR-25), and so is what the
larger grids cost.

**9.** Choose **Compare side by side…**.
→ A **new browser window** opens on `/projects/<id>/comparison/structure?ids=…`, titled *Networks
side by side*. The dashboard window is untouched - same scroll position, same four rows still ticked.

> If your browser blocks the pop-up, the dashboard raises an amber banner with the address as a
> link. That is a feature of the ids being in the URL, not a workaround: open it and continue.

---

## C. The grid (`columns = ⌈√n⌉`, `rows = ⌈n / columns⌉`)

**10.** Look at the layout of the four panes.
→ A **2 × 2**. Each pane shows, top to bottom: the network's name with its version badge, a
*Baseline* / *Frozen* / *Editable* badge and its clock, the miniature, the node and link counts, and
that network's topological suite under a **Structural metrics** heading with its three most critical
nodes. Four panes is within six, so **every suite is open** - this is exactly what the window has
always drawn (FR-27's default). Beside the legend above the grid: a **Collapse all** button and the
sentence *"4 panes fit in 2 rows, so each one opens showing its structural metrics."* Under both, a
**Metrics shown in every pane** block with every box ticked - section E2 is that control.

**11.** In the address bar, delete one id from `?ids=` and press Enter.
→ Three panes, laid out **two over one** (⌈√3⌉ = 2 columns, ⌈3/2⌉ = 2 rows).

**12.** Delete another.
→ Two panes, **left and right**.

**13.** Put both ids back (or press Back twice).
→ The 2 × 2 returns. **The window survived being edited and reloaded**, which is what the ids being
in the URL buys.

---

## D. The shared by-name selection - the point of the view

**14.** In the **first** pane, click the dot labelled `DC-1`.
→ Three things at once:
> - A blue selection ring appears on `DC-1` in **v1, v2 and v4**, each of which prints
>   **"● DC-1 is here."** above its miniature.
> - **v3 prints "○ DC-1 is not in Baseline v3."** - the absence stated, not left to be noticed.
> - The header reads **Selected node `DC-1`** with an amber badge **in 3 of 4**, followed by
>   *"- that is a structural difference between these configurations, which is what this view is
>   for."*

**15.** Click `DC-1` in a *different* pane.
→ Nothing changes: it is the same selection, by name. Click it a second time in the same pane.
→ The selection clears everywhere and the header returns to its instruction line.

**16.** Click a node that every variant still has - `PLANT-1`, say.
→ Lit in all four panes, and the badge is **green: in 4 of 4**. That is the reading "no structural
difference here", and it should be as quick to see as the amber one.

**17.** Click a **link** - an arc, not a dot. Any arc into or out of `DC-1` is the interesting one.
→ The header names it `PLANT-1 → DC-1`, three panes light that arc, and v3 says
**"PLANT-1 → DC-1 is not in Baseline v3."** A link is matched on **its two endpoint names**, and the
direction is part of the match.

**18.** Click one of the rows under **Most critical nodes** in any pane.
→ The same by-name selection: that node lights in every pane that has it. The numbers and the
picture answer one gesture.

**19.** Click **empty space** inside any pane's miniature.
→ The selection clears in every pane. So does **Clear selection** in the header.

---

## E. The collapse, on a window small enough to check by eye (FR-27)

Four panes, all expanded - the state section C left. This section is the control; section F is what
the control is *for*.

**20.** In the first pane, press the **Structural metrics** heading (the chevron reads ▾).
→ That pane's metrics, its *Most critical nodes* table and its footnote all disappear. What stays is
the pane's **title, its badges, its miniature and its node/link counts** - the chevron turns ▸. **The
miniature never collapses**: it is what the window exists to show. No other pane moved.

**21.** Look at the control above the grid.
→ Still **Collapse all**, and hovering it reads *"1 of 4 panes are already collapsed; this collapses
the rest, so one press always takes every pane to the same place."* **A mixed window never says
Expand all** - the label always names the state pressing it will produce.

**22.** Open your browser's network panel, clear it, then press the same pane's heading twice
(closed → open → closed → open, ending open).
→ The numbers come back **instantly**, with no spinner, and **no request is made** - not on collapse
and not on expand. A collapse is a display state over data the window already holds; a collapsed pane
that had skipped its request would arrive empty at the moment you opened it.

**23.** Press **Collapse all**.
→ All four panes become a title and a miniature, and the button now reads **Expand all**.

**24.** Press one pane's heading to open it alone.
→ The button goes back to **Collapse all** (one of four is expanded - a mixed window). Press it.
→ **That one pane closes and the other three stay closed.** Every pane ended in the same place; the
button did not flip each pane and leave you working out which half moved.

**25.** Press **Expand all**.
→ All four fill in.

**26. Keyboard and screen reader.** Tab until focus lands on a pane's **Structural metrics** heading.
→ It is a real button - focus ring, reachable by Tab. Press **Space**, then **Enter**.
→ Each press toggles that pane. A screen reader announces it as *"Hide the structural metrics of
Baseline v2, expanded"* / *"Show the structural metrics of Baseline v2, collapsed"* - it names the
network, because a full window otherwise offers twelve buttons with the same name.

---

## E2. Choosing which metrics the panes print (FR-31)

Still the four-pane window, every suite expanded - the state step 25 left. **Expand any pane you
collapsed before starting**, since a collapsed pane hides the rows this section is about.

**26a.** Look above the grid, under the legend and the *Collapse all* control.
→ A bordered block headed **Metrics shown in every pane** with **one checkbox per metric, all
ticked** - `Density`, `Single points of failure - nodes`, `Single points of failure - arcs`, `Single points of
failure - total`, `Average path length`, `Clustering`, `Node criticality`, `Robustness - random`,
`Robustness - targeted` - in **the same order the rows appear in inside each pane**. Beneath them,
**Select all** (disabled), **Select none** (enabled) and the line *"Showing all 9 metrics."*

> The list is built from what the panes actually returned, not from a table in the browser. If your
> backend computes a metric this build has no name for, it appears here **labelled by its code** -
> that is FR-31 behaving correctly, not a defect.

**26b.** Untick **Density**.
→ The `Density` row disappears from **all four panes at once**, and the summary reads *"Showing 8 of
9 metrics."* **Select all** is now enabled. Nothing else moved: the rows below closed up, the
miniatures are untouched, and the criticality tables are still there.

**26c.** Open your browser's network panel, clear it, then untick and re-tick three more boxes.
→ **No request is made.** Each pane has held its whole suite since the window opened, so this is a
view over numbers already here - the same promise the collapse makes in step 22, and the footer says
both.

**26d.** Untick **Node criticality**.
→ The **Most critical nodes** table and its footnote disappear from every pane. That table *is* that
metric - per-node, which is why it is never one of the rows above it - so a filter that left it
behind would have missed the largest block of figures on the screen.

**26e.** Press **Select none**.
→ Every box clears, every pane's numbers go, and each pane prints *"No metrics are selected, so this
pane shows its shape and no numbers. Tick one above, or press Select all."* The summary reads
*"Showing none of the 9 metrics."*, **Select none** is now disabled and **Select all** is enabled.

> That sentence is deliberately **not** *"No structural metrics were returned for this network"* -
> one is a fact about the network, the other is the consequence of a control you are holding, and a
> pane that reported the second as the first would be blaming the data for the filter.

**26f.** Tick two boxes - say **Density** and **Single points of failure - total**.
→ Both come back in every pane, in suite order rather than the order you ticked them. The summary
reads *"Showing 2 of 9 metrics."* and **both buttons are live**: this is the mixed state, which is
what a filter in use looks like, and both destinations are one press from here. That is why there are
two controls where the collapse has one.

**26g.** Hover each button.
→ *Select all*: *"Show all 9 metrics in every pane again. Nothing is re-read…"*. *Select none*:
*"Hide every metric, leaving each pane its title and its shape…"*. Press **Select all**.
→ Nine boxes, nine rows per pane, and *Select all* goes dead again.

**26h. Keyboard and screen reader.** Tab into the block.
→ Focus lands on each checkbox in turn with a visible ring; **Space** toggles the one under focus. A
screen reader announces the group name *"Metrics shown in every pane"* before the first box, because
it is a real `<fieldset>` with a `<legend>` and every box has a real `<label>`. The disabled button
is announced as disabled rather than silently ignoring the press.

**26i.** Untick four metrics, then collapse two panes and expand them again.
→ The filter is untouched by the collapse - the two controls do not know about each other. Now
**reload the whole page**.
→ **All nine are ticked again.** Like the collapse, the filter belongs to *this window* and is not
stored on the device: the window is opened per comparison, so a filter carried into the next one - of
other networks, for another question - would hide numbers nobody chose to hide. The footer says so.

**26j.** Untick three metrics, then edit `?ids=` down to two networks and press Enter.
→ **All boxes are ticked again**: new ids are a new subject, and a filter is a question about the
numbers *these* configurations report. Put the ids back, untick three again, and instead stop the
backend and press **Try again** on the red banner (step 45 covers that path).
→ **The three stay unticked.** A retry is the same ask on the same networks, not a new comparison -
the same split the collapse makes in step 45, for the same reason.

**26k.** Untick everything except **Robustness - targeted**, then press **Collapse all**, then expand
one pane.
→ The two controls are independent: collapsing hides the whole suite, and the filter decides what is
in it when it comes back. The pane shows one row.

Leave the window with **Select all** pressed before continuing.

---

## F. Twelve panes - the raised cap and what pays for it (FR-25, FR-27)

**27.** Back on the dashboard, get the project to **twelve** networks. The quick way is the row
menu's **Duplicate network** (FR-26): open any row's **⋯** menu, choose it, accept the prefilled
name, and repeat until the table holds twelve.
→ Twelve rows.

**28.** Tick all twelve.
→ The menu reads *Actions · 12 selected* and **Compare side by side… is enabled**. Seven was refused
by the first cap, and eleven and twelve by the second.

**29.** Choose it. This is the reading the amendments exist for.
→ A new window of **twelve panes in 4 columns × 3 rows, with no gap in it**, and **every pane opens
collapsed**: twelve titles, twelve miniatures, twelve closed headings. *Shapes beside shapes.* Above
the grid the control reads **Expand all** - every pane is collapsed, so that is the state pressing it
produces - beside the sentence *"12 panes take more than 2 rows, so each opens as a title and a
miniature - shapes beside shapes, with the numbers a click away."*

**29a.** Drop one id from `?ids=` so eleven panes are drawn, then put it back.
→ Eleven draws **the same 4 × 3 grid with one empty cell in the last row**; twelve fills it. That is
the whole of the raise: the cap moved to the count the grid already had room for, and nothing about
the window's size or cost changed with it.

**30.** Press **Expand all**.
→ All twelve fill in with their metrics and criticality tables. The window is now tall - **the third
row scrolls**, which is exactly what the collapse is there to spare you and the accepted cost of
the third row.

**31.** Press **Collapse all**, then open just two panes you want to weigh against each other.
→ Two sets of numbers among twelve shapes. That is the working state a window this size is for.

**32.** Reload the window with your browser's network panel open, and watch the requests.
→ Twelve `GET /networks/{id}/metrics/topological` fire **one after another**, not at once - and they
fire **even though every pane opened collapsed**. `NODE_CRITICALITY` is one maximum-flow per node, so
twelve at once would make every pane slow rather than the first pane fast; and a collapsed pane that
skipped its request would have nothing to show when you opened it. **This is the only thing the two
extra panes cost** - two more suites, no new column and no new row.

**33.** Note the panes after the reload.
→ **Collapsed again.** The collapse belongs to *this window*, not to the device: the footer says so,
and there is no `localStorage` behind it. The window is opened per comparison, so a collapse
remembered across openings would be a preference nobody set - deliberately the opposite call from
the editor's playback speed, which applies to a network you return to.

**34.** Edit `?ids=` down to four networks and press Enter.
→ Four panes in a 2 × 2, **expanded** - the default follows the count, and up to six panes it is what
the window has always done.

**35.** Back on the dashboard, tick a thirteenth network (duplicate one more if needed).
→ *Actions · 13 selected*, **Compare side by side… disabled**, and the sentence under it now ends
*"…Untick 1 to open the rest side by side."* **The cap is stated where the action is offered** - and
thirteen is refused because it is the first count that takes a fourth row.

**36.** In the comparison window's address bar, add a thirteenth id to `?ids=` by hand and reload.
→ Still twelve panes, plus an amber banner naming the id it did not draw and restating the cap. **A
cap is a cap, not a refusal** - a bookmark that has grown past the limit still shows you something.

**37.** Edit `?ids=` to `3,3,banana` and reload.
→ One pane, and a banner reporting both the folded repeat and the unreadable entry, quoting
`"banana"` back at you.

**38.** Bookmark the twelve-pane URL, close the window, and open the bookmark.
→ The same twelve panes, collapsed. Nothing was held in the dashboard's memory.

---

## G. Read-only, and the question it does not answer

**39.** Look for a way to change anything: an edit control, a run button, a delete, a draggable node.
→ **There is none.** Nodes and arcs are click targets and nothing else. The *Frozen* badge on
`Baseline v1` is information about that configuration, not a gate - nothing here could be
refused by a freeze.

**40.** Read the line under the window's title.
→ *"Structural and read-only - nothing here edits, runs or deletes. For how these configurations
performed, open the metric matrix…"*, with **metric matrix** a link. Collapsing is not an exception
to that line: it changes what is on screen and nothing about a configuration.

**41.** Press it.
→ This window navigates to `/projects/<id>/comparison` - the variants × metrics matrix of FR-10.
Press Back.
→ The panes return, with the selection cleared (a new visit is a new reading).

**42.** Watch the panes as the window first opens (reload it and look quickly, with the suites
expanded).
→ The miniatures appear first, then each pane's suite fills in **one after another**, each with its
own spinner and the note *"one maximum-flow per node"*. That order is deliberate: the structures are
read in parallel and the suites in sequence.

**43.** With the window open and settled, click nodes and links for a while, collapse and expand a
few panes, and tick metrics on and off, then open your browser's network panel and reload nothing.
→ **No further requests.** A selection is a name matched against data already in the window; the
structural suite is a property of the network, not of what is selected in it; and a collapse and a
metric filter are both display states over rows already held.

---

## H. Degraded cases

**44.** On the dashboard (other window), delete one of the networks the comparison window is showing
- pick an editable one and use its row's own Delete. Then reload the comparison window.
→ That pane's header reads **Network #NN** with a *Not in this project* badge and one sentence
saying it may have been deleted. The other panes are unaffected, and the grid re-flows for the count
it still draws.

**45.** Stop the backend and reload the comparison window.
→ A red banner with **Try again**, and no panes. Restart the backend and press **Try again**.
→ Everything comes back, laid out by the default for the count. **Try again** re-reads the data and
touches neither which panes are collapsed nor which metrics are ticked - it is the same ask on the
same networks, not a new comparison - but a full page reload *is* a new opening, so the FR-27 default
and the FR-31 all-ticked default both answer again (steps 33, 26i).

**46.** Import a network with **no `pos_x`/`pos_y`** columns (drop them from the sample's `nodes`
sheet) and compare it against one that has them.
→ The coordinate-less pane draws by **echelon** and says so underneath: *"Some nodes carry no canvas
coordinates, so this is laid out by echelon…"*. Two panes drawn by two different arrangements cannot
be compared by eye, so the pane has to say which one it drew.

**47.** Narrow the window to phone width.
→ One column, panes stacked, everything still readable - and the per-pane collapse still works, which
is what makes a twelve-network comparison usable at all on a narrow screen. Below a tablet there is no
side-by-side to be had, which is the same judgement the cap makes at the other end.

---

## What this script does not cover

- The **metric matrix** (FR-10) - its own view, its own reading.
- The **results dashboard's** miniature (FR-22). It is the same component, generalised in place, so
  `../simulations/MANUAL-TEST.md` is the regression check that FR-25 changed nothing there: the
  tints, the period cursor, the scope line and the legend must all behave exactly as before.
- **Set delete** and **Export as a project** (FR-23, FR-24) - `../projects/MANUAL-TEST.md`,
  sections G–N.
