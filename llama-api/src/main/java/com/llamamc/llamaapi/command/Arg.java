package com.llamamc.llamaapi.command;

public @interface Argument {
    String name();
    ArgumentType type();
    boolean required() default true;
    int position() default -1;
}
