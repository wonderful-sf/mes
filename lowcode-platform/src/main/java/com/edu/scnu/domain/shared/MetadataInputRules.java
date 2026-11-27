package com.edu.scnu.domain.shared;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MetadataInputRules {
    private static final Pattern MODEL_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern TENANT_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}");
    private static final Set<String> DISALLOWED_WORDS = Set.of("fuck", "fucking", "fucked", "shit");

    private MetadataInputRules() {}

    public static String identifierError(String input, String label) {
        if (input == null || !MODEL_IDENTIFIER.matcher(input).matches()) {
            return label + "须以小写字母开头，只能包含小写字母、数字和下划线，最多 64 个字符。";
        }
        return inappropriate(input) ? label + "包含不适当用词。" : null;
    }

    public static String nameError(String input, String label, int maxLength) {
        if (input == null || input.isBlank() || input.codePointCount(0, input.length()) > maxLength
                || input.codePoints().anyMatch(Character::isISOControl)) {
            return label + "须为 1 至 " + maxLength + " 个字符，且不得包含控制字符。";
        }
        return inappropriate(input) ? label + "包含不适当用词。" : null;
    }

    public static String versionError(String version) {
        return version != null && VERSION.matcher(version).matches()
                ? null : "版本号须为三个非负整数，例如 1.0.0。";
    }

    public static String tenantCode(String raw) {
        String code = raw == null ? "" : raw.strip();
        if (!TENANT_CODE.matcher(code).matches()) {
            throw DomainException.invalidParams("租户编码须以字母开头，只能包含字母、数字、下划线和短横线，最多 32 个字符。");
        }
        if (inappropriate(code)) throw DomainException.invalidParams("租户编码包含不适当用词。");
        return code;
    }

    public static String tenantName(String raw) {
        if (raw == null || raw.codePoints().anyMatch(Character::isISOControl)) {
            throw DomainException.invalidParams("租户名称须为 1 至 40 个字符，且不得包含控制字符。");
        }
        String name = raw.strip();
        String error = nameError(name, "租户名称", 40);
        if (error != null) throw DomainException.invalidParams(error);
        return name;
    }

    private static boolean inappropriate(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (String word : normalized.split("[^a-z]+")) {
            if (DISALLOWED_WORDS.contains(word)) return true;
        }
        return false;
    }
}
