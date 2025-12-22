package com.llamamc.llamaapi.network;

import com.llamamc.llamaapi.component.Component;

public sealed interface InboundDecision
        permits InboundDecision.Continue, InboundDecision.Consume, InboundDecision.Disconnect, InboundDecision.Fail {

    record Continue() implements InboundDecision {
        public static final Continue INSTANCE = new Continue();
    }

    record Consume() implements InboundDecision {
        public static final Consume INSTANCE = new Consume();
    }

    record Disconnect(Component reason) implements InboundDecision {}

    record Fail(Throwable error) implements InboundDecision {}
}
