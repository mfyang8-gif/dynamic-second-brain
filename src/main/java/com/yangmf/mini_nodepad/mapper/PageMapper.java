package com.yangmf.mini_nodepad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
@Mapper
public interface PageMapper extends BaseMapper<Page> {

    List<Page> selectRecycleBin(@Param("userId") String userId);

    int permanentDeleteById(@Param("id") String id, @Param("userId") String userId);

    int batchPermanentDeleteByIds(@Param("ids") List<String> ids, @Param("userId") String userId);

    int restoreById(@Param("id") String id, @Param("userId") String userId);

    int batchRestoreByIds(@Param("ids") List<String> ids, @Param("userId") String userId);

    int permanentDeleteRecycleBin(@Param("userId") String userId);

    int cleanExpiredRecycleBin(@Param("beforeTime") LocalDateTime beforeTime);
    int sumChunkCountByBookId(@Param("bookId") String bookId);

}