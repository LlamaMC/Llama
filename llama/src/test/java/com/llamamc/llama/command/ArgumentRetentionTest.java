package com.llamamc.llama.command;

import com.llamamc.llamaapi.command.Argument;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ArgumentRetentionTest {
    @Test
    void argumentIsRetainedAtRuntime() {
        var retention = Argument.class.getAnnotation(Retention.class);

        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void argumentTargetsParametersOnly() {
        var target = Argument.class.getAnnotation(Target.class);

        assertArrayEquals(new ElementType[]{ElementType.PARAMETER}, target.value());
    }
}
