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
package io.agentscope.core.e2e;

/** E2E 测试常量，包括数据 URL 与常用值。<p>测试图片及视频托管在阿里云 OSS，供测试访问。 */
public final class E2ETestConstants {

    private E2ETestConstants() {
        // 禁止实例化
    }

    // ==========================================================================
    // 测试图片 URL
    // ==========================================================================

    /** 视觉测试使用的猫图片。 */
    public static final String CAT_IMAGE_URL =
            "https://agentscope-test.oss-cn-beijing.aliyuncs.com/Cat03.jpg";

    /** 视觉测试使用的狗和女孩图片。 */
    public static final String DOG_GIRL_IMAGE_URL =
            "https://agentscope-test.oss-cn-beijing.aliyuncs.com/dog_and_girl.png";

    // ==========================================================================
    // 测试视频 URL
    // ==========================================================================

    /** 视频分析测试使用的视频。 */
    public static final String TEST_VIDEO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4";

    // ==========================================================================
    // 测试超时
    // ==========================================================================

    /** E2E 测试默认超时，单位秒。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** 简单操作的较短超时，单位秒。 */
    public static final int SHORT_TIMEOUT_SECONDS = 30;

    /** 复杂操作的较长超时，单位秒。 */
    public static final int LONG_TIMEOUT_SECONDS = 300;
}
