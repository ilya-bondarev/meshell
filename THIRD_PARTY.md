# Third-party notes

The APK build includes Java classes from this tree, Android resources, and three project assets: the compiled native broker, `start.sh`, and `mkshrc`. SDK/NDK build tools and the Android framework are not copied into the APK as libraries.

The project code and artwork are released under MIT-0 by their owner. External tools and services are separate dependencies, not part of the source archive: the Android SDK/NDK, a JDK, curl, OpenAI/ChatGPT and the Codex client release metadata on npm, and Yandex Metrika. Keep their own licenses and service terms when provisioning or redistributing them. The pinned CI actions and Gitleaks binary are used only in CI.

The OAuth client identifier in `OpenAiClient` is public protocol configuration, not a private credential. It is not proof that use of a third-party service is authorized by its operator.
