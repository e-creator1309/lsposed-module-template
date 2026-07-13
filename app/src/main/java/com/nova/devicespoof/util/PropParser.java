package com.nova.devicespoof.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the raw text a user pastes into the companion app into a key → value map.
 *
 * Two formats are accepted, matching what people actually copy around:
 *
 *  1. build.prop style:      ro.product.model=SM-S928B
 *     (one KEY=VALUE per line, "#" comments and blank lines ignored)
 *
 *  2. floating_feature.xml style:
 *     <category name="SEC_FLOATING_FEATURE_COMMON_CONFIG_MODEL_NAME">SM-S928B</category>
 *     or the attribute-value form some OEM dumps use:
 *     <config name="SEC_FLOATING_FEATURE_...">value</config>
 *
 * Both are plain, deterministic text parsing — nothing here touches the device's
 * real /system/build.prop or /system/etc/floating_feature.xml. The parsed map is only
 * ever handed back to a specific app's process, in memory, for the lifetime of that
 * process.
 */
public final class PropParser {

    private PropParser() {}

    private static final Pattern XML_ENTRY = Pattern.compile(
            "<\\s*(?:category|config|feature)\\b[^>]*\\bname\\s*=\\s*\"([^\"]+)\"[^>]*>([^<]*)<\\s*/\\s*(?:category|config|feature)\\s*>",
            Pattern.CASE_INSENSITIVE);

    /** Parses "key=value" lines. Ignores blank lines and lines starting with '#'. */
    public static Map<String, String> parseBuildProp(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty()) out.put(key, value);
        }
        return out;
    }

    /** Parses the &lt;category name="..."&gt;value&lt;/category&gt; style used by floating_feature.xml dumps. */
    public static Map<String, String> parseFloatingFeatures(String xml) {
        Map<String, String> out = new LinkedHashMap<>();
        if (xml == null) return out;
        Matcher m = XML_ENTRY.matcher(xml);
        while (m.find()) {
            String key = m.group(1).trim();
            String value = m.group(2) == null ? "" : m.group(2).trim();
            if (!key.isEmpty()) out.put(key, value);
        }
        return out;
    }
}
