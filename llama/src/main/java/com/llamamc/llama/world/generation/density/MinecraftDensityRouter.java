package com.llamamc.llama.world.generation.density;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.llamamc.llama.world.generation.noise.MinecraftNoises;
import com.llamamc.llama.world.generation.noise.Noise;
import com.llamamc.llama.world.generation.noise.NormalNoise;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MinecraftDensityRouter {
    private static final String DENSITIES = "/minecraft/26.3/worldgen/density-functions.tsv";
    private static final String NOISES = "/minecraft/26.3/worldgen/noises.tsv";

    private final long seed;
    private final Map<String, JsonElement> definitions;
    private final Map<String, JsonObject> noiseDefinitions;
    private final Map<String, DensityFunction> compiled = new HashMap<>();
    private final Map<String, Noise> noises = new HashMap<>();

    public MinecraftDensityRouter(long seed) {
        this.seed = seed;
        definitions = loadElements(DENSITIES);
        noiseDefinitions = loadObjects(NOISES);
    }

    public DensityFunction overworld() {
        return reference("overworld/final_density");
    }

    private DensityFunction compile(JsonElement element) {
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                var value = primitive.getAsFloat();
                return (x, y, z) -> value;
            }
            return reference(key(primitive.getAsString()));
        }

        var object = element.getAsJsonObject();
        var type = key(string(object, "type"));
        return switch (type) {
            case "cache" -> new CachedDensity(compile(object.get("input")));
            case "blend_density" -> compile(object.get("input"));
            case "blend_alpha" -> constant(1.0F);
            case "blend_offset", "beardifier" -> constant(0.0F);
            case "add" -> binary(object, (left, right) -> left + right);
            case "sub" -> binary(object, (left, right) -> left - right);
            case "mul" -> binary(object, (left, right) -> left * right);
            case "div" -> binary(object, (left, right) -> left / right);
            case "min" -> binary(object, Math::min);
            case "max" -> binary(object, Math::max);
            case "abs" -> unary(object, Math::abs);
            case "square" -> unary(object, value -> value * value);
            case "cube" -> unary(object, value -> value * value * value);
            case "half_negative" -> unary(object, value -> value > 0.0F ? value : value * 0.5F);
            case "quarter_negative" -> unary(object, value -> value > 0.0F ? value : value * 0.25F);
            case "squeeze" -> unary(object, MinecraftDensityRouter::squeeze);
            case "clamp" -> clamp(object);
            case "gradient" -> gradient(object);
            case "lerp" -> lerp(object);
            case "range_choice" -> rangeChoice(object);
            case "interval_select" -> intervalSelect(object);
            case "noise" -> noise(object);
            case "shift_a" -> shift(object, false);
            case "shift_b" -> shift(object, true);
            case "interpolated" -> interpolated(object);
            case "spline" -> spline(object.getAsJsonObject("spline"));
            case "old_blended_noise" -> blended(object);
            default -> throw new IllegalArgumentException("Unsupported density function type: " + type);
        };
    }

    private DensityFunction reference(String name) {
        var existing = compiled.get(name);
        if (existing != null) {
            return existing;
        }
        var definition = definitions.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown density function: " + name);
        }
        var function = compile(definition);
        compiled.put(name, function);
        return function;
    }

    private DensityFunction binary(JsonObject object, FloatBinary operation) {
        var left = compile(object.get("left"));
        var right = compile(object.get("right"));
        return (x, y, z) -> operation.apply(left.sample(x, y, z), right.sample(x, y, z));
    }

    private DensityFunction unary(JsonObject object, FloatUnary operation) {
        var input = compile(object.get("input"));
        return (x, y, z) -> operation.apply(input.sample(x, y, z));
    }

    private DensityFunction clamp(JsonObject object) {
        var input = compile(object.get("input"));
        var minimum = number(object, "min");
        var maximum = number(object, "max");
        return (x, y, z) -> Math.max(minimum, Math.min(maximum, input.sample(x, y, z)));
    }

    private DensityFunction gradient(JsonObject object) {
        var axis = string(object, "axis");
        var fromCoordinate = integer(object, "from_coordinate");
        var toCoordinate = integer(object, "to_coordinate");
        var fromValue = number(object, "from_value");
        var factor = (number(object, "to_value") - fromValue) / (toCoordinate - fromCoordinate);
        var minimum = Math.min(fromCoordinate, toCoordinate);
        var maximum = Math.max(fromCoordinate, toCoordinate);
        return (x, y, z) -> {
            var coordinate = switch (axis) {
                case "x" -> x;
                case "y" -> y;
                case "z" -> z;
                default -> throw new IllegalArgumentException("Unknown gradient axis: " + axis);
            };
            var clamped = Math.max(minimum, Math.min(maximum, coordinate));
            return fromValue + (clamped - fromCoordinate) * factor;
        };
    }

    private DensityFunction lerp(JsonObject object) {
        var alpha = compile(object.get("alpha"));
        var first = compile(object.get("first"));
        var second = compile(object.get("second"));
        return (x, y, z) -> {
            var start = first.sample(x, y, z);
            return start + alpha.sample(x, y, z) * (second.sample(x, y, z) - start);
        };
    }

    private DensityFunction rangeChoice(JsonObject object) {
        var input = compile(object.get("input"));
        var minimum = number(object, "min_inclusive");
        var maximum = number(object, "max_exclusive");
        var inside = compile(object.get("when_in_range"));
        var outside = compile(object.get("when_out_of_range"));
        return (x, y, z) -> {
            var value = input.sample(x, y, z);
            return value >= minimum && value < maximum ? inside.sample(x, y, z) : outside.sample(x, y, z);
        };
    }

    private DensityFunction intervalSelect(JsonObject object) {
        var input = compile(object.get("input"));
        var thresholds = floatArray(object.getAsJsonArray("thresholds"));
        var functions = new ArrayList<DensityFunction>();
        object.getAsJsonArray("functions").forEach(element -> functions.add(compile(element)));
        return (x, y, z) -> {
            var value = input.sample(x, y, z);
            for (var index = 0; index < thresholds.length; index++) {
                if (value < thresholds[index]) {
                    return functions.get(index).sample(x, y, z);
                }
            }
            return functions.getLast().sample(x, y, z);
        };
    }

    private DensityFunction noise(JsonObject object) {
        var sampler = noise(string(object, "noise"));
        var xzScale = decimal(object, "xz_scale", 1.0);
        var yScale = decimal(object, "y_scale", 1.0);
        var shiftX = optional(object, "shift_x");
        var shiftY = optional(object, "shift_y");
        var shiftZ = optional(object, "shift_z");
        return (x, y, z) -> sampler.sample(x * xzScale + shiftX.sample(x, y, z),
                y * yScale + shiftY.sample(x, y, z), z * xzScale + shiftZ.sample(x, y, z));
    }

    private DensityFunction shift(JsonObject object, boolean swapped) {
        var sampler = noise(string(object, "noise"));
        return swapped
                ? (x, y, z) -> sampler.sample(z * 0.25, x * 0.25, 0.0) * 4.0F
                : (x, y, z) -> sampler.sample(x * 0.25, 0.0, z * 0.25) * 4.0F;
    }

    private DensityFunction interpolated(JsonObject object) {
        var input = compile(object.get("input"));
        var cellXz = integer(object, "cell_size_xz");
        var cellY = integer(object, "cell_size_y");
        return new InterpolatedDensity(input, cellXz, cellY);
    }

    private DensityFunction spline(JsonObject object) {
        var coordinate = compile(object.get("coordinate"));
        var locations = new ArrayList<Float>();
        var values = new ArrayList<DensityFunction>();
        var derivatives = new ArrayList<Float>();
        for (var element : object.getAsJsonArray("points")) {
            var point = element.getAsJsonObject();
            locations.add(number(point, "location"));
            values.add(point.get("value").isJsonObject()
                    ? spline(point.getAsJsonObject("value")) : compile(point.get("value")));
            derivatives.add(number(point, "derivative"));
        }
        return (x, y, z) -> sampleSpline(coordinate.sample(x, y, z), locations, values, derivatives, x, y, z);
    }

    private DensityFunction blended(JsonObject object) {
        var sampler = MinecraftNoises.blended(seed, decimal(object, "xz_scale", 1.0),
                decimal(object, "y_scale", 1.0), decimal(object, "xz_factor", 1.0),
                decimal(object, "y_factor", 1.0), decimal(object, "smear_scale_multiplier", 1.0));
        return (x, y, z) -> sampler.sample(x, y, z);
    }

    private Noise noise(String rawKey) {
        var name = key(rawKey);
        return noises.computeIfAbsent(name, ignored -> {
            var object = noiseDefinitions.get(name);
            if (object == null) {
                throw new IllegalArgumentException("Unknown noise: " + name);
            }
            var count = integer(object, "octave_count", 1);
            var modifiers = new double[count];
            java.util.Arrays.fill(modifiers, 1.0);
            if (object.has("amplitude_modifiers")) {
                var array = object.getAsJsonArray("amplitude_modifiers");
                for (var index = 0; index < array.size(); index++) {
                    modifiers[index] = array.get(index).getAsDouble();
                }
            }
            var parameters = new NormalNoise.Parameters(decimal(object, "base_amplitude", 1.0),
                    integer(object, "base_octave"), true, modifiers);
            return MinecraftNoises.normal(seed, name, parameters);
        });
    }

    private DensityFunction optional(JsonObject object, String name) {
        return object.has(name) ? compile(object.get(name)) : constant(0.0F);
    }

    private static float sampleSpline(float input, List<Float> locations, List<DensityFunction> values,
                                      List<Float> derivatives, int x, int y, int z) {
        var last = locations.size() - 1;
        var start = -1;
        while (start + 1 < locations.size() && input >= locations.get(start + 1)) {
            start++;
        }
        if (start < 0) {
            return extend(input, locations.getFirst(), values.getFirst().sample(x, y, z), derivatives.getFirst());
        }
        if (start == last) {
            return extend(input, locations.getLast(), values.getLast().sample(x, y, z), derivatives.getLast());
        }
        var firstX = locations.get(start);
        var secondX = locations.get(start + 1);
        var alpha = (input - firstX) / (secondX - firstX);
        var firstY = values.get(start).sample(x, y, z);
        var secondY = values.get(start + 1).sample(x, y, z);
        var firstCorrection = derivatives.get(start) * (secondX - firstX) - (secondY - firstY);
        var secondCorrection = -derivatives.get(start + 1) * (secondX - firstX) + (secondY - firstY);
        return lerp(alpha, firstY, secondY) + alpha * (1.0F - alpha)
                * lerp(alpha, firstCorrection, secondCorrection);
    }

    private static float extend(float input, float location, float value, float derivative) {
        return derivative == 0.0F ? value : value + derivative * (input - location);
    }

    private static float squeeze(float value) {
        var clamped = Math.max(-1.0F, Math.min(1.0F, value));
        return clamped / 2.0F - clamped * clamped * clamped / 24.0F;
    }

    private static float lerp(float alpha, float from, float to) {
        return from + alpha * (to - from);
    }

    private static DensityFunction constant(float value) {
        return (x, y, z) -> value;
    }

    private static float[] floatArray(com.google.gson.JsonArray array) {
        var values = new float[array.size()];
        for (var index = 0; index < values.length; index++) {
            values[index] = array.get(index).getAsFloat();
        }
        return values;
    }

    private static Map<String, JsonElement> loadElements(String resource) {
        var values = new HashMap<String, JsonElement>();
        read(resource, (name, json) -> values.put(name, JsonParser.parseString(json)));
        return Map.copyOf(values);
    }

    private static Map<String, JsonObject> loadObjects(String resource) {
        var values = new HashMap<String, JsonObject>();
        read(resource, (name, json) -> values.put(name, JsonParser.parseString(json).getAsJsonObject()));
        return Map.copyOf(values);
    }

    private static void read(String resource, EntryConsumer consumer) {
        try (var stream = MinecraftDensityRouter.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing worldgen resource " + resource);
            }
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                reader.lines().filter(line -> !line.isBlank()).forEach(line -> {
                    var separator = line.indexOf('\t');
                    consumer.accept(line.substring(0, separator), line.substring(separator + 1));
                });
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String key(String value) {
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    private static String string(JsonObject object, String name) {
        return object.get(name).getAsString();
    }

    private static float number(JsonObject object, String name) {
        return object.get(name).getAsFloat();
    }

    private static int integer(JsonObject object, String name) {
        return object.get(name).getAsInt();
    }

    private static int integer(JsonObject object, String name, int fallback) {
        return object.has(name) ? integer(object, name) : fallback;
    }

    private static double decimal(JsonObject object, String name, double fallback) {
        return object.has(name) ? object.get(name).getAsDouble() : fallback;
    }

    @FunctionalInterface
    private interface FloatBinary {
        float apply(float left, float right);
    }

    @FunctionalInterface
    private interface FloatUnary {
        float apply(float value);
    }

    @FunctionalInterface
    private interface EntryConsumer {
        void accept(String name, String json);
    }

    private static final class CachedDensity implements DensityFunction {
        private final DensityFunction input;
        private final ThreadLocal<LastValue> last = ThreadLocal.withInitial(LastValue::new);

        private CachedDensity(DensityFunction input) {
            this.input = input;
        }

        @Override
        public float sample(int blockX, int blockY, int blockZ) {
            var cached = last.get();
            if (cached.matches(blockX, blockY, blockZ)) {
                return cached.value;
            }
            cached.set(blockX, blockY, blockZ, input.sample(blockX, blockY, blockZ));
            return cached.value;
        }
    }

    private static final class InterpolatedDensity implements DensityFunction {
        private final DensityFunction input;
        private final int cellXz;
        private final int cellY;
        private final ThreadLocal<Cell> last = ThreadLocal.withInitial(Cell::new);

        private InterpolatedDensity(DensityFunction input, int cellXz, int cellY) {
            this.input = input;
            this.cellXz = cellXz;
            this.cellY = cellY;
        }

        @Override
        public float sample(int blockX, int blockY, int blockZ) {
            var cellX = Math.floorDiv(blockX, cellXz) * cellXz;
            var cellY = Math.floorDiv(blockY, this.cellY) * this.cellY;
            var cellZ = Math.floorDiv(blockZ, cellXz) * cellXz;
            var cell = last.get();
            if (!cell.matches(cellX, cellY, cellZ)) {
                cell.load(input, cellX, cellY, cellZ, cellXz, this.cellY);
            }
            var alphaX = (float) (blockX - cellX) / cellXz;
            var alphaY = (float) (blockY - cellY) / this.cellY;
            var alphaZ = (float) (blockZ - cellZ) / cellXz;
            return cell.interpolate(alphaX, alphaY, alphaZ);
        }
    }

    private static final class LastValue {
        private int x;
        private int y;
        private int z;
        private float value;
        private boolean populated;

        private boolean matches(int x, int y, int z) {
            return populated && this.x == x && this.y == y && this.z == z;
        }

        private void set(int x, int y, int z, float value) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.value = value;
            populated = true;
        }
    }

    private static final class Cell {
        private final float[] corners = new float[8];
        private int x;
        private int y;
        private int z;
        private boolean populated;

        private boolean matches(int x, int y, int z) {
            return populated && this.x == x && this.y == y && this.z == z;
        }

        private void load(DensityFunction input, int x, int y, int z, int width, int height) {
            this.x = x;
            this.y = y;
            this.z = z;
            corners[0] = input.sample(x, y, z);
            corners[1] = input.sample(x + width, y, z);
            corners[2] = input.sample(x, y + height, z);
            corners[3] = input.sample(x + width, y + height, z);
            corners[4] = input.sample(x, y, z + width);
            corners[5] = input.sample(x + width, y, z + width);
            corners[6] = input.sample(x, y + height, z + width);
            corners[7] = input.sample(x + width, y + height, z + width);
            populated = true;
        }

        private float interpolate(float alphaX, float alphaY, float alphaZ) {
            return lerp(alphaZ,
                    lerp(alphaY, lerp(alphaX, corners[0], corners[1]), lerp(alphaX, corners[2], corners[3])),
                    lerp(alphaY, lerp(alphaX, corners[4], corners[5]), lerp(alphaX, corners[6], corners[7])));
        }
    }
}
