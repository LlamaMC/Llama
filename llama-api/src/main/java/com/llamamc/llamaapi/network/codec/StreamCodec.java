package com.llamamc.llamaapi.network.codec;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

public interface StreamCodec<B, V> extends StreamEncoder<B, V>, StreamDecoder<B, V> {


    static <B, V> StreamCodec<B, V> of(StreamEncoder<B, V> encoder, StreamDecoder<B, V> decoder) {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(decoder, "decoder");
        return new StreamCodec<>() {
            @Override
            public V decode(B input) {
                return decoder.decode(input);
            }

            @Override
            public void encode(B output, V value) {
                encoder.encode(output, value);
            }
        };
    }

    static <B, V> StreamCodec<B, V> unit(V instance) {
        return new StreamCodec<>() {
            @Override
            public V decode(B input) {
                return instance;
            }

            @Override
            public void encode(B output, V value) {
                if (!Objects.equals(value, instance)) {
                    throw new IllegalStateException("Can't encode '" + value + "', expected '" + instance + "'");
                }
            }
        };
    }

    static <B, C, T1> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1,
            Function<C, T1> g1,
            Function<T1, C> ctor
    ) {
        Objects.requireNonNull(c1);
        Objects.requireNonNull(g1);
        Objects.requireNonNull(ctor);
        return new StreamCodec<>() {
            @Override
            public C decode(B input) {
                return ctor.apply(c1.decode(input));
            }

            @Override
            public void encode(B output, C value) {
                c1.encode(output, g1.apply(value));
            }
        };
    }

    static <B, C, T1, T2> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1,
            Function<C, T1> g1,
            StreamCodec<? super B, T2> c2,
            Function<C, T2> g2,
            BiFunction<T1, T2, C> ctor
    ) {
        Objects.requireNonNull(c1);
        Objects.requireNonNull(g1);
        Objects.requireNonNull(c2);
        Objects.requireNonNull(g2);
        Objects.requireNonNull(ctor);
        return new StreamCodec<>() {
            @Override
            public C decode(B input) {
                return ctor.apply(c1.decode(input), c2.decode(input));
            }

            @Override
            public void encode(B output, C value) {
                c1.encode(output, g1.apply(value));
                c2.encode(output, g2.apply(value));
            }
        };
    }

    static <B, C, T1, T2, T3> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1,
            StreamCodec<? super B, T2> c2, Function<C, T2> g2,
            StreamCodec<? super B, T3> c3, Function<C, T3> g3,
            Function3<T1, T2, T3, C> ctor
    ) {
        Objects.requireNonNull(c1);
        Objects.requireNonNull(g1);
        Objects.requireNonNull(c2);
        Objects.requireNonNull(g2);
        Objects.requireNonNull(c3);
        Objects.requireNonNull(g3);
        Objects.requireNonNull(ctor);
        return new StreamCodec<>() {
            @Override
            public C decode(B input) {
                return ctor.apply(c1.decode(input), c2.decode(input), c3.decode(input));
            }

            @Override
            public void encode(B output, C value) {
                c1.encode(output, g1.apply(value));
                c2.encode(output, g2.apply(value));
                c3.encode(output, g3.apply(value));
            }
        };
    }

    default <O> StreamCodec<B, O> map(Function<? super V, ? extends O> to, Function<? super O, ? extends V> from) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(from, "from");
        return new StreamCodec<B, O>() {
            @Override
            public O decode(B input) {
                return to.apply(StreamCodec.this.decode(input));
            }

            @Override
            public void encode(B output, O value) {
                StreamCodec.this.encode(output, from.apply(value));
            }
        };
    }

    default <O> StreamCodec<O, V> mapStream(Function<? super O, ? extends B> wrapper) {
        Objects.requireNonNull(wrapper, "wrapper");
        return new StreamCodec<O, V>() {
            @Override
            public V decode(O input) {
                return StreamCodec.this.decode(wrapper.apply(input));
            }

            @Override
            public void encode(O output, V value) {
                StreamCodec.this.encode(wrapper.apply(output), value);
            }
        };
    }

    default <O> StreamCodec<B, O> dispatch(Function<? super O, ? extends V> typeGetter, Function<? super V, ? extends StreamCodec<? super B, ? extends O>> codecByType) {
        Objects.requireNonNull(typeGetter, "typeGetter");
        Objects.requireNonNull(codecByType, "codecByType");
        return new StreamCodec<>() {
            @Override
            public O decode(B input) {
                V key = StreamCodec.this.decode(input);
                @SuppressWarnings("unchecked")
                StreamCodec<? super B, ? extends O> codec = codecByType.apply(key);
                return codec.decode(input);
            }

            @Override
            public void encode(B output, O value) {
                V key = typeGetter.apply(value);
                @SuppressWarnings("unchecked")
                StreamCodec<? super B, O> codec = (StreamCodec<? super B, O>) codecByType.apply(key);
                StreamCodec.this.encode(output, key);
                codec.encode(output, value);
            }
        };
    }

    @FunctionalInterface
    interface Function3<A, B, C, R> {
        R apply(A a, B b, C c);
    }
}
