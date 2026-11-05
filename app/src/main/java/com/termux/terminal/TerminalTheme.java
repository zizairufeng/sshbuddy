/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.termux.terminal;

/**
 * App-specific palette for the vendored Termux renderer. Kept separate so the
 * upstream files remain close to their original form.
 */
public final class TerminalTheme {

    private static boolean sApplied;

    private TerminalTheme() {
    }

    public static synchronized void apply() {
        if (sApplied) return;
        int[] colors = TerminalColors.COLOR_SCHEME.mDefaultColors;
        // Match SSHBuddy's dark blue/green palette.
        colors[0] = 0xff1b2530;  // ANSI black: visible against the background
        colors[8] = 0xff5a6b7b;  // bright black / dim text
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xffd7e1ea;
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xff080c11;
        colors[TextStyle.COLOR_INDEX_CURSOR] = 0xff22d3a6;
        sApplied = true;
    }
}
