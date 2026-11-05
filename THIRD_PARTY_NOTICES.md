# Third-party notices

SSHBuddy bundles the following open-source components:

## Termux terminal-emulator / terminal-view

- Project: Termux (`termux-app`), https://github.com/termux/termux-app
- Modules: `terminal-emulator`, `terminal-view`
- Commit used: `8629e632fcb95da272221be327db653fb24befe9`
- License: Apache License 2.0 (as stated in Termux `LICENSE.md`, exception for
  terminal-emulator/terminal-view, derived from
  [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator))
- Copyright: Termux contributors; Android-Terminal-Emulator contributors
  (Copyright 2012 Steven Luo and others)

The files under `app/src/main/java/com/termux/` and the two text-selection handle
drawables are vendored from those modules. `com.termux.terminal.TerminalSession`
was replaced with a small remote-SSH-backed implementation; `JNI.java` and the
local PTY transport were removed. Everything else keeps upstream behaviour.

A copy of the Apache License 2.0 is shipped inside the APK at
`assets/licenses/Apache-2.0.txt` and in this repository at
`app/src/main/assets/licenses/Apache-2.0.txt`.

## Other libraries

- JSch (fork `com.github.mwiede:jsch`), BSD-style license
- OkHttp, Apache License 2.0
- Gson, Apache License 2.0
- AndroidX / Jetpack Compose, Apache License 2.0
- FreeRDP (Android JNI libraries), Apache License 2.0
