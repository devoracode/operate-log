package io.github.devoracode.operatelog.sanitizer;

/** 敏感数据脱敏器：作用于序列化后的整段文本。 */
public interface SensitiveDataMasker {

    String mask(String content);
}
