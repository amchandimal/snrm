import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';

import {
  Duration,
  Id,
  NODE_TYPES,
  NetworkLink,
  NetworkNode,
  NodeProduct,
  NodeProductRequest,
  NodeType,
  Rate,
  TimeUnit,
} from '../../../core/models';
import {
  convertDuration,
  convertRate,
  durationInPeriods,
  formatNumber,
  ratePerPeriod,
} from '../../../core/time-units';
import { UnitValueComponent, UnitChange } from '../../../shared/unit-value/unit-value.component';
import { NODE_TYPE_PROFILES } from '../echelon-rules';
import { CAPTION_MAX_LENGTH, captionChanged } from '../element-captions';
import { ElementInspectorComponent } from '../element-inspector/element-inspector.component';
import { LinkAttributes, NodeAttributes, toNodeProductRequest } from '../editor-commands';
import { NetworkDashboardComponent } from '../network-dashboard/network-dashboard.component';
import { NetworkEditorStore } from '../network-editor.store';
import { UnitPreferencesService } from '../unit-preferences.service';

/** Node fields the panel edits through the bulk PATCH path. */
type NodeField = keyof NodeAttributes;
/** Link fields the panel edits through the bulk PATCH path. */
type LinkField = keyof LinkAttributes;
/** The node fields that carry a bare number, so a parsed value is assignable without a cast. */
type NodeNumberField = 'fixedCost' | 'varCost' | 'failureProb' | 'lat' | 'lng';

/** What the panel shows for one plain field across the selection. */
interface FieldState {
  /** The shared value as text, or '' when the selection disagrees. */
  readonly value: string;
  /** True when the selected elements hold different values - rendered as a `-` placeholder. */
  readonly mixed: boolean;
  /** True when every selected element has the field unset. Only nullable fields can be. */
  readonly empty: boolean;
}

/**
 * What the panel shows for one unit-bearing field across the selection.
 *
 * The value and the unit are `mixed` independently in principle but reported as one flag, because
 * the box shows one number: 6 HOUR beside 6 DAY is not "the same value in different units", it is
 * two different lengths of time, and pretending otherwise is how a bulk edit overwrites data.
 */
interface UnitFieldState {
  readonly value: number | null;
  readonly unit: TimeUnit;
  readonly mixed: boolean;
  /** True when every selected element has the field unset - an unconstrained capacity. */
  readonly empty: boolean;
}

const EMPTY_FIELD: FieldState = { value: '', mixed: false, empty: true };

