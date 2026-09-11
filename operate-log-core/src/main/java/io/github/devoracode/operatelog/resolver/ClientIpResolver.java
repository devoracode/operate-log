package io.github.devoracode.operatelog.resolver;

/** 客户端 IP 解析器。 */
public interface ClientIpResolver {

    /** 当前请求客户端 IP；没有 HTTP 请求时返回 {@code null}。 */
    String resolve();
}
