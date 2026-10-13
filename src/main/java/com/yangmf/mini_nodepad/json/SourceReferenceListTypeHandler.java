package com.yangmf.mini_nodepad.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangmf.mini_nodepad.ai.rag.SourceReference;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * {@code List<SourceReference>} ↔ MySQL text 列（JSON 数组字符串）的双向映射。
 *
 * 为什么不复用 {@link JsonArrayTypeHandler}：它绑定 {@code List<String>}，
 * 泛型擦除后 Jackson 只能把对象元素反序列化成 LinkedHashMap，实体字段类型对不上。
 * 列类型沿用 source_page_ids 的惯例用 text 存 JSON（非 MySQL json 类型），保持全表一致。
 *
 * 空列表写 NULL（与既有 handler 的「空即 null」语义一致，避免历史查询出现 "[]" 噪声）。
 */
public class SourceReferenceListTypeHandler extends BaseTypeHandler<List<SourceReference>> {

    private static final ObjectMapper OBJECT_MAPPER = new JacksonObjectMapper();
    private static final TypeReference<List<SourceReference>> TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<SourceReference> parameter,
                                    JdbcType jdbcType) throws SQLException {
        try {
            if (parameter == null || parameter.isEmpty()) {
                ps.setString(i, null);
            } else {
                ps.setString(i, OBJECT_MAPPER.writeValueAsString(parameter));
            }
        } catch (JsonProcessingException e) {
            throw new SQLException("Error converting sourceReferences to JSON string", e);
        }
    }

    @Override
    public List<SourceReference> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<SourceReference> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<SourceReference> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<SourceReference> parse(String content) throws SQLException {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(content, TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("Error parsing JSON string to List<SourceReference>", e);
        }
    }
}
