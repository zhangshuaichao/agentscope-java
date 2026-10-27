/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.extensions.model.ollama.options;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AgentScope 的 Ollama 模型配置选项实现，提供强类型构建器，
 * 用于设置温度、上下文大小与惩罚等模型参数。
 * <p>此类映射到 Ollama API 的模型创建与推理参数，
 * 同时支持高级模型加载参数（例如 GPU 卸载、内存管理）
 * 和运行时生成选项（例如采样参数、惩罚）。
 * <p>提供与通用 {@link GenerateOptions} 相互转换的辅助方法，
 * 便于集成 AgentScope 通用模型接口。
 */
@JsonInclude(Include.NON_NULL)
public class OllamaOptions {

    // --------------------------------------------------------------------------
    // 高级模型加载参数
    // 参见：https://github.com/ggerganov/llama.cpp/blob/master/examples/main/README.md
    // --------------------------------------------------------------------------

    // @formatter:off

    /**
     * 非一致性内存访问（NUMA）优化开关。
     * 有助于多 CPU 插槽系统提高内存访问速度。
     */
    @JsonProperty("numa")
    private Boolean useNUMA;

    /**
     * 指定模型运行窗口的最大上下文长度（token 数）。
     * 未指定时默认为 2048。
     */
    @JsonProperty("num_ctx")
    private Integer numCtx;

    /**
     * 定义提示评估时单个批次处理的 token 数。默认为 512。
     */
    @JsonProperty("num_batch")
    private Integer numBatch;

    /**
     * 指定卸载到 GPU 加速器的模型层数。设为 -1 可动态卸载所有层。
     */
    @JsonProperty("num_gpu")
    private Integer numGPU;

    /**
     * 使用多个 GPU 时指定主 GPU 索引。该 GPU 保存临时缓冲区及额外开销数据。默认为 0。
     */
    @JsonProperty("main_gpu")
    private Integer mainGPU;

    /**
     * 为显存有限的 GPU 启用节省内存模式。可能降低性能，但允许加载更大模型。
     */
    @JsonProperty("low_vram")
    private Boolean lowVRAM;

    /**
     * 切换键值缓存是否使用 16 位浮点精度。默认为 true，以平衡性能与内存。
     */
    @JsonProperty("f16_kv")
    private Boolean f16KV;

    /**
     * 是否计算并返回提示中每个 token 的 logits，而不只是生成的 token。
     * 对于需要输入对数概率的任务至关重要。
     */
    @JsonProperty("logits_all")
    private Boolean logitsAll;

    /**
     * 为 true 时仅加载词表数据，不加载完整模型权重。
     * 适用于只需分词的轻量操作。
     */
    @JsonProperty("vocab_only")
    private Boolean vocabOnly;

    /**
     * 控制是否使用内存映射文件（mmap）加载模型。为 null 时系统自动选择最佳策略。
     */
    @JsonProperty("use_mmap")
    private Boolean useMMap;

    /**
     * 启用时将模型数据锁定在 RAM 中，防止换出到磁盘。
     * 可提高性能稳定性，但需要足够的物理内存。
     */
    @JsonProperty("use_mlock")
    private Boolean useMLock;

    /**
     * 设置分配给生成任务的 CPU 线程数。为获得最佳性能，通常应与物理 CPU 核数一致。
     */
    @JsonProperty("num_thread")
    private Integer numThread;

    // --------------------------------------------------------------------------
    // 运行时生成与预测选项
    // --------------------------------------------------------------------------

    /**
     * 决定初始提示中应在上下文内保留的 token 数量。默认为 4。
     */
    @JsonProperty("num_keep")
    private Integer numKeep;

    /**
     * 设置随机数生成器种子以获得可复现结果。设为 -1 时每次运行使用随机种子。
     */
    @JsonProperty("seed")
    private Integer seed;

    /**
     * 响应中生成的最大 token 数。设为 -1 表示无限生成，-2 表示填满剩余上下文。
     */
    @JsonProperty("num_predict")
    private Integer numPredict;

    /**
     * 将下一个 token 的选择限制在概率最高的 K 个候选中。标准值为 40。
     */
    @JsonProperty("top_k")
    private Integer topK;

    /**
     * 核采样：选择累计概率超过 P 的最小 token 集合。标准值为 0.9。
     */
    @JsonProperty("top_p")
    private Double topP;

    /**
     * 相对于最高概率 token 的最小概率阈值。过滤概率更低的 token。默认为 0.0。
     */
    @JsonProperty("min_p")
    private Double minP;

    /**
     * 无尾采样（Tail Free Sampling，TFS）参数，用于降低低概率 token 的影响。
     * 值为 1.0 时禁用此功能。
     */
    @JsonProperty("tfs_z")
    private Float tfsZ;

