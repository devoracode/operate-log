package io.github.devoracode.operatelog.resolver;

/** 客户端 IP 解析器。 */
public interface ClientIpResolver {

    /**
     * 当前请求客户端 IP。
     *
     * @return 客户端 IP 字面量；无 HTTP 请求或解析不到时为 {@code null}
     */
    String resolve();
}
