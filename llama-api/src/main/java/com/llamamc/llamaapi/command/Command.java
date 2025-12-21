package com.llamamc.llamaapi.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Command {

    String name();

    String description() default "";

    String permission() default "";

    String[] aliases() default {};

}
