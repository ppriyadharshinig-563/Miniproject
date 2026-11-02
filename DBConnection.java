import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBConnection {

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("MySQL Driver not found in classpath: " + e.getMessage());
        }
    }

    public static Connection getConnection() throws SQLException {
        // 1. Read environment variables for cloud deployment
        String envUrl = System.getenv("MYSQL_URL");
        if (envUrl == null || envUrl.isBlank()) {
            envUrl = System.getenv("DATABASE_URL");
        }
        String envUser = System.getenv("MYSQL_USER");
        if (envUser == null || envUser.isBlank()) {
            envUser = System.getenv("DB_USER");
        }
        String envPass = System.getenv("MYSQL_PASSWORD");
        if (envPass == null) {
            envPass = System.getenv("DB_PASSWORD");
        }

        // 2. If MYSQL_URL or DATABASE_URL is provided (Cloud deployment)
        if (envUrl != null && !envUrl.isBlank()) {
            envUrl = envUrl.trim();

            // Direct JDBC URL (e.g. jdbc:mysql://host:port/smart_bus_db)
            if (envUrl.startsWith("jdbc:mysql://")) {
                if (envUser != null && !envUser.isBlank()) {
                    return DriverManager.getConnection(envUrl, envUser, envPass != null ? envPass : "");
                }
                return DriverManager.getConnection(envUrl);
            }

            // Standard URI format (mysql://user:pass@host:port/smart_bus_db)
            if (envUrl.startsWith("mysql://")) {
                try {
                    URI uri = new URI(envUrl);
                    String host = uri.getHost();
                    int port = uri.getPort() > 0 ? uri.getPort() : 3306;
                    String path = uri.getPath();
                    String dbName = (path != null && path.length() > 1) ? path.substring(1) : "smart_bus_db";
                    String userInfo = uri.getUserInfo();

                    String user = envUser;
                    String pass = envPass;
                    if (user == null || user.isBlank()) {
                        user = (userInfo != null && userInfo.contains(":")) ? userInfo.split(":")[0] : "root";
                    }
                    if (pass == null) {
                        pass = (userInfo != null && userInfo.contains(":")) ? userInfo.split(":", 2)[1] : "";
                    }

                    String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName
                            + "?useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC";
                    return DriverManager.getConnection(jdbcUrl, user, pass);
                } catch (Exception e) {
                    System.err.println("Error parsing MYSQL_URL, attempting fallback: " + e.getMessage());
                }
            }
        }

        // 3. Discrete host/user variables (DB_HOST, MYSQL_USER, MYSQL_PASSWORD)
        String host = System.getenv("DB_HOST");
        String port = System.getenv("DB_PORT");
        String dbName = System.getenv("DB_NAME");

        if (host != null && !host.isBlank()) {
            if (port == null || port.isBlank()) port = "3306";
            if (dbName == null || dbName.isBlank()) dbName = "smart_bus_db";
            String user = (envUser != null && !envUser.isBlank()) ? envUser : "root";
            String pass = envPass != null ? envPass : "";

            String sslParam = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    ? "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    : "?useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC";

            String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName + sslParam;
            return DriverManager.getConnection(jdbcUrl, user, pass);
        }

        // 4. Default fallback for local development (exact existing credentials)
        String localUrl = "jdbc:mysql://localhost:3306/smart_bus_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        return DriverManager.getConnection(localUrl, "root", "Gpriya@563");
    }
}
