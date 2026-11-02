
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBConnection {

    private static String getUrl() {
        String configuredUrl = ConfigLoader.get("MYSQL_URL", "");
        if (!configuredUrl.isBlank()) {
            return configuredUrl;
        }

        String host = ConfigLoader.get("DB_HOST", "localhost");
        String port = ConfigLoader.get("DB_PORT", "3306");
        String database = ConfigLoader.get("DB_NAME", "smart_bus_db");
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }

    public static Connection getConnection() throws SQLException {
        String user = ConfigLoader.get("MYSQL_USER", ConfigLoader.get("DB_USER", "root"));
        String password = ConfigLoader.get("MYSQL_PASSWORD", ConfigLoader.get("DB_PASSWORD", ""));
        return DriverManager.getConnection(getUrl(), user, password);
    }
}