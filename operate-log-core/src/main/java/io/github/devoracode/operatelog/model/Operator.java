package io.github.devoracode.operatelog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 当前操作人信息，由 {@code OperatorResolver} 提供。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Operator {
    private String userId;
    private String userAccount;
    private String userName;
}
