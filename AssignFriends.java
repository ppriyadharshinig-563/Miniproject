import java.sql.*;

public class AssignFriends {
    public static void main(String[] args) {
        String studentSql = "INSERT INTO students (student_id, name, roll_number, department, transport_authorized, assigned_bus_id, pickup_stop) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + "name = VALUES(name), roll_number = VALUES(roll_number), department = VALUES(department), "
                + "transport_authorized = VALUES(transport_authorized), assigned_bus_id = VALUES(assigned_bus_id), pickup_stop = VALUES(pickup_stop)";

        String feeSql = "INSERT INTO fees (student_id, amount, payment_status, payment_date) "
                + "VALUES (?, 12000.00, 'Paid', CURDATE()) "
                + "ON DUPLICATE KEY UPDATE amount = VALUES(amount), payment_status = VALUES(payment_status), payment_date = VALUES(payment_date)";

        Object[][] students = {
            { 201, "Nityaa V", "25CSR201", "B.E - CSE", 1, 1, "Thindal" },
            { 203, "Nivetha K", "25CSR203", "B.E - CSE", 1, 2, "Lakshmi Nagar" },
            { 209, "Poorani R", "25CSR209", "B.E - CSE", 1, 3, "Vijayamangalam" }
        };

        try (Connection con = DBConnection.getConnection()) {
            try (PreparedStatement psStu = con.prepareStatement(studentSql);
                 PreparedStatement psFee = con.prepareStatement(feeSql)) {
                for (Object[] s : students) {
                    psStu.setInt(1, (Integer) s[0]);
                    psStu.setString(2, (String) s[1]);
                    psStu.setString(3, (String) s[2]);
                    psStu.setString(4, (String) s[3]);
                    psStu.setInt(5, (Integer) s[4]);
                    psStu.setInt(6, (Integer) s[5]);
                    psStu.setString(7, (String) s[6]);
                    psStu.executeUpdate();

                    psFee.setInt(1, (Integer) s[0]);
                    psFee.executeUpdate();
                    System.out.println("Assigned Student: " + s[1] + " (" + s[2] + ") -> Bus " + s[5] + " (" + s[6] + ")");
                }
            }

            // Verify count
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT student_id, name, roll_number, assigned_bus_id, pickup_stop FROM students ORDER BY student_id")) {
                System.out.println("\n--- Current Students in DB ---");
                while (rs.next()) {
                    System.out.println(rs.getInt(1) + " | " + rs.getString(2) + " | " + rs.getString(3) + " | Bus " + rs.getObject(4) + " | " + rs.getString(5));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
