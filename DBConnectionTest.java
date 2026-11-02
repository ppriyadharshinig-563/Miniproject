import java.sql.Connection;
import java.sql.SQLException;

public class DBConnectionTest {

    public static void main(String[] args) {
        try (Connection con = DBConnection.getConnection()) {
            System.out.println("MySQL Connected Successfully!");
        } catch (SQLException e) {
            System.out.println("Connection Failed!");
            e.printStackTrace();
        }
    }
}
