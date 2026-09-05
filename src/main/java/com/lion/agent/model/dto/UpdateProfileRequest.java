package com.lion.agent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料请求
 */
@Data
@Schema(description = "修改个人资料请求")
public class UpdateProfileRequest {

    @Schema(description = "昵称，不传则不修改", example = "小明")
    @Size(max = 32, message = "昵称长度需在 1-32 之间")
    private String nickname;

    @Schema(description = "头像地址，不传则不修改，传空字符串则清除", example = "https://example.com/avatar.png")
    @Size(max = 255, message = "头像地址长度不能超过 255")
    private String avatar;
}
