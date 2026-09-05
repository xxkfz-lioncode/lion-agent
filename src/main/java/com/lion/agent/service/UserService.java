package com.lion.agent.service;

import com.lion.agent.model.dto.LoginRequest;
import com.lion.agent.model.dto.RegisterRequest;
import com.lion.agent.model.dto.UpdatePasswordRequest;
import com.lion.agent.model.dto.UpdateProfileRequest;
import com.lion.agent.model.entity.User;

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

    /**
     * 修改个人资料（昵称 / 头像），返回更新后的用户
     */
    User updateProfile(UpdateProfileRequest request);

    /**
     * 修改密码（需校验原密码，成功后强制重新登录）
     */
    void updatePassword(UpdatePasswordRequest request);
}
