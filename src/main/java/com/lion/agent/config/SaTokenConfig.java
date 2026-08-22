package com.lion.agent.config;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.router.SaRouter;
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
            // 接口文档
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/favicon.ico",
            "/error",
            "/mcp"
    };



    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                // SSE 异步分发阶段跳过鉴权，避免 SaTokenContext 上下文未初始化
                if (DispatcherType.ASYNC.equals(request.getDispatcherType())) {
                    return true;
                }
                // 白名单直接放行（不用 SaRouter.match(...).stop()，避免抛出未被捕获的 StopMatchException）
                if (SaRouter.isMatchCurrURI(EXCLUDE_PATHS)) {
                    return true;
                }
                // 其余请求必须登录
                StpUtil.checkLogin();
                return true;
            }
        })
        // 接口路径前缀
        .addPathPatterns("/**")
        .excludePathPatterns("/error");
    }
}
