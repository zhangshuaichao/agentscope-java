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
package io.agentscope.core.rag.store;

/**
 * 计算向量间距离与相似度的工具类。
 *
 * <p>提供余弦相似度、欧氏距离等向量搜索常见度量的静态计算方法。
 */
class DistanceCalculator {

    private DistanceCalculator() {
        // 工具类，禁止实例化。
    }

    /**
     * 计算两个向量的余弦相似度。
     *
     * <p>衡量向量夹角余弦，范围 -1 到 1。1 表示方向相同，0 表示正交，-1 表示方向相反。
     *
     * <p>公式：cos(θ) = (A · B) / (||A|| * ||B||)。
     *
     * @param vector1 第一个向量
     * @param vector2 第二个向量
     * @return -1 到 1 的余弦相似度
     * @throws IllegalArgumentException 当向量为 null、空或长度不同时
     */
    public static double cosineSimilarity(final double[] vector1, final double[] vector2) {
        validateVectors(vector1, vector2);

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            norm1 += vector1[i] * vector1[i];
            norm2 += vector2[i] * vector2[i];
        }

        double denominator = Math.sqrt(norm1) * Math.sqrt(norm2);
        if (denominator == 0.0) {
            // 至少一个向量为零向量。
            // 零向量的余弦相似度未定义，按约定返回 0.0。
            return 0.0;
        }

        return dotProduct / denominator;
    }

    /**
     * 计算两个向量的欧氏距离。
     *
     * <p>衡量 n 维空间两点间直线距离，越小越相似。
     *
     * <p>公式：d = sqrt(Σ(ai - bi)²)。
     *
     * @param vector1 第一个向量
     * @param vector2 第二个向量
     * @return 非负欧氏距离
     * @throws IllegalArgumentException 当向量为 null、空或长度不同时
     */
    public static double euclideanDistance(final double[] vector1, final double[] vector2) {
        return Math.sqrt(squaredEuclideanDistance(vector1, vector2));
    }

    /**
     * 计算两个向量的平方欧氏距离。
     *
     * <p>避免开方，速度更快，适合只比较远近而无需实际距离值的情况。
     *
     * @param vector1 第一个向量
     * @param vector2 第二个向量
     * @return 非负平方欧氏距离
     * @throws IllegalArgumentException 当向量为 null、空或长度不同时
     */
    public static double squaredEuclideanDistance(final double[] vector1, final double[] vector2) {
        validateVectors(vector1, vector2);

        double sumSquaredDiff = 0.0;
        for (int i = 0; i < vector1.length; i++) {
            double diff = vector1[i] - vector2[i];
            sumSquaredDiff += diff * diff;
        }

        return sumSquaredDiff;
    }

    /**
     * 校验两个向量均非 null、非空且长度相同。
     *
     * @param vector1 第一个向量
     * @param vector2 第二个向量
     * @throws IllegalArgumentException 当校验失败时
     */
    private static void validateVectors(final double[] vector1, final double[] vector2) {
        if (vector1 == null) {
            throw new IllegalArgumentException("First vector cannot be null");
        }
        if (vector2 == null) {
            throw new IllegalArgumentException("Second vector cannot be null");
        }
        if (vector1.length == 0) {
            throw new IllegalArgumentException("First vector cannot be empty");
        }
        if (vector2.length == 0) {
            throw new IllegalArgumentException("Second vector cannot be empty");
        }
        if (vector1.length != vector2.length) {
            throw new IllegalArgumentException(
                    String.format(
                            "Vectors must have the same length: %d != %d",
                            vector1.length, vector2.length));
        }
    }
}
