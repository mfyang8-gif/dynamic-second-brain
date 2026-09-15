package com.yangmf.mini_nodepad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yangmf.mini_nodepad.pojo.entity.Book;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BookMapper extends BaseMapper<Book> {
}
