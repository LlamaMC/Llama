package com.llamamc.llama.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class PluginDependencyResolverTest {

    @Test
    void ordersDependenciesBeforeTheirDependents() {
        var resolution = resolve(
                candidate("a", List.of("b"), List.of()),
                candidate("b", List.of(), List.of("c")),
                candidate("c", List.of(), List.of()));

        assertEquals(List.of("c", "b", "a"), ids(resolution));
        assertEquals(List.of(), resolution.rejected());
    }

    @Test
    void keepsScanOrderForPluginsThatAreReadyAtTheSameTime() {
        var resolution = resolve(
                candidate("zeta", List.of(), List.of()),
                candidate("alpha", List.of(), List.of()),
                candidate("mid", List.of("zeta"), List.of()));

        assertEquals(List.of("zeta", "alpha", "mid"), ids(resolution));
    }

    @Test
    void disablesEveryMemberOfACycleAndNamesTheCycle() {
        var resolution = resolve(
                candidate("a", List.of("b"), List.of()),
                candidate("b", List.of("a"), List.of()),
                candidate("c", List.of(), List.of()),
                candidate("d", List.of("a"), List.of()));

        assertEquals(List.of("c"), ids(resolution));
        assertEquals(Set.of("a", "b", "d"), rejectedIds(resolution));
        assertEquals("dependency cycle: a -> b -> a", reasonOf(resolution, "a"));
        assertEquals("dependency cycle: b -> a -> b", reasonOf(resolution, "b"));
        assertEquals("dependency 'a' is disabled", reasonOf(resolution, "d"));
    }

    @Test
    void disablesAPluginThatDependsOnItself() {
        var resolution = resolve(candidate("self", List.of("self"), List.of()));

        assertEquals(List.of(), ids(resolution));
        assertEquals("dependency cycle: self -> self", reasonOf(resolution, "self"));
    }

    @Test
    void missingHardDependencyDisablesEveryDependentButNotOthers() {
        var resolution = resolve(
                candidate("x", List.of("missing"), List.of()),
                candidate("y", List.of("x"), List.of()),
                candidate("z", List.of(), List.of()));

        assertEquals(List.of("z"), ids(resolution));
        assertEquals("missing dependency 'missing'", reasonOf(resolution, "x"));
        assertEquals("dependency 'x' is disabled", reasonOf(resolution, "y"));
    }

    @Test
    void softDependencyOnlyChangesTheOrder() {
        var absent = resolve(candidate("p", List.of(), List.of("absent")));

        assertEquals(List.of("p"), ids(absent));
        assertEquals(List.of(), absent.rejected());

        var ordered = resolve(
                candidate("q", List.of(), List.of("p")),
                candidate("p", List.of(), List.of()));

        assertEquals(List.of("p", "q"), ids(ordered));
        assertEquals(List.of(), ordered.rejected());
    }

    @Test
    void duplicateIdDisablesTheSecondCandidate() {
        var first = new PluginCandidate(Path.of("first.jar"), descriptor("a", List.of(), List.of()));
        var second = new PluginCandidate(Path.of("second.jar"), descriptor("a", List.of(), List.of()));

        var resolution = PluginDependencyResolver.resolve(
                List.of(first, second, candidate("b", List.of("a"), List.of())));

        assertEquals(List.of("a", "b"), ids(resolution));
        assertEquals(Path.of("first.jar"), resolution.order().getFirst().jar());
        var rejection = resolution.rejected().getFirst();
        assertEquals(Path.of("second.jar"), rejection.candidate().jar());
        assertTrue(rejection.reason().contains("duplicate plugin id 'a'"), rejection.reason());
    }

    private static PluginCandidate candidate(String id, List<String> depends, List<String> softDepends) {
        return new PluginCandidate(Path.of(id + ".jar"), descriptor(id, depends, softDepends));
    }

    private static PluginDescriptor descriptor(String id, List<String> depends, List<String> softDepends) {
        return new PluginDescriptor(id, id, "1.0.0", id + ".Main", depends, softDepends);
    }

    private static PluginDependencyResolver.Resolution resolve(PluginCandidate... candidates) {
        return PluginDependencyResolver.resolve(List.of(candidates));
    }

    private static List<String> ids(PluginDependencyResolver.Resolution resolution) {
        return resolution.order().stream().map(candidate -> candidate.descriptor().id()).toList();
    }

    private static Set<String> rejectedIds(PluginDependencyResolver.Resolution resolution) {
        return resolution.rejected().stream()
                .map(rejection -> rejection.candidate().descriptor().id())
                .collect(Collectors.toSet());
    }

    private static String reasonOf(PluginDependencyResolver.Resolution resolution, String id) {
        return resolution.rejected().stream()
                .filter(rejection -> rejection.candidate().descriptor().id().equals(id))
                .findFirst()
                .orElseThrow()
                .reason();
    }
}
