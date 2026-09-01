/**
 * What an upload's *file names* say about it: a batch of workbooks is a batch of networks
 * (FR-28).
 *
 * Pure - no Angular, no HTTP, no `File` - for the reason `network-selection.ts` and
 * `core/run-discard.ts` are pure: this is the part that has to be right, and none of it can be read
 * off a component spec without a drag-and-drop.
 *
 * ## Two questions, and they are both about the name
 *
 * **What is this network called?** The rule is exact: one file, one network, named after the
 * file - the extension stripped and the name trimmed; a name that survives that empty refuses its own
 * file rather than inventing one. {@link networkNameFromFileName} is that rule and the *only* place
 * it exists - `data-import.store.suggestName`, which has guessed a single workbook's name since the
 * wizard was written, now calls it too, so a workbook imported alone and the same workbook imported
 * in a batch cannot be named two different things.
 *
 * **What shape is this upload?** Before FR-28 the answer was always "one network" and the server was
 * left to work out from the file names whether it was reading a CSV set, a workbook or an XML
 * document. That is still true of everything {@link uploadShape} calls `SINGLE`, and nothing about
 * those paths changes. What is new is that **two or more workbooks now mean two or more networks**,
 * which is a reading the wizard has to take before it posts anything - the preview goes to one file
 * (one mapping for the batch), the roles step exists, and the import becomes N requests.
 *
 * ## Why a mixed upload is refused here rather than by the server
 *
 * A batch is workbooks and nothing else. The moment a second workbook means a second network, an
 * upload holding a workbook *and* a `nodes.csv` has two readings - "a batch, plus some loose files"
 * and "one network split across a workbook and a CSV" - and the wizard would have to guess. Several
 * XML documents are the same trap wearing the other hat: an XML document is one whole network,
 * so several of them look exactly like a batch and are not one, because the batch form is
 * defined over workbooks.
 *
 * Neither case is refused *because it would fail*: it is refused because the wizard cannot say what
 * it would do. So the refusal names what the batch form accepts rather than what went wrong, and the
 * single-file shapes - one workbook, a CSV set, one XML document - are untouched by all of it.
 */

/** `.xlsx` and `.xlsm`, the two the file picker and the server both accept. */
const WORKBOOK_EXTENSION = /\.xls[xm]$/i;

/** The interchange document. One file, one whole network - never part of a batch. */
const XML_EXTENSION = /\.xml$/i;

/** Two workbooks is the smallest thing that is a batch rather than an import. */
export const BATCH_MINIMUM = 2;

/** True for the one format a batch is made of. */
export function isWorkbook(fileName: string): boolean {
  return WORKBOOK_EXTENSION.test(fileName);
}

/** True for the self-describing document. */
export function isXmlDocument(fileName: string): boolean {
  return XML_EXTENSION.test(fileName);
}

/**
 * The network name a file carries: its own name, extension stripped and trimmed (FR-28).
 *
 * **Null is a real answer and the caller must handle it.** `.xlsx` and `"   .xlsx"` are both files
 * whose name is nothing at all once the rule has been applied, and what happens then is settled:
 * such a file refuses itself rather than inventing a name. A generated stand-in - `Network 3`, the
 * file's index, the project's name - would put a label in the table that nothing in the upload
 * supports, on a network the researcher then has to identify by its contents.
 *
 * **Only the final extension goes.** `Baseline.v2.xlsx` is `Baseline.v2`, because the dots in the
 * middle are the researcher's and only the last one is the format's. `Baseline.xlsx.xlsx` therefore
 * derives `Baseline.xlsx`, which looks odd and is right: stripping greedily would silently rename a
 * file somebody deliberately named that.
 *
 * **Nothing checks the project for a clash, deliberately.** A name already used in the
 * project takes the next version number exactly as any other network of that name would. That is
 * the server's `findMaxVersion(name) + 1`, and a client-side uniqueness check would either
 * pre-empt it - turning the documented way to add a variant into an error - or duplicate it and
 * disagree with it under a concurrent import.
 */
export function networkNameFromFileName(fileName: string): string | null {
  const trimmed = fileName.replace(WORKBOOK_EXTENSION, '').trim();
  return trimmed.length > 0 ? trimmed : null;
}

/** What the wizard reads an upload as. */
export const UploadShape = {
  /** Nothing chosen yet. */
  EMPTY: 'EMPTY',
  /** One network: a CSV set, one workbook, or one XML document. Unchanged since before FR-28. */
  SINGLE: 'SINGLE',
  /** Several workbooks and nothing else: one network per file (FR-28). */
  BATCH: 'BATCH',
  /** Neither, and the wizard says which (see {@link uploadRefusal}). */
  REFUSED: 'REFUSED',
} as const;

export type UploadShape = (typeof UploadShape)[keyof typeof UploadShape];

/**
 * What the batch form takes, in one sentence - said on the upload step and in every refusal.
 *
 * One constant rather than two copies, under the rule `archive-rules.ts` states for its own pair:
 * three screens saying a thing three times is three sentences that will eventually differ, with the
 * one that differed being the one somebody read.
 */
export const BATCH_ACCEPTS =
  'A batch is Excel workbooks and nothing else: drop two or more .xlsx files to import one network ' +
  'per file. A CSV set, a single workbook and an XML document each import one network, as they ' +
  'always have - but they cannot be mixed with a batch or with each other.';

/** How the wizard reads this upload (FR-28). */
export function uploadShape(fileNames: readonly string[]): UploadShape {
  if (fileNames.length === 0) {
    return UploadShape.EMPTY;
  }
  const workbooks = fileNames.filter(isWorkbook);
  if (workbooks.length > 0) {
    // A workbook beside anything else is the ambiguous case: one network split across files, or a
    // batch with strays in it. Two workbooks and nothing else is a batch; one is what it always was.
    if (workbooks.length !== fileNames.length) {
      return UploadShape.REFUSED;
    }
    return workbooks.length >= BATCH_MINIMUM ? UploadShape.BATCH : UploadShape.SINGLE;
  }
  return fileNames.filter(isXmlDocument).length >= BATCH_MINIMUM
    ? UploadShape.REFUSED
    : UploadShape.SINGLE;
}

/**
 * Why this upload cannot be read, or null when it can.
 *
 * Names the count that is wrong and then states what the batch form accepts, in that order: the
 * reader has already looked at their own files, so the useful half is the rule they have just met.
 */
export function uploadRefusal(fileNames: readonly string[]): string | null {
  if (uploadShape(fileNames) !== UploadShape.REFUSED) {
    return null;
  }
  const workbooks = fileNames.filter(isWorkbook);
  if (workbooks.length > 0) {
    const others = fileNames.filter((name) => !isWorkbook(name));
    return (
      `This upload mixes ${countOf(workbooks.length, 'workbook')} with ` +
      `${countOf(others.length, 'other file')} (${others.join(', ')}). ${BATCH_ACCEPTS}`
    );
  }
  return (
    `This upload holds ${countOf(fileNames.filter(isXmlDocument).length, 'XML document')}. An XML ` +
    'document already carries one whole network, so several of them are several ' +
    `networks - import them one at a time. ${BATCH_ACCEPTS}`
  );
}

function countOf(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}
