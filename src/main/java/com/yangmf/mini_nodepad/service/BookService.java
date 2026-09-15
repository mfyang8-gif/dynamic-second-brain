package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.pojo.dto.BookCreateDTO;
import com.yangmf.mini_nodepad.pojo.dto.BookUpdateDTO;
import com.yangmf.mini_nodepad.pojo.dto.PageQueryDTO;
import com.yangmf.mini_nodepad.pojo.vo.BookVO;
import com.yangmf.mini_nodepad.result.PageResult;

import java.util.List;

public interface BookService {

    String createBook(BookCreateDTO dto);

    BookVO getBookById(String id);

    PageResult<BookVO> listMyBooks(PageQueryDTO queryDTO);

    void updateBook(String id, BookUpdateDTO dto);

    void deleteBook(String id);

    void deleteBooks(List<String> ids);
}