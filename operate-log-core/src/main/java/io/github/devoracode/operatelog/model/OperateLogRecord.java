package io.github.devoracode.operatelog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 操作日志记录。
 *
 * @author devoracode
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

    private Integer httpStatus;

    private String clientIp;

    private String userAgent;

    private boolean success;

    private long costTime;

    private Instant startTime;

    private Instant endTime;

    private String errorType;

    private String errorMessage;

    private String errorStack;
}
