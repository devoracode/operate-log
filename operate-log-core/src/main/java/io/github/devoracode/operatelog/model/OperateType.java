package io.github.devoracode.operatelog.model;

/**
 * 操作类型。按 name 序列化，新增枚举值不影响存量数据；
 * 下游若按枚举反序列化，需与组件版本同步升级。
 */
public enum OperateType {

    /** 新增数据。 */
    CREATE,

    /** 修改已有数据。 */
    UPDATE,

    /** 删除数据。 */
    DELETE,

    /** 查询数据。 */
    QUERY,

    /** 导出数据。 */
    EXPORT,

    /** 导入数据。 */
    IMPORT,

    /** 登录系统。 */
    LOGIN,

    /** 登出系统。 */
    LOGOUT,

    /** 启用对象（账号、开关等）。 */
    ENABLE,

    /** 停用对象。 */
    DISABLE,

    /** 无法归类的其它操作，也是注解默认值。 */
    OTHER,

    /** 授予权限或访问权。 */
    GRANT,

    /** 回收已授予的权限。 */
    REVOKE,

    /** 下载文件。 */
    DOWNLOAD,

    /** 打印单据或报表。 */
    PRINT
}
