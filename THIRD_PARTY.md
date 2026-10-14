# Third-party notes

The APK build includes Java classes from this tree, Android resources, and three project assets: the compiled native broker, `start.sh`, and `mkshrc`. SDK/NDK build tools and the Android framework are not copied into the APK as libraries.

The project code and artwork are released under MIT-0 by their owner. External tools and services are separate dependencies, not part of the source archive: the Android SDK/NDK, a JDK, curl, OpenAI/ChatGPT, and the Codex client release metadata on npm. Keep their own licenses and service terms when provisioning or redistributing them.

**Settings → Packages** and `packages_install` download Termux packages, such as the Codex CLI (Apache-2.0), git (GPL-2.0) and OpenJDK (GPL-2.0 with Classpath Exception), from `packages.termux.dev` onto the phone at run time. MeShell does not bundle or redistribute them; each keeps its own license, and Termux publishes the build sources at https://github.com/termux/termux-packages. The xz decoder in `XzStream` is original MeShell code. The pinned CI actions and Gitleaks binary are used only in CI.

The OAuth client identifier in `OpenAiClient` is public protocol configuration, not a private credential. It is not proof that use of a third-party service is authorized by its operator.
