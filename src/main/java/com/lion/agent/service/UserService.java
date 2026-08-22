package com.lion.agent.service;

import com.lion.agent.dto.LoginRequest;
import com.lion.agent.dto.RegisterRequest;
import com.lion.agent.entity.User;

import java.util.Map;

/**
 * 用户服务
 */
public interface UserService {

    /**
     * 注册
     */
    void register(RegisterRequest request);

    /**
     * 登录，返回 token 与用户信息
     */
    Map<String, Object> login(LoginRequest request);

    /**
     * 退出登录
     */
    void logout();

    /**
     * 获取当前登录用户
     */
    User getCurrentUser();
}
