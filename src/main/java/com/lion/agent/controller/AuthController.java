package com.lion.agent.controller;

import com.lion.agent.common.Result;
import com.lion.agent.model.dto.LoginRequest;
import com.lion.agent.model.dto.RegisterRequest;
import com.lion.agent.model.dto.UpdatePasswordRequest;
import com.lion.agent.model.dto.UpdateProfileRequest;
import com.lion.agent.model.entity.User;
import com.lion.agent.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口
 */
@Tag(name = "01-认证管理", description = "注册 / 登录 / 退出 / 当前用户 / 个人资料")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return Result.success("注册成功", null);
    }

    @Operation(summary = "用户登录", description = "返回 token，后续请求在 Header 中携带 Authorization: <token>")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        return Result.success("登录成功", userService.login(request));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        userService.logout();
        return Result.success();
    }

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/me")
    public Result<User> me() {
        return Result.success(userService.getCurrentUser());
    }

    @Operation(summary = "修改个人资料（昵称 / 头像）", description = "返回更新后的用户信息")
    @PutMapping("/profile")
    public Result<User> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return Result.success("修改成功", userService.updateProfile(request));
    }

    @Operation(summary = "修改密码", description = "校验原密码后更新；成功后当前会话强制下线，需重新登录")
    @PutMapping("/password")
    public Result<Void> updatePassword(@Valid @RequestBody UpdatePasswordRequest request) {
        userService.updatePassword(request);
        return Result.success("修改成功，请重新登录", null);
    }
}
