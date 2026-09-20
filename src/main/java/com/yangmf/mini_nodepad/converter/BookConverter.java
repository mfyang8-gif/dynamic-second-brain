package com.yangmf.mini_nodepad.converter;

import com.yangmf.mini_nodepad.pojo.dto.BookCreateDTO;
import com.yangmf.mini_nodepad.pojo.entity.Book;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface BookConverter {

    Book toEntity(BookCreateDTO dto);
}