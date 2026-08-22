package com.lion.agent.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一状态码
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    /** 成功 */
    SUCCESS(200, "操作成功"),

    /** 参数错误 */
    PARAM_ERROR(400, "参数错误"),

    /** 未登录 / token 失效 */
    UNAUTHORIZED(401, "未登录或登录已过期"),

    /** 无权限 */
    FORBIDDEN(403, "无访问权限"),

    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),

    /** 业务异常 */
    BUSINESS_ERROR(500, "业务处理失败"),

    /** 系统异常 */
    SYSTEM_ERROR(500, "系统繁忙，请稍后再试");

    private final int code;
    private final String message;
}
