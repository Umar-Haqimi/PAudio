# Release builds strip verbose/debug/info logging (SPEC §6). Never log thresholds, curves, names or devices.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
