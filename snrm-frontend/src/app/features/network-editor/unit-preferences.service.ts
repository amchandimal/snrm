import { Injectable, signal } from '@angular/core';

import { TimeUnit } from '../../core/models';

/**
 * Which unit each field opens on.
 *
 * > "The unit defaults to the network's period unit for durations and to the last unit the user
 * > picked in that field, so repetitive entry stays fast."
 *
 * The two halves of that rule are not symmetric, and deliberately so. A **duration** always starts
 * at the network's period unit: the period is the one unit the network has an opinion about, and a
 * lead time entered in it converts exactly. A **rate** starts at whatever the researcher last chose
 * *for that field*, because rates are entered in the unit the data came in - capacities in weeks
 * because the plant reports weekly, demand in days because the sales file is daily - and re-picking
 * it on every node is the kind of friction that makes people leave the default and mean something
 * else by it.
 *
 * Per field, not global: a network whose capacities are weekly and whose demand is daily must not
 * have the two fight over one remembered unit.
 *
 * Session-scoped, like the rest of the editor's state - provided on the route component, not in the
 * root injector. A preference is a habit within one modelling session, not a stored setting.
 */
@Injectable()
export class UnitPreferencesService {
  /** The fields of the editor that carry a unit. */
  static readonly LINK_LEAD_TIME = 'linkLeadTime';
  static readonly LINK_CAPACITY = 'linkCapacity';
  static readonly NODE_PROCESSING_TIME = 'nodeProcessingTime';
  static readonly NODE_CAPACITY = 'nodeCapacity';
  static readonly NODE_PRODUCT_DEMAND = 'nodeProductDemand';
  static readonly NODE_PRODUCT_HOLDING_COST = 'nodeProductHoldingCost';
  /** The period itself, in the time-settings dialog. */
  static readonly NETWORK_PERIOD = 'networkPeriod';

  private readonly lastUsed = signal<Readonly<Record<string, TimeUnit | undefined>>>({});

  /** Record a unit the user picked in a field. Called for durations too, though only rates read it. */
  remember(field: string, unit: TimeUnit): void {
    this.lastUsed.update((units) => ({ ...units, [field]: unit }));
  }

  /**
   * The unit a field should open on for a value that has none of its own.
   *
   * @param kind       durations follow the period; rates follow the last pick
   * @param periodUnit the network's period unit - the fallback for both
   */
  defaultFor(field: string, kind: 'duration' | 'rate', periodUnit: TimeUnit): TimeUnit {
    if (kind === 'duration') {
      return periodUnit;
    }
    return this.lastUsed()[field] ?? periodUnit;
  }
}
