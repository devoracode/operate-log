package io.github.devoracode.operatelog.sanitizer;

/**
 * 敏感数据脱敏器。
 *
 * @author devoracode
 */
public interface SensitiveDataMasker {

    /**
     * 对内容进行脱敏。
     *
     * @param content 内容
     * @return 脱敏后的内容
     */
    String mask(String content);
}
