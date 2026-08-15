package com.wuwa.config.manager.privilege;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ShellEscaperTest {
    @Test
    public void quotesSpacesUnicodeAndApostrophes() {
        assertEquals("'/storage/test path/鸣潮'", ShellEscaper.quote("/storage/test path/鸣潮"));
        assertEquals("'a'\\''b'", ShellEscaper.quote("a'b"));
    }
}

