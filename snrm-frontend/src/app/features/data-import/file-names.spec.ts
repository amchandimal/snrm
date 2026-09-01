import {
  BATCH_ACCEPTS,
  BATCH_MINIMUM,
  UploadShape,
  isWorkbook,
  isXmlDocument,
  networkNameFromFileName,
  uploadRefusal,
  uploadShape,
} from './file-names';

/**
 * Names from file names, and the shape of an upload - a batch of workbooks is a batch of
 * networks (FR-28).
 *
 * The rule under test is one sentence - "one file, one network, named after the file -
 * the extension stripped and the name trimmed; a name that survives that empty refuses its own file
 * rather than inventing one" - and the half of it that is easy to get wrong is the last clause,
 * because a stand-in name looks like a feature until somebody has to identify the network it
 * produced. Where a message is asserted it is asserted **against the exported constant**, so a
 * reword fails the test rather than the reader (`network-selection.spec.ts`'s rule for
 * `archive-rules`).
 */
describe('file-names', () => {
  describe('networkNameFromFileName', () => {
    it('takes the file’s own name with the extension stripped', () => {
      expect(networkNameFromFileName('Baseline.xlsx')).toBe('Baseline');
      expect(networkNameFromFileName('Dual sourcing.xlsm')).toBe('Dual sourcing');
    });

    it('trims what is left, so a padded file name is not a padded network name', () => {
      expect(networkNameFromFileName('  Baseline  .xlsx')).toBe('Baseline');
    });

    it('is case-insensitive about the extension, as the picker and the server both are', () => {
      expect(networkNameFromFileName('Baseline.XLSX')).toBe('Baseline');
    });

    it('strips only the final extension, so dots in the researcher’s own name survive', () => {
      // `Baseline.v2` is a name somebody chose. Stripping greedily would rename their file for them.
      expect(networkNameFromFileName('Baseline.v2.xlsx')).toBe('Baseline.v2');
      expect(networkNameFromFileName('Baseline.xlsx.xlsx')).toBe('Baseline.xlsx');
    });

    it('refuses the file rather than inventing a name, when nothing is left', () => {
      // The clause this module exists for. A generated stand-in would put a label in the
      // project table that nothing in the upload supports.
      expect(networkNameFromFileName('.xlsx')).toBeNull();
      expect(networkNameFromFileName('   .xlsx')).toBeNull();
      expect(networkNameFromFileName('')).toBeNull();
    });

    it('leaves a name with no recognised extension alone', () => {
      // Not reachable through the batch - `uploadShape` never calls a batch of these - but the rule
      // is "strip the format's extension", not "strip everything after the last dot".
      expect(networkNameFromFileName('Baseline')).toBe('Baseline');
      expect(networkNameFromFileName('nodes.csv')).toBe('nodes.csv');
    });
  });

  describe('isWorkbook / isXmlDocument', () => {
    it('knows the two formats a batch and a single document are made of', () => {
      expect(isWorkbook('a.xlsx')).toBeTrue();
      expect(isWorkbook('a.xlsm')).toBeTrue();
      expect(isWorkbook('a.xls')).toBeFalse();
      expect(isWorkbook('a.csv')).toBeFalse();
      expect(isXmlDocument('network.xml')).toBeTrue();
      expect(isXmlDocument('network.xlsx')).toBeFalse();
    });
  });

  describe('uploadShape', () => {
    it('reads the three single-network uploads exactly as before FR-28', () => {
      // The whole regression surface of this feature in one expectation: a CSV set, one workbook and
      // one XML document are what they have always been, and nothing downstream of them changes.
      expect(uploadShape(['nodes.csv', 'links.csv', 'products.csv', 'node_products.csv'])).toBe(
        UploadShape.SINGLE,
      );
      expect(uploadShape(['Baseline.xlsx'])).toBe(UploadShape.SINGLE);
      expect(uploadShape(['network.xml'])).toBe(UploadShape.SINGLE);
    });

    it('reads two or more workbooks as a batch', () => {
      expect(uploadShape(['a.xlsx', 'b.xlsx'])).toBe(UploadShape.BATCH);
      expect(uploadShape(['a.xlsx', 'b.xlsx', 'c.xlsm', 'd.xlsx'])).toBe(UploadShape.BATCH);
    });

    it('takes two as the smallest batch, and one as the import it always was', () => {
      expect(BATCH_MINIMUM).toBe(2);
      expect(uploadShape(workbooks(BATCH_MINIMUM - 1))).toBe(UploadShape.SINGLE);
      expect(uploadShape(workbooks(BATCH_MINIMUM))).toBe(UploadShape.BATCH);
    });

    it('refuses a workbook mixed with anything else, at any count', () => {
      // Ambiguous rather than broken: "a batch with a stray in it" and "one network split across a
      // workbook and a CSV" are both readings, and the wizard would have to guess between them.
      expect(uploadShape(['Baseline.xlsx', 'nodes.csv'])).toBe(UploadShape.REFUSED);
      expect(uploadShape(['a.xlsx', 'b.xlsx', 'nodes.csv'])).toBe(UploadShape.REFUSED);
      expect(uploadShape(['a.xlsx', 'network.xml'])).toBe(UploadShape.REFUSED);
    });

    it('refuses several XML documents, which look like a batch and are not one', () => {
      expect(uploadShape(['one.xml', 'two.xml'])).toBe(UploadShape.REFUSED);
    });

    it('leaves an XML document beside CSV files to the server, as it always did', () => {
      // Deliberately *not* refused: nothing about it looks like a batch, so FR-28 introduces no
      // ambiguity here and this path is untouched.
      expect(uploadShape(['network.xml', 'nodes.csv'])).toBe(UploadShape.SINGLE);
    });

    it('answers EMPTY for nothing chosen, so the upload step has no opinion yet', () => {
      expect(uploadShape([])).toBe(UploadShape.EMPTY);
    });
  });

  describe('uploadRefusal', () => {
    it('says nothing about an upload it can read', () => {
      expect(uploadRefusal(['Baseline.xlsx'])).toBeNull();
      expect(uploadRefusal(['a.xlsx', 'b.xlsx'])).toBeNull();
      expect(uploadRefusal([])).toBeNull();
    });

    it('names the strays and then states what the batch form accepts', () => {
      const refusal = uploadRefusal(['a.xlsx', 'b.xlsx', 'nodes.csv']) ?? '';
      expect(refusal).toContain('2 workbooks');
      expect(refusal).toContain('nodes.csv');
      expect(refusal).toContain(BATCH_ACCEPTS);
    });

    it('counts one stray in the singular', () => {
      expect(uploadRefusal(['Baseline.xlsx', 'nodes.csv']) ?? '').toContain('1 other file');
    });

    it('explains several XML documents as several whole networks', () => {
      const refusal = uploadRefusal(['one.xml', 'two.xml']) ?? '';
      expect(refusal).toContain('2 XML documents');
      expect(refusal).toContain('one at a time');
      expect(refusal).toContain(BATCH_ACCEPTS);
    });
  });
});

/** `['file-1.xlsx', … ]` - a batch of the right size, built from the constant rather than typed. */
function workbooks(count: number): string[] {
  return Array.from({ length: count }, (_unused, index) => `file-${index + 1}.xlsx`);
}
