package com.llamamc.llama.network.codec;

import com.llamamc.llamaapi.network.codec.StreamCodec;
import com.llamamc.llamaapi.network.codec.StreamDecoder;
import com.llamamc.llamaapi.network.codec.StreamEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(10)
class StreamCodecGuardTest {
    private final StreamCodec<Object, String> codec = StreamCodec.unit("value");
    private final StreamEncoder<Object, String> encoder = (output, value) -> {
    };
    private final StreamDecoder<Object, String> decoder = input -> "value";
    private final Function<String, String> identity = Function.identity();

    @Test
    void ofNamesEveryNullArgument() {
        assertNullArgument("encoder", () -> StreamCodec.of(null, decoder));
        assertNullArgument("decoder", () -> StreamCodec.of(encoder, null));
    }

    @Test
    void compositeWithOneCodecNamesEveryNullArgument() {
        assertNullArgument("c1", () -> StreamCodec.composite(null, identity, identity));
        assertNullArgument("g1", () -> StreamCodec.composite(codec, null, identity));
        assertNullArgument("ctor", () -> StreamCodec.composite(codec, identity, null));
    }

    @Test
    void compositeWithTwoCodecsNamesEveryNullArgument() {
        BiFunction<String, String, String> join = (first, second) -> first + second;
        assertNullArgument("c1", () -> StreamCodec.composite(null, identity, codec, identity, join));
        assertNullArgument("g1", () -> StreamCodec.composite(codec, null, codec, identity, join));
        assertNullArgument("c2", () -> StreamCodec.composite(codec, identity, null, identity, join));
        assertNullArgument("g2", () -> StreamCodec.composite(codec, identity, codec, null, join));
        assertNullArgument("ctor", () -> StreamCodec.composite(codec, identity, codec, identity, null));
    }

    @Test
    void compositeWithThreeCodecsNamesEveryNullArgument() {
        StreamCodec.Function3<String, String, String, String> join =
                (first, second, third) -> first + second + third;
        assertNullArgument("c1", () -> StreamCodec.composite(null, identity, codec, identity, codec, identity, join));
        assertNullArgument("g1", () -> StreamCodec.composite(codec, null, codec, identity, codec, identity, join));
        assertNullArgument("c2", () -> StreamCodec.composite(codec, identity, null, identity, codec, identity, join));
        assertNullArgument("g2", () -> StreamCodec.composite(codec, identity, codec, null, codec, identity, join));
        assertNullArgument("c3", () -> StreamCodec.composite(codec, identity, codec, identity, null, identity, join));
        assertNullArgument("g3", () -> StreamCodec.composite(codec, identity, codec, identity, codec, null, join));
        assertNullArgument("ctor", () -> StreamCodec.composite(codec, identity, codec, identity, codec, identity, null));
    }

    @Test
    void mapNamesEveryNullArgument() {
        assertNullArgument("to", () -> codec.map(null, identity));
        assertNullArgument("from", () -> codec.map(identity, null));
    }

    @Test
    void mapStreamNamesTheNullWrapper() {
        assertNullArgument("wrapper", () -> codec.mapStream(null));
    }

    @Test
    void dispatchNamesEveryNullArgument() {
        Function<String, StreamCodec<Object, String>> codecByType = key -> codec;
        assertNullArgument("typeGetter", () -> codec.dispatch(null, codecByType));
        assertNullArgument("codecByType", () -> codec.dispatch(identity, null));
    }

    private static void assertNullArgument(String argument, Executable call) {
        var exception = assertThrows(NullPointerException.class, call);
        assertEquals(argument, exception.getMessage());
    }
}
