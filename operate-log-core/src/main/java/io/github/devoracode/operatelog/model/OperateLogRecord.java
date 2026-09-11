package io.github.devoracode.operatelog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 操作日志记录：一条审计数据的完整字段集合，由 Handler 落地前序列化输出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperateLogRecord {
    private String id;
    private String traceId;
    private String application;
    private String environment;
    private String version;
    private String module;
    private String operation;
    private OperateType operationType;
    private String description;
    private String businessId;
    private String operatorUserId;
    private String operatorUserAccount;
    private String operatorUserName;
    private String requestMethod;
    private String requestUrl;
    private String requestUri;
    private String requestQuery;
    private String requestHeaders;
    private String requestBody;
    private String responseBody;
    private String clientIp;
    private String userAgent;
    private boolean success;
    private long costTime;
    private Instant startTime;
    private Instant endTime;
    private String errorType;
    private String errorMessage;
    private String errorStack;
    /**
     * 业务自定义字段，经 {@code OperateLogContextHolder#putExtra} 写入；
     * 未写入时为 {@code null}，与其余可空字段一致（下游 schema 稳定）。
     */
    private Map<String, Object> extra;
}
