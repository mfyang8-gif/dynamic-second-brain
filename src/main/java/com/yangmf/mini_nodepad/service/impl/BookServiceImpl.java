package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.converter.BookConverter;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.mapper.BookMapper;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.dto.BookCreateDTO;
import com.yangmf.mini_nodepad.pojo.dto.BookUpdateDTO;
import com.yangmf.mini_nodepad.pojo.dto.PageQueryDTO;
import com.yangmf.mini_nodepad.pojo.entity.Book;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.pojo.vo.BookVO;
import com.yangmf.mini_nodepad.result.BatchOperationResult;
import com.yangmf.mini_nodepad.result.PageResult;
import com.yangmf.mini_nodepad.service.BookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookServiceImpl extends ServiceImpl<BookMapper, Book> implements BookService {

    private final BookMapper bookMapper;
    private final PageMapper pageMapper;
    private final BookConverter bookConverter;

    @Override
    public String createBook(BookCreateDTO dto) {
        String currentUserId = getCurrentUserId();
        Book book = bookConverter.toEntity(dto);
        book.setId(UUID.randomUUID().toString().replace("-", ""));
        book.setUserId(currentUserId);
        this.save(book);
        log.info("知识库创建成功 | bookId={}, userId={}", book.getId(), currentUserId);
        return book.getId();
    }

    @Override
    public BookVO getBookById(String id) {
        Book book = getByIdWithOwnershipCheck(id);
        Long pageCount = countPages(id);
        return convertToVO(book, pageCount);
    }

    @Override
    public PageResult<BookVO> listMyBooks(PageQueryDTO queryDTO) {
        String currentUserId = getCurrentUserId();
        log.debug("分页查询知识库 | queryDTO={}", queryDTO);
        PageHelper.startPage(queryDTO.getPage(), queryDTO.getPageSize());

        LambdaQueryWrapper<Book> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Book::getUserId, currentUserId)
                .orderByDesc(Book::getCreatedAt);
        List<Book> bookList = this.list(wrapper);

        PageInfo<Book> pageInfo = new PageInfo<>(bookList);

        List<BookVO> voList = pageInfo.getList().stream()
                .map(book -> {
                    Long pageCount = countPages(book.getId());
                    return convertToVO(book, pageCount);
                })
                .toList();

        return PageResult.<BookVO>builder()
                .total(pageInfo.getTotal())
                .page(queryDTO.getPage())
                .pageSize(queryDTO.getPageSize())
                .records(voList)
                .build();
    }

    @Override
    public void updateBook(String id, BookUpdateDTO dto) {
        String currentUserId = getCurrentUserId();

        LambdaUpdateWrapper<Book> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Book::getId, id)
                .eq(Book::getUserId, currentUserId);

        if (dto.getName() != null) {
            wrapper.set(Book::getName, dto.getName());
        }
        if (dto.getDescription() != null) {
            wrapper.set(Book::getDescription, dto.getDescription());
        }

        if (!this.update(wrapper)) {
            throw new ForbiddenException("无权操作该知识库");
        }
        log.info("知识库更新成功 | bookId={}, userId={}", id, currentUserId);
    }

    @Override
    public void deleteBook(String id) {
        String currentUserId = getCurrentUserId();

        LambdaUpdateWrapper<Book> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Book::getId, id)
                .eq(Book::getUserId, currentUserId)
                .set(Book::getDeleted, 1)
                .set(Book::getDeletedAt, LocalDateTime.now());
        if (!this.update(wrapper)) {
            throw new ForbiddenException("无权操作该知识库");
        }
        log.info("知识库已删除 | bookId={}, userId={}", id, currentUserId);
    }

    @Override
    public BatchOperationResult deleteBooks(List<String> ids) {
        String currentUserId = getCurrentUserId();

        LambdaUpdateWrapper<Book> wrapper = new LambdaUpdateWrapper<>();
        wrapper.in(Book::getId, ids)
                .eq(Book::getUserId, currentUserId)
                .set(Book::getDeleted, 1)
                .set(Book::getDeletedAt, LocalDateTime.now());
        int affected = bookMapper.update(null, wrapper);
        if (affected == 0) {
            throw new ForbiddenException("无权操作这些知识库");
        }
        log.info("批量删除知识库 | userId={}, 请求: {} 条, 成功: {} 条", currentUserId, ids.size(), affected);
        return BatchOperationResult.of(ids.size(), affected);
    }

    // ==================== 私有方法 ====================

    private String getCurrentUserId() {
        return String.valueOf(BaseContext.getCurrentId());
    }

    private Book getByIdWithOwnershipCheck(String id) {
        String currentUserId = getCurrentUserId();
        log.debug("知识库归属校验 | bookId={}, userId={}", id, currentUserId);
        LambdaQueryWrapper<Book> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Book::getId, id)
                .eq(Book::getUserId, currentUserId);
        Book book = this.getOne(wrapper);
        if (book == null) {
            throw new ForbiddenException("无权访问该知识库");
        }
        return book;
    }

    private Long countPages(String bookId) {
        LambdaQueryWrapper<Page> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Page::getBookId, bookId);
        return pageMapper.selectCount(wrapper);
    }

    private BookVO convertToVO(Book book, Long pageCount) {
        return BookVO.builder()
                .id(book.getId())
                .name(book.getName())
                .description(book.getDescription())
                .pageCount(pageCount)
                .createdAt(book.getCreatedAt())
                .build();
    }
}