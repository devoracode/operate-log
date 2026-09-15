package io.github.devoracode.operatelog.model;

/**
 * 操作类型。按 name 序列化，新增枚举值不影响存量数据；
 * 下游若按枚举反序列化，需与组件版本同步升级。
 */
public enum OperateType {

    CREATE,

    UPDATE,

    DELETE,

    QUERY,

    EXPORT,

    IMPORT,

    LOGIN,

    LOGOUT,

    ENABLE,

    DISABLE,

    OTHER,

    GRANT,

    REVOKE,

    DOWNLOAD,

    PRINT
}
