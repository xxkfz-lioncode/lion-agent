package com.lion.agent.tools.support;

import com.lion.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全守卫：大模型（或经提示注入的终端用户）提交的 SQL 在执行前的统一校验与归一化。
 *
 * <p>背景：{@code DatabaseTools} 属于常驻工具（{@code ALWAYS_ON_TOOLS}），每次对话都会注册，
 * 且常驻池不参与权限过滤，因此工具内部必须自带拦截能力——不能把安危寄托在模型的"自觉"上。</p>
 *
 * <p>防护共六层，任一层不通过即抛 {@link BusinessException} 阻断执行：</p>
 * <ol>
 *   <li><b>注释</b>：禁止 {@code /*} {@code --} {@code #}。既防 {@code SEL/*x* /ECT} 这类绕过，
 *       也避免后续自动补 {@code LIMIT} 时被行注释吞掉而失效</li>
 *   <li><b>多语句</b>：分号之后不得再有可执行内容，防 {@code SELECT 1; DROP TABLE t} 的叠加执行</li>
 *   <li><b>高危关键字</b>：DDL、权限变更、文件导出、DoS 函数一律拒绝</li>
 *   <li><b>系统库</b>：SELECT 放行 information_schema / performance_schema / sys 等（查表结构、
 *       看性能指标是正当需求）；INSERT / UPDATE / DELETE 命中即拒绝，防止改写 mysql.user 这类
 *       等同提权的操作</li>
 *   <li><b>语句类型白名单</b>：查询工具只接受 SELECT，写工具只接受对应的单一 DML</li>
 *   <li><b>影响范围</b>：UPDATE / DELETE 必须带 WHERE；SELECT 自动补 LIMIT</li>
 * </ol>
 *
 * <p>关键字匹配前会先挖空字符串字面量，以保证判定基于"SQL 骨架"而非数据：
 * {@code WHERE name = ' drop table '} 不会被误杀，而真正的 {@code DROP TABLE} 无处可藏。</p>
 */
@Slf4j
@Component
public class SqlGuard {

    /** 查询最大返回行数：防止无 LIMIT 的全表查询拖垮连接池并撑爆模型上下文 */
    public static final int MAX_ROWS = 200;

    /** 允许的语句类型，与 {@code DatabaseTools} 的每个工具一一对应 */
    public enum SqlType {
        SELECT, INSERT, UPDATE, DELETE
    }

    /** 字符串字面量（含 SQL 的双写转义），判定时先挖空 */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"");

    /** 注释起始标记 */
    private static final Pattern COMMENT = Pattern.compile("/\\*|\\*/|--|#");

    /**
     * 高危关键字：
     * <ul>
     *   <li>DDL / 结构变更：DROP TRUNCATE ALTER CREATE RENAME</li>
     *   <li>权限与会话：GRANT REVOKE SHUTDOWN KILL</li>
     *   <li>过程调用：CALL EXECUTE（内部逻辑不可控，等同任意代码执行）</li>
     *   <li>DoS 函数：SLEEP BENCHMARK GET_LOCK LOAD_FILE</li>
     *   <li>文件导出：INTO OUTFILE / INTO DUMPFILE（可落马到磁盘）</li>
     * </ul>
     * 说明：REPLACE / MERGE 未列入，它们会被第 ⑤ 层语句类型白名单拦下。
     */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(DROP|TRUNCATE|ALTER|CREATE|RENAME|GRANT|REVOKE|SHUTDOWN|KILL|CALL|EXECUTE)\\b"
                    + "|\\b(SLEEP|BENCHMARK|GET_LOCK|LOAD_FILE)\\b"
                    + "|\\bINTO\\s+(OUT|DUMP)FILE\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 系统库访问：写操作命中即拒绝（改 mysql.user 这类等同提权）；
     * SELECT 放行，读 information_schema 看表结构、读 performance_schema 看性能指标都是正当需求。
     */
    private static final Pattern SYSTEM_SCHEMA = Pattern.compile(
            "\\b(information_schema|performance_schema|mysql|sys)\\s*\\.", Pattern.CASE_INSENSITIVE);

    private static final Pattern LIMIT_CLAUSE = Pattern.compile("\\bLIMIT\\s+\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE_CLAUSE = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 校验并归一化 SQL
     *
     * @param sql      原始 SQL（模型传入，不可信）
     * @param expected 当前工具允许的唯一语句类型
     * @return 归一化后可安全执行的 SQL（SELECT 无 LIMIT 时自动补 {@link #MAX_ROWS} 行上限）
     * @throws BusinessException 任一防护层不通过时抛出，提示语可直接回给调用方
     */
    public String check(String sql, SqlType expected) {
        if (sql == null || sql.isBlank()) {
            throw new BusinessException("SQL 语句不能为空");
        }
        String trimmed = sql.trim();
        // 末尾单个分号无害，先去掉；残留分号将在第 ② 层被视为多语句
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        String skeleton = STRING_LITERAL.matcher(trimmed).replaceAll("''");

        // ① 注释
        if (COMMENT.matcher(skeleton).find()) {
            log.warn("[SqlGuard] SQL 含注释，已拦截: {}", truncate(trimmed));
            throw new BusinessException("SQL 不允许包含注释");
        }

        // ② 多语句
        if (skeleton.contains(";")) {
            log.warn("[SqlGuard] 检测到多语句，已拦截: {}", truncate(trimmed));
            throw new BusinessException("不允许一次执行多条 SQL 语句");
        }

        // ③ 高危关键字
        Matcher matcher = FORBIDDEN.matcher(skeleton);
        if (matcher.find()) {
            log.warn("[SqlGuard] 命中高危关键字「{}」，已拦截: {}", matcher.group(), truncate(trimmed));
            throw new BusinessException("该 SQL 包含高危操作（" + matcher.group().toUpperCase() + "），已拒绝执行");
        }

        // ④ 系统库：只读查询放行（查表结构、统计信息是正当需求），写操作一律拒绝
        if (expected != SqlType.SELECT) {
            Matcher schemaMatcher = SYSTEM_SCHEMA.matcher(skeleton);
            if (schemaMatcher.find()) {
                log.warn("[SqlGuard] {} 尝试写系统库「{}」，已拦截: {}", expected, schemaMatcher.group(), truncate(trimmed));
                throw new BusinessException("不允许对系统库执行 " + expected.name() + " 操作");
            }
        }

        // ⑤ 语句类型白名单：必须以期望的关键字开头
        String keyword = expected.name();
        if (!skeleton.regionMatches(true, 0, keyword, 0, keyword.length())) {
            throw new BusinessException("只允许执行 " + keyword + " 语句");
        }

        // ⑥ 影响范围
        if (expected == SqlType.SELECT) {
            // 无 LIMIT 时补上限，避免全表扫描拖垮连接与上下文
            return LIMIT_CLAUSE.matcher(trimmed).find() ? trimmed : trimmed + " LIMIT " + MAX_ROWS;
        }
        if (!WHERE_CLAUSE.matcher(skeleton).find()) {
            throw new BusinessException(keyword + " 必须带 WHERE 条件，禁止全表操作");
        }
        return trimmed;
    }

    /** 日志脱敏截断：SQL 可能很长，只保留前 300 字符便于定位 */
    private String truncate(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.length() > 300 ? sql.substring(0, 300) + "…(已截断)" : sql;
    }
}
