package com.lion.agent.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具权限标注
 * <p>
 * 标注在工具类上，声明调用该工具所需的最低权限码。
 * 权限码与接口层的 Sa-Token 权限码同一套语义（如 "user:list"）。
 * 未标注 = 公开工具（任何登录用户可调用）。
 * <p>
 * 注意：当前项目尚未实现 {@code StpInterface}（权限体系），
 * 标注后默认全部放行；等接入权限体系后自动按权限码过滤。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolPermission {

    /** 所需权限码，空串表示公开 */
    String value() default "";
}
