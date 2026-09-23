package io.github.devoracode.operatelog.resolver;

/** 客户端 IP 解析器。 */
public interface ClientIpResolver {

    /**
     * 当前请求客户端 IP；没有 HTTP 请求时返回 {@code null}。
     *
     * @return 客户端 IP 字面量；无法判定时为 {@code null}。Starter 的默认实现按
     *         {@code operate-log.http.trust-proxy} 决定是否采信代理头
     */
    String resolve();
}
