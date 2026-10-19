
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class ViewStudents {
    public static void main(String[] args) {

        String url = "jdbc:mysql://localhost:3306/smart_bus_db";
        String user = "root";
        String password = "Gpriya@563";

        String sql = "SELECT * FROM students";

        try (
            Connection con =
                DriverManager.getConnection(url, user, password);
            Statement stmt = con.createStatement();
            ResultSet rs = stmt.executeQuery(sql)
        ) {
            while (rs.next()) {
                System.out.println("Student ID: "
                    + rs.getInt("student_id"));
                System.out.println("Name: "
                    + rs.getString("name"));
                System.out.println("Roll Number: "
                    + rs.getString("roll_number"));
                System.out.println("Department: "
                    + rs.getString("department"));
                System.out.println("--------------------");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}