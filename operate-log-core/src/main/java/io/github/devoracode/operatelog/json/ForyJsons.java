package io.github.devoracode.operatelog.json;

import org.apache.fory.json.ForyJson;

/**
 * 组件默认 {@link ForyJson} 实例的集中出处。
 *
 * <p>{@code ForyJson} 不可变、线程安全，全局复用一份即可；每次 {@code build()} 都会重建类型缓存，
 * 因此不要在调用点构造。这里显式打开 {@code writeNullFields}：日志记录的字段集合必须稳定，
 * 缺值也输出 {@code null}，否则下游 schema 会随内容抖动。</p>
 *
 * <p>选用 Fory JSON 而非宿主 Jackson：Boot 4 起宿主默认 Jackson 3（{@code tools.jackson}），
 * 依赖宿主 {@code ObjectMapper} 会让本组件在 Boot 4 上装配失败；自持一套 JSON 实现后
 * Boot 2 / 3 / 4 的日志输出完全一致，也不向宿主推 Jackson 版本。</p>
 */
public final class ForyJsons {

    private static final ForyJson DEFAULT = ForyJson.builder()
            .writeNullFields(true)
            .build();

    private ForyJsons() {
    }

    /** 组件默认实例；需要自定义（如字段模式、命名策略）时自行 {@code build()} 并注入各实现类。 */
    public static ForyJson defaultJson() {
        return DEFAULT;
    }
}
