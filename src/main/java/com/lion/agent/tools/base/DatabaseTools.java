package com.lion.agent.tools.base;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库操作工具集：向大模型暴露读写关系型数据库的能力。
 * <p>
 * 通过 JdbcTemplate / NamedParameterJdbcTemplate 执行 SQL，参数统一以 JSON 对象字符串传入
 * （如 {@code {"id":1,"name":"test"}}），由 Hutool {@link JSONUtil} 解析为具名参数 Map。
 * <p>
 * 注意：{@link Tool} 的 description 是模型判断"要不要调用、怎么传参"的唯一依据，
 * 统一使用英文书写（明确能力 + 何时使用 + 返回格式）。
 */
@Slf4j
@Component
public class DatabaseTools {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 具名参数模板：NamedParameterJdbcTemplate 只是 JdbcTemplate 的包装，这里按需派生，
     * 不强依赖容器中是否已注册对应的 Bean。
     */
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public DatabaseTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /**
     * 查询数据（不带参数）
     */
    @Tool(name = "queryData",
          description = "Query rows from the relational database with a plain SQL SELECT statement (no parameters). "
                  + "Returns a table-like text: columns joined by \" | \" in the header line, one row per line, NULL values printed as \"NULL\". "
                  + "Returns \"No data found\" when the result set is empty. Use it to read data; for write operations use the insert/update/delete tools.")
    public String queryData(@ToolParam(description = "SQL SELECT statement without parameters, e.g. \"SELECT id, name FROM user LIMIT 10\"") String sql) {
        try {
            return formatResultList(jdbcTemplate.queryForList(requireSql(sql)));
        } catch (Exception e) {
            log.error("执行无参查询失败, sql={}", sql, e);
            return "执行查询失败：" + e.getMessage();
        }
    }

    /**
     * 查询数据（带参数）
     */
    @Tool(name = "queryDataWithParams",
          description = "Query rows from the relational database using a named-parameter SQL SELECT statement. "
                  + "Every placeholder such as :id or :name must appear as a key in the 'params' JSON object. "
                  + "Returns the same table-like text as the no-parameter query (\"No data found\" when empty).")
    public String queryData(
            @ToolParam(description = "SQL SELECT statement using named parameters, e.g. \"SELECT * FROM user WHERE id = :id\"") String sql,
            @ToolParam(description = "Named parameters as a JSON object string matching the placeholders, e.g. {\"id\":1, \"name\":\"test\"}. Use {} when there is no parameter.") String params
    ) {
        try {
            List<Map<String, Object>> result = namedParameterJdbcTemplate.queryForList(requireSql(sql), parseParams(params));
            return formatResultList(result);
        } catch (Exception e) {
            log.error("执行带参查询失败, sql={}, params={}", sql, params, e);
            return "执行查询失败：" + e.getMessage();
        }
    }

    /**
     * 插入数据
     */
    @Tool(name = "insertData",
          description = "Insert a row into the database with a named-parameter INSERT statement. "
                  + "Returns how many rows were inserted, e.g. \"1 row(s) inserted successfully\".")
    public String insertData(
            @ToolParam(description = "SQL INSERT statement using named parameters, e.g. \"INSERT INTO user (name) VALUES (:name)\"") String sql,
            @ToolParam(description = "Named parameters as a JSON object string, e.g. {\"name\":\"test\"}") String params
    ) {
        try {
            int rowsAffected = namedParameterJdbcTemplate.update(requireSql(sql), parseParams(params));
            return "成功插入 " + rowsAffected + " 行";
        } catch (Exception e) {
            log.error("执行插入失败, sql={}, params={}", sql, params, e);
            return "插入数据失败：" + e.getMessage();
        }
    }

