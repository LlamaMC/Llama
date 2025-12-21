package com.llamamc.llamaapi.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Depend {

    DependType dependType() default DependType.SOFT;
    String[] plugin() default {};
}