package com.firewolf.xiaolinstudy.data;

public final class VersionTools {
    private VersionTools() {}

    public static boolean isNewer(String candidate, String current) {
        int[] left = parse(candidate);
        int[] right = parse(current);
        int count = Math.max(left.length, right.length);
        for (int index = 0; index < count; index++) {
            int leftValue = index < left.length ? left[index] : 0;
            int rightValue = index < right.length ? right[index] : 0;
            if (leftValue != rightValue) return leftValue > rightValue;
        }
        return false;
    }

    private static int[] parse(String version) {
        String[] parts = (version == null ? "" : version.trim()).split("\\.");
        int[] values = new int[Math.max(1, parts.length)];
        for (int index = 0; index < parts.length; index++) {
            String digits = parts[index].replaceAll("[^0-9].*$", "");
            try {
                values[index] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                values[index] = 0;
            }
        }
        return values;
    }
}
