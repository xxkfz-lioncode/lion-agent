package com.lion.agent.controller.test;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


/**
 * 结构化输出
 * 使用两种方式：
 * 1、使用全局的ChatClient，structChatClient
 * 2、单次请求中使用
 */
@Tag(name = "结构化输出", description = "全局 & 单次请求")
@RestController
@RequestMapping("/struct")
public class StructTestController {
    /**
     * 结构化输出专用 ChatClient（仅开启原生结构化输出）
     */
    @Autowired
    private ChatClient structChatClient;

    @Autowired
    private ChatModel chatModel;

    /**
     * 使用全局的ChatClient配置结构化输出
     *
     * @param actor
     * @return
     */
    @GetMapping("/filmography")
    @Operation(summary = "使用全局的ChatClient配置结构化输出", description = "返回 ActorFilmography对象")
    public ActorFilmography getFilmography(@RequestParam(defaultValue = "Tom Hanks") String actor) {
        return structChatClient.prompt()
                .user("Generate a filmography for " + actor + ". " +
                        "Return the name of the actor and a list of at least 5 film titles.")
                .call()
                .entity(ActorFilmography.class);
    }



    @GetMapping("/getFilmography2")
    @Operation(summary = "在单次请求中使用", description = "返回 ActorFilmography对象")
    public ActorFilmography getFilmography2(@RequestParam(defaultValue = "Tom Hanks") String actor) {
        return ChatClient.builder(chatModel).build().prompt()
                .user("Generate a filmography for " + actor + ". " +
                        "Return the name of the actor and a list of at least 5 film titles.")
                .call()
                .entity(ActorFilmography.class, ChatClient.EntityParamSpec::useProviderStructuredOutput); // 单次启用
    }


    @GetMapping("/mapStructOutPut")
    @Operation(summary = "Map 输出转换器", description = "返回Map")
    public Map<String, Object> mapStructOutPut(){
      return ChatClient.create(chatModel).prompt()
                .user(u -> u.text("Provide me a List of {subject}")
                        .param("subject", "an array of numbers from 1 to 9 under they key name 'numbers'"))
                .call()
                .entity(new ParameterizedTypeReference<Map<String, Object>>() {});
    }



    /**
     * 用于接收 LLM 结构化输出的包装类。
     * 注意：因为 OpenAI 不支持顶层 JSON 数组，所以必须用一个 Record/POJO 把 List 包起来。
     */
    public record ActorFilmography(
            String name,           // 演员姓名
            List<String> films     // 电影列表
    ) {
    }

}


