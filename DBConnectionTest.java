import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBConnectionTest {

    public static void main(String[] args) {

        String url = "jdbc:mysql://localhost:3306/smart_bus_db";
        String user = "root";
        String password = "Gpriya@563";

        try {
            Connection con = DriverManager.getConnection(
                url, user, password
            );

            System.out.println("MySQL Connected Successfully!");

            con.close();

        } catch (SQLException e) {
            System.out.println("Connection Failed!");
            e.printStackTrace();
        }
    }
}
