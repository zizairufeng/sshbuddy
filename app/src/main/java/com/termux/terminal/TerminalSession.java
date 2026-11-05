/*
 * Copyright (C) 2012 Steven Luo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.termux.terminal;

import java.nio.charset.StandardCharsets;

/**
 * Remote-backed terminal session.
 *
 * <p>This is a deliberately small replacement for Termux's original
 * {@code TerminalSession}. The original owns a local PTY through JNI; SSHBuddy
 * already has an SSH channel, so the transport is injected instead. Everything
 * else (ANSI/VT parsing, Unicode widths, scrollback, selection and rendering)
 * comes from the vendored Apache-2.0 Termux terminal-emulator/terminal-view.</p>
 *
 * <p>All methods are called on the Android main thread. Network I/O happens in
 * {@code SshSession}; this class only owns screen state.</p>
 */
public final class TerminalSession extends TerminalOutput {

    private static final int TRANSCRIPT_ROWS = 4000;
    private static final int INITIAL_COLUMNS = 80;
    private static final int INITIAL_ROWS = 24;
    private static final int INITIAL_CELL_WIDTH = 8;
    private static final int INITIAL_CELL_HEIGHT = 16;

    private final TerminalSessionClient mClient;
    private final InputSink mInputSink;
    private final SizeSink mSizeSink;

    public TerminalEmulator mEmulator;

    private int mColumns = INITIAL_COLUMNS;
    private int mRows = INITIAL_ROWS;
    private int mCellWidth = INITIAL_CELL_WIDTH;
    private int mCellHeight = INITIAL_CELL_HEIGHT;

    /** Bytes typed/committed by the user, forwarded to the SSH channel. */
    public interface InputSink {
        void write(byte[] data);
    }

    /** Reports the visible cell grid so the remote PTY can be resized. */
    public interface SizeSink {
        void resize(int columns, int rows);
    }

    public TerminalSession(TerminalSessionClient client, InputSink inputSink, SizeSink sizeSink) {
        mClient = client;
        mInputSink = inputSink;
        mSizeSink = sizeSink;
        TerminalTheme.apply();
        mEmulator = new TerminalEmulator(this, mColumns, mRows, mCellWidth, mCellHeight,
            TRANSCRIPT_ROWS, mClient);
        mSizeSink.resize(mColumns, mRows);
    }

    /** TerminalView calls this on every layout/size change. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (columns < 1 || rows < 1) return;
        boolean changed = columns != mColumns || rows != mRows
            || cellWidthPixels != mCellWidth || cellHeightPixels != mCellHeight;
        mColumns = columns;
        mRows = rows;
        mCellWidth = Math.max(1, cellWidthPixels);
        mCellHeight = Math.max(1, cellHeightPixels);
        if (changed) {
            mEmulator.resize(columns, rows, mCellWidth, mCellHeight);
            mSizeSink.resize(columns, rows);
        }
    }

    /** Feed raw bytes received from the SSH channel. */
    public void feed(byte[] data, int length) {
        if (data == null || length <= 0) return;
        mEmulator.append(data, length);
        notifyScreenUpdate();
    }

    public void feed(byte[] data) {
        feed(data, data == null ? 0 : data.length);
    }

    /** Append app-generated status text (already UTF-8) without sending it to SSH. */
    public void appendSystem(String text) {
        if (text == null || text.isEmpty()) return;
        feed(text.getBytes(StandardCharsets.UTF_8));
    }

    public String screenText() {
        return mEmulator.getScreen().getTranscriptText();
    }

    public void resetTerminal() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    public int getColumns() {
        return mColumns;
    }

    public int getRows() {
        return mRows;
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (data == null || count <= 0) return;
        byte[] copy = new byte[count];
        System.arraycopy(data, offset, copy, 0, count);
        mInputSink.write(copy);
    }

    /** Write one Unicode code point as UTF-8, optionally prefixed by ESC (Alt). */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint < 0 || codePoint > 0x10FFFF
            || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) return;
        StringBuilder sb = new StringBuilder(2);
        if (prependEscape) sb.append('\u001b');
        sb.appendCodePoint(codePoint);
        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
        write(data, 0, data.length);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    public TerminalSessionClient getClient() {
        return mClient;
    }

    public void notifyScreenUpdate() {
        if (mClient != null) mClient.onTextChanged(this);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }
}
