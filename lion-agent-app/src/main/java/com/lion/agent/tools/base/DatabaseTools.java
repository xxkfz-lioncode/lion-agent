package com.lion.agent.tools.base;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lion.common.exception.BusinessException;
import com.lion.agent.tools.support.SqlGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 数据库操作工具集：向大模型暴露读写关系型数据库的能力。
 * <p>
 * 通过 JdbcTemplate / NamedParameterJdbcTemplate 执行 SQL，参数统一以 JSON 对象字符串传入
 * （如 {@code {"id":1,"name":"test"}}），由 Hutool {@link JSONUtil} 解析为具名参数 Map。
 * <p>
 * <b>安全约束</b>：本类属于常驻工具（每次对话都会注册，且不参与权限过滤），因此每个方法都先经
 * {@link SqlGuard} 校验后才执行——DDL、多语句、注释一律拒绝，无 WHERE 的 UPDATE/DELETE 拒绝，
 * SELECT 自动补行数上限；系统库（information_schema / mysql / performance_schema / sys）
 * 仅允许只读查询，写操作一律拒绝。工具自身不引入任何"信任模型输出"的环节。
 * <p>
 * 注意：{@link Tool} 的 description 是模型判断"要不要调用、怎么传参"的唯一依据，
 * 统一使用英文书写（明确能力 + 何时使用 + 返回格式 + 限制）。
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

    /** SQL 安全守卫：执行前的统一校验（六层防护，见类注释） */
    private final SqlGuard sqlGuard;

    public DatabaseTools(JdbcTemplate jdbcTemplate, SqlGuard sqlGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.sqlGuard = sqlGuard;
    }

    /**
     * 查询数据（不带参数）
     */
    @Tool(name = "queryData",
          description = "Query rows from the relational database with a plain SQL SELECT statement (no parameters). "
                  + "Returns a table-like text: columns joined by \" | \" in the header line, one row per line, NULL values printed as \"NULL\". "
                  + "Returns \"No data found\" when the result set is empty. Use it to read data; for write operations use the insert/update/delete tools. "
                  + "Restrictions: only a single SELECT statement is accepted — DDL, comments and multiple statements are rejected; "
                  + "reading system schemas (information_schema, performance_schema) is allowed. "
                  + "The result is automatically limited to " + SqlGuard.MAX_ROWS + " rows.")
    public String queryData(@ToolParam(description = "SQL SELECT statement without parameters, e.g. \"SELECT id, name FROM user LIMIT 10\"") String sql,
                            ToolContext context) {
        return execute(SqlGuard.SqlType.SELECT,
                () -> formatResultList(jdbcTemplate.queryForList(sqlGuard.check(sql, SqlGuard.SqlType.SELECT))));
    }

    /**
     * 查询数据（带参数）
     */
    @Tool(name = "queryDataWithParams",
          description = "Query rows from the relational database using a named-parameter SQL SELECT statement. "
                  + "Every placeholder such as :id or :name must appear as a key in the 'params' JSON object. "
                  + "Returns the same table-like text as the no-parameter query (\"No data found\" when empty). "
                  + "Restrictions: only a single SELECT statement is accepted; reading system schemas such as information_schema is allowed, "
                  + "and the result is automatically limited to " + SqlGuard.MAX_ROWS + " rows.")
    public String queryData(
            @ToolParam(description = "SQL SELECT statement using named parameters, e.g. \"SELECT * FROM user WHERE id = :id\"") String sql,
            @ToolParam(description = "Named parameters as a JSON object string matching the placeholders, e.g. {\"id\":1, \"name\":\"test\"}. Use {} when there is no parameter.") String params
    ) {
        return execute(SqlGuard.SqlType.SELECT,
                () -> formatResultList(namedParameterJdbcTemplate.queryForList(
                        sqlGuard.check(sql, SqlGuard.SqlType.SELECT), parseParams(params))));
    }

    /**
     * 插入数据
     */
    @Tool(name = "insertData",
          description = "Insert a row into the database with a named-parameter INSERT statement. "
                  + "Returns how many rows were inserted, e.g. \"1 row(s) inserted successfully\". "
                  + "Restrictions: only a single INSERT statement is accepted; DDL, multiple statements and system schemas are rejected.")
    public String insertData(
            @ToolParam(description = "SQL INSERT statement using named parameters, e.g. \"INSERT INTO user (name) VALUES (:name)\"") String sql,
            @ToolParam(description = "Named parameters as a JSON object string, e.g. {\"name\":\"test\"}") String params
    ) {
        return execute(SqlGuard.SqlType.INSERT,
                () -> "成功插入 " + namedParameterJdbcTemplate.update(
                        sqlGuard.check(sql, SqlGuard.SqlType.INSERT), parseParams(params)) + " 行");
    }

    /**
     * 更新数据
     */
    @Tool(name = "updateData",
          description = "Update existing rows using a named-parameter UPDATE statement. "
                  + "Returns how many rows were updated, e.g. \"2 row(s) updated successfully\". "
                  + "Restrictions: an UPDATE without a WHERE clause is rejected, because it would rewrite the whole table; "
                  + "writing to system schemas (mysql, information_schema, performance_schema, sys) is also rejected.")
    public String updateData(
            @ToolParam(description = "SQL UPDATE statement using named parameters, e.g. \"UPDATE user SET name = :name WHERE id = :id\"") String sql,
            @ToolParam(description = "Named parameters as a JSON object string, e.g. {\"id\":1, \"name\":\"test\"}") String params
    ) {
        return execute(SqlGuard.SqlType.UPDATE,
                () -> "成功更新 " + namedParameterJdbcTemplate.update(
                        sqlGuard.check(sql, SqlGuard.SqlType.UPDATE), parseParams(params)) + " 行");
    }

    /**
     * 删除数据
     */
    @Tool(name = "deleteData",
          description = "Delete rows using a named-parameter DELETE statement. "
                  + "Returns how many rows were deleted, e.g. \"1 row(s) deleted successfully\". "
                  + "Restrictions: a DELETE without a WHERE clause is rejected, because it would empty the whole table; "
                  + "writing to system schemas (mysql, information_schema, performance_schema, sys) is also rejected.")
    public String deleteData(
            @ToolParam(description = "SQL DELETE statement using named parameters, e.g. \"DELETE FROM user WHERE id = :id\"") String sql,
            @ToolParam(description = "Named parameters as a JSON object string, e.g. {\"id\":1}") String params
    ) {
        return execute(SqlGuard.SqlType.DELETE,
                () -> "成功删除 " + namedParameterJdbcTemplate.update(
                        sqlGuard.check(sql, SqlGuard.SqlType.DELETE), parseParams(params)) + " 行");
    }

    // ==================== 统一执行与错误处理 ====================

    /**
     * 统一执行入口：安全校验 → 执行 → 成功/失败日志。
     *
     * <p>错误分两类处理，避免把数据库原始报错（含表名、字段、版本等结构信息）回灌给模型：</p>
     * <ul>
     *   <li>{@link BusinessException}：由 {@link SqlGuard} 主动拦截，提示语是我们自己写的中文，可安全返回</li>
     *   <li>其他异常：只回一句通用提示，细节留在日志里供排查</li>
     * </ul>
     */
    private String execute(SqlGuard.SqlType type, Supplier<String> action) {
        long start = System.currentTimeMillis();
        try {
            String result = action.get();
            log.info("[DatabaseTools] {} 执行成功，耗时={}ms", type, System.currentTimeMillis() - start);
            return result;
        } catch (BusinessException e) {
            log.warn("[DatabaseTools] {} 被安全守卫拦截：{}", type, e.getMessage());
            return "拒绝执行：" + e.getMessage();
        } catch (Exception e) {
            log.error("[DatabaseTools] {} 执行失败：{}", type, e.getMessage());
            return type + " 执行失败：请检查 SQL 语法、字段名或数据权限";
        }
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
