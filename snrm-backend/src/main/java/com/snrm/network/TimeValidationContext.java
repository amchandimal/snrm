package com.snrm.network;

/**
 * Where a resolution check is running, which is the one thing allowed to change a finding's
 * severity: a duration that converts to zero periods is "error on import, warning in the editor".
 *
 * <p>The asymmetry is deliberate and worth keeping. Import is a gate — nothing is stored until the
 * whole file passes, so refusing costs the user a correction and buys a network that means what the
 * file said. The editor is a workspace, where a half-entered lead time is a normal
 * intermediate state and refusing it would make the tool unusable; there the finding belongs in the
 * dismissible banner.
 */
public enum TimeValidationContext {

    /**
     * Interactive editing — the default for {@code GET /networks/{id}/time-validation} and for the
     * report returned when the time base changes. Nothing is refused here.
     */
    EDITOR,

    /**
     * A CSV/XLSX import. Nothing calls with this yet: the importer is not built. It exists
     * so the severity rule lives in one place when that lands, rather than being rediscovered.
     */
    IMPORT
}
