package com.demo.aiknowledge.support;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记"真连 MySQL/Redis"的集成测试：本机基础设施不可达时整类跳过，而不是失败。
 * 判定逻辑见 {@link LocalInfraCondition}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(LocalInfraCondition.class)
public @interface RequiresLocalInfra {
}
