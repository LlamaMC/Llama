package com.llamamc.llamaapi.config;

import com.llamamc.llamaapi.config.type.*;

import java.io.InputStream;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class ConfigFactory {
    private static final List<ITypeProcessor> PROCESSORS = List.of(
            new JsonTypeProcessor(),
            new YamlTypeProcessor(),
            new TomlTypeProcessor(),
            new PropertiesTypeProcessor()
    );

    private ConfigFactory() {}

    public static <T> T load(Class<T> configClass) {
        try {
            Config config = configClass.getAnnotation(Config.class);
            if (config == null)
                throw new IllegalStateException();
            Path basePath = config.path().isEmpty() ? Paths.get(".") : Paths.get(config.path());
            if (!Files.exists(basePath)) Files.createDirectories(basePath);
            String fileName = config.name() + config.type().getExtension();
            Path file = basePath.resolve(fileName);
            if (!Files.exists(file)) {
                Files.createFile(file);
                try (Writer writer = Files.newBufferedWriter(file)) {
                    writer.write(defaultContent(config.type()));
                }
            }
            ITypeProcessor processor = PROCESSORS.stream()
                    .filter(p -> p.supports(fileName))
                    .findFirst()
                    .orElseThrow();
            Map<String, Object> data;
            try (InputStream in = Files.newInputStream(file)) {
                data = processor.parse(in);
            }

            T instance = configClass.getDeclaredConstructor().newInstance();
            bind(instance, configClass, data);

            return instance;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String defaultContent(ConfigType type) {
        return switch (type) {
            case JSON -> "{ }";
            case YAML -> "---\n";
            case TOML, PROPERTIES -> "";
        };
    }

    private static <T> void bind(T instance, Class<T> clazz, Map<String, Object> data) throws Exception {
        for (Method method : clazz.getMethods()) {
            if (!method.getName().startsWith("set") || method.getParameterCount() != 1) continue;

            String fieldName = Character.toLowerCase(method.getName().charAt(3)) + method.getName().substring(4);
            String key = fieldName;

            try {
                Field field = clazz.getDeclaredField(fieldName);
                if (field.isAnnotationPresent(Key.class))
                    key = field.getAnnotation(Key.class).value();
            } catch (NoSuchFieldException ignored) {}

            Object raw = resolve(data, key);
            if (raw == null) continue;

            method.invoke(instance, convert(raw, method.getGenericParameterTypes()[0]));
        }
    }

    private static Object resolve(Map<String, Object> map, String path) {
        Object current = map;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = m.get(part);
        }
        return current;
    }

    private static Object convert(Object value, Type targetType) {
        if (targetType instanceof Class<?> c) {
            if (c.isAssignableFrom(value.getClass())) return value;
            if (c == int.class || c == Integer.class) return Integer.parseInt(value.toString());
            if (c == boolean.class || c == Boolean.class) return Boolean.parseBoolean(value.toString());
            if (c == String.class) return value.toString();
        }
        if (targetType instanceof ParameterizedType p) {
            if (p.getRawType() == List.class && value instanceof List<?>) return value;
            if (p.getRawType() == Set.class && value instanceof List<?> list) return new HashSet<>(list);
            if (p.getRawType() == Map.class && value instanceof Map<?, ?>) return value;
        }
        throw new IllegalStateException();
    }

    public static <T> void save(T instance) {
        try {
            Class<?> clazz = instance.getClass();
            Config config = clazz.getAnnotation(Config.class);
            if (config == null) throw new IllegalStateException();
            Path basePath = config.path().isEmpty() ? Paths.get(".") : Paths.get(config.path());
            if (!Files.exists(basePath)) Files.createDirectories(basePath);
            String fileName = config.name() + config.type().getExtension();
            Path file = basePath.resolve(fileName);
            Map<String, Object> data = new HashMap<>();
            for (Method method : clazz.getMethods()) {
                if (!method.getName().startsWith("get") || method.getParameterCount() != 0) continue;
                String fieldName = Character.toLowerCase(method.getName().charAt(3)) + method.getName().substring(4);
                Field field;
                try {
                    field = clazz.getDeclaredField(fieldName);
                } catch (NoSuchFieldException e) {
                    continue;
                }
                String key = field.isAnnotationPresent(Key.class) ? field.getAnnotation(Key.class).value() : fieldName;
                Object value = method.invoke(instance);
                insertIntoMap(data, key.split("\\."), value);
            }
            ITypeProcessor processor = PROCESSORS.stream()
                    .filter(p -> p.supports(fileName))
                    .findFirst()
                    .orElseThrow();
            try (Writer writer = Files.newBufferedWriter(file)) {
                processor.write(writer, data);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void insertIntoMap(Map<String, Object> map, String[] path, Object value) {
        Map<String, Object> current = map;
        for (int i = 0; i < path.length - 1; i++)
            current = (Map<String, Object>) current.computeIfAbsent(path[i], k -> new HashMap<>());
        current.put(path[path.length - 1], value);
    }
}
