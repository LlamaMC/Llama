package com.llamamc.llamaapi.plugin;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Load {
    LoadPhase value() default LoadPhase.POST_WORLD;
}
