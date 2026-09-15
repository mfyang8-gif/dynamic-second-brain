package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.pojo.dto.BookCreateDTO;
import com.yangmf.mini_nodepad.pojo.dto.BookUpdateDTO;
import com.yangmf.mini_nodepad.pojo.dto.PageQueryDTO;
import com.yangmf.mini_nodepad.pojo.vo.BookVO;
import com.yangmf.mini_nodepad.result.PageResult;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.BookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库的创建、查询、更新、删除接口")
public class BookController {

    private final BookService bookService;

    @PostMapping
    @Operation(summary = "创建知识库")
    public Result<String> create(@Valid @RequestBody BookCreateDTO dto) {
        return Result.success(bookService.createBook(dto));
    }
    //TODO:是否公开，如果公开，则不需要鉴权--后期做，开始做大厅的时候
    @GetMapping("/{id}")
    @Operation(summary = "获取知识库详情")
    public Result<BookVO> getById(@NotBlank(message = "知识库ID不能为空") @PathVariable String id) {
        return Result.success(bookService.getBookById(id));
    }

    @GetMapping
    @Operation(summary = "分页获取我的知识库列表")
    public Result<PageResult<BookVO>> listMyBooks(@Valid PageQueryDTO queryDTO) {
        return Result.success(bookService.listMyBooks(queryDTO));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新知识库")
    public Result<Void> update(@NotBlank(message = "知识库ID不能为空") @PathVariable String id,
                               @Valid @RequestBody BookUpdateDTO dto) {
        bookService.updateBook(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除知识库")
    public Result<Void> delete(@NotBlank(message = "知识库ID不能为空") @PathVariable String id) {
        bookService.deleteBook(id);
        return Result.success();
    }

    @PostMapping("/batch/delete")
    @Operation(summary = "批量删除知识库")
    public Result<Void> batchDelete(@NotEmpty(message = "请选择要删除的知识库") @RequestBody List<String> ids) {
        bookService.deleteBooks(ids);
        return Result.success();
    }
}