    /**
     * Typical P 采样参数，用于平衡多样性与似然。默认为 1.0。
     */
    @JsonProperty("typical_p")
    private Float typicalP;

    /**
     * 检查重复惩罚的窗口大小（token 数）。默认为 64。
     * 0 表示禁用，-1 表示覆盖完整上下文。
     */
    @JsonProperty("repeat_last_n")
    private Integer repeatLastN;

    /**
     * 控制输出随机性。较高值产生更有创造性的结果，较低值使输出更确定。
     */
    @JsonProperty("temperature")
    private Double temperature;

    /**
     * 应用于重复 token 的惩罚因子，以减少重复。默认为 1.1。
     */
    @JsonProperty("repeat_penalty")
    private Double repeatPenalty;

    /**
     * 根据 token 是否已在文本中出现施加惩罚，抑制重复话题。
     */
    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    /**
     * 根据 token 已出现的次数施加惩罚，抑制重复相同词语。
     */
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    /**
     * 启用 Mirostat 采样以控制困惑度。
     * 0 = 禁用，1 = Mirostat v1，2 = Mirostat v2。
     */
    @JsonProperty("mirostat")
    private Integer mirostat;

    /**
     * Mirostat 采样的目标熵参数。默认为 5.0。
     */
    @JsonProperty("mirostat_tau")
    private Float mirostatTau;

    /**
     * Mirostat 采样的学习率参数。默认为 0.1。
     */
    @JsonProperty("mirostat_eta")
    private Float mirostatEta;

    /**
     * 为 true 时惩罚换行 token，鼓励单行输出。默认为 true。
     */
    @JsonProperty("penalize_newline")
    private Boolean penalizeNewline;

    /**
     * 停止字符串列表；一旦生成其中任意字符串，就立即终止响应生成。
     */
    @JsonProperty("stop")
    private List<String> stop;

    // --------------------------------------------------------------------------
    // 请求级覆盖项
    // --------------------------------------------------------------------------

    /**
     * 覆盖本次请求的模型名称。
     */
    @JsonProperty("model")
    private String model;

    /**
     * 指定期望的输出格式。目前支持 "json" 或 null（文本）。
     */
    @JsonProperty("format")
    private Object format;

    /**
     * 请求完成后模型继续加载在内存中的时长。例如 "5m" 表示 5 分钟。
     */
    @JsonProperty("keep_alive")
    private String keepAlive;

    /**
     * 输入提示超出上下文窗口时是否截断。默认为 true。
     */
    @JsonProperty("truncate")
    private Boolean truncate;

    /**
     * 思维链推理能力的配置，允许对支持的模型启用／禁用思考或设置推理级别。
     *
     * @see ThinkOption
     */
    @JsonProperty("think")
    private ThinkOption thinkOption;

    private ExecutionConfig executionConfig;

    public static Builder builder() {
        return new Builder();
    }

    public static OllamaOptions fromOptions(OllamaOptions fromOptions) {
        return builder()
                .model(fromOptions.getModel())
                .format(fromOptions.getFormat())
                .keepAlive(fromOptions.getKeepAlive())
                .truncate(fromOptions.getTruncate())
                .thinkOption(fromOptions.getThinkOption())
                .useNUMA(fromOptions.getUseNUMA())
                .numCtx(fromOptions.getNumCtx())
                .numBatch(fromOptions.getNumBatch())
                .numGPU(fromOptions.getNumGPU())
                .mainGPU(fromOptions.getMainGPU())
                .lowVRAM(fromOptions.getLowVRAM())
                .f16KV(fromOptions.getF16KV())
                .logitsAll(fromOptions.getLogitsAll())
                .vocabOnly(fromOptions.getVocabOnly())
                .useMMap(fromOptions.getUseMMap())
                .useMLock(fromOptions.getUseMLock())
                .numThread(fromOptions.getNumThread())
                .numKeep(fromOptions.getNumKeep())
                .seed(fromOptions.getSeed())
                .numPredict(fromOptions.getNumPredict())
                .topK(fromOptions.getTopK())
                .topP(fromOptions.getTopP())
                .minP(fromOptions.getMinP())
                .tfsZ(fromOptions.getTfsZ())
                .typicalP(fromOptions.getTypicalP())
                .repeatLastN(fromOptions.getRepeatLastN())
                .temperature(fromOptions.getTemperature())
                .repeatPenalty(fromOptions.getRepeatPenalty())
                .presencePenalty(fromOptions.getPresencePenalty())
                .frequencyPenalty(fromOptions.getFrequencyPenalty())
                .mirostat(fromOptions.getMirostat())
                .mirostatTau(fromOptions.getMirostatTau())
                .mirostatEta(fromOptions.getMirostatEta())
                .penalizeNewline(fromOptions.getPenalizeNewline())
                .stop(fromOptions.getStop())
                .executionConfig(fromOptions.getExecutionConfig())
                .build();
    }

