package com.wuwa.config.manager.privilege;

public final class ShellEscaper {
    private ShellEscaper() {}

    /** Returns one POSIX-sh-safe argument, including its surrounding quotes. */
    public static String quote(String value) {
        if (value == null) throw new IllegalArgumentException("value == null");
        return "'" + value.replace("'", "'\\''") + "'";
    }
}

