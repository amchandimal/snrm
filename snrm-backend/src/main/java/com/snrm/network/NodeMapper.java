package com.snrm.network;

import com.snrm.common.TimeUnit;
import com.snrm.common.TimeValueMapper;
import com.snrm.common.TimeValues;
import org.mapstruct.AfterMapping;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * {@link Node} ↔ DTO mapping.
 *
 * <p>The two write methods differ only in what they do with a null, and that difference is the
 * whole PUT/PATCH contract of this API:
 *
 * <ul>
 *   <li>{@link #replace} uses {@code SET_TO_NULL}, so a field absent from a PUT is cleared. That is
 *       how a capacity, a region or a coordinate is unset.</li>
 *   <li>{@link #patch} uses {@code IGNORE}, so a field absent from a PATCH is left alone. That is
 *       what makes the batched edits safe across a multi-selection whose nodes differ in
 *       every attribute the user is not currently changing.</li>
 * </ul>
 *
 * <p><strong>The unit-bearing fields are mapped by hand.</strong> {@code capacity} and
 * {@code processingTime} are ignored in both write methods and applied afterwards by
 * {@link #applyTimeValues}, because neither generated strategy is right for them: {@code SET_TO_NULL}
 * would null an embeddable whose columns are {@code NOT NULL}, and neither knows that an omitted
 * field falls back to the network's period unit rather than to nothing. The read direction needs no
 * such help — {@link TimeValueMapper} in {@code uses} resolves it.
 *
 * <p><strong>The caption pair is mapped by hand too (FR-30).</strong> Neither generated strategy is
 * right for it either: {@code SET_TO_NULL} would drive {@code captionVisible} to {@code false} on
 * every PUT that omits it, where V10 and the import both say an omitted flag means
 * <em>visible</em>; and neither strategy knows that a present-but-blank caption is a
 * <em>clear</em> on a PATCH, which is the only way the editor's one save queue can remove an
 * annotation. Both fields are therefore ignored by the generated code and applied by
 * {@link #applyCaption}, which delegates to {@link Captions} — the single statement of that
 * contract.
 *
 * <p>Both suppress unmapped-target reporting: a {@code Node} has associations no request DTO
 * carries — its network, its per-product rows — and they are meant to be untouched, not warned
 * about. The read direction keeps the default policy, so a field added to {@link NodeDto} and
 * forgotten here still produces a warning at compile time.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = TimeValueMapper.class)
public interface NodeMapper {

    @Mapping(target = "networkId", source = "network.id")
    NodeDto toDto(Node node);

    List<NodeDto> toDtoList(List<Node> nodes);

    /** Full replacement: an omitted nullable field is cleared. */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.SET_TO_NULL,
            unmappedTargetPolicy = ReportingPolicy.IGNORE)
    @Mapping(target = "capacity", ignore = true)
    @Mapping(target = "processingTime", ignore = true)
    @Mapping(target = "caption", ignore = true)
    @Mapping(target = "captionVisible", ignore = true)
    void replace(NodeRequest request, @MappingTarget Node node);

    /** Partial edit: an omitted field is left unchanged. */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
            unmappedTargetPolicy = ReportingPolicy.IGNORE)
    @Mapping(target = "capacity", ignore = true)
    @Mapping(target = "processingTime", ignore = true)
    @Mapping(target = "caption", ignore = true)
    @Mapping(target = "captionVisible", ignore = true)
    void patch(NodePatch patch, @MappingTarget Node node);

    /**
     * Applies the unit-bearing fields of a PUT: an omitted capacity clears to unconstrained, an
     * omitted processing time to zero, both in the network's period unit.
     */
    @AfterMapping
    default void applyTimeValues(NodeRequest request, @MappingTarget Node node) {
        TimeUnit fallback = node.getNetwork().periodUnit();
        node.setCapacity(TimeValues.replaceCapacity(node.getCapacity(), request.capacity(), fallback));
        node.setProcessingTime(TimeValues.replace(node.getProcessingTime(),
                request.processingTime(), fallback));
    }

    /** Applies the unit-bearing fields of a PATCH: an omitted one is left exactly as it was. */
    @AfterMapping
    default void applyTimeValues(NodePatch patch, @MappingTarget Node node) {
        node.setCapacity(TimeValues.patch(node.getCapacity(), patch.capacity()));
        node.setProcessingTime(TimeValues.patch(node.getProcessingTime(), patch.processingTime()));
    }

    /**
     * Applies the caption pair of a PUT: an omitted or blank caption clears it, an omitted flag
     * means visible (FR-30).
     */
    @AfterMapping
    default void applyCaption(NodeRequest request, @MappingTarget Node node) {
        node.setCaption(Captions.replace(request.caption()));
        node.setCaptionVisible(Captions.replaceVisible(request.captionVisible()));
    }

    /**
     * Applies the caption pair of a PATCH: an omitted field is unchanged, and a caption that is
     * present but blank <em>clears</em> it — the only clear this endpoint can express.
     */
    @AfterMapping
    default void applyCaption(NodePatch patch, @MappingTarget Node node) {
        node.setCaption(Captions.patch(node.getCaption(), patch.caption()));
        node.setCaptionVisible(Captions.patchVisible(node.isCaptionVisible(),
                patch.captionVisible()));
    }
}