    /**
     * 将通用 {@link GenerateOptions} 转换为 {@link OllamaOptions}。
     * <p>此适配方法将框架标准选项映射为 Ollama 专属参数。
     * 注意，某些字段（如执行配置或工具）属于不同层，因此不在此映射。
     *
     * @param genOptions 待转换的通用选项
     * @return 配置好的 {@link OllamaOptions} 实例
     */
    public static OllamaOptions fromGenerateOptions(GenerateOptions genOptions) {
        if (genOptions == null) {
            return builder().build();
        }

        Builder builder = builder();

        if (genOptions.getTemperature() != null) {
            builder.temperature(genOptions.getTemperature());
        }
        if (genOptions.getTopP() != null) {
            builder.topP(genOptions.getTopP());
        }
        if (genOptions.getTopK() != null) {
            builder.topK(genOptions.getTopK());
        }
        if (genOptions.getMaxTokens() != null) {
            builder.numPredict(genOptions.getMaxTokens());
        }
        if (genOptions.getFrequencyPenalty() != null) {
            builder.frequencyPenalty(genOptions.getFrequencyPenalty());
        }
        if (genOptions.getPresencePenalty() != null) {
            builder.presencePenalty(genOptions.getPresencePenalty());
        }
        if (genOptions.getSeed() != null) {
            // Ollama 的 seed 使用 Integer，GenerateOptions 使用 Long
            builder.seed(genOptions.getSeed().intValue());
        }

        // 将思考预算映射为启用思考：budget > 0 时启用
        if (genOptions.getThinkingBudget() != null && genOptions.getThinkingBudget() > 0) {
            builder.thinkOption(ThinkOption.ThinkBoolean.ENABLED);
        }

        ResponseFormat responseFormat = genOptions.getResponseFormat();
        if (responseFormat != null) {
            if ("json_schema".equals(responseFormat.getType())) {
                // Ollama 需要 schema 本身，而非 OpenAI 的 response-format 包装。
                builder.format(responseFormat.getJsonSchema().getSchema());
            } else if ("json_object".equals(responseFormat.getType())) {
                builder.format("json");
            }
        }

        // 从映射中映射附加参数
        if (genOptions.getAdditionalBodyParams() != null) {
            applyAdditionalParams(builder, genOptions.getAdditionalBodyParams());
        }

        return builder.build();
    }

