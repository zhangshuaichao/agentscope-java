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
package io.agentscope.harness.coding.reviewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.coding.observability.CodingAgentMetrics;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将审查发现项作为行内评论发布到 GitHub PR。
 *
 * <p>创建一次 GitHub pull request review，每个发现项对应行内评论。发现项变为 {@code resolved} 时，解决已有 GitHub 审查线程。
 */
public class GitHubReviewPublisher {

    private static final String GITHUB_API = "https://api.github.com";
    private static final Pattern PR_URL_PATTERN =
            Pattern.compile("https://github\\.com/([^/]+)/([^/]+)/pull/(\\d+)");
    private static final int MIN_SEVERITY_ORDINAL = 2; // 发布 medium、high、critical 严重级别

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final CodingAgentMetrics metrics;

    public GitHubReviewPublisher() {
        this(null);
    }

    public GitHubReviewPublisher(CodingAgentMetrics metrics) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.mapper = new ObjectMapper();
        this.metrics = metrics;
    }

    /**
     * 将 {@code threadId} 累积发现项发布为一次 GitHub PR 审查。
     *
     * @param threadId 会话线程 ID
     * @param prUrl 完整 PR URL
     * @param token GitHub 令牌
     * @param findingsService 发现项来源
     */
    public void publish(
            String threadId, String prUrl, String token, ReviewerFindingsService findingsService)
            throws IOException, InterruptedException {
        Matcher m = PR_URL_PATTERN.matcher(prUrl != null ? prUrl : "");
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid PR URL: " + prUrl);
        }
        String owner = m.group(1);
        String repo = m.group(2);
        int prNumber = Integer.parseInt(m.group(3));

        List<Finding> findings = findingsService.listFindings(threadId);

        StringBuilder reviewBody = new StringBuilder();
        if (findings.isEmpty()) {
            reviewBody.append("No issues found. ✅");
        } else {
            int publishable =
                    (int)
                            findings.stream()
                                    .filter(
                                            f ->
                                                    "open".equals(f.getStatus())
                                                            && severityOrdinal(f.getSeverity())
                                                                    >= MIN_SEVERITY_ORDINAL)
                                    .count();
            reviewBody.append("Found ").append(findings.size()).append(" finding(s)");
            if (publishable < findings.size()) {
                reviewBody
                        .append(" (")
                        .append(publishable)
                        .append(" above threshold published as inline comments)");
            }
            reviewBody.append(".");
        }

        // 为可发布的未解决发现项构建行内评论
        List<Map<String, Object>> comments =
                findings.stream()
                        .filter(
                                f ->
                                        "open".equals(f.getStatus())
                                                && severityOrdinal(f.getSeverity())
                                                        >= MIN_SEVERITY_ORDINAL
                                                && f.getFile() != null
                                                && f.getStartLine() != null)
                        .map(f -> buildInlineComment(f))
                        .toList();

        String reviewPayload =
                mapper.writeValueAsString(
                        Map.of(
                                "body",
                                reviewBody.toString(),
                                "event",
                                "COMMENT",
                                "comments",
                                comments));

        String url =
                GITHUB_API + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/reviews";
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "AgentScope-CodingAgent/1.0")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(reviewPayload))
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "GitHub API error "
                            + response.statusCode()
                            + " posting review: "
                            + response.body());
        }
        if (metrics != null) {
            metrics.recordReviewPublished();
        }
    }

    private static Map<String, Object> buildInlineComment(Finding f) {
        StringBuilder body = new StringBuilder();
        body.append("**[").append(f.getSeverity().toUpperCase()).append("]** ");
        body.append(f.getDescription());
        if (f.getSuggestion() != null && !f.getSuggestion().isBlank()) {
            body.append("\n\n```suggestion\n").append(f.getSuggestion()).append("\n```");
        }
        return Map.of(
                "path", f.getFile(),
                "line", f.getStartLine(),
                "side", "RIGHT",
                "body", body.toString());
    }

    private static int severityOrdinal(String severity) {
        if (severity == null) return 0;
        return switch (severity.toLowerCase()) {
            case "informational" -> 0;
            case "low" -> 1;
            case "medium" -> 2;
            case "high" -> 3;
            case "critical" -> 4;
            default -> 0;
        };
    }
}
