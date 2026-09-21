package com.yangmf.mini_nodepad.converter;

import com.yangmf.mini_nodepad.enums.AiProcessStatusEnum;
import com.yangmf.mini_nodepad.enums.SourceTypeEnum;
import com.yangmf.mini_nodepad.pojo.dto.PageIngestDTO;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.pojo.vo.PageVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface PageConverter {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "sourceType", ignore = true)
    @Mapping(target = "aiProcessStatus", ignore = true)
    @Mapping(target = "aiProcessMsg", ignore = true)
    @Mapping(target = "chunkCount", ignore = true)
    Page toEntity(PageIngestDTO dto);

    @Mapping(source = "sourceType", target = "sourceType", qualifiedByName = "sourceTypeToString")
    @Mapping(source = "aiProcessStatus", target = "aiProcessStatus", qualifiedByName = "aiStatusToCode")
    PageVO toVO(Page page);

    @Named("sourceTypeToString")
    default String sourceTypeToString(SourceTypeEnum sourceType) {
        return sourceType != null ? sourceType.name() : null;
    }

    @Named("aiStatusToCode")
    default Integer aiStatusToCode(AiProcessStatusEnum status) {
        return status != null ? status.getCode() : null;
    }
}