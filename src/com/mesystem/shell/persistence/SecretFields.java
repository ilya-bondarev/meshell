package com.mesystem.shell;

final class SecretFields {
    static boolean sensitive(String name) {
        String key=name.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
        return key.contains("private") || key.contains("password") || key.contains("token") ||
            key.contains("secret") || key.contains("userdata") || key.contains("apikey") ||
            key.contains("accesskey") || key.equals("key") || key.equals("authorization") || key.equals("keystore");
    }
}
