
import java.sql.Connection;
import java.sql.PreparedStatement;

public class StudentDBTest {

    public static void main(String[] args) {
        String sql = "INSERT INTO students "
                + "(name, roll_number, department, transport_authorized, pickup_stop) "
                + "VALUES (?, ?, ?, ?, ?)";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, "Demo Student");
            ps.setString(2, "DEMO001");
            ps.setString(3, "CSE");
            ps.setBoolean(4, true);
            ps.setString(5, "Perundurai");

            int rows = ps.executeUpdate();

            System.out.println(rows + " student saved successfully!");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}