    /**
     * 更新数据
     */
    @Tool(name = "updateData",
          description = "Update existing rows using a named-parameter UPDATE statement. "
                  + "Returns how many rows were updated, e.g. \"2 row(s) updated successfully\". "
                  + "Remind the caller that an UPDATE without a WHERE clause rewrites the whole table.")
    public String updateData(
            @ToolParam(description = "SQL UPDATE statement using named parameters, e.g. \"UPDATE user SET name = :name WHERE id = :id\"") String sql,
            @ToolParam(description = "Named parameters as a JSON object string, e.g. {\"id\":1, \"name\":\"test\"}") String params
    ) {
        try {
            int rowsAffected = namedParameterJdbcTemplate.update(requireSql(sql), parseParams(params));
            return "成功更新 " + rowsAffected + " 行";
        } catch (Exception e) {
            log.error("执行更新失败, sql={}, params={}", sql, params, e);
            return "更新数据失败：" + e.getMessage();
        }
    }

    /**
     * 删除数据
     */
    @Tool(name = "deleteData",
          description = "Delete rows using a named-parameter DELETE statement. "
                  + "Returns how many rows were deleted, e.g. \"1 row(s) deleted successfully\". "
                  + "A DELETE without a WHERE clause empties the whole table, so always filter explicitly.")
    public String deleteData(
            @ToolParam(description = "SQL DELETE statement using named parameters, e.g. \"DELETE FROM user WHERE id = :id\"") String sql,
            @ToolParam(description = "Named parameters as a JSON object string, e.g. {\"id\":1}") String params
    ) {
        try {
            int rowsAffected = namedParameterJdbcTemplate.update(requireSql(sql), parseParams(params));
            return "成功删除 " + rowsAffected + " 行";
        } catch (Exception e) {
            log.error("执行删除失败, sql={}, params={}", sql, params, e);
            return "删除数据失败：" + e.getMessage();
        }
    }

    /**
     * SQL 非空校验
     */
    private String requireSql(String sql) {
        if (StrUtil.isBlank(sql)) {
            throw new IllegalArgumentException("SQL 语句不能为空");
        }
        return sql.trim();
    }

    /**
     * 解析 JSON 参数为具名参数 Map（使用 Hutool JSONUtil）。
     * <p>
     * 返回 {@link LinkedHashMap} 而非 JSONObject：Spring 的具名参数源会把 Map 类型的值
     * 当作嵌套参数源展开，直接用 JSONObject 可能干扰参数绑定。
     * </p>
     *
     * @param paramsJson 参数 JSON 字符串，可为空
     * @return 具名参数 Map，解析失败或为空时返回空 Map
     */
    private Map<String, Object> parseParams(String paramsJson) {
        if (StrUtil.isBlank(paramsJson)) {
            return Collections.emptyMap();
        }
        try {
            JSONObject json = JSONUtil.parseObj(paramsJson.trim());
            Map<String, Object> paramMap = new LinkedHashMap<>();
            json.forEach(paramMap::put);
            return paramMap;
        } catch (Exception e) {
            log.error("解析 JSON 参数失败, params={}", paramsJson, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 格式化查询结果为紧凑的表格文本（首行为表头，第二行为分隔线，其后为数据行）
     */
    private String formatResultList(List<Map<String, Object>> resultList) {
        if (resultList == null || resultList.isEmpty()) {
            return "未查询到数据";
        }

        StringBuilder sb = new StringBuilder();
        List<String> columns = List.copyOf(resultList.get(0).keySet());

        // 表头
        String header = String.join(" | ", columns);
        sb.append(header).append('\n');
        // 分隔线
        sb.append("-".repeat(header.length())).append('\n');

        // 数据行（按表头顺序取值，避免不同行 keySet 顺序不一致导致错位）
        for (Map<String, Object> row : resultList) {
            sb.append(columns.stream()
                    .map(col -> {
                        Object val = row.get(col);
                        return val == null ? "NULL" : val.toString();
                    })
                    .reduce((a, b) -> a + " | " + b)
                    .orElse(""))
                    .append('\n');
        }

        return sb.toString();
    }
}
