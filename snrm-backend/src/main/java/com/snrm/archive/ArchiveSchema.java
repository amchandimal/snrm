package com.snrm.archive;

import java.util.Locale;

/**
 * The layout of a project archive: entry names, the format version, and how a network is keyed.
 *
 * <p>Declared once and shared by {@link ProjectArchiveService} and {@link ProjectRestoreService}, for
 * the reason {@code ImportSchema} and {@code XmlSchema} are shared by their reader and writer: an
 * archive that cannot be re-read is worse than no archive at all, and two lists of names is how that
 * happens.
 */
public final class ArchiveSchema {

    /**
     * The archive layout this code reads and writes.
     *
     * <p>Checked on read and refused if unknown rather than parsed optimistically, exactly as
     * {@code XmlSchema.SCHEMA_VERSION} is — and unlike the <em>engine</em> version, which
     * warns. The difference is what each one governs: a reader that cannot parse the document cannot
     * report anything useful about it, whereas a reader that parses a document from an older engine
     * can say precisely what it found and let the researcher judge.
     *
     * <p>Bumped only when the layout changes incompatibly. Adding an optional field is not that;
     * note however that {@code spring.jackson.deserialization.fail-on-unknown-properties=true} makes
     * an <em>unknown</em> field fatal, which is why the version is read from the tree before the
     * document is bound.
     */
    public static final int FORMAT_VERSION = 1;

    /** The JSON document: manifest, project, scenarios, runs and results. */
    public static final String BUNDLE_ENTRY = "bundle.json";

    /** Directory holding one XML interchange document per network. */
    public static final String NETWORKS_DIR = "networks/";

    /** What an archive is called: {@code Resilience-study-archive.zip}. */
    public static final String FILENAME_PATTERN = "%s-archive.zip";

    /**
     * What a <em>subset</em> archive is called: {@code Resilience-study-3-networks-archive.zip}
     * (FR-24).
     *
     * <p>A second pattern rather than a parameter on the first, so the whole-project name is
     * produced by the identical statement it always was. The distinction earns its place because
     * the two files are meant to coexist: FR-24's point is that the subset is a <em>copy</em>, so a
     * researcher who exports three of six networks still has the whole-project archive beside it,
     * and two downloads called {@code Resilience-study-archive.zip} would be told apart only by the
     * browser's {@code (1)}. The count and not the names, because six network names do not fit in a
     * filename and {@code bundle.json}'s manifest carries them exactly.
     */
    public static final String SUBSET_FILENAME_PATTERN = "%s-%d-networks-archive.zip";

    public static final String CONTENT_TYPE = "application/zip";

    private ArchiveSchema() {
    }

    /**
     * A network's stable key within a bundle: its name and version.
     *
     * <p>{@code uq_network (project_id, name, version)} makes the pair unique inside a project, which
     * is the only scope a bundle covers — so this identifies a network without using its id, which is
     * the one thing that does not survive the move into a new database.
     */
    public static String networkKey(String name, int version) {
        return name + "@v" + version;
    }

    /**
     * The zip entry a network's XML document is written to.
     *
     * <p>Prefixed with the network's ordinal because {@link #safeEntryName} is lossy: it replaces
     * every character a filesystem might object to, so the two distinct networks "Plant A" and
     * "Plant_A" would otherwise claim the same entry and one would silently overwrite the other.
     * The ordinal makes the name unique without making it unreadable.
     *
     * @param ordinal the network's 1-based position in the bundle
     */
    public static String networkEntry(int ordinal, String key) {
        return "%s%03d-%s.xml".formatted(NETWORKS_DIR, ordinal, safeEntryName(key));
    }

    /**
     * A zip entry name safe on every filesystem an archive may be unpacked on.
     *
     * <p>Network names are free text and routinely contain {@code /} or {@code :}. A raw name would
     * produce an entry that unzips into an unexpected directory — or, with {@code ..}, outside the
     * target one entirely, which is the zip-slip class of bug. The name is never reconstructed from
     * this: {@code bundle.json} carries the network's real name and points at its entry by the exact
     * string written here, so the mapping is never inverted and the lossiness costs nothing.
     */
    static String safeEntryName(String key) {
        String cleaned = key.replaceAll("[^A-Za-z0-9._@-]", "_").trim();
        return cleaned.isEmpty() ? "network" : cleaned;
    }

    /** A filename the {@code Content-Disposition} header and Windows will both accept. */
    static String safeFilename(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return cleaned.isEmpty() ? "project" : cleaned;
    }

    /** Case-insensitive key for matching names across the bundle, as {@code RowValidator.key} does. */
    static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
