package com.lion.agent.retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>spring-ai-retry 使用示例（main 方法直接跑，打日志观察行为）</b>
 *
 * <p>这个包一共就 3 样东西：
 * <ul>
 *   <li>{@link RetryUtils#DEFAULT_RETRY_TEMPLATE}：预置的<b>长</b>退避模板（SDK 内部默认用）</li>
 *   <li>{@link RetryUtils#SHORT_RETRY_TEMPLATE}：预置的<b>短</b>退避模板（给 Embedding 这类轻量调用）</li>
 *   <li>{@link TransientAiException} / {@link NonTransientAiException}：
 *       标记「可重试」/「重试也没用」——决定要不要重试的关键</li>
 * </ul>
 *
 * <p>两个预置模板的实际参数（从 spring-ai-retry-2.0.1.jar 反编译得到）：
 * <pre>
 *   DEFAULT_RETRY_TEMPLATE : maxRetries=10, delay=2s, multiplier=5, maxDelay=180s
 *   SHORT_RETRY_TEMPLATE   : maxRetries=10, delay=100ms（无倍数递增）
 *   两者均 includes : TransientAiException、ResourceAccessException
 * </pre>
 * 默认模板退避时间很长（首次就睡 2 秒），演示里用自研的 {@link #fastTemplate}（毫秒级延迟），
 * 只有场景 4 会真的用到默认模板。
 *
 * <p><b>怎么跑</b>（二选一）：
 * <pre>
 *   1) IDEA：直接右键 Run 'SpringAiRetryDemo.main()'
 *   2) Maven：mvn test-compile exec:java -Dexec.mainClass=com.lion.agent.retry.SpringAiRetryDemo
 * </pre>
 *
 * <p><b>接在哪儿用</b>（本项目可落地的两处）：
 * <pre>{@code
 *   // 1) DashScopeRerankUtils：已有 @CircuitBreaker，再套一层指数退避
 *   return RetryUtils.execute(RetryUtils.DEFAULT_RETRY_TEMPLATE, () ->
 *           restClient.post().uri(rerankUrl).retrieve().body(String.class));
 *
 *   // 2) A2aDemoService 调远程 9999：网络抖动导致的失败才重试
 *   return RetryUtils.execute(RetryUtils.SHORT_RETRY_TEMPLATE, () ->
 *           client.sendMessage(message));
 * }</pre>
 * 把「HTTP 失败 → 抛 TransientAiException」这一步做好，重试策略就自动生效了。
 */
public class SpringAiRetryDemo {

    private static final Logger log = LoggerFactory.getLogger(SpringAiRetryDemo.class);

    public static void main(String[] args) throws Throwable {
        log.info("==================== spring-ai-retry 行为演示 ====================");
        scenario1TransientRetryUntilSuccess();
        scenario2RetriesExhausted();
        scenario3NonTransientNeverRetry();
        scenario4DefaultTemplate();
        scenario5ExponentialBackoff();
        scenario6CustomPredicate();
        log.info("==================== 演示结束 ====================");
    }

    // ==================== 六个演示场景 ====================

    /** 场景 1：临时性故障 —— 失败 2 次后第 3 次成功，重试自动完成 */
    private static void scenario1TransientRetryUntilSuccess() throws Throwable {
        log.info("【场景 1】临时异常（429）→ 重试直到成功");
        // 造一个「前 2 次失败、之后成功」的假远程调用
        FlakyRemoteCall call = FlakyRemoteCall.transientFail(2);

        // 真实业务里第二个参数就是业务逻辑本身（例如 () -> restClient.post()...body(String.class)）
        // call::call 是方法引用，等价于 () -> call.call()：把「怎么调用」交出去，而不是立刻调用
        String result = RetryUtils.execute(fastTemplate(5, Duration.ofMillis(10), 1.0), call::call);

        // result = "success@attempt-3"（第 3 次成功时返回，数字即成功发生在第几次）
        // call.attempts() = 3（真实调用总次数）
        // 换算：总调用 3 次 = 首次 1 次 + 重试 2 次，未触及 maxRetries=5 的上限
        log.info("   结果：{}，实际调用 {} 次（= 首次 + 2 次重试）\n", result, call.attempts());
    }

    /** 场景 2：一直失败 —— 重试次数耗尽后异常向上抛出 */
    private static void scenario2RetriesExhausted() {
        log.info("【场景 2】持续失败 → 重试耗尽后抛异常");
        FlakyRemoteCall call = FlakyRemoteCall.transientFail(Integer.MAX_VALUE);

        RetryTemplate template = fastTemplate(3, Duration.ofMillis(10), 1.0);

        try {
            template.execute(call::call);
        } catch (Exception e) {
            log.warn("   抛出：{}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
        log.info("   实际调用 {} 次（= 1 次首次 + 3 次重试）\n", call.attempts());
    }

    /** 场景 3：非临时异常 —— 一次都不重试，快速失败 */
    private static void scenario3NonTransientNeverRetry() {
        log.info("【场景 3】非临时异常（401 密钥错）→ 不重试");
        FlakyRemoteCall call = FlakyRemoteCall.nonTransientFail();

        RetryTemplate template = fastTemplate(5, Duration.ofMillis(10), 1.0);

        try {
            template.execute(call::call);
        } catch (Exception e) {
            log.warn("   抛出：{}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
        log.info("   实际调用 {} 次 —— 不在 includes 里，一次都不重试\n", call.attempts());
    }

    /** 场景 4：直接使用 SDK 预置的默认模板（首次成功，不触发退避） */
    private static void scenario4DefaultTemplate() throws Throwable {
        log.info("【场景 4】直接用 SDK 预置的默认模板");
        log.info("   DEFAULT_RETRY_TEMPLATE = {}", RetryUtils.DEFAULT_RETRY_TEMPLATE);
        log.info("   SHORT_RETRY_TEMPLATE   = {}", RetryUtils.SHORT_RETRY_TEMPLATE);

        FlakyRemoteCall call = FlakyRemoteCall.transientFail(0);

        String result = RetryUtils.execute(RetryUtils.DEFAULT_RETRY_TEMPLATE, call::call);

        log.info("   结果：{}，实际调用 {} 次\n", result, call.attempts());
    }

    /** 场景 5：指数退避 —— delay=20ms、multiplier=2，依次等待 20 / 40 / 80ms */
    private static void scenario5ExponentialBackoff() {
        log.info("【场景 5】指数退避 delay=20ms multiplier=2 → 依次等待 20/40/80ms");
        FlakyRemoteCall call = FlakyRemoteCall.transientFail(Integer.MAX_VALUE);

        RetryTemplate template = fastTemplate(3, Duration.ofMillis(20), 2.0);

        long start = System.nanoTime();
        try {
            template.execute(call::call);
        } catch (Exception e) {
            log.warn("   抛出：{}", e.getClass().getSimpleName());
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        log.info("   实际调用 {} 次，总耗时 {}ms（纯退避等待 ≈140ms）\n", call.attempts(), elapsedMs);
    }

    /** 场景 6：自定义 predicate —— 按异常消息决定是否重试（429 重试，403 不重试） */
    private static void scenario6CustomPredicate() {
        log.info("【场景 6】自定义 predicate：只重试消息含 429 的异常");
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(3)
                .predicate(t -> t.getMessage() != null && t.getMessage().contains("429"))
                .delay(Duration.ofMillis(5))
                .multiplier(1.0)
                .maxDelay(Duration.ofMillis(5))
                .build();
        RetryTemplate template = new RetryTemplate(policy);

        FlakyRemoteCall retried = new FlakyRemoteCall(Integer.MAX_VALUE,
                new RuntimeException("HTTP 429 Too Many Requests"));
        try {
            template.execute(retried::call);
        } catch (Exception e) {
            log.warn("   429 抛出：{}", e.getClass().getSimpleName());
        }
        log.info("   429 → 实际调用 {} 次（重试到耗尽）", retried.attempts());

        FlakyRemoteCall skipped = new FlakyRemoteCall(Integer.MAX_VALUE,
                new RuntimeException("HTTP 403 Forbidden"));
        try {
            template.execute(skipped::call);
        } catch (Exception e) {
            log.warn("   403 抛出：{}", e.getClass().getSimpleName());
        }
        log.info("   403 → 实际调用 {} 次（不重试）\n", skipped.attempts());
    }

    // ==================== 模拟对象与工具方法 ====================

    /**
     * 模拟远程调用：前 failTimes 次抛异常，之后成功。
     * 真实场景里这里就是 RestClient/WebClient 的一次 HTTP 调用。
     */
    static class FlakyRemoteCall {

        private final AtomicInteger attempts = new AtomicInteger();
        private final int failTimes;
        private final RuntimeException failure;

        FlakyRemoteCall(int failTimes, RuntimeException failure) {
            this.failTimes = failTimes;
            this.failure = failure;
        }

        /** 抛「可重试」异常，模拟 429 限流 / 连接超时 */
        static FlakyRemoteCall transientFail(int times) {
            return new FlakyRemoteCall(times, new TransientAiException("429 Too Many Requests"));
        }

        /** 抛「不可重试」异常，模拟 401 密钥错误 / 400 参数非法 */
        static FlakyRemoteCall nonTransientFail() {
            return new FlakyRemoteCall(Integer.MAX_VALUE, new NonTransientAiException("401 Invalid API Key"));
        }

        String call() {
            int n = attempts.incrementAndGet();
            log.info("      └─ 第 {} 次调用...", n);
            if (n <= failTimes) {
                log.warn("         ✗ 失败：{}", failure.getMessage());
                throw failure;
            }
            log.info("         ✓ 成功");
            return "success@attempt-" + n;
        }

        int attempts() {
            return attempts.get();
        }
    }

    /**
     * 构造一个「毫秒级延迟」的模板供演示使用。
     * 写法与 SDK 内部的 createDefaultRetryTemplate() 完全一致，只是把时间压短。
     */
    private static RetryTemplate fastTemplate(long maxRetries, Duration delay, double multiplier) {
        RetryPolicy policy = RetryPolicy.builder()
                // 重试次数上限：首次调用不算在内，所以总共最多执行 maxRetries + 1 次
                .maxRetries(maxRetries)
                // 异常白名单：只有抛 TransientAiException 才重试
                // 换成 .includes(ResourceAccessException.class) 就能覆盖 HTTP 超时场景
                .includes(TransientAiException.class)
                // 第 1 次失败后等待的基础时长
                .delay(delay)
                // 每次失败后等待时间的递增倍数：
                //   1.0 = 固定间隔（10ms, 10ms, 10ms...）
                //   2.0 = 指数退避（20ms, 40ms, 80ms...）
                .multiplier(multiplier)
                // 等待时长的上限，防止 multiplier 累计后睡太久
                //   例如 delay=2s multiplier=5 时，第 3 次本该等 50s，会被压到上限
                .maxDelay(Duration.ofMillis(500))
                // 生成不可变的 RetryPolicy 对象（此时规则已锁定，改不了了）
                .build();

        // RetryTemplate = 执行器，拿着规则去 .execute(任务)，负责「调几次、等多久、何时放弃」
        return new RetryTemplate(policy);
    }
}