/**
 * The property side panel.
 *
 * > "the property side panel edits single or bulk attributes"
 *
 * ## Why fields commit on blur, one at a time
 *
 * A bulk PATCH leaves omitted fields alone, and that is the whole point of it: setting one capacity
 * across twelve nodes must not overwrite the eleven attributes on which those nodes differ. So the
 * panel sends *only the field the user just left*, never a whole form. It also gives undo a natural
 * granularity - "Set capacity on 12 nodes" is one step back, not twelve.
 *
 * ## Why clearing a value is a different button
 *
 * `PATCH` cannot express "make this null": an omitted field there means "leave alone". Only
 * `PUT /nodes/{id}` can clear one, and only for a single element, since a full replacement needs
 * every other field's current value. Hence the small × beside each nullable field, disabled
 * whenever more than one element is selected.
 *
 * ## Units
 *
 * Every duration and rate is an `app-unit-value`: a number box and a unit dropdown. Changing the
 * unit **restates** the value - 36 h becomes 1.5 d - and lands as one command, so it is one Ctrl-Z
 * away and the original pair comes back exactly. Across a multi-selection each element restates its
 * own number, which is why the unit handlers hand the store a function rather than a value.
 *
 * The line under each field says what the engine will make of the figure. It is a courtesy, not a
 * warning: the authoritative account of a conversion that loses something is the resolution banner,
 * which asks the server.
 *
 * ## The caption, which breaks both of those rules on purpose (FR-30)
 *
 * **The text is single-selection only and the checkbox is not.** The two are split: a caption is
 * prose about *this* element, so writing one across a multi-selection would put the same sentence on
 * five different things - the reason the per-product rows are single-node too - while hiding or
 * showing every caption at once is exactly what a reader preparing a figure wants. So the field is
 * replaced by a sentence on a multi-selection and the checkbox stays live beneath it.
 *
 * **And emptying the caption field clears it, so it has no × button.** Every other nullable field
 * here needs one because a PATCH cannot say "set to nothing" - but the backend gave this one field a
 * meaning for the empty string on the bulk endpoint the panel already writes through
 * (`element-captions.ts`, `com.snrm.network.Captions`). A × beside a field the keyboard can already
 * clear would be a second gesture for one action, and the hint under the field says so instead.
 *
 * ## The simulation card (FR-18)
 *
 * `app-element-inspector` sits above the form and shows what the selected element *did* in the run
 * being played. It is here rather than in a fourth panel because this is already the surface a
 * selected element is described on, and it is always open - a selection that opened a second
 * surface would put an element's attributes and its behaviour in two places for the same click. The
 * card gates itself on playback being enabled and on exactly one element being selected, so nothing
 * in this component's own branches changes.
 *
 * ## The empty selection is the network dashboard (FR-19)
 *
 * The `none` branch used to say *Nothing selected*. It now hosts `app-network-dashboard`, which
 * answers the question the panel was always asking - *what is selected, and what is it?* - with
 * **the network**: the live playback figures, the run's metric suite, and the structural suite that
 * needs no run at all.
 *
 * **No gesture was added for it.** Clicking empty canvas already unselects everything and Escape
 * already does the same, so both already produced `selectionKind() === 'none'`; this is
 * that state given content. The dashboard is passive - it reads three stores and writes only the
 * selection, and only when a criticality row is clicked - so deselecting in order to *clear* a
 * selection still does exactly that.
 *
 * The panel's own header steps aside on that branch, because a header naming a *selection* has
 * nothing to say about the empty one; the dashboard names the network instead, and keeps the old
 * sentence as a one-line hint at its foot.
 */
