package io.github.devoracode.operatelog.test.boot3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.devoracode.operatelog.aspect.OperateLogAspect;
import io.github.devoracode.operatelog.boot.OperateLogAutoConfiguration;
import io.github.devoracode.operatelog.serializer.OperateLogSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class OperateLogBoot3JacksonAssemblyTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OperateLogAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader("javax.servlet", "jakarta.servlet"));

    @Test
    void missingJacksonComponentsSkipAutoConfiguration() {
        for (String hidden : new String[]{"com.fasterxml.jackson.databind", "com.fasterxml.jackson.datatype.jsr310"}) {
            this.runner.withClassLoader(new FilteredClassLoader("javax.servlet", "jakarta.servlet", hidden)).run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(0, context.getBeanNamesForType(OperateLogAspect.class).length);
                assertFalse(ConditionEvaluationReport.get(context.getBeanFactory()).getConditionAndOutcomesBySource()
                        .get(OperateLogAutoConfiguration.class.getName()).isFullMatch());
            });
        }
    }

    @Test
    void privateMapperCanSerializeTimeWithoutAnyHostMapperBean() {
        this.runner.run(context -> {
            assertEquals(0, context.getBeanNamesForType(ObjectMapper.class).length);
            assertEquals("\"1970-01-01T00:00:00Z\"",
                    context.getBean(OperateLogSerializer.class).serialize(Instant.EPOCH));
        });
    }

    @Test
    void ambiguousMappersUseThePrivateFallback() {
        this.runner.withBean("firstMapper", ObjectMapper.class, ObjectMapper::new)
                .withBean("secondMapper", ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(2, context.getBeanNamesForType(ObjectMapper.class).length);
                    assertEquals("\"1970-01-01T00:00:00Z\"",
                            context.getBean(OperateLogSerializer.class).serialize(Instant.EPOCH));
                });
    }

    @Test
    void primaryMapperSettingsAreReusedWithoutMutatingTheHost() {
        ObjectMapper primary = new ObjectMapper()
                .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS);
        this.runner.withBean("firstMapper", ObjectMapper.class, ObjectMapper::new)
                .withBean("primaryMapper", ObjectMapper.class, () -> primary, definition -> definition.setPrimary(true))
                .run(context -> {
                    assertSame(primary, context.getBean(ObjectMapper.class));
                    assertEquals("0", context.getBean(OperateLogSerializer.class).serialize(Instant.EPOCH));
                    assertEquals(0, primary.getRegisteredModuleIds().size());
                });
    }
}
