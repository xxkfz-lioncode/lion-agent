package com.lion.agent.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 权限认证配置
 * <p>
 * 注册权限拦截器，除白名单外，所有请求均需登录。
 * <p>
 * 放行规则：
 * <ul>
 *   <li>白名单路径（登录注册、接口文档等）由 Spring 的 {@code excludePathPatterns} 直接放行</li>
 *   <li>{@code com.lion.agent.controller.test} 包下的联调测试接口免登录（拦截器内按包名判断）</li>
 * </ul>
 * <p>
 * 注意：SSE（SseEmitter）异步请求在异步分发（async dispatch）阶段会再次进入拦截器链，
 * 此时 SaTokenContext 上下文（由 OncePerRequestFilter 初始化）尚未绑定到异步线程，
 * 若继续执行 SaRouter 鉴权会抛出 {@code SaTokenContext 上下文尚未初始化}，
 * 因此异步分发阶段直接放行。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /** 无需登录即可访问的白名单 */
    private static final String[] EXCLUDE_PATHS = {
            "/api/auth/login",
            "/api/auth/register",
            // 接口文档（springdoc-openapi）
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/swagger-ui/index.html",
            "/favicon.ico",
            "/error",
            "/mcp"
    };

    /** 免登录包前缀：controller/test 包下的联调测试接口（本地调试用） */
    private static final String ANONYMOUS_PACKAGE_PREFIX = "com.lion.agent.controller.test";



    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                // SSE 异步分发阶段跳过鉴权，避免 SaTokenContext 上下文未初始化
                if (DispatcherType.ASYNC.equals(request.getDispatcherType())) {
                    return true;
                }
                // controller/test 包下的联调测试接口免登录（后续新增测试接口无需再改配置）
                if (handler instanceof HandlerMethod handlerMethod
                        && handlerMethod.getBeanType().getPackageName().startsWith(ANONYMOUS_PACKAGE_PREFIX)) {
                    return true;
                }
                // 其余请求必须登录（白名单已交给 Spring 的 excludePathPatterns 处理）
                StpUtil.checkLogin();
                return true;
            }
        })
        // 拦截所有请求；白名单通过 excludePathPatterns 在 Spring 层面直接放行
        .addPathPatterns("/**")
        .excludePathPatterns(EXCLUDE_PATHS);
    }
}
