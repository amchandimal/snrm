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
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * {@link Product} and {@link NodeProduct} ↔ DTO mapping.
 *
 * <p>Both live in one mapper because they are one concern to a caller: the product catalogue and
 * what each node does with it. Neither has a create-from-DTO method — a {@code Product} needs its
 * project and a {@code NodeProduct} needs both of its parents, and {@link ProductService} is where
 * those are resolved and checked.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = TimeValueMapper.class)
public interface ProductMapper {

    @Mapping(target = "projectId", source = "project.id")
    ProductDto toDto(Product product);

    List<ProductDto> toDtoList(List<Product> products);

    @BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)
    void replace(ProductRequest request, @MappingTarget Product product);

    // The ids come from the associations rather than from the @EmbeddedId: @MapsId derives that
    // key from these same two references, and reading the references is correct at every point in
    // the entity's life, including before the derived key has been resolved.
    @Mapping(target = "nodeId", source = "node.id")
    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "productName", source = "product.name")
    NodeProductDto toDto(NodeProduct nodeProduct);

    List<NodeProductDto> toNodeProductDtoList(List<NodeProduct> nodeProducts);

    /**
     * Everything except the two rates, which {@link #applyTimeValues} writes afterwards for the
     * reason {@link NodeMapper} documents.
     */
    @Mapping(target = "demand", ignore = true)
    @Mapping(target = "holdingCost", ignore = true)
    @BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)
    void replace(NodeProductRequest request, @MappingTarget NodeProduct nodeProduct);

    /**
     * The two rates of a PUT. Both columns are {@code NOT NULL}, so an omitted field becomes zero
     * over the network's period unit rather than unconstrained.
     */
    @AfterMapping
    default void applyTimeValues(NodeProductRequest request, @MappingTarget NodeProduct nodeProduct) {
        TimeUnit fallback = nodeProduct.getNode().getNetwork().periodUnit();
        nodeProduct.setDemand(
                TimeValues.replaceQuantity(nodeProduct.getDemand(), request.demand(), fallback));
        nodeProduct.setHoldingCost(TimeValues.replaceQuantity(nodeProduct.getHoldingCost(),
                request.holdingCost(), fallback));
    }
}
