package org.aadi.triptourworld.guardrails;

import java.util.regex.Pattern;

/** Output guardrail: redacts secrets/PII the model might echo and guarantees the "verify before booking" disclaimer. */
public final class OutputGuardrail {
    public static final String DISCLAIMER =
            "_Prices, availability and weather are estimates from third-party data and the AI model. "
                    + "Please verify everything before booking._";

    private static final Pattern[] SECRETS = {
            Pattern.compile("gsk_[A-Za-z0-9]{16,}"),
            Pattern.compile("tvly-[A-Za-z0-9_-]{10,}"),
            Pattern.compile("lsv2_[A-Za-z0-9_]{10,}"),
            Pattern.compile("(?i)(api[_-]?key|secret|token|password)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-]{12,}[\"']?"),
            Pattern.compile("postgres(ql)?://\\S+"),
    };
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){13,16}\\b");
    private static final Pattern LONG_PHONE = Pattern.compile("\\+\\d[\\d\\s-]{8,}\\d");

    public String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String out = text;
        for (Pattern p : SECRETS) {
            out = p.matcher(out).replaceAll("[redacted-secret]");
        }
        out = EMAIL.matcher(out).replaceAll("[redacted-email]");
        out = CARD.matcher(out).replaceAll("[redacted-number]");
        out = LONG_PHONE.matcher(out).replaceAll("[redacted-phone]");
        if (!out.contains("verify everything before booking")) {
            out = out.stripTrailing() + "\n\n" + DISCLAIMER;
        }
        return out;
    }
}
