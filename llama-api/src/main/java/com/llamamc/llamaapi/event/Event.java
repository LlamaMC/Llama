package com.llamamc.llamaapi.event;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Event {

    EventPriority priority() default EventPriority.NORMAL;
    boolean async() default false;

}
