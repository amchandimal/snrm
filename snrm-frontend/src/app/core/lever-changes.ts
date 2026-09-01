import { LeverChanges } from './models/network.model';

/**
 * Rendering `configuration_variant.lever_changes_json` - the lever-change annotation.
 *
 * Free of Angular and of HTTP, like `core/time-units.ts` and `core/metric-display.ts`. It lives in
 * `core/` rather than in one feature because two screens read the same field for the same reason:
 * the comparison matrix annotates each column with it, and the project dashboard's
 * provenance tree annotates each fork with it. A second copy would eventually render the
 * same diff two different ways.
 */

/** One lever change, flattened for an annotation list. */
export interface LeverEntry {
  readonly key: string;
  readonly value: string;
}

/**
 * The lever diff as a flat list of label/value pairs.
 *
 * Generic on purpose. `lever_changes_json` is free-form - the persistence layer stays agnostic of
 * the lever vocabulary and that vocabulary arrives with Phase 2 - so parsing it
 * against a shape this build invented would break the first time the configuration engine writes a
 * lever family nobody has thought of yet. Nested objects are flattened with dotted keys, arrays are
 * joined, and anything else is stringified: the reader gets what was recorded, which is the most a
 * schema-less field can honestly offer.
 */
export function flattenLevers(
  levers: LeverChanges | null | undefined,
  prefix = '',
): LeverEntry[] {
  if (!levers || typeof levers !== 'object') {
    return [];
  }
  const entries: LeverEntry[] = [];
  for (const [key, value] of Object.entries(levers)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value === null || value === undefined) {
      continue;
    }
    if (Array.isArray(value)) {
      entries.push({ key: path, value: value.map((item) => stringify(item)).join(', ') });
    } else if (typeof value === 'object') {
      entries.push(...flattenLevers(value as LeverChanges, path));
    } else {
      entries.push({ key: path, value: String(value) });
    }
  }
  return entries;
}

function stringify(value: unknown): string {
  return typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value);
}