    /**
     * 将此 {@link OllamaOptions} 转为通用 {@link GenerateOptions}。
     *
     * @return 配置好的 {@link GenerateOptions} 实例
     */
    public GenerateOptions toGenerateOptions() {
        GenerateOptions.Builder builder = GenerateOptions.builder();

        if (this.temperature != null) {
            builder.temperature(this.temperature);
        }
        if (this.topP != null) {
            builder.topP(this.topP);
        }
        if (this.topK != null) {
            builder.topK(this.topK);
        }
        if (this.numPredict != null) {
            builder.maxTokens(this.numPredict);
        }
        if (this.frequencyPenalty != null) {
            builder.frequencyPenalty(this.frequencyPenalty);
        }
        if (this.presencePenalty != null) {
            builder.presencePenalty(this.presencePenalty);
        }
        if (this.seed != null) {
            builder.seed(this.seed.longValue());
        }
        if (this.executionConfig != null) {
            builder.executionConfig(this.executionConfig);
        }

        // 将其他 Ollama 专属选项作为附加参数
        Map<String, Object> map = this.toMap();
        // 移除已映射的标准选项
        map.remove("temperature");
        map.remove("top_p");
        map.remove("top_k");
        map.remove("num_predict");
        map.remove("frequency_penalty");
        map.remove("presence_penalty");
        map.remove("seed");

        // 将剩余项作为附加参数
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                builder.additionalBodyParam(entry.getKey(), entry.getValue());
            }
        }

        return builder.build();
    }

    private static void applyAdditionalParams(Builder builder, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value == null) {
                continue;
            }

            // 尝试将键匹配到 ParamKey 值
            // 可以使用字符串 switch
            // 注意：类型转换逻辑较简单，生产环境可能需要更稳健的转换
            switch (key) {
                case "numa":
                    if (value instanceof Boolean) builder.useNUMA((Boolean) value);
                    break;
                case "num_ctx":
                    if (value instanceof Number) builder.numCtx(((Number) value).intValue());
                    break;
                case "num_batch":
                    if (value instanceof Number) builder.numBatch(((Number) value).intValue());
                    break;
                case "num_gpu":
                    if (value instanceof Number) builder.numGPU(((Number) value).intValue());
                    break;
                case "main_gpu":
                    if (value instanceof Number) builder.mainGPU(((Number) value).intValue());
                    break;
                case "low_vram":
                    if (value instanceof Boolean) builder.lowVRAM((Boolean) value);
                    break;
                case "f16_kv":
                    if (value instanceof Boolean) builder.f16KV((Boolean) value);
                    break;
                case "logits_all":
                    if (value instanceof Boolean) builder.logitsAll((Boolean) value);
                    break;
                case "vocab_only":
                    if (value instanceof Boolean) builder.vocabOnly((Boolean) value);
                    break;
                case "use_mmap":
                    if (value instanceof Boolean) builder.useMMap((Boolean) value);
                    break;
                case "use_mlock":
                    if (value instanceof Boolean) builder.useMLock((Boolean) value);
                    break;
                case "num_thread":
                    if (value instanceof Number) builder.numThread(((Number) value).intValue());
                    break;
                case "num_keep":
                    if (value instanceof Number) builder.numKeep(((Number) value).intValue());
                    break;
                case "seed":
                    if (value instanceof Number) builder.seed(((Number) value).intValue());
                    break;
                case "num_predict":
                    if (value instanceof Number) builder.numPredict(((Number) value).intValue());
                    break;
                case "top_k":
                    if (value instanceof Number) builder.topK(((Number) value).intValue());
                    break;
                case "top_p":
                    if (value instanceof Number) builder.topP(((Number) value).doubleValue());
                    break;
                case "min_p":
                    if (value instanceof Number) builder.minP(((Number) value).doubleValue());
                    break;
                case "tfs_z":
                    if (value instanceof Number) builder.tfsZ(((Number) value).floatValue());
                    break;
                case "typical_p":
                    if (value instanceof Number) builder.typicalP(((Number) value).floatValue());
                    break;
                case "repeat_last_n":
                    if (value instanceof Number) builder.repeatLastN(((Number) value).intValue());
                    break;
                case "temperature":
                    if (value instanceof Number)
                        builder.temperature(((Number) value).doubleValue());
                    break;
                case "repeat_penalty":
                    if (value instanceof Number)
                        builder.repeatPenalty(((Number) value).doubleValue());
                    break;
                case "presence_penalty":
                    if (value instanceof Number)
                        builder.presencePenalty(((Number) value).doubleValue());
                    break;
                case "frequency_penalty":
                    if (value instanceof Number)
                        builder.frequencyPenalty(((Number) value).doubleValue());
                    break;
                case "mirostat":
                    if (value instanceof Number) builder.mirostat(((Number) value).intValue());
                    break;
                case "mirostat_tau":
                    if (value instanceof Number) builder.mirostatTau(((Number) value).floatValue());
                    break;
                case "mirostat_eta":
                    if (value instanceof Number) builder.mirostatEta(((Number) value).floatValue());
                    break;
                case "penalize_newline":
                    if (value instanceof Boolean) builder.penalizeNewline((Boolean) value);
                    break;
                case "stop":
                    if (value instanceof List) builder.stop((List<String>) value);
                    break;
                case "model":
                    if (value instanceof String) builder.model((String) value);
                    break;
                case "format":
                    builder.format(value);
                    break;
                case "keep_alive":
                    if (value instanceof String) builder.keepAlive((String) value);
                    break;
                case "truncate":
                    if (value instanceof Boolean) builder.truncate((Boolean) value);
                    break;
                case "think":
                    if (value instanceof Boolean) {
                        builder.thinkOption(
                                (Boolean) value
                                        ? ThinkOption.ThinkBoolean.ENABLED
                                        : ThinkOption.ThinkBoolean.DISABLED);
                    } else if (value instanceof String) {
                        builder.thinkOption(new ThinkOption.ThinkLevel((String) value));
                    } else if (value instanceof Map) {
                        builder.thinkOption(
                                JsonUtils.getJsonCodec().convertValue(value, ThinkOption.class));
                    }
                    break;
            }
        }
    }

    public String getModel() {
        return this.model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Object getFormat() {
        return this.format;
    }

    public void setFormat(Object format) {
        this.format = format;
    }

    public String getKeepAlive() {
        return this.keepAlive;
    }

    public void setKeepAlive(String keepAlive) {
        this.keepAlive = keepAlive;
    }

    public Boolean getUseNUMA() {
        return this.useNUMA;
    }

    public void setUseNUMA(Boolean useNUMA) {
        this.useNUMA = useNUMA;
    }

    public Integer getNumCtx() {
        return this.numCtx;
    }

    public void setNumCtx(Integer numCtx) {
        this.numCtx = numCtx;
    }

    public Integer getNumBatch() {
        return this.numBatch;
    }

    public void setNumBatch(Integer numBatch) {
        this.numBatch = numBatch;
    }

    public Integer getNumGPU() {
        return this.numGPU;
    }

    public void setNumGPU(Integer numGPU) {
        this.numGPU = numGPU;
    }

    public Integer getMainGPU() {
        return this.mainGPU;
    }

    public void setMainGPU(Integer mainGPU) {
        this.mainGPU = mainGPU;
    }

    public Boolean getLowVRAM() {
        return this.lowVRAM;
    }

    public void setLowVRAM(Boolean lowVRAM) {
        this.lowVRAM = lowVRAM;
    }

    public Boolean getF16KV() {
        return this.f16KV;
    }

    public void setF16KV(Boolean f16kv) {
        this.f16KV = f16kv;
    }

    public Boolean getLogitsAll() {
        return this.logitsAll;
    }

    public void setLogitsAll(Boolean logitsAll) {
        this.logitsAll = logitsAll;
    }

    public Boolean getVocabOnly() {
        return this.vocabOnly;
    }

    public void setVocabOnly(Boolean vocabOnly) {
        this.vocabOnly = vocabOnly;
    }

    public Boolean getUseMMap() {
        return this.useMMap;
    }

    public void setUseMMap(Boolean useMMap) {
        this.useMMap = useMMap;
    }

    public Boolean getUseMLock() {
        return this.useMLock;
    }

    public void setUseMLock(Boolean useMLock) {
        this.useMLock = useMLock;
    }

    public Integer getNumThread() {
        return this.numThread;
    }

    public void setNumThread(Integer numThread) {
        this.numThread = numThread;
    }

    public Integer getNumKeep() {
        return this.numKeep;
    }

    public void setNumKeep(Integer numKeep) {
        this.numKeep = numKeep;
    }

    public Integer getSeed() {
        return this.seed;
    }

    public void setSeed(Integer seed) {
        this.seed = seed;
    }

    public Integer getMaxTokens() {
        return getNumPredict();
    }

    public void setMaxTokens(Integer maxTokens) {
        setNumPredict(maxTokens);
    }

    public Integer getNumPredict() {
        return this.numPredict;
    }

    public void setNumPredict(Integer numPredict) {
        this.numPredict = numPredict;
    }

    public Integer getTopK() {
        return this.topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Double getTopP() {
        return this.topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Double getMinP() {
        return this.minP;
    }

    public void setMinP(Double minP) {
        this.minP = minP;
    }

    public Float getTfsZ() {
        return this.tfsZ;
    }

    public void setTfsZ(Float tfsZ) {
        this.tfsZ = tfsZ;
    }

    public Float getTypicalP() {
        return this.typicalP;
    }

    public void setTypicalP(Float typicalP) {
        this.typicalP = typicalP;
    }

    public Integer getRepeatLastN() {
        return this.repeatLastN;
    }

    public void setRepeatLastN(Integer repeatLastN) {
        this.repeatLastN = repeatLastN;
    }

    public Double getTemperature() {
        return this.temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getRepeatPenalty() {
        return this.repeatPenalty;
    }

    public void setRepeatPenalty(Double repeatPenalty) {
        this.repeatPenalty = repeatPenalty;
    }

    public Double getPresencePenalty() {
        return this.presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public Double getFrequencyPenalty() {
        return this.frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public Integer getMirostat() {
        return this.mirostat;
    }

    public void setMirostat(Integer mirostat) {
        this.mirostat = mirostat;
    }

    public Float getMirostatTau() {
        return this.mirostatTau;
    }

    public void setMirostatTau(Float mirostatTau) {
        this.mirostatTau = mirostatTau;
    }

    public Float getMirostatEta() {
        return this.mirostatEta;
    }

    public void setMirostatEta(Float mirostatEta) {
        this.mirostatEta = mirostatEta;
    }

    public Boolean getPenalizeNewline() {
        return this.penalizeNewline;
    }

    public void setPenalizeNewline(Boolean penalizeNewline) {
        this.penalizeNewline = penalizeNewline;
    }

    public List<String> getStopSequences() {
        return getStop();
    }

    public void setStopSequences(List<String> stopSequences) {
        setStop(stopSequences);
    }

    public List<String> getStop() {
        return this.stop;
    }

    public void setStop(List<String> stop) {
        this.stop = stop;
    }

    public Boolean getTruncate() {
        return this.truncate;
    }

    public void setTruncate(Boolean truncate) {
        this.truncate = truncate;
    }

    public ThinkOption getThinkOption() {
        return this.thinkOption;
    }

    public void setThinkOption(ThinkOption thinkOption) {
        this.thinkOption = thinkOption;
    }

    public ExecutionConfig getExecutionConfig() {
        return this.executionConfig;
    }

    public void setExecutionConfig(ExecutionConfig executionConfig) {
        this.executionConfig = executionConfig;
    }

    /**
     * 将 {@link OllamaOptions} 序列化为 {@link Map} 表示。
     * @return 包含已配置选项的映射
     */
    public Map<String, Object> toMap() {
        return JsonUtils.getJsonCodec().convertValue(this, Map.class);
    }

    public OllamaOptions copy() {
        return fromOptions(this);
    }

    /**
     * 将给定 {@link GenerateOptions} 合并到新实例，给定选项优先。
     * <p>此便捷方法先将 {@link GenerateOptions} 转为 {@link OllamaOptions}，再执行合并。
     *
     * @param other 待合并的选项（可以为 null）
     * @return 合并后的新 {@link OllamaOptions} 实例
     */
    public OllamaOptions merge(GenerateOptions other) {
        if (other == null) {
            return this.copy();
        }
        return this.merge(OllamaOptions.fromGenerateOptions(other));
    }

    /**
     * 将给定选项合并到新实例，给定选项优先。
     *
     * @param other 待合并的选项（可以为 null）
     * @return 合并后的新 {@link OllamaOptions} 实例
     */
    public OllamaOptions merge(OllamaOptions other) {
        if (other == null) {
            return this.copy();
        }

        Builder builder = this.toBuilder();

        if (other.getModel() != null) builder.model(other.getModel());
        if (other.getFormat() != null) builder.format(other.getFormat());
        if (other.getKeepAlive() != null) builder.keepAlive(other.getKeepAlive());
        if (other.getTruncate() != null) builder.truncate(other.getTruncate());
        if (other.getThinkOption() != null) builder.thinkOption(other.getThinkOption());
        if (other.getUseNUMA() != null) builder.useNUMA(other.getUseNUMA());
        if (other.getNumCtx() != null) builder.numCtx(other.getNumCtx());
        if (other.getNumBatch() != null) builder.numBatch(other.getNumBatch());
        if (other.getNumGPU() != null) builder.numGPU(other.getNumGPU());
        if (other.getMainGPU() != null) builder.mainGPU(other.getMainGPU());
        if (other.getLowVRAM() != null) builder.lowVRAM(other.getLowVRAM());
        if (other.getF16KV() != null) builder.f16KV(other.getF16KV());
        if (other.getLogitsAll() != null) builder.logitsAll(other.getLogitsAll());
        if (other.getVocabOnly() != null) builder.vocabOnly(other.getVocabOnly());
        if (other.getUseMMap() != null) builder.useMMap(other.getUseMMap());
        if (other.getUseMLock() != null) builder.useMLock(other.getUseMLock());
        if (other.getNumThread() != null) builder.numThread(other.getNumThread());
        if (other.getNumKeep() != null) builder.numKeep(other.getNumKeep());
        if (other.getSeed() != null) builder.seed(other.getSeed());
        if (other.getNumPredict() != null) builder.numPredict(other.getNumPredict());
        if (other.getTopK() != null) builder.topK(other.getTopK());
        if (other.getTopP() != null) builder.topP(other.getTopP());
        if (other.getMinP() != null) builder.minP(other.getMinP());
        if (other.getTfsZ() != null) builder.tfsZ(other.getTfsZ());
        if (other.getTypicalP() != null) builder.typicalP(other.getTypicalP());
        if (other.getRepeatLastN() != null) builder.repeatLastN(other.getRepeatLastN());
        if (other.getTemperature() != null) builder.temperature(other.getTemperature());
        if (other.getRepeatPenalty() != null) builder.repeatPenalty(other.getRepeatPenalty());
        if (other.getPresencePenalty() != null) builder.presencePenalty(other.getPresencePenalty());
        if (other.getFrequencyPenalty() != null)
            builder.frequencyPenalty(other.getFrequencyPenalty());
        if (other.getMirostat() != null) builder.mirostat(other.getMirostat());
        if (other.getMirostatTau() != null) builder.mirostatTau(other.getMirostatTau());
        if (other.getMirostatEta() != null) builder.mirostatEta(other.getMirostatEta());
        if (other.getPenalizeNewline() != null) builder.penalizeNewline(other.getPenalizeNewline());
        if (other.getStop() != null) builder.stop(other.getStop());
        if (other.getExecutionConfig() != null) builder.executionConfig(other.getExecutionConfig());

        return builder.build();
    }

    public Builder toBuilder() {
        return builder()
                .model(this.model)
                .format(this.format)
                .keepAlive(this.keepAlive)
                .truncate(this.truncate)
                .thinkOption(this.thinkOption)
                .useNUMA(this.useNUMA)
                .numCtx(this.numCtx)
                .numBatch(this.numBatch)
                .numGPU(this.numGPU)
                .mainGPU(this.mainGPU)
                .lowVRAM(this.lowVRAM)
                .f16KV(this.f16KV)
                .logitsAll(this.logitsAll)
                .vocabOnly(this.vocabOnly)
                .useMMap(this.useMMap)
                .useMLock(this.useMLock)
                .numThread(this.numThread)
                .numKeep(this.numKeep)
                .seed(this.seed)
                .numPredict(this.numPredict)
                .topK(this.topK)
                .topP(this.topP)
                .minP(this.minP)
                .tfsZ(this.tfsZ)
                .typicalP(this.typicalP)
                .repeatLastN(this.repeatLastN)
                .temperature(this.temperature)
                .repeatPenalty(this.repeatPenalty)
                .presencePenalty(this.presencePenalty)
                .frequencyPenalty(this.frequencyPenalty)
                .mirostat(this.mirostat)
                .mirostatTau(this.mirostatTau)
                .mirostatEta(this.mirostatEta)
                .penalizeNewline(this.penalizeNewline)
                .stop(this.stop)
                .executionConfig(this.executionConfig);
    }

    // @formatter:on

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        OllamaOptions that = (OllamaOptions) o;
        return Objects.equals(this.model, that.model)
                && Objects.equals(this.format, that.format)
                && Objects.equals(this.keepAlive, that.keepAlive)
                && Objects.equals(this.truncate, that.truncate)
                && Objects.equals(this.thinkOption, that.thinkOption)
                && Objects.equals(this.useNUMA, that.useNUMA)
                && Objects.equals(this.numCtx, that.numCtx)
                && Objects.equals(this.numBatch, that.numBatch)
                && Objects.equals(this.numGPU, that.numGPU)
                && Objects.equals(this.mainGPU, that.mainGPU)
                && Objects.equals(this.lowVRAM, that.lowVRAM)
                && Objects.equals(this.f16KV, that.f16KV)
                && Objects.equals(this.logitsAll, that.logitsAll)
                && Objects.equals(this.vocabOnly, that.vocabOnly)
                && Objects.equals(this.useMMap, that.useMMap)
                && Objects.equals(this.useMLock, that.useMLock)
                && Objects.equals(this.numThread, that.numThread)
                && Objects.equals(this.numKeep, that.numKeep)
                && Objects.equals(this.seed, that.seed)
                && Objects.equals(this.numPredict, that.numPredict)
                && Objects.equals(this.topK, that.topK)
                && Objects.equals(this.topP, that.topP)
                && Objects.equals(this.minP, that.minP)
                && Objects.equals(this.tfsZ, that.tfsZ)
                && Objects.equals(this.typicalP, that.typicalP)
                && Objects.equals(this.repeatLastN, that.repeatLastN)
                && Objects.equals(this.temperature, that.temperature)
                && Objects.equals(this.repeatPenalty, that.repeatPenalty)
                && Objects.equals(this.presencePenalty, that.presencePenalty)
                && Objects.equals(this.frequencyPenalty, that.frequencyPenalty)
                && Objects.equals(this.mirostat, that.mirostat)
                && Objects.equals(this.mirostatTau, that.mirostatTau)
                && Objects.equals(this.mirostatEta, that.mirostatEta)
                && Objects.equals(this.penalizeNewline, that.penalizeNewline)
                && Objects.equals(this.stop, that.stop);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.model,
                this.format,
                this.keepAlive,
                this.truncate,
                this.thinkOption,
                this.useNUMA,
                this.numCtx,
                this.numBatch,
                this.numGPU,
                this.mainGPU,
                this.lowVRAM,
                this.f16KV,
                this.logitsAll,
                this.vocabOnly,
                this.useMMap,
                this.useMLock,
                this.numThread,
                this.numKeep,
                this.seed,
                this.numPredict,
                this.topK,
                this.topP,
                this.minP,
                this.tfsZ,
                this.typicalP,
                this.repeatLastN,
                this.temperature,
                this.repeatPenalty,
                this.presencePenalty,
                this.frequencyPenalty,
                this.mirostat,
                this.mirostatTau,
                this.mirostatEta,
                this.penalizeNewline,
                this.stop);
    }

    /**
     * Ollama 参数键。
     */
    public enum ParamKey {
        NUMA("numa"),
        NUM_CTX("num_ctx"),
        NUM_BATCH("num_batch"),
        NUM_GPU("num_gpu"),
        MAIN_GPU("main_gpu"),
        LOW_VRAM("low_vram"),
        F16_KV("f16_kv"),
        LOGITS_ALL("logits_all"),
        VOCAB_ONLY("vocab_only"),
        USE_MMAP("use_mmap"),
        USE_MLOCK("use_mlock"),
        NUM_THREAD("num_thread"),
        NUM_KEEP("num_keep"),
        SEED("seed"),
        NUM_PREDICT("num_predict"),
        TOP_K("top_k"),
        TOP_P("top_p"),
        MIN_P("min_p"),
        TFS_Z("tfs_z"),
        TYPICAL_P("typical_p"),
        REPEAT_LAST_N("repeat_last_n"),
        TEMPERATURE("temperature"),
        REPEAT_PENALTY("repeat_penalty"),
        PRESENCE_PENALTY("presence_penalty"),
        FREQUENCY_PENALTY("frequency_penalty"),
        MIROSTAT("mirostat"),
        MIROSTAT_TAU("mirostat_tau"),
        MIROSTAT_ETA("mirostat_eta"),
        PENALIZE_NEWLINE("penalize_newline"),
        STOP("stop"),
        MODEL("model"),
        FORMAT("format"),
        KEEP_ALIVE("keep_alive"),
        TRUNCATE("truncate"),
        THINK("think");

        private final String key;

        ParamKey(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }
    }

    public static final class Builder {

        private final OllamaOptions options = new OllamaOptions();

        public Builder model(String model) {
            this.options.model = model;
            return this;
        }

        public Builder format(Object format) {
            this.options.format = format;
            return this;
        }

        public Builder keepAlive(String keepAlive) {
            this.options.keepAlive = keepAlive;
            return this;
        }

        public Builder truncate(Boolean truncate) {
            this.options.truncate = truncate;
            return this;
        }

        public Builder executionConfig(ExecutionConfig executionConfig) {
            this.options.setExecutionConfig(executionConfig);
            return this;
        }

        public Builder useNUMA(Boolean useNUMA) {
            this.options.useNUMA = useNUMA;
            return this;
        }

        public Builder numCtx(Integer numCtx) {
            this.options.numCtx = numCtx;
            return this;
        }

        public Builder numBatch(Integer numBatch) {
            this.options.numBatch = numBatch;
            return this;
        }

        public Builder numGPU(Integer numGPU) {
            this.options.numGPU = numGPU;
            return this;
        }

        public Builder mainGPU(Integer mainGPU) {
            this.options.mainGPU = mainGPU;
            return this;
        }

        public Builder lowVRAM(Boolean lowVRAM) {
            this.options.lowVRAM = lowVRAM;
            return this;
        }

        public Builder f16KV(Boolean f16KV) {
            this.options.f16KV = f16KV;
            return this;
        }

        public Builder logitsAll(Boolean logitsAll) {
            this.options.logitsAll = logitsAll;
            return this;
        }

        public Builder vocabOnly(Boolean vocabOnly) {
            this.options.vocabOnly = vocabOnly;
            return this;
        }

        public Builder useMMap(Boolean useMMap) {
            this.options.useMMap = useMMap;
            return this;
        }

        public Builder useMLock(Boolean useMLock) {
            this.options.useMLock = useMLock;
            return this;
        }

        public Builder numThread(Integer numThread) {
            this.options.numThread = numThread;
            return this;
        }

        public Builder numKeep(Integer numKeep) {
            this.options.numKeep = numKeep;
            return this;
        }

        public Builder seed(Integer seed) {
            this.options.seed = seed;
            return this;
        }

        public Builder numPredict(Integer numPredict) {
            this.options.numPredict = numPredict;
            return this;
        }

        public Builder topK(Integer topK) {
            this.options.topK = topK;
            return this;
        }

        public Builder topP(Double topP) {
            this.options.topP = topP;
            return this;
        }

        public Builder minP(Double minP) {
            this.options.minP = minP;
            return this;
        }

        public Builder tfsZ(Float tfsZ) {
            this.options.tfsZ = tfsZ;
            return this;
        }

        public Builder typicalP(Float typicalP) {
            this.options.typicalP = typicalP;
            return this;
        }

        public Builder repeatLastN(Integer repeatLastN) {
            this.options.repeatLastN = repeatLastN;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.options.temperature = temperature;
            return this;
        }

        public Builder repeatPenalty(Double repeatPenalty) {
            this.options.repeatPenalty = repeatPenalty;
            return this;
        }

        public Builder presencePenalty(Double presencePenalty) {
            this.options.presencePenalty = presencePenalty;
            return this;
        }

        public Builder frequencyPenalty(Double frequencyPenalty) {
            this.options.frequencyPenalty = frequencyPenalty;
            return this;
        }

        public Builder mirostat(Integer mirostat) {
            this.options.mirostat = mirostat;
            return this;
        }

        public Builder mirostatTau(Float mirostatTau) {
            this.options.mirostatTau = mirostatTau;
            return this;
        }

        public Builder mirostatEta(Float mirostatEta) {
            this.options.mirostatEta = mirostatEta;
            return this;
        }

        public Builder penalizeNewline(Boolean penalizeNewline) {
            this.options.penalizeNewline = penalizeNewline;
            return this;
        }

        public Builder stop(List<String> stop) {
            this.options.stop = stop;
            return this;
        }

        public Builder thinkOption(ThinkOption thinkOption) {
            this.options.thinkOption = thinkOption;
            return this;
        }

        public OllamaOptions build() {
            return this.options;
        }
    }
}
