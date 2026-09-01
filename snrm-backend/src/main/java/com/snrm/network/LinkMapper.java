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
 * {@link Link} ↔ DTO mapping.
 *
 * <p>Only attributes are written. The endpoints of an arc are set once, by {@link LinkService},
 * after the self-loop, duplicate and cross-network checks have passed — a mapper that could
 * repoint a link would route around all three. {@code sourceNodeId} on a request DTO deliberately
 * does not match the {@code sourceNode} property on the entity, so no mapping is even generated
 * for it.
 *
 * <p>The null-handling split between {@link #replace} and {@link #patch} is the same contract
 * {@link NodeMapper} documents, and so is the reason {@code leadTime} and {@code capacity} — and
 * the caption pair of FR-30 — are ignored by the generated code and applied by hand afterwards.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = TimeValueMapper.class)
public interface LinkMapper {

    @Mapping(target = "networkId", source = "network.id")
    @Mapping(target = "sourceNodeId", source = "sourceNode.id")
    @Mapping(target = "targetNodeId", source = "targetNode.id")
    LinkDto toDto(Link link);

    List<LinkDto> toDtoList(List<Link> links);

    /** Attributes of a link being created; the endpoints are already set. */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.SET_TO_NULL,
            unmappedTargetPolicy = ReportingPolicy.IGNORE)
    @Mapping(target = "leadTime", ignore = true)
    @Mapping(target = "capacity", ignore = true)
    @Mapping(target = "caption", ignore = true)
    @Mapping(target = "captionVisible", ignore = true)
    void applyAttributes(LinkRequest request, @MappingTarget Link link);

    /** Full replacement: an omitted nullable field is cleared. */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.SET_TO_NULL,
            unmappedTargetPolicy = ReportingPolicy.IGNORE)
    @Mapping(target = "leadTime", ignore = true)
    @Mapping(target = "capacity", ignore = true)
    @Mapping(target = "caption", ignore = true)
    @Mapping(target = "captionVisible", ignore = true)
    void replace(LinkAttributesRequest request, @MappingTarget Link link);

    /** Partial edit: an omitted field is left unchanged. */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
            unmappedTargetPolicy = ReportingPolicy.IGNORE)
    @Mapping(target = "leadTime", ignore = true)
    @Mapping(target = "capacity", ignore = true)
    @Mapping(target = "caption", ignore = true)
    @Mapping(target = "captionVisible", ignore = true)
    void patch(LinkPatch patch, @MappingTarget Link link);

    /** The unit-bearing fields of a create: omitted means zero transit, unconstrained capacity. */
    @AfterMapping
    default void applyTimeValues(LinkRequest request, @MappingTarget Link link) {
        TimeUnit fallback = link.getNetwork().periodUnit();
        link.setLeadTime(TimeValues.replace(link.getLeadTime(), request.leadTime(), fallback));
        link.setCapacity(TimeValues.replaceCapacity(link.getCapacity(), request.capacity(), fallback));
    }

    /** The unit-bearing fields of a PUT: omitted clears, in the network's period unit. */
    @AfterMapping
    default void applyTimeValues(LinkAttributesRequest request, @MappingTarget Link link) {
        TimeUnit fallback = link.getNetwork().periodUnit();
        link.setLeadTime(TimeValues.replace(link.getLeadTime(), request.leadTime(), fallback));
        link.setCapacity(TimeValues.replaceCapacity(link.getCapacity(), request.capacity(), fallback));
    }

    /** The unit-bearing fields of a PATCH: omitted is left exactly as it was. */
    @AfterMapping
    default void applyTimeValues(LinkPatch patch, @MappingTarget Link link) {
        link.setLeadTime(TimeValues.patch(link.getLeadTime(), patch.leadTime()));
        link.setCapacity(TimeValues.patch(link.getCapacity(), patch.capacity()));
    }

    /** The caption pair of a create: blank is none, and an omitted flag means visible (FR-30). */
    @AfterMapping
    default void applyCaption(LinkRequest request, @MappingTarget Link link) {
        link.setCaption(Captions.replace(request.caption()));
        link.setCaptionVisible(Captions.replaceVisible(request.captionVisible()));
    }

    /** The caption pair of a PUT: omitted or blank clears, an omitted flag means visible (FR-30). */
    @AfterMapping
    default void applyCaption(LinkAttributesRequest request, @MappingTarget Link link) {
        link.setCaption(Captions.replace(request.caption()));
        link.setCaptionVisible(Captions.replaceVisible(request.captionVisible()));
    }

    /** The caption pair of a PATCH: omitted is unchanged, present-but-blank clears. */
    @AfterMapping
    default void applyCaption(LinkPatch patch, @MappingTarget Link link) {
        link.setCaption(Captions.patch(link.getCaption(), patch.caption()));
        link.setCaptionVisible(Captions.patchVisible(link.isCaptionVisible(),
                patch.captionVisible()));
    }
}
