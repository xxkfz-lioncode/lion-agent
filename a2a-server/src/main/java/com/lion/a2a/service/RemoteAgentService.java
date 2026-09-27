package com.lion.a2a.service;

import com.lion.a2a.tool.WeatherTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 远程 Agent 本体：一个挂着工具的 ChatClient。
 *
 * <p>结构上和主项目里的本地子 Agent（DemoSubagents.Executor）完全同构——
 * 都是「新起一个 ChatClient + 自己的 system prompt + 自己的工具」，
 * 区别只是这个实例跑在 9999 进程里，通过 A2A 协议对外提供服务。
 */
@Slf4j
@Service
public class RemoteAgentService {

    private static final String SYSTEM_PROMPT = """
            你是运行在远程 A2A 服务端（lion-a2a-server）的天气助手「远程天气助手」，
            只负责回答天气相关问题。

            工作规则：
            1. 查询天气必须调用 getWeather 工具获取数据，严禁自己编造温度或天气现象。
            2. 用户没说城市时，先回复你没有城市信息，请用户补充，不要瞎猜。
            3. 与天气无关的问题（翻译、写代码、闲聊等），直接说明你只提供天气查询服务，不要硬答。
            4. 回答简洁，一般不超过 3 句话。
            5. 结尾固定加一行：（来自远程 A2A Agent·lion-a2a-server）
            """;

    private final ChatClient chatClient;

    public RemoteAgentService(ChatClient.Builder chatClientBuilder, WeatherTools weatherTools) {
        // 这里的 ChatClient 只服务远程 Agent 自己：独立人设 + 独立工具，不看主 Agent 的对话历史
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(weatherTools)
                .build();
    }

    /**
     * 执行一次远程任务：可能触发工具调用循环（模型要天气 → 调 getWeather → 再生成回答）。
     *
     * @param userText 主 Agent 派下来的任务文本
     * @return 本 Agent 的最终回答
     */
    public String answer(String userText) {
        log.info("[a2a-server] 收到任务：{}", userText);
        String answer = chatClient.prompt().user(userText).call().content();
        log.info("[a2a-server] 模型回答：{}", answer);
        return answer;
    }
}
