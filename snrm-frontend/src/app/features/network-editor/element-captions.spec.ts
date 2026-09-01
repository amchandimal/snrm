import {
  CAPTION_CLASS,
  CAPTION_FONT_SIZE,
  CAPTION_GAP,
  CAPTION_ID_PREFIX,
  CAPTION_MAX_LENGTH,
  CLEARED_CAPTION,
  LABEL_PADDING,
  NAME_FONT_SIZE,
  NAME_MARGIN_Y,
  captionChanged,
  captionElementId,
  captionElements,
  captionOwnerElementId,
  drawnCaption,
  isCaptionElementId,
  linkCaptionAnchor,
  linkCaptionOffsetY,
  nodeCaptionAnchor,
  nodeCaptionOffsetY,
  previousAttributeValue,
} from './element-captions';

/**
 * The whole of FR-30 that is not a Cytoscape call.
 *
 * Three groups, and each pins a claim rather than a shape: what the canvas draws, what an edit means
 * to an endpoint that cannot ordinarily say "nothing", and where the second line lands. The
 * arithmetic is worked by hand below from the four constants the canvas stylesheet states, so a font
 * size changed on one side and not the other fails here rather than putting a caption a pixel inside
 * the name it sits under.
 */

/** The node rule's uniform diameter, and the two ends of the criticality range. */
const NODE_SIZE = 46;
const NODE_SIZE_MIN = 30;
const NODE_SIZE_MAX = 74;

describe('element-captions - what draws', () => {
  it('draws a visible caption', () => {
    expect(drawnCaption('Nordic hub - 3PL operated', true)).toBe('Nordic hub - 3PL operated');
  });

  it('draws nothing when the checkbox is off, and keeps the text for the next reader', () => {
    // The flag hides an annotation without losing it, which is what a screenshot wants.
    expect(drawnCaption('Two lines; second one idle since Q1', false)).toBeNull();
  });

  it('draws nothing for an empty caption WHATEVER the flag says', () => {
    // The rule holds outright, and it is the reason the checkbox is a control over annotation
    // that exists rather than a way to reserve a second line of space.
    expect(drawnCaption(null, true)).toBeNull();
    expect(drawnCaption(null, false)).toBeNull();
    // `''` is the state the store holds for the two seconds between a clear and its flush.
    expect(drawnCaption(CLEARED_CAPTION, true)).toBeNull();
    expect(drawnCaption('   ', true)).toBeNull();
  });

  it('builds the whole desired set, and leaves out the elements with nothing to draw', () => {
    const wanted = captionElements(
      [
        { id: 1, caption: 'Sole source - 6-week qualification', captionVisible: true },
        { id: 2, caption: 'Kept, not drawn', captionVisible: false },
        { id: 3, caption: null, captionVisible: true },
      ],
      [{ id: 30, caption: 'Contracted road leg', captionVisible: true }],
      (id) => `n${id}`,
      (id) => `l${id}`,
    );

    expect([...wanted.keys()]).toEqual([captionElementId('n1'), captionElementId('l30')]);
    expect(wanted.get(captionElementId('n1'))).toEqual({
      id: captionElementId('n1'),
      ownerElementId: 'n1',
      text: 'Sole source - 6-week qualification',
    });
  });
});

describe('element-captions - the caption element is nobody else’s', () => {
  /**
   * The two prefixes `graph-canvas` parses a real element by, and the one it reserves for the corner
   * drag handles. Written out rather than imported because all three are private to that component;
   * what matters here is that a caption id is claimed by none of them, since the render diff removes
   * only what it recognises and this feature's pass must be able to say the same.
   */
  const NODE_ELEMENT_PREFIX = 'n';
  const EDGE_ELEMENT_PREFIX = 'l';
  const HANDLE_ID_PREFIX = 'snrm-eh-handle';

  it('round-trips the element it describes', () => {
    expect(captionOwnerElementId(captionElementId('n12'))).toBe('n12');
    expect(captionOwnerElementId(captionElementId('l30'))).toBe('l30');
  });

  it('is claimed by neither element-id parser, so a render cannot delete it', () => {
    const captionIds = [captionElementId('n12'), captionElementId('l30')];

    for (const id of captionIds) {
      expect(id.startsWith(NODE_ELEMENT_PREFIX)).toBeFalse();
      expect(id.startsWith(EDGE_ELEMENT_PREFIX)).toBeFalse();
      expect(id.startsWith(HANDLE_ID_PREFIX)).toBeFalse();
    }
  });

  it('claims nothing else on the canvas, so its own pass cannot delete a handle or a ghost', () => {
    // Every id `graph-canvas` puts on the canvas that is not a caption's: two real elements, the
    // four corner handles, and edgehandles' own preview and ghost.
    for (const id of ['n12', 'l30', `${HANDLE_ID_PREFIX}-nw`, 'eh-preview', 'eh-ghost-edge']) {
      expect(isCaptionElementId(id)).toBeFalse();
    }
    expect(isCaptionElementId(undefined)).toBeFalse();
    expect(isCaptionElementId(null)).toBeFalse();
    // The prefix on its own describes no element, so it is not one either.
    expect(isCaptionElementId(CAPTION_ID_PREFIX)).toBeFalse();
  });

  it('names a class that is not one of Cytoscape’s or edgehandles’', () => {
    expect(CAPTION_CLASS).not.toBe('snrm-playback');
    expect(CAPTION_CLASS.startsWith('eh-')).toBeFalse();
  });
});

