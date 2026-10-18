# AYN Thor dual-screen build

This branch adds a full terminal keyboard on the second display: the SSH session
fills the top screen, and the bottom screen becomes a keyboard with Esc, Tab,
Ctrl, Alt, arrows, the full ASCII symbol set, and F1-F12 (via Fn).

It can be turned off in Settings → Keyboard → "Keyboard on second screen".

## Build

    docker compose -f thor/compose.yaml run --rm build

The APK lands in `app/build/outputs/apk/oss/debug/app-oss-debug.apk`.

## Install

    adb install -r app/build/outputs/apk/oss/debug/app-oss-debug.apk

It installs as `org.connectbot` with a debug signature, so uninstall any Play
Store or F-Droid ConnectBot first.
