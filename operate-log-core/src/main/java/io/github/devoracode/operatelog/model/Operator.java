package io.github.devoracode.operatelog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前操作人信息。
 *
 * @author devoracode
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Operator {

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 用户账号。
     */
    private String userAccount;

    /**
     * 用户名称。
     */
    private String userName;
}
