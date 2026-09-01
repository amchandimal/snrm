package com.snrm.network;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a caption means on each write path, and the one length it cannot exceed (FR-30).
 *
 * <p>{@link Captions} is where the whole null contract lives — both mappers delegate to it and hold
 * no rule of their own — so this is the test of the contract rather than of a mapper. It runs
 * without Spring, without a database and without Docker, because none of it is about persistence;
 * {@code CaptionEditTest} is the same claims through the services and the schema.
 *
 * <p>The claim worth stating: <strong>a PATCH can clear a caption and cannot clear anything
 * else</strong>. A caption is an ordinary edit written through the editor's debounced bulk PATCH,
 * and a PATCH cannot express "set to nothing" — those two are only
 * compatible if this one field gives a present-but-empty string a meaning. If the assertions below
 * ever start agreeing with {@link NodePatch#name()}'s blank-is-a-refusal rule, removing a caption
 * will have quietly become a full-replacement PUT that overwrites every other attribute of the node.
 */
@DisplayName("The caption null contract — FR-30")
class CaptionsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stopValidator() {
        factory.close();
    }

    // -------------------------------------------------------------------- the PUT path

    @Test
    @DisplayName("PUT: an omitted caption clears it, which is the ordinary replacement contract")
    void putOmittedClears() {
        assertThat(Captions.replace(null)).isNull();
    }

    @Test
    @DisplayName("PUT: a blank caption clears it too — there is one form of 'no caption'")
    void putBlankClears() {
        assertThat(Captions.replace("")).isNull();
        assertThat(Captions.replace("   ")).isNull();
        assertThat(Captions.replace("\t\n ")).isNull();
    }

    @Test
    @DisplayName("PUT: a caption is trimmed and kept")
    void putTrimsAndKeeps() {
        assertThat(Captions.replace("  Nordic hub — 3PL operated  "))
                .isEqualTo("Nordic hub — 3PL operated");
    }

    @Test
    @DisplayName("PUT: an omitted flag means VISIBLE, never hidden")
    void putOmittedFlagIsVisible() {
        // The rule V10's NOT NULL DEFAULT TRUE, an omitted column at import and an omitted
        // attribute all state: typing a caption shows one, without a second gesture. MapStruct's
        // SET_TO_NULL would have driven this to false on every PUT that omits the field, which is
        // why the pair is ignored by the generated mapping and applied through Captions.
        assertThat(Captions.replaceVisible(null)).isTrue();
        assertThat(Captions.replaceVisible(Boolean.FALSE)).isFalse();
        assertThat(Captions.replaceVisible(Boolean.TRUE)).isTrue();
    }

    // ------------------------------------------------------------------ the PATCH path

    @Test
    @DisplayName("PATCH: null leaves the caption exactly as it was")
    void patchNullLeavesAlone() {
        assertThat(Captions.patch("Single-sourced", null)).isEqualTo("Single-sourced");
        assertThat(Captions.patch(null, null)).isNull();
    }

    @Test
    @DisplayName("PATCH: a present-but-blank caption CLEARS it — the clearing path the editor uses")
    void patchBlankClears() {
        assertThat(Captions.patch("Single-sourced", "")).isNull();
        assertThat(Captions.patch("Single-sourced", "   ")).isNull();
    }

    @Test
    @DisplayName("PATCH: a present caption replaces, trimmed")
    void patchReplacesTrimmed() {
        assertThat(Captions.patch("Single-sourced", "  Dual-sourced from Q3 "))
                .isEqualTo("Dual-sourced from Q3");
    }

    @Test
    @DisplayName("PATCH: an omitted flag is unchanged — the flag needs no convention of its own")
    void patchFlagOmittedIsUnchanged() {
        assertThat(Captions.patchVisible(false, null)).isFalse();
        assertThat(Captions.patchVisible(true, null)).isTrue();
        assertThat(Captions.patchVisible(true, Boolean.FALSE)).isFalse();
        assertThat(Captions.patchVisible(false, Boolean.TRUE)).isTrue();
    }

    @Test
    @DisplayName("hiding a caption and clearing one are different acts, and stay different")
    void hidingIsNotClearing() {
        // The distinction FR-30 exists for: "so annotation can be written once and shown or hidden
        // without being lost". A hidden caption is still there for the next reader.
        String kept = Captions.patch("Nordic hub", null);
        boolean hidden = Captions.patchVisible(true, Boolean.FALSE);

        assertThat(kept).isEqualTo("Nordic hub");
        assertThat(hidden).isFalse();
    }

    // ------------------------------------------------------------------- the length cap

    /** Exactly {@link Captions#MAX_LENGTH}, which the {@code VARCHAR(200)} of V10 accepts. */
    private static final String AT_THE_CAP = "c".repeat(Captions.MAX_LENGTH);

    /** One over, which the column would truncate or the driver would refuse. */
    private static final String OVER_THE_CAP = "c".repeat(Captions.MAX_LENGTH + 1);

    @Test
    @DisplayName("201 characters is refused on the node PUT, naming the field")
    void nodeRequestRefusesOverTheCap() {
        assertThat(captionViolations(node(AT_THE_CAP))).isEmpty();
        assertThat(captionViolations(node(OVER_THE_CAP)))
                .singleElement()
                .isEqualTo("caption must be at most 200 characters");
    }

    @Test
    @DisplayName("201 characters is refused on the bulk PATCH too")
    void nodePatchRefusesOverTheCap() {
        assertThat(captionViolations(nodePatch(AT_THE_CAP))).isEmpty();
        assertThat(captionViolations(nodePatch(OVER_THE_CAP))).hasSize(1);
    }

    @Test
    @DisplayName("and on both link writes — a link is captioned on the same terms as a node")
    void linkWritesRefuseOverTheCap() {
        assertThat(captionViolations(new LinkRequest(1L, 2L, null, null, 0, 0, AT_THE_CAP, null)))
                .isEmpty();
        assertThat(captionViolations(new LinkRequest(1L, 2L, null, null, 0, 0, OVER_THE_CAP, null)))
                .hasSize(1);
        assertThat(captionViolations(
                new LinkAttributesRequest(null, null, 0, 0, OVER_THE_CAP, null))).hasSize(1);
        assertThat(captionViolations(new LinkPatch(1L, null, null, null, null, AT_THE_CAP, null)))
                .isEmpty();
        assertThat(captionViolations(new LinkPatch(1L, null, null, null, null, OVER_THE_CAP, null)))
                .hasSize(1);
    }

    @Test
    @DisplayName("a blank caption is never a length problem — it is the clear")
    void blankIsNotALengthProblem() {
        assertThat(captionViolations(nodePatch(""))).isEmpty();
    }

    // -------------------------------------------------------------------------- fixtures

    private static NodeRequest node(String caption) {
        return new NodeRequest("SUP-1", NodeType.SUPPLIER, null, null, 0, 0, 0, null, null, null,
                null, null, caption, null);
    }

    private static NodePatch nodePatch(String caption) {
        return new NodePatch(1L, null, null, null, null, null, null, null, null, null, null, null,
                null, caption, null);
    }

    /** Only the {@code caption} messages, so an unrelated field cannot make a test pass. */
    private static List<String> captionViolations(Object request) {
        return validator.validate(request).stream()
                .filter(violation -> violation.getPropertyPath().toString().equals("caption"))
                .map(violation -> violation.getMessage())
                .toList();
    }
}
