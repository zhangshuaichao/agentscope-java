/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.coding.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.function.Consumer;

/**
 * 在 GitHub PR 上触发审查智能体的工具。编码智能体调用时，通过构造期间注入的 {@link Consumer} 回调将审查运行入队。
 */
public class RequestPrReviewTool {

    private final Consumer<String> reviewerDispatcher;

    /**
     * @param reviewerDispatcher 接收 PR URL 并异步分发审查智能体的回调
     */
    public RequestPrReviewTool(Consumer<String> reviewerDispatcher) {
        this.reviewerDispatcher = reviewerDispatcher;
    }

    @Tool(
            description =
                    "Request a code review from the reviewer agent for a GitHub pull request. Call"
                            + " this after pushing your changes and opening a PR. The reviewer"
                            + " will analyze the diff and post inline comments.")
    public String request_pr_review(
            @ToolParam(
                            name = "pr_url",
                            description =
                                    "Full GitHub PR URL (e.g."
                                            + " https://github.com/owner/repo/pull/123)")
                    String prUrl) {
        if (prUrl == null || prUrl.isBlank()) {
            return "Error: pr_url is required";
        }
        try {
            reviewerDispatcher.accept(prUrl.trim());
            return "Review requested for "
                    + prUrl.trim()
                    + ". The reviewer agent will analyze the PR and post inline comments shortly.";
        } catch (Exception e) {
            return "Error dispatching reviewer: " + e.getMessage();
        }
    }
}
