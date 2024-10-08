/*
 * Copyright 2024 T Jake Luciani
 *
 * The Jlama Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.github.tjake.jlama.safetensors.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.tjake.jlama.safetensors.tokenizer.TokenizerModel;
import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.JinjavaConfig;
import com.hubspot.jinjava.LegacyOverrides;
import com.hubspot.jinjava.interpret.RenderResult;
import com.hubspot.jinjava.lib.fn.ELFunctionDefinition;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class also renders the prompt templates of the huggingface model format (using jinja templates)
 * @see <a href="https://huggingface.co/docs/transformers/main/en/chat_templating#templates-for-chat-models">Chat Templating</a>
 */
public class PromptSupport {
    private static final Logger logger = LoggerFactory.getLogger(PromptSupport.class);

    // This matches the jinja config in huggingface
    private static final Jinjava jinjava = new Jinjava(JinjavaConfig.newBuilder()
            .withTrimBlocks(true)
            .withLstripBlocks(true)
            .withLegacyOverrides(LegacyOverrides.newBuilder()
                    .withParseWhitespaceControlStrictly(true)
                    .withUseTrimmingForNotesAndExpressions(true)
                    .withUseSnakeCasePropertyNaming(true)
                    .build())
            .build());

    static {
        jinjava.getGlobalContext()
                .registerFunction(new ELFunctionDefinition(
                        "", "raise_exception", PromptSupport.class, "raiseException", String.class));
    }

    private final TokenizerModel m;

    public PromptSupport(TokenizerModel model) {
        this.m = model;
    }

    public Builder builder() {
        return new Builder(this.m);
    }

    public boolean hasPromptTemplates() {
        return !m.promptTemplates().isEmpty();
    }

    public static void raiseException(String message) {
        logger.warn("Prompt template error: " + message);
    }

    private enum PromptType {
        DEFAULT,
        TOOL,
        RAG
    }

    private enum PromptRole {
        USER,
        SYSTEM,
        ASSISTANT,
        TOOL,
        TOOL_CALL
    }

    static class Message {
        private final Object content;
        private final PromptRole role;
        private final ToolCallFunction toolCalls;

        private Message(Object content, PromptRole role) {
            this.content = content;
            this.role = role;
            this.toolCalls = null;
        }

        private Message(ToolCall toolCall) {
            this.content = null;
            this.role = PromptRole.TOOL_CALL;
            this.toolCalls = new ToolCallFunction(toolCall);
        }

        public Object getContent() {
            return content;
        }

        public Map toMap() {
            Map map = new HashMap();
            map.put("role", role.name().toLowerCase());

            if (content != null) {
                map.put("content", content);
            }

            if (toolCalls != null) {
                map.put("tool_calls", List.of(toolCalls.toMap()));
            }

            return map;
        }

        public String getRole() {
            return role.name().toLowerCase();
        }

        public List<ToolCallFunction> toolCalls() {
            if (toolCalls == null) {
                return null;
            }

            return List.of(toolCalls);
        }
    }

    static class ToolCallFunction {
        private final ToolCall call;

        private ToolCallFunction(ToolCall call) {
            this.call = call;
        }

        public InnerToolCall function() {
            return new InnerToolCall(call);
        }

        public Map toMap() {
            return Map.of("function", Map.of("name", call.getName(), "arguments", call.getParameters()));
        }
    }

    static class InnerToolCall {
        private final ToolCall call;

        private InnerToolCall(ToolCall call) {
            this.call = call;
        }

        public Map<String, Object> arguments() {
            return call.getParameters();
        }

        public String name() {
            return call.getName();
        }
    }

    public static class Builder {
        private final TokenizerModel m;
        private PromptType type = PromptType.DEFAULT;
        private boolean addGenerationPrompt = true;

        private List<Message> messages = new ArrayList<>(2);
        private List<Tool> tools = null;

        private Builder(TokenizerModel m) {
            this.m = m;
        }

        public Builder usePromptType(PromptType type) {
            this.type = type;
            return this;
        }

        public Builder addGenerationPrompt(boolean addGenerationPrompt) {
            this.addGenerationPrompt = addGenerationPrompt;
            return this;
        }

        public Builder addUserMessage(String content) {
            messages.add(new Message(content, PromptRole.USER));
            return this;
        }

        public Builder addToolResult(Result result) {
            messages.add(new Message(result.toJson(), PromptRole.TOOL));
            return this;
        }

        public Builder addToolCall(ToolCall call) {
            messages.add(new Message(call));
            return this;
        }

        public Builder addSystemMessage(String content) {
            messages.add(new Message(content, PromptRole.SYSTEM));
            return this;
        }

        public Builder addAssistantMessage(String content) {
            messages.add(new Message(content, PromptRole.ASSISTANT));
            return this;
        }

        public Builder addTools(List<Tool> tools) {
            if (this.tools == null) {
                this.tools = new ArrayList<>(tools);
            } else {
                throw new IllegalArgumentException("Tools already set");
            }
            return this;
        }

        public Builder addTools(Tool... tools) {
            if (this.tools == null) {
                this.tools = Arrays.asList(tools);
            } else {
                throw new IllegalArgumentException("Tools already set");
            }
            return this;
        }

        public boolean hasTools() {
            return tools != null && !tools.isEmpty();
        }

        public List<Tool> getTools() {
            return tools;
        }

        public String build() {
            if (messages.isEmpty()) {
                return "";
            }

            if (m.promptTemplates().isEmpty()) {
                throw new UnsupportedOperationException("Prompt templates are not available for this model");
            }

            String template = m.promptTemplates()
                    .map(t -> t.get(type.name().toLowerCase()))
                    .orElseThrow(
                            () -> new UnsupportedOperationException("Prompt template not available for type: " + type));

            Map args = new HashMap();

            args.putAll(Map.of(
                            "messages",
                            messages.stream().map(Message::toMap).toList(),
                            "add_generation_prompt",
                            addGenerationPrompt,
                            "eos_token",
                            m.eosToken(),
                            "bos_token",
                            "")); // We add the BOS ourselves

            if (tools != null) {
                args.put("tools", tools);
            }

            RenderResult r =  jinjava.renderForResult(template, args);

            if (r.hasErrors())
                logger.warn("Prompt template errors: " + r.getErrors());

            return r.getOutput();
        }
    }
}
