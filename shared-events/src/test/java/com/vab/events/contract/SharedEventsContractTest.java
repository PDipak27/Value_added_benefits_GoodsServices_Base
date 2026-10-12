package com.vab.events.contract;

import com.fasterxml.jackson.databind.JsonNode;
import io.eventuate.common.json.mapper.JSonMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Wire-contract tests for every saga command/reply and Order domain event in shared-events. These
 * messages cross service boundaries through Eventuate's JSonMapper, and the services deploy independently,
 * so the JSON shape is a contract: it is pinned in {@code contracts/shared-events-fields.properties}.
 */
class SharedEventsContractTest {

    private static final Instant SAMPLE_INSTANT = Instant.parse("2026-10-12T10:15:30.123Z");
    private static Properties contract;

    @BeforeAll
    static void loadContract() throws Exception {
        contract = new Properties();
        try (InputStream in = SharedEventsContractTest.class.getResourceAsStream("/contracts/shared-events-fields.properties")) {
            contract.load(in);
        }
    }

    /** All message classes compiled from com.vab.events (excluding the common helpers and this test package). */
    static List<Class<?>> messageClasses() throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        Enumeration<URL> roots = SharedEventsContractTest.class.getClassLoader().getResources("com/vab/events");
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            if (!"file".equals(url.getProtocol())) continue;
            Path base = Path.of(url.toURI());
            try (Stream<Path> files = Files.walk(base)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String rel = base.relativize(p).toString().replace(File.separatorChar, '.');
                    String name = "com.vab.events." + rel.substring(0, rel.length() - ".class".length());
                    if (name.contains("$") || name.startsWith("com.vab.events.common.")
                            || name.startsWith("com.vab.events.contract.")) continue;
                    classes.add(Class.forName(name));
                }
            }
        }
        return classes;
    }

    /** A fully populated instance: every field gets a non-default sample value of its type. */
    private static Object sample(Class<?> type) throws Exception {
        Object o = type.getDeclaredConstructor().newInstance();
        for (Field f : instanceFields(type)) {
            f.setAccessible(true);
            f.set(o, sampleValue(f));
        }
        return o;
    }

    private static Object sampleValue(Field f) {
        Class<?> t = f.getType();
        if (t == String.class) return "v-" + f.getName();
        if (t == long.class || t == Long.class) return 42L;
        if (t == int.class || t == Integer.class) return 7;
        if (t == boolean.class || t == Boolean.class) return true;
        if (t == Instant.class) return SAMPLE_INSTANT;
        if (t.isEnum()) return t.getEnumConstants()[0];
        throw new IllegalStateException("No sample for " + t + " (" + f + ") — extend sampleValue()");
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Arrays.stream(c.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers())).forEach(fields::add);
        }
        return fields;
    }

    private static Set<String> jsonKeys(String json) throws Exception {
        JsonNode node = JSonMapper.objectMapper.readTree(json);
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    // ── contract ────────────────────────────────────────────────────────────

    /** Every message class is pinned in the contract file, and the file lists no class that no longer exists. */
    @Test
    void contract_file_covers_exactly_the_message_classes() throws Exception {
        Set<String> compiled = messageClasses().stream().map(Class::getName).collect(Collectors.toCollection(TreeSet::new));

        assertThat(compiled).isNotEmpty();
        assertThat(new TreeSet<>(contract.stringPropertyNames())).isEqualTo(compiled);
    }

    /** The serialized JSON property names equal the pinned contract (a rename/removal/addition is a deliberate contract change). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("messageClasses")
    void serialized_properties_match_the_contract(Class<?> type) throws Exception {
        Set<String> expected = new TreeSet<>(Arrays.asList(contract.getProperty(type.getName(), "").split(",")));
        expected.remove("");

        assertThat(jsonKeys(JSonMapper.toJson(sample(type)))).isEqualTo(expected);
    }

    /** Every message deserializes back to an equal object (needs a no-arg constructor, and getters for every field). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("messageClasses")
    void round_trips_through_eventuate_json(Class<?> type) throws Exception {
        Object original = sample(type);

        Object copy = JSonMapper.fromJson(JSonMapper.toJson(original), type);

        assertThat(copy).usingRecursiveComparison().isEqualTo(original);
    }

    /** Unknown properties from a newer producer are tolerated by an older consumer (additive changes stay compatible). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("messageClasses")
    void unknown_properties_are_ignored(Class<?> type) throws Exception {
        String json = JSonMapper.toJson(sample(type));
        String withExtra = json.substring(0, json.length() - 1) + (json.length() > 2 ? "," : "") + "\"addedLater\":\"x\"}";

        assertThat(JSonMapper.fromJson(withExtra, type)).isNotNull();
    }

    // ── java.time.Instant on the wire (EventuateJackson) ─────────────────────

    /** Classes carrying an Instant field. */
    static List<Class<?>> instantBearingClasses() throws Exception {
        return messageClasses().stream()
                .filter(c -> instanceFields(c).stream().anyMatch(f -> f.getType() == Instant.class))
                .toList();
    }

    /** A fresh class loader over the test classpath, so JSonMapper starts WITHOUT JavaTimeModule registered. */
    private static URLClassLoader isolatedLoader() throws Exception {
        String[] entries = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) urls[i] = new File(entries[i]).toURI().toURL();
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    private static Method toJson(ClassLoader loader) throws Exception {
        return Class.forName(JSonMapper.class.getName(), true, loader).getMethod("toJson", Object.class);
    }

    /** Control: without EventuateJackson, Eventuate's JSonMapper cannot serialize an Instant (why the hook exists). */
    @Test
    void plain_jsonmapper_rejects_instant() throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Method toJson = toJson(loader);

            Throwable thrown = catchThrowable(() -> toJson.invoke(null, Map.of("at", SAMPLE_INSTANT)));

            assertThat(thrown).isNotNull();
            assertThat(rootCause(thrown).getMessage()).contains("Java 8 date/time type");
        }
    }

    /** Each Instant-bearing message registers JavaTimeModule itself (static hook), so a producer can serialize it in a fresh JVM. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("instantBearingClasses")
    void instant_bearing_message_registers_java_time_itself(Class<?> type) throws Exception {
        try (URLClassLoader loader = isolatedLoader()) {
            Class<?> isolated = Class.forName(type.getName(), true, loader);   // runs the class's static hook
            Object instance = isolated.getDeclaredConstructor().newInstance();
            for (Field f : instanceFields(isolated)) {
                if (f.getType().getName().equals(Instant.class.getName())) {
                    f.setAccessible(true);
                    f.set(instance, SAMPLE_INSTANT);
                }
            }

            String json = (String) toJson(loader).invoke(null, instance);

            assertThat(json).isNotBlank();
        }
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t;
    }
}
