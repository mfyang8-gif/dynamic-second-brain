package com.yangmf.mini_nodepad.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(name = "分页查询参数")
public class PageQueryDTO {

    @Min(value = 1, message = "页码最小为1")
    @Schema(description = "页码，默认1", example = "1")
    private int page = 1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 100, message = "每页条数最大为100")
    @Schema(description = "每页条数，默认10", example = "10")
    private int pageSize = 10;
}