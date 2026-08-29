package com.lion.agent.memory;

import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.List;

public class ReadLimitChatMemory implements ChatMemory {
    private final ChatMemory delegate;
    private final int limit;

    public ReadLimitChatMemory(ChatMemory delegate, int limit) {
        this.delegate = delegate;
        this.limit = limit;
    }
    @Override
    public void add(@NotNull String conversationId, @NotNull List<Message> messages) {
        // 写入不截断：全量进存储，存多少由内层窗口决定
        delegate.add(conversationId, messages);
    }

    @NotNull
    @Override
    public List<Message> get(@NotNull String conversationId) {
        List<Message> all = delegate.get(conversationId);

        // SYSTEM 消息不参与截断（若存在则永远保留在最前，防止系统提示词被窗口挤掉）
        List<Message> systems = new ArrayList<>();
        List<Message> others = new ArrayList<>();
        for (Message msg : all) {
            if (msg.getMessageType() == MessageType.SYSTEM) {
                systems.add(msg);
            } else {
                others.add(msg);
            }
        }

        if (others.size() <= limit) {
            return all;
        }

        // 只取最近 limit 条（消息按时间正序存储，尾部即最新）
        List<Message> result = new ArrayList<>(systems);
        result.addAll(others.subList(others.size() - limit, others.size()));
        return result;
    }

    @Override
    public void clear(@NotNull String conversationId) {
        delegate.clear(conversationId);
    }
}
