import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/**
 * ConfigLoader: Loads configuration from .env and environment variables without external dependencies.
 */
public class ConfigLoader {

    private static final Map<String, String> envMap = new HashMap<>();

    static {
        loadEnvFile();
    }

    private static void loadEnvFile() {
        File envFile = new File(".env");
        if (!envFile.exists()) {
            envFile = new File("Miniproject1/.env");
        }
        if (envFile.exists() && envFile.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eqIndex = line.indexOf('=');
                    if (eqIndex > 0) {
                        String key = line.substring(0, eqIndex).trim();
                        String value = line.substring(eqIndex + 1).trim();
                        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                            value = value.substring(1, value.length() - 1);
                        }
                        envMap.put(key, value);
                    }
                }
            } catch (Exception e) {
                System.err.println("Notice: Could not load .env file: " + e.getMessage());
            }
        }
    }

    public static String get(String key, String defaultValue) {
        String sysVal = System.getenv(key);
        if (sysVal != null && !sysVal.trim().isEmpty()) {
            return sysVal.trim();
        }
        String fileVal = envMap.get(key);
        if (fileVal != null && !fileVal.trim().isEmpty()) {
            return fileVal.trim();
        }
        return defaultValue;
    }

    public static String getGoogleMapsApiKey() {
        return get("GOOGLE_MAPS_BROWSER_KEY", "");
    }

    public static void setGoogleMapsApiKey(String key) {
        if (key != null) {
            envMap.put("GOOGLE_MAPS_BROWSER_KEY", key.trim());
        }
    }

    public static boolean isGoogleMapsConfigured() {
        String key = getGoogleMapsApiKey();
        return key != null && !key.trim().isEmpty();
    }

    public static double getDefaultLat() {
        try {
            return Double.parseDouble(get("MAP_DEFAULT_LAT", "11.2748"));
        } catch (NumberFormatException e) {
            return 11.2748;
        }
    }

    public static double getDefaultLng() {
        try {
            return Double.parseDouble(get("MAP_DEFAULT_LNG", "77.5828"));
        } catch (NumberFormatException e) {
            return 77.5828;
        }
    }

    public static int getDefaultZoom() {
        try {
            return Integer.parseInt(get("MAP_DEFAULT_ZOOM", "12"));
        } catch (NumberFormatException e) {
            return 12;
        }
    }

    public static String getDestinationName() {
        return get("COLLEGE_DESTINATION_NAME", "Kongu Engineering College");
    }

    public static double getDestinationLat() {
        try {
            return Double.parseDouble(get("COLLEGE_DESTINATION_LAT", "11.2742"));
        } catch (NumberFormatException e) {
            return 11.2742;
        }
    }

    public static double getDestinationLng() {
        try {
            return Double.parseDouble(get("COLLEGE_DESTINATION_LNG", "77.6070"));
        } catch (NumberFormatException e) {
            return 77.6070;
        }
    }
}
