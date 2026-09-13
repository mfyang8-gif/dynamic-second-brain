package com.yangmf.mini_nodepad.json;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@MappedTypes(List.class)
public class JsonArrayTypeHandler extends BaseTypeHandler<List<String>> {

    private static final ObjectMapper OBJECT_MAPPER = new JacksonObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType) throws SQLException {
        try {
            if (CollectionUtils.isEmpty(parameter)) {
                ps.setString(i, null);
            } else {
                ps.setString(i, OBJECT_MAPPER.writeValueAsString(parameter));
            }
        } catch (JsonProcessingException e) {
            throw new SQLException("Error converting list to JSON string", e);
        }
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String json = rs.getString(columnName);
        return parseArray(json);
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String json = rs.getString(columnIndex);
        return parseArray(json);
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String json = cs.getString(columnIndex);
        return parseArray(json);
    }

    @SuppressWarnings("unchecked")
    private List<String> parseArray(String content) throws SQLException {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(content, List.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Error parsing JSON string to list", e);
        }
    }
}
