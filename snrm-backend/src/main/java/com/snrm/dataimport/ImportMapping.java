package com.snrm.dataimport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The user's answer to step 2 of the wizard: which source column feeds which canonical one.
 *
 * <blockquote>"the import wizard lets the user map non-standard column names onto the canonical ones
 * before validation."</blockquote>
 *
 * <p>Sent as one JSON object keyed by sheet, then by the header as it appears in the file:
 *
 * <pre>{@code
 * {"nodes": {"Facility": "name", "Kind": "type", "Notes": null},
 *  "links": {"From": "source", "To": "target"}}
 * }</pre>
 *
 * <p><strong>Keyed by source header, not by canonical column.</strong> That is the direction the user
 * works in — they are looking at their own file's columns and deciding what each one is — and it is the
 * only direction that can express "ignore this column" (a null value) as distinct from "this canonical
 * column has no source" (absent from the map). Inverting it would also make two source columns claiming
 * one canonical column unrepresentable, and that is a mistake worth reporting rather than one worth
 * making impossible to state.
 *
 * <p>Only overrides need to be sent. Anything absent falls back to
 * {@link ImportSchema#suggestMapping}, which matches headers that are already canonical — so a file
 * written to the schema needs no mapping at all, and the wizard's step 2 is a confirmation rather than
 * a chore.
 */
public record ImportMapping(Map<ImportSheet, Map<String, String>> bySheet) {

    private static final TypeReference<Map<String, Map<String, String>>> JSON_SHAPE =
            new TypeReference<>() {
            };

    public ImportMapping {
        bySheet = bySheet == null ? Map.of() : Map.copyOf(bySheet);
    }

    /** No overrides: every header is matched by {@link ImportSchema#suggestMapping} alone. */
    public static ImportMapping empty() {
        return new ImportMapping(Map.of());
    }

    /**
     * Parses the {@code mapping} form field.
     *
     * @param json  the JSON object above, or null/blank for no overrides
     * @param mapper the application's configured {@link ObjectMapper}
     * @throws IllegalArgumentException if the JSON is malformed or names a sheet that does not exist —
     *                                 a 400, since the wizard composes this and a typo in it is a bug
     *                                 in the client rather than a problem with the user's data
     */
    public static ImportMapping parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        Map<String, Map<String, String>> raw;
        try {
            raw = mapper.readValue(json, JSON_SHAPE);
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "mapping is not a JSON object of {sheet: {sourceColumn: canonicalColumn}}: "
                            + failure.getMessage(), failure);
        }

        Map<ImportSheet, Map<String, String>> bySheet = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : raw.entrySet()) {
            ImportSheet sheet = ImportSheet.ofName(entry.getKey()).orElseThrow(
                    () -> new IllegalArgumentException(("mapping names an unknown sheet '%s'; "
                            + "expected one of network_meta, nodes, links, products, node_products")
                            .formatted(entry.getKey())));
            Map<String, String> columns = entry.getValue();
            bySheet.put(sheet, columns == null ? Map.of() : new LinkedHashMap<>(columns));
        }
        return new ImportMapping(bySheet);
    }

    /** The overrides for one sheet; empty when the user left it alone. */
    public Map<String, String> forSheet(ImportSheet sheet) {
        return bySheet.getOrDefault(sheet, Map.of());
    }
}