describe('element-captions - what an edit means', () => {
  it('reports a real change and only a real change', () => {
    expect(captionChanged(null, 'Nordic hub')).toBeTrue();
    expect(captionChanged('Nordic hub', 'Nordic hub, 3PL')).toBeTrue();
    // Emptying a caption that exists IS a change - the whole point of FR-30's clear.
    expect(captionChanged('Nordic hub', '')).toBeTrue();
  });

  it('reports no change for the two ways of typing nothing new', () => {
    // Tabbing through an empty field on an uncaptioned element must cost neither a PATCH nor an
    // undo step, and `sameFieldValue` cannot say so: null and '' are one state for a caption alone.
    expect(captionChanged(null, '')).toBeFalse();
    expect(captionChanged(null, '   ')).toBeFalse();
    expect(captionChanged('Nordic hub', '  Nordic hub  ')).toBeFalse();
  });

  it('undoes a caption typed onto an element that had none, which a PATCH can only just express', () => {
    // The exception the backend carved out for FR-30: a present-but-empty caption CLEARS, so the
    // undo of "typed a caption" has something to send. Every other field's null is unsayable here.
    expect(previousAttributeValue('caption', null)).toBe(CLEARED_CAPTION);
    expect(previousAttributeValue('region', null)).toBeUndefined();
    expect(previousAttributeValue('capacity', null)).toBeUndefined();
  });

  it('undoes back to the caption that was there, and to the flag that was set', () => {
    expect(previousAttributeValue('caption', 'Nordic hub')).toBe('Nordic hub');
    // `caption_visible` is NOT NULL, so it never reaches the exception at all.
    expect(previousAttributeValue('captionVisible', false)).toBeFalse();
    expect(previousAttributeValue('captionVisible', true)).toBeTrue();
  });

  it('caps the text at the column the migration declares', () => {
    expect(CAPTION_MAX_LENGTH).toBe(200);
  });
});

describe('element-captions - where the second line sits', () => {
  it('clears the name below a node of the default size', () => {
    // 46/2 = 23 to the node's edge, + 6 margin, + (11 + 2×2) for the name's box, + 2 of air = 46.
    expect(nodeCaptionOffsetY(NODE_SIZE)).toBe(46);
    expect(nodeCaptionAnchor({ x: 400, y: 180 }, NODE_SIZE)).toEqual({ x: 400, y: 226 });
  });

  it('follows the criticality encoding at both ends of its range', () => {
    // The reason `applyCriticality` re-anchors: a caption pinned to the default diameter would sit
    // inside the most critical node and float away from the least.
    expect(nodeCaptionOffsetY(NODE_SIZE_MIN)).toBe(38);
    expect(nodeCaptionOffsetY(NODE_SIZE_MAX)).toBe(60);
    expect(nodeCaptionOffsetY(NODE_SIZE_MAX) - nodeCaptionOffsetY(NODE_SIZE_MIN)).toBe(
      (NODE_SIZE_MAX - NODE_SIZE_MIN) / 2,
    );
  });

  it('clears the arc’s lead-time label at its midpoint', () => {
    // (10 + 2×2)/2 = 7 for half the label's box, + 2 of air.
    expect(linkCaptionOffsetY()).toBe(9);
    expect(linkCaptionAnchor({ x: 320, y: 240 })).toEqual({ x: 320, y: 249 });
  });

  it('anchors the top of the caption’s box, so a wrapped caption grows away from the label', () => {
    // Both offsets clear the *whole* of the label above rather than half of it plus a centre, which
    // is what `text-valign: bottom` on the caption element needs to be true.
    expect(nodeCaptionOffsetY(NODE_SIZE)).toBeGreaterThan(
      NODE_SIZE / 2 + NAME_MARGIN_Y + NAME_FONT_SIZE + 2 * LABEL_PADDING,
    );
    expect(linkCaptionOffsetY()).toBeGreaterThan(CAPTION_GAP);
  });

  it('is smaller than the name it sits under, which is required rather than preferred', () => {
    expect(CAPTION_FONT_SIZE).toBeLessThan(NAME_FONT_SIZE);
  });
});