@Component({
  selector: 'app-property-panel',
  standalone: true,
  imports: [UnitValueComponent, ElementInspectorComponent, NetworkDashboardComponent],
  templateUrl: './property-panel.component.html',
  styleUrl: './property-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PropertyPanelComponent {
  readonly store = inject(NetworkEditorStore);
  private readonly units = inject(UnitPreferencesService);

  private readonly nameInput = viewChild<ElementRef<HTMLInputElement>>('nameInput');

  readonly nodeTypes = NODE_TYPES;
  readonly typeProfiles = NODE_TYPE_PROFILES;
  /** `caption VARCHAR(200)`, so the input can stop the typing rather than the server (FR-30). */
  readonly captionMaxLength = CAPTION_MAX_LENGTH;

  /** Per-field validation message, keyed by field name. Cleared when the field next validates. */
  readonly fieldErrors = signal<Readonly<Record<string, string | undefined>>>({});

  /**
   * Draft text per field, so a half-typed value survives change detection.
   *
   * Reset whenever the selection changes - the draft belongs to the elements it was typed against.
   * The unit-bearing fields keep their own drafts inside `app-unit-value`.
   */
  readonly drafts = signal<Readonly<Record<string, string | undefined>>>({});

  /** Product chosen in the "add a product" picker, as the select reports it: an id, or ''. */
  readonly productToAdd = signal('');

  /**
   * Name typed into the "new product" field beside the picker.
   *
   * The picker can only offer what the catalogue holds, and a project started from scratch has an
   * empty one - at which point a node that needs demand has no way to be given any without leaving
   * the canvas for the catalogue screen. This is the shortcut: it creates the product in
   * the project and puts it on the node in one gesture. The catalogue screen remains where a product
   * is renamed, priced or deleted; this only adds one.
   */
  readonly newProductName = signal('');
  readonly newProductError = signal<string | null>(null);

  readonly kind = this.store.selectionKind;
  readonly nodes = this.store.selectedNodes;
  readonly links = this.store.selectedLinks;

  readonly editable = computed(() => !this.store.readOnly());

  readonly title = computed(() => {
    switch (this.kind()) {
      case 'node':
        return this.nodes()[0]?.name ?? 'Node';
      case 'nodes':
        return `${this.nodes().length} nodes`;
      case 'link':
        return 'Link';
      case 'links':
        return `${this.links().length} links`;
      case 'mixed':
        return `${this.nodes().length} nodes, ${this.links().length} links`;
      default:
        // Unreachable on screen: the header is not rendered on the empty selection, where the
        // dashboard supplies its own naming the network. Kept as the honest fallback for a
        // `SelectionKind` this switch does not enumerate.
        return 'Nothing selected';
    }
  });

  /** Endpoint names for a single selected link, read-only - endpoints cannot be repointed. */
  readonly linkEndpoints = computed(() => {
    const link = this.links()[0];
    if (!link || this.links().length !== 1) {
      return null;
    }
    const nodes = this.store.nodes();
    return {
      source: nodes.get(link.sourceNodeId)?.name ?? `#${link.sourceNodeId}`,
      target: nodes.get(link.targetNodeId)?.name ?? `#${link.targetNodeId}`,
    };
  });

  /** Distinct warnings across the selected links, for the badge list. */
  readonly warnings = computed(() => [...new Set(this.store.selectedLinkWarnings())]);

  // ------------------------------------------------------------- unit-bearing fields

  readonly nodeCapacity = computed(() =>
    commonRate(
      this.nodes(),
      (node) => node.capacity,
      this.units.defaultFor(
        UnitPreferencesService.NODE_CAPACITY,
        'rate',
        this.store.periodUnit(),
      ),
    ),
  );

  readonly nodeProcessingTime = computed(() =>
    commonDuration(this.nodes(), (node) => node.processingTime, this.store.periodUnit()),
  );

  readonly linkLeadTime = computed(() =>
    commonDuration(this.links(), (link) => link.leadTime, this.store.periodUnit()),
  );

  readonly linkCapacity = computed(() =>
    commonRate(
      this.links(),
      (link) => link.capacity,
      this.units.defaultFor(
        UnitPreferencesService.LINK_CAPACITY,
        'rate',
        this.store.periodUnit(),
      ),
    ),
  );

  /** Per-product rows of the single selected node (`NODE_PRODUCT`). */
  readonly nodeProducts = this.store.selectedNodeProducts;
  readonly availableProducts = this.store.availableProducts;

  constructor() {
    effect(
      () => {
        // Touching the selection signals is what makes this reset when it changes.
        this.store.selectedNodeIds();
        this.store.selectedLinkIds();
        this.drafts.set({});
        this.fieldErrors.set({});
        this.productToAdd.set('');
        this.newProductName.set('');
        this.newProductError.set(null);
      },
      { allowSignalWrites: true },
    );
  }

  /** Focus and select the name field - what the drop gesture calls after creating a node. */
  focusName(): void {
    // A microtask, so the newly selected node's panel has rendered its input first.
    queueMicrotask(() => {
      const input = this.nameInput()?.nativeElement;
      input?.focus();
      input?.select();
    });
  }

  // ------------------------------------------------------------------- field reading

  nodeField(field: NodeField): FieldState {
    const draft = this.drafts()[field];
    const state = commonValue(this.nodes(), field);
    return draft === undefined ? state : { ...state, value: draft };
  }

  linkField(field: LinkField): FieldState {
    const draft = this.drafts()[field];
    const state = commonValue(this.links(), field);
    return draft === undefined ? state : { ...state, value: draft };
  }

  errorFor(field: string): string | null {
    return this.fieldErrors()[field] ?? null;
  }

  onDraft(field: string, value: string): void {
    this.drafts.update((drafts) => ({ ...drafts, [field]: value }));
  }

  // ------------------------------------------------------------------ field commits

  /** Commit a node text field. `name` is single-selection only - names are unique per network. */
  async commitNodeText(field: 'name' | 'region', label: string): Promise<void> {
    const raw = (this.drafts()[field] ?? '').trim();
    if (this.drafts()[field] === undefined) {
      return;
    }
    if (field === 'name' && !raw) {
      this.setError(field, 'A name is required.');
      return;
    }
    if (field === 'name' && this.nodes().length !== 1) {
      return;
    }
    if (!raw) {
      // Empty means "clear", which PATCH cannot express - the × button is the way to do it.
      this.setError(field, 'Use the × to clear this field.');
      return;
    }
    this.clearError(field);
    await this.store.applyNodeAttributes(nodeAttribute(field, raw), label);
    this.dropDraft(field);
  }

  async commitNodeNumber(field: NodeNumberField, label: string): Promise<void> {
    const raw = this.drafts()[field];
    if (raw === undefined) {
      return;
    }
    const parsed = this.parseNumber(field, raw, field === 'failureProb');
    if (parsed === null) {
      return;
    }
    await this.store.applyNodeAttributes(nodeAttribute(field, parsed), label);
    this.dropDraft(field);
  }

  async commitNodeType(value: string): Promise<void> {
    const type = value as NodeType;
    if (!NODE_TYPES.includes(type)) {
      return;
    }
    await this.store.applyNodeAttributes({ type }, 'type');
  }

  async commitLinkNumber(field: 'unitCost' | 'failureProb', label: string): Promise<void> {
    const raw = this.drafts()[field];
    if (raw === undefined) {
      return;
    }
    const parsed = this.parseNumber(field, raw, field === 'failureProb');
    if (parsed === null) {
      return;
    }
    await this.store.applyLinkAttributes(linkAttribute(field, parsed), label);
    this.dropDraft(field);
  }

  // ---------------------------------------------------------------- captions (FR-30)

  /**
   * The caption on the single selected element, or the draft being typed over it.
   *
   * Read through `nodeField`/`linkField` like every other text field, which is what gives it the
   * draft that survives change detection and the `empty` placeholder.
   */
  captionField(): FieldState {
    const kind = this.kind();
    return kind === 'link' || kind === 'links'
      ? this.linkField('caption')
      : this.nodeField('caption');
  }

  /** State of the *Show caption on canvas* box across the whole selection - it bulk-applies. */
  readonly nodeCaptionVisible = computed(() => captionVisibility(this.nodes()));
  readonly linkCaptionVisible = computed(() => captionVisibility(this.links()));

  /**
   * Commit the caption text of the one selected element (FR-30).
   *
   * **An empty field clears the caption**, which is the one thing a bulk PATCH can normally not do
   * and the reason there is no × here: the backend reads a present-but-blank `caption` on this
   * endpoint as *clear it* (`element-captions.ts`). So the empty string goes out as an ordinary
   * edit, on the ordinary path, one Ctrl-Z from coming back.
   *
   * `captionChanged` is what keeps tabbing through an empty field on an uncaptioned element free:
   * null and `''` are one state for a caption and would compare unequal in the store's generic
   * `sameFieldValue`, which would cost a PATCH and an undo step for a keystroke nobody made.
   */
  async commitCaption(): Promise<void> {
    const draft = this.drafts()['caption'];
    if (draft === undefined) {
      return;
    }
    const element = this.kind() === 'node' ? this.nodes()[0] : this.links()[0];
    const singleSelection = this.kind() === 'node' || this.kind() === 'link';
    if (!element || !singleSelection) {
      return;
    }
    const typed = draft.trim();
    if (typed.length > CAPTION_MAX_LENGTH) {
      this.setError('caption', `A caption is at most ${CAPTION_MAX_LENGTH} characters.`);
      return;
    }
    this.clearError('caption');
    if (!captionChanged(element.caption, typed)) {
      this.dropDraft('caption');
      return;
    }
    if (this.kind() === 'node') {
      await this.store.applyNodeAttributes({ caption: typed }, 'caption');
    } else {
      await this.store.applyLinkAttributes({ caption: typed }, 'caption');
    }
    this.dropDraft('caption');
  }

  /**
   * Show or hide the captions of the whole selection (FR-30).
   *
   * The half of this feature that deliberately *is* a bulk edit: an element with no caption is
   * unaffected on screen either way - an empty caption draws nothing whatever the flag says - so the
   * only thing this can do to a selection is hide, or restore, the annotation the reader can see.
   */
  async commitCaptionVisible(checked: boolean): Promise<void> {
    if (this.kind() === 'node' || this.kind() === 'nodes') {
      await this.store.applyNodeAttributes({ captionVisible: checked }, 'caption visibility');
      return;
    }
    await this.store.applyLinkAttributes({ captionVisible: checked }, 'caption visibility');
  }

  // ------------------------------------------------- unit-bearing commits

  /**
   * A number typed into a unit field: keep the unit on show and write the new number.
   *
   * The *displayed* unit, not each element's own, because that is the one the user was looking at
   * when they typed. On a selection whose units agree the two are the same thing; on one whose units
   * differ the box shows the network's period unit and "3" plainly means three of that.
   */
  commitNodeProcessingTime(value: number): void {
    const unit = this.nodeProcessingTime().unit;
    void this.store.applyNodeAttributes({ processingTime: { value, unit } }, 'processing time');
  }

  commitNodeCapacity(value: number): void {
    const timeUnit = this.nodeCapacity().unit;
    void this.store.applyNodeAttributes({ capacity: { value, timeUnit } }, 'capacity');
  }

  commitLinkLeadTime(value: number): void {
    const unit = this.linkLeadTime().unit;
    void this.store.applyLinkAttributes({ leadTime: { value, unit } }, 'lead time');
  }

  commitLinkCapacity(value: number): void {
    const timeUnit = this.linkCapacity().unit;
    void this.store.applyLinkAttributes({ capacity: { value, timeUnit } }, 'capacity');
  }

  /**
   * A unit picked from a dropdown: restate every selected element's own value in it.
   *
   * One command for the whole selection, so twelve links re-united in hours is one step back. Any
   * number typed but not yet committed travels with the change, so "36, and make that hours" does
   * not cost two undo steps.
   */
  pickNodeProcessingTimeUnit(change: UnitChange): void {
    this.units.remember(UnitPreferencesService.NODE_PROCESSING_TIME, change.unit);
    void this.store.applyNodeAttributesEach(
      (node) => ({
        processingTime: convertDuration(
          withPendingValue(node.processingTime, change.pendingValue),
          change.unit,
        ),
      }),
      'processing time unit',
    );
  }

  pickNodeCapacityUnit(change: UnitChange): void {
    this.units.remember(UnitPreferencesService.NODE_CAPACITY, change.unit);
    void this.store.applyNodeAttributesEach(
      (node) => ({
        capacity: convertRate(withPendingRate(node.capacity, change.pendingValue), change.unit),
      }),
      'capacity unit',
    );
  }

  pickLinkLeadTimeUnit(change: UnitChange): void {
    this.units.remember(UnitPreferencesService.LINK_LEAD_TIME, change.unit);
    void this.store.applyLinkAttributes(
      (link) => ({
        leadTime: convertDuration(
          withPendingValue(link.leadTime, change.pendingValue),
          change.unit,
        ),
      }),
      'lead time unit',
    );
  }

  pickLinkCapacityUnit(change: UnitChange): void {
    this.units.remember(UnitPreferencesService.LINK_CAPACITY, change.unit);
    void this.store.applyLinkAttributes(
      (link) => ({
        capacity: convertRate(withPendingRate(link.capacity, change.pendingValue), change.unit),
      }),
      'capacity unit',
    );
  }

  // ------------------------------------------------------------------ restatement hints

  /** "= 2 periods" - what the clock will make of a declared duration. */
  durationHint(state: UnitFieldState): string | null {
    if (state.mixed || state.value === null) {
      return null;
    }
    const network = this.store.network();
    if (!network) {
      return null;
    }
    const periods = durationInPeriods(
      { value: state.value, unit: state.unit },
      network.periodLength,
      network.roundingPolicy,
    );
    if (state.value > 0 && periods === 0) {
      return '= 0 periods - the engine treats this as instantaneous';
    }
    return `= ${formatNumber(periods)} period${periods === 1 ? '' : 's'} on this network's clock`;
  }

  /** "= 57.14 per period" - a rate is rescaled, never rounded. */
  rateHint(state: UnitFieldState): string | null {
    if (state.mixed || state.value === null) {
      return null;
    }
    const network = this.store.network();
    if (!network) {
      return null;
    }
    const perPeriod = ratePerPeriod(
      { value: state.value, timeUnit: state.unit },
      network.periodLength,
    );
    return perPeriod === null ? null : `= ${formatNumber(perPeriod)} per period`;
  }

  // ------------------------------------------------------------------ per-product rows

  productField(row: NodeProduct, field: 'initialInventory' | 'safetyStock'): string {
    const draft = this.drafts()[productDraftKey(row.productId, field)];
    return draft ?? String(row[field]);
  }

  /** Demand and holding cost, as the shared unit control sees them. */
  productRate(row: NodeProduct, field: 'demand' | 'holdingCost'): UnitFieldState {
    const rate = row[field];
    return { value: rate.value, unit: rate.timeUnit, mixed: false, empty: rate.value === null };
  }

  commitProductRate(row: NodeProduct, field: 'demand' | 'holdingCost', value: number): void {
    const rate: Rate = { value, timeUnit: row[field].timeUnit };
    void this.store.applyNodeProduct(
      row.productId,
      withRate(toNodeProductRequest(row), field, rate),
      field === 'demand' ? 'demand' : 'holding cost',
    );
  }

  pickProductRateUnit(row: NodeProduct, field: 'demand' | 'holdingCost', change: UnitChange): void {
    this.units.remember(
      field === 'demand'
        ? UnitPreferencesService.NODE_PRODUCT_DEMAND
        : UnitPreferencesService.NODE_PRODUCT_HOLDING_COST,
      change.unit,
    );
    const restated = convertRate(withPendingRate(row[field], change.pendingValue), change.unit);
    void this.store.applyNodeProduct(
      row.productId,
      withRate(toNodeProductRequest(row), field, restated),
      field === 'demand' ? 'demand unit' : 'holding cost unit',
    );
  }

  async commitProductNumber(
    row: NodeProduct,
    field: 'initialInventory' | 'safetyStock',
    label: string,
  ): Promise<void> {
    const key = productDraftKey(row.productId, field);
    const raw = this.drafts()[key];
    if (raw === undefined) {
      return;
    }
    const parsed = this.parseNumber(key, raw, false);
    if (parsed === null) {
      return;
    }
    await this.store.applyNodeProduct(
      row.productId,
      withStockLevel(toNodeProductRequest(row), field, parsed),
      label,
    );
    this.dropDraft(key);
  }

  onProductDraft(productId: Id, field: 'initialInventory' | 'safetyStock', value: string): void {
    this.onDraft(productDraftKey(productId, field), value);
  }

  productErrorFor(productId: Id, field: 'initialInventory' | 'safetyStock'): string | null {
    return this.errorFor(productDraftKey(productId, field));
  }

  onProductPicked(value: string): void {
    this.productToAdd.set(value);
  }

  /** Put the product chosen in the picker on this node. */
  async addProduct(): Promise<void> {
    const productId = Number(this.productToAdd());
    if (!Number.isFinite(productId) || productId <= 0) {
      return;
    }
    await this.attachProduct(productId);
    this.productToAdd.set('');
  }

  onNewProductName(value: string): void {
    this.newProductName.set(value);
    if (this.newProductError()) {
      this.newProductError.set(null);
    }
  }

  /**
   * Create a catalogue product and put it straight on this node (FR-01).
   *
   * Two writes, deliberately not one: `POST /projects/{id}/products` adds it to the project, then
   * the same upsert the picker uses attaches it here. Only the second goes on the undo stack -
   * undoing "add product" should take the row off this node, not delete an entry every variant of
   * the project can now see. If the second half fails, the product still exists and is waiting in
   * the picker, which is a better place to be left than with neither.
   */
  async createAndAddProduct(): Promise<void> {
    const name = this.newProductName().trim();
    if (!name) {
      this.newProductError.set('Give the product a name.');
      return;
    }
    if (this.store.creatingProduct() || !this.editable()) {
      return;
    }
    if (this.store.products().some((product) => product.name === name)) {
      // The API answers this with a 409 `DUPLICATE_NAME`; catching it here keeps the round trip for
      // the cases the client cannot see, and names the picker as the way to the existing one.
      this.newProductError.set('The project already has a product with that name - pick it above.');
      return;
    }
    const product = await this.store.createProduct(name);
    if (!product) {
      // The store put the reason in the editor's action banner; the field keeps what was typed so
      // the name is not lost to a failed write.
      return;
    }
    this.newProductName.set('');
    this.newProductError.set(null);
    await this.attachProduct(product.id);
  }

  /**
   * Put a catalogue product on this node, with everything at zero.
   *
   * The rates open on the unit this researcher last used for demand and for holding cost, falling
   * back to the network's period unit - a zero carries no arithmetic the choice could
   * distort, so the unit is purely a starting point for the number that follows.
   */
  private async attachProduct(productId: Id): Promise<void> {
    const periodUnit = this.store.periodUnit();
    await this.store.applyNodeProduct(
      productId,
      {
        demand: {
          value: 0,
          timeUnit: this.units.defaultFor(
            UnitPreferencesService.NODE_PRODUCT_DEMAND,
            'rate',
            periodUnit,
          ),
        },
        initialInventory: 0,
        safetyStock: 0,
        holdingCost: {
          value: 0,
          timeUnit: this.units.defaultFor(
            UnitPreferencesService.NODE_PRODUCT_HOLDING_COST,
            'rate',
            periodUnit,
          ),
        },
      },
      'product',
    );
  }

  async removeProduct(row: NodeProduct): Promise<void> {
    await this.store.removeNodeProduct(row.productId);
  }

  // ------------------------------------------------------------------------- clearing

  async clearNodeField(field: 'capacity' | 'region' | 'lat' | 'lng', label: string): Promise<void> {
    this.dropDraft(field);
    this.clearError(field);
    await this.store.clearNodeField(field, label);
  }

  async clearLinkCapacity(): Promise<void> {
    this.dropDraft('capacity');
    this.clearError('capacity');
    await this.store.clearLinkCapacity();
  }

  // ------------------------------------------------------------------------ internals

  /** Parses and range-checks, recording the message rather than sending a value the API would reject. */
  private parseNumber(field: string, raw: string, isProbability: boolean): number | null {
    const trimmed = raw.trim();
    if (!trimmed) {
      this.setError(field, 'Use the × to clear this field.');
      return null;
    }
    const value = Number(trimmed);
    if (!Number.isFinite(value)) {
      this.setError(field, 'Enter a number.');
      return null;
    }
    if (value < 0) {
      this.setError(field, 'Must not be negative.');
      return null;
    }
    if (isProbability && value > 1) {
      this.setError(field, 'A probability is between 0 and 1.');
      return null;
    }
    this.clearError(field);
    return value;
  }

  private setError(field: string, message: string): void {
    this.fieldErrors.update((errors) => ({ ...errors, [field]: message }));
  }

  private clearError(field: string): void {
    this.fieldErrors.update((errors) => {
      if (!(field in errors)) {
        return errors;
      }
      const next = { ...errors };
      delete next[field];
      return next;
    });
  }

  private dropDraft(field: string): void {
    this.drafts.update((drafts) => {
      const next = { ...drafts };
      delete next[field];
      return next;
    });
  }
}

/** What the *Show caption on canvas* box reads across a selection (FR-30). */
interface CaptionVisibility {
  readonly checked: boolean;
  /** True when the selection disagrees - drawn as the box's indeterminate state. */
  readonly mixed: boolean;
}

/**
 * The flag a selection shares, or `mixed`.
 *
 * Its own function rather than `commonValue` because a checkbox needs a boolean and a tri-state,
 * where that one answers with the string a text input shows - and `'false'` is a truthy string,
 * which is exactly the kind of quiet inversion a checkbox would render as "shown".
 */
function captionVisibility(
  elements: readonly { readonly captionVisible: boolean }[],
): CaptionVisibility {
  if (!elements.length) {
    return { checked: false, mixed: false };
  }
  const first = elements[0].captionVisible;
  const agrees = elements.every((element) => element.captionVisible === first);
  return { checked: agrees && first, mixed: !agrees };
}

/**
 * A one-field patch, built so the compiler still checks the value against that field's type.
 *
 * `{ [field]: value }` with a union-typed key widens to an index signature and would need a cast;
 * assigning through a generic key keeps `fixedCost: 'abc'` an error.
 */
function nodeAttribute<K extends keyof NodeAttributes>(
  key: K,
  value: NodeAttributes[K],
): NodeAttributes {
  const attributes: NodeAttributes = {};
  attributes[key] = value;
  return attributes;
}

function linkAttribute<K extends keyof LinkAttributes>(
  key: K,
  value: LinkAttributes[K],
): LinkAttributes {
  const attributes: LinkAttributes = {};
  attributes[key] = value;
  return attributes;
}

/** Draft key for a per-product number, since several rows are on screen at once. */
function productDraftKey(productId: Id, field: string): string {
  return `product:${productId}:${field}`;
}

/**
 * One field of a node–product request replaced, written out per field.
 *
 * A computed key would widen the literal to an index signature and lose the type check on the
 * value - the same reason {@link nodeAttribute} exists. The PUT is a full replacement, so the other
 * three figures have to travel whether or not they changed.
 */
function withRate(
  request: NodeProductRequest,
  field: 'demand' | 'holdingCost',
  rate: Rate,
): NodeProductRequest {
  return field === 'demand' ? { ...request, demand: rate } : { ...request, holdingCost: rate };
}

function withStockLevel(
  request: NodeProductRequest,
  field: 'initialInventory' | 'safetyStock',
  value: number,
): NodeProductRequest {
  return field === 'initialInventory'
    ? { ...request, initialInventory: value }
    : { ...request, safetyStock: value };
}

/** The declared duration with an uncommitted number substituted, keeping its unit. */
function withPendingValue(duration: Duration, pending: number | null): Duration {
  return pending === null ? duration : { value: pending, unit: duration.unit };
}

/** The declared rate with an uncommitted number substituted, keeping its unit. */
function withPendingRate(rate: Rate, pending: number | null): Rate {
  return pending === null ? rate : { value: pending, timeUnit: rate.timeUnit };
}

/**
 * The value a plain field holds across the whole selection.
 *
 * `mixed` is what makes bulk editing honest: a field the selected elements disagree on shows a `-`
 * placeholder rather than one arbitrary element's value, so nobody overwrites eleven nodes by
 * tabbing past a box that looked pre-filled.
 */
function commonValue<T extends NetworkNode | NetworkLink>(
  elements: readonly T[],
  field: keyof T,
): FieldState {
  if (!elements.length) {
    return EMPTY_FIELD;
  }
  const first = elements[0][field];
  for (const element of elements) {
    if (element[field] !== first) {
      return { value: '', mixed: true, empty: false };
    }
  }
  return {
    value: first === null || first === undefined ? '' : String(first),
    mixed: false,
    empty: first === null || first === undefined,
  };
}

/**
 * The duration a selection shares, or `mixed`.
 *
 * Value **and** unit have to agree: 6 HOUR and 6 DAY are different lengths of time, and showing "6"
 * for both would invite an edit that means one of them.
 */
function commonDuration<T>(
  elements: readonly T[],
  read: (element: T) => Duration,
  fallbackUnit: TimeUnit,
): UnitFieldState {
  if (!elements.length) {
    return { value: null, unit: fallbackUnit, mixed: false, empty: true };
  }
  const first = read(elements[0]);
  const agrees = elements.every((element) => {
    const duration = read(element);
    return duration.value === first.value && duration.unit === first.unit;
  });
  return agrees
    ? { value: first.value, unit: first.unit, mixed: false, empty: false }
    : { value: null, unit: fallbackUnit, mixed: true, empty: false };
}

/** The rate a selection shares, or `mixed`. A null value is unconstrained, not absent. */
function commonRate<T>(
  elements: readonly T[],
  read: (element: T) => Rate,
  fallbackUnit: TimeUnit,
): UnitFieldState {
  if (!elements.length) {
    return { value: null, unit: fallbackUnit, mixed: false, empty: true };
  }
  const first = read(elements[0]);
  const agrees = elements.every((element) => {
    const rate = read(element);
    return rate.value === first.value && rate.timeUnit === first.timeUnit;
  });
  return agrees
    ? { value: first.value, unit: first.timeUnit, mixed: false, empty: first.value === null }
    : { value: null, unit: fallbackUnit, mixed: true, empty: false };
}
