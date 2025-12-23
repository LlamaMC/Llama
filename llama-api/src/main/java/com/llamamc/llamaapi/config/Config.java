package com.llamamc.llamaapi.config;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Config {
    String path() default "";
    String name();
    ConfigType type() default ConfigType.TOML;
}
