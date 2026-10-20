package ru.itmo.soa.common;
public final class Settings {
    private Settings() {}
    public static String get(String key, String fallback) {
        return System.getProperty("soa." + key.toLowerCase().replace('_', '.'), System.getenv().getOrDefault("SOA_" + key, fallback));
    }
}
