import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;


/** Small dependency-free API used by the HTML dashboard. */
public class BackendServer {
    private static final Map<Integer, Student> students = new ConcurrentHashMap<>();
    private static final List<CollegeBus> buses = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        seedData();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/health", BackendServer::health);
        server.createContext("/api/students", BackendServer::studentApi);
        server.createContext("/api/buses", BackendServer::busApi);
        server.createContext("/api/routes", BackendServer::routeApi);
        server.createContext("/api/fees", BackendServer::feeApi);
        server.createContext("/api/attendance", BackendServer::attendanceApi);
        server.createContext("/api/requirements", BackendServer::requirementApi);
        server.createContext("/api/manifest", BackendServer::manifestApi);
        server.createContext("/api/breakdowns", BackendServer::breakdownApi);
        server.setExecutor(null);
        server.start();
        System.out.println("Backend running at http://localhost:8080");
    }

    private static void seedData() {
        students.put(101, new Student(101, "Rahul Sharma", "Tambaram", true));
        students.put(102, new Student(102, "Priya Nair", "Chromepet", true));
        students.put(103, new Student(103, "Karthik Raj", "Guindy", false));
        buses.add(new CollegeBus(1, "TN-09-CB-101", 20));
        buses.add(new CollegeBus(2, "TN-09-CB-102", 20));
        buses.add(new CollegeBus(3, "TN-09-CB-103", 20));
    }

    private static void health(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "GET")) return;
        send(exchange, 200, "{\"status\":\"ok\",\"service\":\"smart-transport-backend\"}");
    }

    
private static void studentApi(HttpExchange exchange) throws IOException {
    
exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    if ("OPTIONS".equals(exchange.getRequestMethod())) {
        allow(exchange, "GET");
        return;
    }

    if ("GET".equals(exchange.getRequestMethod())) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;

        String sql = "SELECT student_id, name, pickup_stop, "
                + "transport_authorized FROM students";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                if (!first) json.append(',');
                first = false;

                json.append("{\"id\":")
                    .append(rs.getInt("student_id"))
                    .append(",\"name\":\"")
                    .append(escape(rs.getString("name")))
                    .append("\",\"stop\":\"")
                    .append(escape(rs.getString("pickup_stop")))
                    .append("\",\"authorized\":")
                    .append(rs.getBoolean("transport_authorized"))
                    .append(",\"present\":false,\"busRequired\":false}");
            }

            json.append(']');
            send(exchange, 200, json.toString());

        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500, "{\"error\":\"Database error\"}");
        }
        return;
    }

    if (!allow(exchange, "POST")) return;

    Map<String, String> form = formData(readBody(exchange));

    try {
        int id = Integer.parseInt(form.get("id"));
        String name = form.get("name");
        String stop = form.get("stop");
        boolean authorized = Boolean.parseBoolean(
                form.getOrDefault("authorized", "false"));

        if (name == null || name.isBlank()
                || stop == null || stop.isBlank()) {
            send(exchange, 400,
                    "{\"error\":\"id, name and stop are required\"}");
            return;
        }

        String sql = "INSERT INTO students "
                + "(student_id, name, roll_number, department, "
                + "transport_authorized, pickup_stop) "
                + "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setInt(1, id);
            ps.setString(2, name);
            ps.setString(3, "STU" + id);
            ps.setString(4, "CSE");
            ps.setBoolean(5, authorized);
            ps.setString(6, stop);

            ps.executeUpdate();

            send(exchange, 201,
                    "{\"message\":\"Student saved to database\","
                    + "\"id\":" + id + "}");
        }

    } catch (SQLException e) {
        e.printStackTrace();
        send(exchange, 400,
                "{\"error\":\"Could not save student. "
                + "Check duplicate ID or roll number.\"}");
    } catch (Exception e) {
        send(exchange, 400,
                "{\"error\":\"Invalid student details\"}");
    }
}

    private static void busApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String sql = "SELECT b.bus_id, b.bus_name, COALESCE(b.bus_number, '') AS bus_number, "
                    + "COALESCE(b.bus_type, 'Standard Bus') AS bus_type, "
                    + "COALESCE(b.driver_name, 'Unassigned') AS driver_name, "
                    + "COALESCE(b.driver_phone, '') AS driver_phone, "
                    + "b.capacity, COALESCE(b.route_name, '') AS route_name, "
                    + "COALESCE(b.status, 'Active') AS status, "
                    + "b.route_id, "
                    + "COUNT(DISTINCT s.student_id) AS assigned_count, "
                    + "GROUP_CONCAT(DISTINCT st.stop_name ORDER BY st.stop_order SEPARATOR ' • ') AS stops_list "
                    + "FROM buses b "
                    + "LEFT JOIN students s ON s.assigned_bus_id = b.bus_id "
                    + "LEFT JOIN stops st ON st.route_id = b.route_id "
                    + "GROUP BY b.bus_id, b.bus_name, b.bus_number, b.bus_type, b.driver_name, b.driver_phone, "
                    + "b.capacity, b.route_name, b.status, b.route_id "
                    + "ORDER BY b.bus_id ASC";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                StringBuilder json = new StringBuilder("[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;

                    int cap = rs.getInt("capacity");
                    int assigned = rs.getInt("assigned_count");
                    int avail = Math.max(0, cap - assigned);
                    int pct = cap > 0 ? (int) Math.round((assigned * 100.0) / cap) : 0;

                    json.append("{\"id\":").append(rs.getInt("bus_id"))
                        .append(",\"name\":\"").append(escape(rs.getString("bus_name")))
                        .append("\",\"number\":\"").append(escape(rs.getString("bus_number")))
                        .append("\",\"type\":\"").append(escape(rs.getString("bus_type")))
                        .append("\",\"driver\":\"").append(escape(rs.getString("driver_name")))
                        .append("\",\"phone\":\"").append(escape(rs.getString("driver_phone")))
                        .append("\",\"capacity\":").append(cap)
                        .append(",\"assigned\":").append(assigned)
                        .append(",\"available\":").append(avail)
                        .append(",\"occupancyPercent\":").append(pct)
                        .append(",\"route\":\"").append(escape(rs.getString("route_name")))
                        .append("\",\"stops\":\"").append(escape(rs.getString("stops_list")))
                        .append("\",\"status\":\"").append(escape(rs.getString("status")))
                        .append("\"}");
                }
                json.append(']');
                send(exchange, 200, json.toString());

            } catch (SQLException e) {
                e.printStackTrace();
                send(exchange, 500, "{\"error\":\"Database error while retrieving buses: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        Map<String, String> form = formData(readBody(exchange));
        try {
            String idStr = form.get("id");
            if (idStr == null) idStr = form.get("bus_id");
            String capStr = form.get("capacity");

            if (idStr == null || idStr.isBlank() || capStr == null || capStr.isBlank()) {
                send(exchange, 400, "{\"error\":\"bus id and capacity are required\"}");
                return;
            }

            int busId = Integer.parseInt(idStr);
            int newCapacity = Integer.parseInt(capStr);

            if (newCapacity <= 0) {
                send(exchange, 400, "{\"error\":\"Capacity must be greater than 0\"}");
                return;
            }

            try (Connection con = DBConnection.getConnection()) {
                String checkSql = "SELECT COUNT(*) AS assigned FROM students WHERE assigned_bus_id = ?";
                int assignedCount = 0;
                try (PreparedStatement checkPs = con.prepareStatement(checkSql)) {
                    checkPs.setInt(1, busId);
                    try (ResultSet rs = checkPs.executeQuery()) {
                        if (rs.next()) {
                            assignedCount = rs.getInt("assigned");
                        }
                    }
                }

                if (newCapacity < assignedCount) {
                    send(exchange, 400, "{\"error\":\"Cannot set capacity to " + newCapacity
                            + ". Bus currently has " + assignedCount + " students assigned.\"}");
                    return;
                }

                String updateSql = "UPDATE buses SET capacity = ?, available_seats = GREATEST(0, ? - ?) WHERE bus_id = ?";
                try (PreparedStatement ps = con.prepareStatement(updateSql)) {
                    ps.setInt(1, newCapacity);
                    ps.setInt(2, newCapacity);
                    ps.setInt(3, assignedCount);
                    ps.setInt(4, busId);
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        send(exchange, 404, "{\"error\":\"Bus not found\"}");
                        return;
                    }
                }

                send(exchange, 200, "{\"message\":\"Bus capacity updated successfully\","
                        + "\"busId\":" + busId
                        + ",\"capacity\":" + newCapacity
                        + ",\"assigned\":" + assignedCount
                        + ",\"available\":" + (newCapacity - assignedCount) + "}");
            }

        } catch (NumberFormatException e) {
            send(exchange, 400, "{\"error\":\"Invalid numeric input for bus id or capacity\"}");
        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500, "{\"error\":\"Database error while updating bus capacity: " + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Invalid request data: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void routeApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if (!allow(exchange, "GET")) return;

        String sql = "SELECT r.route_id, r.route_name, COALESCE(r.corridor_name, '') AS corridor_name, "
                + "COALESCE(r.start_location, '') AS start_location, COALESCE(r.end_location, '') AS end_location, "
                + "r.bus_id, COALESCE(b.bus_name, '') AS bus_name, COALESCE(b.bus_number, '') AS bus_number, "
                + "s.stop_id, s.stop_name, s.stop_order, COALESCE(s.scheduled_time, '') AS scheduled_time, "
                + "COALESCE(s.road_type, '') AS road_type "
                + "FROM routes r "
                + "LEFT JOIN buses b ON r.bus_id = b.bus_id "
                + "LEFT JOIN stops s ON r.route_id = s.route_id "
                + "ORDER BY r.route_id ASC, s.stop_order ASC";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            Map<Integer, Map<String, Object>> routeMap = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                int routeId = rs.getInt("route_id");
                Map<String, Object> route = routeMap.computeIfAbsent(routeId, id -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    try {
                        m.put("id", routeId);
                        m.put("name", rs.getString("route_name"));
                        m.put("corridor", rs.getString("corridor_name"));
                        m.put("start", rs.getString("start_location"));
                        m.put("end", rs.getString("end_location"));
                        m.put("busId", rs.getInt("bus_id"));
                        m.put("busName", rs.getString("bus_name"));
                        m.put("busNumber", rs.getString("bus_number"));
                        m.put("stops", new ArrayList<Map<String, Object>>());
                    } catch (SQLException ex) {
                        ex.printStackTrace();
                    }
                    return m;
                });

                int stopId = rs.getInt("stop_id");
                if (stopId > 0 && rs.getString("stop_name") != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> stopsList = (List<Map<String, Object>>) route.get("stops");
                    Map<String, Object> stop = new java.util.LinkedHashMap<>();
                    stop.put("id", stopId);
                    stop.put("name", rs.getString("stop_name"));
                    stop.put("order", rs.getInt("stop_order"));
                    stop.put("time", rs.getString("scheduled_time"));
                    stop.put("road", rs.getString("road_type"));
                    stopsList.add(stop);
                }
            }

            StringBuilder json = new StringBuilder("[");
            boolean firstRoute = true;
            for (Map<String, Object> r : routeMap.values()) {
                if (!firstRoute) json.append(',');
                firstRoute = false;

                json.append("{\"id\":").append(r.get("id"))
                    .append(",\"name\":\"").append(escape((String) r.get("name")))
                    .append("\",\"corridor\":\"").append(escape((String) r.get("corridor")))
                    .append("\",\"start\":\"").append(escape((String) r.get("start")))
                    .append("\",\"end\":\"").append(escape((String) r.get("end")))
                    .append("\",\"busId\":").append(r.get("busId"))
                    .append(",\"busName\":\"").append(escape((String) r.get("busName")))
                    .append("\",\"busNumber\":\"").append(escape((String) r.get("busNumber")))
                    .append("\",\"stops\":[");

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> stops = (List<Map<String, Object>>) r.get("stops");
                boolean firstStop = true;
                for (Map<String, Object> st : stops) {
                    if (!firstStop) json.append(',');
                    firstStop = false;

                    json.append("{\"id\":").append(st.get("id"))
                        .append(",\"name\":\"").append(escape((String) st.get("name")))
                        .append("\",\"order\":").append(st.get("order"))
                        .append(",\"time\":\"").append(escape((String) st.get("time")))
                        .append("\",\"road\":\"").append(escape((String) st.get("road")))
                        .append("\"}");
                }
                json.append("]}");
            }
            json.append(']');
            send(exchange, 200, json.toString());

        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500, "{\"error\":\"Database error while retrieving routes: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void feeApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String sql = "SELECT s.student_id, s.name, s.transport_authorized, s.assigned_bus_id, "
                    + "COALESCE(b.bus_name, 'Unallocated') AS bus_name, "
                    + "f.fee_id, "
                    + "COALESCE(f.amount, 12000.00) AS amount, "
                    + "COALESCE(f.payment_status, CASE WHEN s.transport_authorized = 1 THEN 'Paid' ELSE 'Unpaid' END) AS payment_status, "
                    + "f.payment_date "
                    + "FROM students s "
                    + "LEFT JOIN buses b ON s.assigned_bus_id = b.bus_id "
                    + "LEFT JOIN fees f ON s.student_id = f.student_id "
                    + "ORDER BY s.student_id ASC";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                StringBuilder json = new StringBuilder("[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;

                    int stuId = rs.getInt("student_id");
                    double amt = rs.getDouble("amount");
                    String status = rs.getString("payment_status");
                    Date payDate = rs.getDate("payment_date");
                    boolean auth = rs.getBoolean("transport_authorized");
                    boolean isPaid = "Paid".equalsIgnoreCase(status);

                    json.append("{\"id\":").append(stuId)
                        .append(",\"studentId\":").append(stuId)
                        .append(",\"name\":\"").append(escape(rs.getString("name")))
                        .append("\",\"bus\":\"").append(escape(rs.getString("bus_name")))
                        .append("\",\"amount\":").append(amt)
                        .append(",\"status\":\"").append(escape(status))
                        .append("\",\"date\":\"").append(payDate != null ? payDate.toString() : "")
                        .append("\",\"cleared\":").append(isPaid || auth)
                        .append('}');
                }
                json.append(']');
                send(exchange, 200, json.toString());

            } catch (SQLException e) {
                e.printStackTrace();
                send(exchange, 500, "{\"error\":\"Database error while retrieving fees: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        Map<String, String> form = formData(readBody(exchange));
        try {
            String idStr = form.get("id");
            if (idStr == null) idStr = form.get("student_id");
            if (idStr == null || idStr.isBlank()) {
                send(exchange, 400, "{\"error\":\"student_id is required\"}");
                return;
            }

            int studentId = Integer.parseInt(idStr);

            String amountStr = form.getOrDefault("amount", "12000");
            double amount = Double.parseDouble(amountStr);
            if (amount <= 0) {
                send(exchange, 400, "{\"error\":\"Fee amount must be greater than 0\"}");
                return;
            }

            String status = form.getOrDefault("status", "Paid").trim();
            if (!"Paid".equalsIgnoreCase(status) && !"Unpaid".equalsIgnoreCase(status) && !"Pending".equalsIgnoreCase(status)) {
                status = "Paid";
            }
            status = Character.toUpperCase(status.charAt(0)) + status.substring(1).toLowerCase();

            boolean isPaid = "Paid".equalsIgnoreCase(status);

            String dateStr = form.get("date");
            Date paymentDate = null;
            if (isPaid) {
                if (dateStr != null && !dateStr.isBlank()) {
                    paymentDate = Date.valueOf(dateStr);
                } else {
                    paymentDate = Date.valueOf(LocalDate.now());
                }
            } else if (dateStr != null && !dateStr.isBlank()) {
                paymentDate = Date.valueOf(dateStr);
            }

            try (Connection con = DBConnection.getConnection()) {
                // Check that student exists
                String checkSql = "SELECT student_id FROM students WHERE student_id = ?";
                try (PreparedStatement checkPs = con.prepareStatement(checkSql)) {
                    checkPs.setInt(1, studentId);
                    try (ResultSet rs = checkPs.executeQuery()) {
                        if (!rs.next()) {
                            send(exchange, 404, "{\"error\":\"Student not found with ID " + studentId + "\"}");
                            return;
                        }
                    }
                }

                String upsertSql = "INSERT INTO fees (student_id, amount, payment_status, payment_date) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE amount = VALUES(amount), payment_status = VALUES(payment_status), payment_date = VALUES(payment_date)";

                try (PreparedStatement ps = con.prepareStatement(upsertSql)) {
                    ps.setInt(1, studentId);
                    ps.setDouble(2, amount);
                    ps.setString(3, status);
                    if (paymentDate != null) {
                        ps.setDate(4, paymentDate);
                    } else {
                        ps.setNull(4, Types.DATE);
                    }
                    ps.executeUpdate();
                }

                // Synchronize transport_authorized on students
                String syncStudentSql = "UPDATE students SET transport_authorized = ? WHERE student_id = ?";
                try (PreparedStatement ps = con.prepareStatement(syncStudentSql)) {
                    ps.setBoolean(1, isPaid);
                    ps.setInt(2, studentId);
                    ps.executeUpdate();
                }

                send(exchange, 200, "{\"message\":\"Fee payment updated successfully\","
                        + "\"studentId\":" + studentId
                        + ",\"amount\":" + amount
                        + ",\"status\":\"" + status + "\""
                        + ",\"date\":\"" + (paymentDate != null ? paymentDate.toString() : "") + "\""
                        + ",\"cleared\":" + isPaid + "}");
            }

        } catch (NumberFormatException e) {
            send(exchange, 400, "{\"error\":\"Invalid numeric input for student_id or amount\"}");
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":\"Invalid date format. Expected YYYY-MM-DD\"}");
        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500, "{\"error\":\"Database error while updating fee: " + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Invalid request data: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void attendanceApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String query = exchange.getRequestURI().getQuery();
            String dateParam = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2 && "date".equals(pair[0])) {
                        dateParam = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                }
            }
            if (dateParam == null || dateParam.isBlank()) {
                dateParam = LocalDate.now().toString();
            }

            Date targetDate;
            try {
                targetDate = Date.valueOf(dateParam);
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "{\"error\":\"Invalid date format. Expected YYYY-MM-DD\"}");
                return;
            }

            String sql = "SELECT s.student_id, s.name, s.pickup_stop, s.transport_authorized, s.assigned_bus_id, "
                    + "COALESCE(b.bus_name, 'Unallocated') AS bus_name, "
                    + "a.attendance_id, a.attendance_date, "
                    + "COALESCE(a.status, 'Present') AS attendance_status, "
                    + "COALESCE(a.boarded, 0) AS boarded "
                    + "FROM students s "
                    + "LEFT JOIN buses b ON s.assigned_bus_id = b.bus_id "
                    + "LEFT JOIN attendance a ON s.student_id = a.student_id AND a.attendance_date = ? "
                    + "ORDER BY s.student_id ASC";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setDate(1, targetDate);
                try (ResultSet rs = ps.executeQuery()) {
                    StringBuilder json = new StringBuilder("[");
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(',');
                        first = false;

                        json.append("{\"id\":").append(rs.getInt("student_id"))
                            .append(",\"name\":\"").append(escape(rs.getString("name")))
                            .append("\",\"bus\":\"").append(escape(rs.getString("bus_name")))
                            .append("\",\"stop\":\"").append(escape(rs.getString("pickup_stop")))
                            .append("\",\"authorized\":").append(rs.getBoolean("transport_authorized"))
                            .append(",\"date\":\"").append(targetDate.toString())
                            .append("\",\"status\":\"").append(escape(rs.getString("attendance_status")))
                            .append("\",\"boarded\":").append(rs.getBoolean("boarded"))
                            .append('}');
                    }
                    json.append(']');
                    send(exchange, 200, json.toString());
                }
            } catch (SQLException e) {
                e.printStackTrace();
                send(exchange, 500, "{\"error\":\"Database error while retrieving attendance: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        Map<String, String> form = formData(readBody(exchange));
        try {
            String idStr = form.get("id");
            if (idStr == null) idStr = form.get("student_id");
            if (idStr == null || idStr.isBlank()) {
                send(exchange, 400, "{\"error\":\"Student id is required\"}");
                return;
            }
            int studentId = Integer.parseInt(idStr);

            String dateStr = form.get("date");
            if (dateStr == null || dateStr.isBlank()) {
                dateStr = LocalDate.now().toString();
            }
            Date attendanceDate = Date.valueOf(dateStr);

            String status = form.getOrDefault("status", "Present");
            if (!"Present".equalsIgnoreCase(status) && !"Absent".equalsIgnoreCase(status)) {
                status = "Present";
            }
            status = Character.toUpperCase(status.charAt(0)) + status.substring(1).toLowerCase();

            boolean boarded = false;
            String boardedStr = form.get("boarded");
            if (boardedStr != null) {
                boarded = "true".equalsIgnoreCase(boardedStr) || "1".equals(boardedStr) || "yes".equalsIgnoreCase(boardedStr);
            }

            String sql = "INSERT INTO attendance (student_id, attendance_date, status, boarded) "
                    + "VALUES (?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE status = VALUES(status), boarded = VALUES(boarded)";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setInt(1, studentId);
                ps.setDate(2, attendanceDate);
                ps.setString(3, status);
                ps.setBoolean(4, boarded);

                ps.executeUpdate();

                send(exchange, 200, "{\"message\":\"Attendance recorded successfully\","
                        + "\"studentId\":" + studentId
                        + ",\"date\":\"" + attendanceDate.toString() + "\""
                        + ",\"status\":\"" + status + "\""
                        + ",\"boarded\":" + boarded + "}");
            }
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":\"Invalid date format or parameters: " + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500, "{\"error\":\"Database error while saving attendance: " + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Invalid attendance data: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void requirementApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String query = exchange.getRequestURI().getQuery();
            String dateParam = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2 && "date".equals(pair[0])) {
                        dateParam = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                }
            }
            if (dateParam == null || dateParam.isBlank()) {
                dateParam = LocalDate.now().plusDays(1).toString();
            }

            Date targetDate;
            try {
                targetDate = Date.valueOf(dateParam);
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "{\"error\":\"Invalid date format. Expected YYYY-MM-DD\"}");
                return;
            }

            String sql = "SELECT s.student_id, s.name, s.pickup_stop, s.transport_authorized, s.assigned_bus_id, "
                    + "COALESCE(b.bus_name, 'Unallocated') AS bus_name, "
                    + "d.demand_id, d.demand_date, "
                    + "COALESCE(d.bus_required, 1) AS bus_required "
                    + "FROM students s "
                    + "LEFT JOIN buses b ON s.assigned_bus_id = b.bus_id "
                    + "LEFT JOIN demands d ON s.student_id = d.student_id AND d.demand_date = ? "
                    + "ORDER BY s.student_id ASC";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setDate(1, targetDate);
                try (ResultSet rs = ps.executeQuery()) {
                    StringBuilder json = new StringBuilder("[");
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(',');
                        first = false;

                        json.append("{\"id\":").append(rs.getInt("student_id"))
                            .append(",\"name\":\"").append(escape(rs.getString("name")))
                            .append("\",\"bus\":\"").append(escape(rs.getString("bus_name")))
                            .append("\",\"stop\":\"").append(escape(rs.getString("pickup_stop")))
                            .append("\",\"authorized\":").append(rs.getBoolean("transport_authorized"))
                            .append(",\"date\":\"").append(targetDate.toString())
                            .append("\",\"busRequired\":").append(rs.getInt("bus_required") == 1)
                            .append('}');
                    }
                    json.append(']');
                    send(exchange, 200, json.toString());
                }
            } catch (SQLException e) {
                e.printStackTrace();
                send(exchange, 500, "{\"error\":\"Database error while retrieving tomorrow demand: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        Map<String, String> form = formData(readBody(exchange));
        try {
            String idStr = form.get("id");
            if (idStr == null) idStr = form.get("student_id");
            if (idStr == null || idStr.isBlank()) {
                send(exchange, 400, "{\"error\":\"Student id is required\"}");
                return;
            }
            int studentId = Integer.parseInt(idStr);

            String dateStr = form.get("date");
            if (dateStr == null || dateStr.isBlank()) {
                dateStr = LocalDate.now().plusDays(1).toString();
            }
            Date demandDate = Date.valueOf(dateStr);

            String reqStr = form.get("required");
            if (reqStr == null) reqStr = form.get("value");
            if (reqStr == null) reqStr = form.get("bus_required");
            boolean busRequired = true;
            if (reqStr != null) {
                busRequired = "true".equalsIgnoreCase(reqStr) || "1".equals(reqStr) || "yes".equalsIgnoreCase(reqStr);
            }

            // 1. Save / Update in demands table
            String demandSql = "INSERT INTO demands (student_id, demand_date, bus_required) "
                    + "VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE bus_required = VALUES(bus_required)";

            // 2. Sync to attendance table travel_intention
            String attendanceSql = "INSERT INTO attendance (student_id, attendance_date, travel_intention, status, boarded) "
                    + "VALUES (?, ?, ?, 'Present', 0) "
                    + "ON DUPLICATE KEY UPDATE travel_intention = VALUES(travel_intention)";

            try (Connection con = DBConnection.getConnection()) {
                try (PreparedStatement psDemand = con.prepareStatement(demandSql)) {
                    psDemand.setInt(1, studentId);
                    psDemand.setDate(2, demandDate);
                    psDemand.setInt(3, busRequired ? 1 : 0);
                    psDemand.executeUpdate();
                }

                try (PreparedStatement psAtt = con.prepareStatement(attendanceSql)) {
                    psAtt.setInt(1, studentId);
                    psAtt.setDate(2, demandDate);
                    psAtt.setInt(3, busRequired ? 1 : 0);
                    psAtt.executeUpdate();
                }

                send(exchange, 200, "{\"message\":\"Tomorrow demand recorded successfully\","
                        + "\"studentId\":" + studentId
                        + ",\"date\":\"" + demandDate.toString() + "\""
                        + ",\"busRequired\":" + busRequired + "}");
            }
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":\"Invalid date or parameter format: " + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500, "{\"error\":\"Database error while saving demand: " + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Invalid demand data: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void manifestApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String query = exchange.getRequestURI().getQuery();
            int busId = 1;
            String dateParam = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2) {
                        if ("bus_id".equals(pair[0]) || "id".equals(pair[0]) || "bus".equals(pair[0])) {
                            try {
                                busId = Integer.parseInt(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                            } catch (NumberFormatException ignored) {}
                        } else if ("date".equals(pair[0])) {
                            dateParam = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        }
                    }
                }
            }

            if (dateParam == null || dateParam.isBlank()) {
                dateParam = LocalDate.now().toString();
            }

            Date todayDate;
            try {
                todayDate = Date.valueOf(dateParam);
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "{\"error\":\"Invalid date format. Expected YYYY-MM-DD\"}");
                return;
            }
            Date tomorrowDate = Date.valueOf(todayDate.toLocalDate().plusDays(1));

            String busSql = "SELECT bus_id, bus_name, COALESCE(bus_number, '') AS bus_number, "
                    + "COALESCE(driver_name, 'Unassigned') AS driver_name, "
                    + "COALESCE(driver_phone, '') AS driver_phone, capacity, "
                    + "COALESCE(route_name, '') AS route_name "
                    + "FROM buses WHERE bus_id = ?";

            String studentSql = "SELECT s.student_id, s.name, s.roll_number, s.department, s.pickup_stop, "
                    + "s.transport_authorized, s.assigned_bus_id, "
                    + "COALESCE(a.boarded, 0) AS boarded, "
                    + "COALESCE(d.bus_required, 0) AS declared_tomorrow "
                    + "FROM students s "
                    + "LEFT JOIN attendance a ON s.student_id = a.student_id AND a.attendance_date = ? "
                    + "LEFT JOIN demands d ON s.student_id = d.student_id AND d.demand_date = ? "
                    + "WHERE s.assigned_bus_id = ? "
                    + "ORDER BY s.student_id ASC";

            try (Connection con = DBConnection.getConnection()) {
                String busName = "Bus " + busId;
                String busNumber = "";
                String driverName = "Driver " + busId;
                String driverPhone = "";
                String routeName = "";
                int capacity = 20;

                try (PreparedStatement psBus = con.prepareStatement(busSql)) {
                    psBus.setInt(1, busId);
                    try (ResultSet rsBus = psBus.executeQuery()) {
                        if (rsBus.next()) {
                            busName = rsBus.getString("bus_name");
                            busNumber = rsBus.getString("bus_number");
                            driverName = rsBus.getString("driver_name");
                            driverPhone = rsBus.getString("driver_phone");
                            capacity = rsBus.getInt("capacity");
                            routeName = rsBus.getString("route_name");
                        }
                    }
                }

                int totalAllocated = 0;
                int totalBoarded = 0;
                int totalAuthorized = 0;
                StringBuilder studentsJson = new StringBuilder("[");

                try (PreparedStatement psStu = con.prepareStatement(studentSql)) {
                    psStu.setDate(1, todayDate);
                    psStu.setDate(2, tomorrowDate);
                    psStu.setInt(3, busId);

                    try (ResultSet rsStu = psStu.executeQuery()) {
                        boolean first = true;
                        int seatIndex = 1;
                        while (rsStu.next()) {
                            if (!first) studentsJson.append(',');
                            first = false;

                            boolean auth = rsStu.getBoolean("transport_authorized");
                            boolean boarded = rsStu.getBoolean("boarded");
                            boolean declaredTomorrow = rsStu.getInt("declared_tomorrow") == 1;

                            totalAllocated++;
                            if (auth) {
                                totalAuthorized++;
                                if (boarded) totalBoarded++;
                            }

                            String seatStr = String.format("%02d", seatIndex++);

                            studentsJson.append("{\"seatNo\":\"").append(seatStr)
                                .append("\",\"id\":").append(rsStu.getInt("student_id"))
                                .append(",\"name\":\"").append(escape(rsStu.getString("name")))
                                .append("\",\"rollNumber\":\"").append(escape(rsStu.getString("roll_number")))
                                .append("\",\"department\":\"").append(escape(rsStu.getString("department")))
                                .append("\",\"stop\":\"").append(escape(rsStu.getString("pickup_stop")))
                                .append("\",\"authorized\":").append(auth)
                                .append(",\"declaredTomorrow\":").append(declaredTomorrow)
                                .append(",\"boarded\":").append(boarded)
                                .append('}');
                        }
                    }
                }
                studentsJson.append(']');

                int seatsVacant = Math.max(0, capacity - totalAllocated);

                StringBuilder json = new StringBuilder("{");
                json.append("\"busId\":").append(busId)
                    .append(",\"busName\":\"").append(escape(busName))
                    .append("\",\"busNumber\":\"").append(escape(busNumber))
                    .append("\",\"driverName\":\"").append(escape(driverName))
                    .append("\",\"driverPhone\":\"").append(escape(driverPhone))
                    .append("\",\"routeName\":\"").append(escape(routeName))
                    .append("\",\"capacity\":").append(capacity)
                    .append(",\"totalAllocated\":").append(totalAllocated)
                    .append(",\"totalAuthorized\":").append(totalAuthorized)
                    .append(",\"totalBoarded\":").append(totalBoarded)
                    .append(",\"seatsVacant\":").append(seatsVacant)
                    .append(",\"date\":\"").append(todayDate.toString())
                    .append("\",\"tomorrowDate\":\"").append(tomorrowDate.toString())
                    .append("\",\"students\":").append(studentsJson.toString())
                    .append('}');

                send(exchange, 200, json.toString());

            } catch (SQLException e) {
                e.printStackTrace();
                send(exchange, 500, "{\"error\":\"Database error while retrieving bus manifest: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        Map<String, String> form = formData(readBody(exchange));
        try {
            String idStr = form.get("id");
            if (idStr == null) idStr = form.get("student_id");
            if (idStr == null || idStr.isBlank()) {
                send(exchange, 400, "{\"error\":\"Student id is required\"}");
                return;
            }
            int studentId = Integer.parseInt(idStr);

            String dateStr = form.get("date");
            if (dateStr == null || dateStr.isBlank()) {
                dateStr = LocalDate.now().toString();
            }
            Date attendanceDate = Date.valueOf(dateStr);

            String boardedStr = form.get("boarded");
            boolean boarded = "true".equalsIgnoreCase(boardedStr) || "1".equals(boardedStr) || "yes".equalsIgnoreCase(boardedStr);

            String sql = "INSERT INTO attendance (student_id, attendance_date, status, boarded) "
                    + "VALUES (?, ?, 'Present', ?) "
                    + "ON DUPLICATE KEY UPDATE status = 'Present', boarded = VALUES(boarded)";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setInt(1, studentId);
                ps.setDate(2, attendanceDate);
                ps.setBoolean(3, boarded);
                ps.executeUpdate();

                send(exchange, 200, "{\"message\":\"Boarding status updated successfully\","
                        + "\"studentId\":" + studentId
                        + ",\"date\":\"" + attendanceDate.toString() + "\""
                        + ",\"boarded\":" + boarded + "}");
            }
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":\"Invalid date or parameter format: " + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500, "{\"error\":\"Database error while updating boarding: " + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Invalid manifest data: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void breakdownApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String activeBreakdownsSql = "SELECT bd.breakdown_id, bd.broken_bus_id, bd.replacement_bus_id, "
                    + "bd.delay_minutes, bd.status, bd.reported_at, "
                    + "b_broken.bus_name AS broken_bus_name, b_broken.route_name AS broken_route_name, "
                    + "b_broken.bus_number AS broken_bus_number, b_broken.driver_name AS broken_driver_name, "
                    + "b_rep.bus_name AS rep_bus_name, b_rep.bus_number AS rep_bus_number, "
                    + "b_rep.driver_name AS rep_driver_name "
                    + "FROM breakdowns bd "
                    + "JOIN buses b_broken ON bd.broken_bus_id = b_broken.bus_id "
                    + "JOIN buses b_rep ON bd.replacement_bus_id = b_rep.bus_id "
                    + "WHERE bd.status = 'Active' "
                    + "ORDER BY bd.reported_at DESC";

            String busesSql = "SELECT b.bus_id, b.bus_name, b.bus_number, b.driver_name, b.capacity, "
                    + "b.route_name, b.bus_type, b.status, "
                    + "COALESCE(bd.delay_minutes, 0) AS active_delay, "
                    + "bd.breakdown_id "
                    + "FROM buses b "
                    + "LEFT JOIN breakdowns bd ON b.bus_id = bd.broken_bus_id AND bd.status = 'Active' "
                    + "ORDER BY b.bus_id ASC";

            try (Connection con = DBConnection.getConnection()) {
                boolean standbyAvailable = true;
                int standbyBusId = 4;
                StringBuilder activeJson = new StringBuilder("[");
                boolean firstBd = true;

                try (PreparedStatement psBd = con.prepareStatement(activeBreakdownsSql);
                     ResultSet rsBd = psBd.executeQuery()) {
                    while (rsBd.next()) {
                        standbyAvailable = false;
                        if (!firstBd) activeJson.append(',');
                        firstBd = false;

                        activeJson.append("{\"breakdownId\":").append(rsBd.getInt("breakdown_id"))
                            .append(",\"brokenBusId\":").append(rsBd.getInt("broken_bus_id"))
                            .append(",\"brokenBusName\":\"").append(escape(rsBd.getString("broken_bus_name")))
                            .append("\",\"brokenRoute\":\"").append(escape(rsBd.getString("broken_route_name")))
                            .append("\",\"brokenBusNumber\":\"").append(escape(rsBd.getString("broken_bus_number")))
                            .append("\",\"brokenDriverName\":\"").append(escape(rsBd.getString("broken_driver_name")))
                            .append("\",\"replacementBusId\":").append(rsBd.getInt("replacement_bus_id"))
                            .append(",\"replacementBusName\":\"").append(escape(rsBd.getString("rep_bus_name")))
                            .append("\",\"replacementBusNumber\":\"").append(escape(rsBd.getString("rep_bus_number")))
                            .append("\",\"replacementDriverName\":\"").append(escape(rsBd.getString("rep_driver_name")))
                            .append("\",\"delayMinutes\":").append(rsBd.getInt("delay_minutes"))
                            .append(",\"status\":\"").append(escape(rsBd.getString("status")))
                            .append("\",\"reportedAt\":\"").append(rsBd.getTimestamp("reported_at").toString())
                            .append("\"}");
                    }
                }
                activeJson.append(']');

                StringBuilder fleetJson = new StringBuilder("[");
                boolean firstBus = true;
                try (PreparedStatement psFleet = con.prepareStatement(busesSql);
                     ResultSet rsFleet = psFleet.executeQuery()) {
                    while (rsFleet.next()) {
                        if (!firstBus) fleetJson.append(',');
                        firstBus = false;

                        fleetJson.append("{\"id\":").append(rsFleet.getInt("bus_id"))
                            .append(",\"name\":\"").append(escape(rsFleet.getString("bus_name")))
                            .append("\",\"number\":\"").append(escape(rsFleet.getString("bus_number")))
                            .append("\",\"driver\":\"").append(escape(rsFleet.getString("driver_name")))
                            .append("\",\"capacity\":").append(rsFleet.getInt("capacity"))
                            .append(",\"route\":\"").append(escape(rsFleet.getString("route_name")))
                            .append("\",\"type\":\"").append(escape(rsFleet.getString("bus_type")))
                            .append("\",\"status\":\"").append(escape(rsFleet.getString("status")))
                            .append("\",\"delayMinutes\":").append(rsFleet.getInt("active_delay"))
                            .append(",\"breakdownId\":").append(rsFleet.getObject("breakdown_id") == null ? "null" : rsFleet.getInt("breakdown_id"))
                            .append('}');
                    }
                }
                fleetJson.append(']');

                String json = "{\"standbyAvailable\":" + standbyAvailable
                        + ",\"standbyBusId\":" + standbyBusId
                        + ",\"activeBreakdowns\":" + activeJson.toString()
                        + ",\"fleet\":" + fleetJson.toString() + "}";

                send(exchange, 200, json);
            } catch (SQLException e) {
                e.printStackTrace();
                send(exchange, 500, "{\"error\":\"Database error while retrieving breakdowns: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        Map<String, String> form = formData(readBody(exchange));
        String query = exchange.getRequestURI().getQuery();
        boolean isResolve = (query != null && query.contains("action=resolve"))
                || "resolve".equalsIgnoreCase(form.get("action"));

        try (Connection con = DBConnection.getConnection()) {
            if (isResolve) {
                String busIdStr = form.get("bus_id");
                if (busIdStr == null) busIdStr = form.get("id");
                if (busIdStr == null || busIdStr.isBlank()) {
                    send(exchange, 400, "{\"error\":\"bus_id is required to resolve incident\"}");
                    return;
                }
                int brokenBusId = Integer.parseInt(busIdStr);

                String resolveBdSql = "UPDATE breakdowns SET status = 'Resolved', resolved_at = CURRENT_TIMESTAMP "
                        + "WHERE broken_bus_id = ? AND status = 'Active'";
                String restoreBusSql = "UPDATE buses SET status = 'Active' WHERE bus_id = ?";
                String restoreRepSql = "UPDATE buses SET status = 'Standby Depot' WHERE bus_id = 4";

                try (PreparedStatement ps1 = con.prepareStatement(resolveBdSql);
                     PreparedStatement ps2 = con.prepareStatement(restoreBusSql);
                     PreparedStatement ps3 = con.prepareStatement(restoreRepSql)) {
                    ps1.setInt(1, brokenBusId);
                    int updated = ps1.executeUpdate();
                    if (updated == 0) {
                        send(exchange, 404, "{\"error\":\"No active breakdown found for Bus #" + brokenBusId + "\"}");
                        return;
                    }
                    ps2.setInt(1, brokenBusId);
                    ps2.executeUpdate();
                    ps3.executeUpdate();

                    send(exchange, 200, "{\"message\":\"Breakdown resolved successfully. Regular bus restored to Active and Standby Mini Bus returned to depot.\","
                            + "\"busId\":" + brokenBusId + "}");
                }
                return;
            }

            String busIdStr = form.get("bus_id");
            if (busIdStr == null) busIdStr = form.get("id");
            if (busIdStr == null || busIdStr.isBlank()) {
                send(exchange, 400, "{\"error\":\"Please select the affected vehicle experiencing issue.\"}");
                return;
            }
            int brokenBusId = Integer.parseInt(busIdStr);

            String repIdStr = form.get("replacement_id");
            int replacementBusId = 4;
            if (repIdStr != null && !repIdStr.isBlank()) {
                try { replacementBusId = Integer.parseInt(repIdStr); } catch (NumberFormatException ignored) {}
            }

            String delayStr = form.get("delay");
            int delayMinutes = 20;
            if (delayStr != null && !delayStr.isBlank()) {
                try { delayMinutes = Integer.parseInt(delayStr); } catch (NumberFormatException ignored) {}
            }
            if (delayMinutes < 5 || delayMinutes > 120) {
                send(exchange, 400, "{\"error\":\"Delay duration must be between 5 and 120 minutes.\"}");
                return;
            }

            if (brokenBusId == replacementBusId) {
                send(exchange, 400, "{\"error\":\"The replacement shuttle cannot be the same as the affected vehicle.\"}");
                return;
            }

            String checkStandbySql = "SELECT bd.breakdown_id, b.bus_name FROM breakdowns bd "
                    + "JOIN buses b ON bd.broken_bus_id = b.bus_id "
                    + "WHERE bd.replacement_bus_id = ? AND bd.status = 'Active'";
            try (PreparedStatement psCheck = con.prepareStatement(checkStandbySql)) {
                psCheck.setInt(1, replacementBusId);
                try (ResultSet rsCheck = psCheck.executeQuery()) {
                    if (rsCheck.next()) {
                        String currentBroken = rsCheck.getString("bus_name");
                        send(exchange, 409, "{\"error\":\"Standby Mini Bus is already deployed for an active breakdown on "
                                + escape(currentBroken) + ". It cannot be assigned to multiple simultaneous incidents.\"}");
                        return;
                    }
                }
            }

            String checkBrokenSql = "SELECT breakdown_id FROM breakdowns WHERE broken_bus_id = ? AND status = 'Active'";
            try (PreparedStatement psBroken = con.prepareStatement(checkBrokenSql)) {
                psBroken.setInt(1, brokenBusId);
                try (ResultSet rsBroken = psBroken.executeQuery()) {
                    if (rsBroken.next()) {
                        send(exchange, 400, "{\"error\":\"Bus #" + brokenBusId + " already has an active breakdown reported.\"}");
                        return;
                    }
                }
            }

            String verifyBusSql = "SELECT bus_name FROM buses WHERE bus_id = ?";
            String brokenBusName = "Bus " + brokenBusId;
            try (PreparedStatement psVerify = con.prepareStatement(verifyBusSql)) {
                psVerify.setInt(1, brokenBusId);
                try (ResultSet rsV = psVerify.executeQuery()) {
                    if (!rsV.next()) {
                        send(exchange, 404, "{\"error\":\"Selected bus #" + brokenBusId + " does not exist in database.\"}");
                        return;
                    }
                    brokenBusName = rsV.getString("bus_name");
                }
            }

            String insertBdSql = "INSERT INTO breakdowns (broken_bus_id, replacement_bus_id, delay_minutes, status) "
                    + "VALUES (?, ?, ?, 'Active')";
            String updateBrokenSql = "UPDATE buses SET status = 'Breakdown Reported' WHERE bus_id = ?";
            String updateRepSql = "UPDATE buses SET status = 'Deployed (Relief)' WHERE bus_id = ?";

            con.setAutoCommit(false);
            try (PreparedStatement psIns = con.prepareStatement(insertBdSql, java.sql.Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement psUpBroken = con.prepareStatement(updateBrokenSql);
                 PreparedStatement psUpRep = con.prepareStatement(updateRepSql)) {

                psIns.setInt(1, brokenBusId);
                psIns.setInt(2, replacementBusId);
                psIns.setInt(3, delayMinutes);
                psIns.executeUpdate();

                int newBdId = 0;
                try (ResultSet generatedKeys = psIns.getGeneratedKeys()) {
                    if (generatedKeys.next()) newBdId = generatedKeys.getInt(1);
                }

                psUpBroken.setInt(1, brokenBusId);
                psUpBroken.executeUpdate();

                psUpRep.setInt(1, replacementBusId);
                psUpRep.executeUpdate();

                con.commit();

                send(exchange, 200, "{\"message\":\"Standby Mini Bus successfully deployed and " + delayMinutes + " minute delay notice broadcasted to student transit hub.\","
                        + "\"breakdownId\":" + newBdId
                        + ",\"brokenBusId\":" + brokenBusId
                        + ",\"brokenBusName\":\"" + escape(brokenBusName) + "\""
                        + ",\"replacementBusId\":" + replacementBusId
                        + ",\"delayMinutes\":" + delayMinutes + "}");
            } catch (SQLException ex) {
                con.rollback();
                throw ex;
            } finally {
                con.setAutoCommit(true);
            }

        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":\"Invalid parameter format: " + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            e.printStackTrace();
            send(exchange, 500, "{\"error\":\"Database error while processing breakdown: " + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Failed to process breakdown request: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static String studentJson(Student s) {
        return "{\"id\":" + s.getStudentId() + ",\"name\":\"" + escape(s.getName())
            + "\",\"stop\":\"" + escape(s.getPickupStop()) + "\",\"authorized\":"
            + s.isTransportAuthorized() + ",\"present\":" + s.isPresent()
            + ",\"busRequired\":" + s.isBusRequired() + "}";
    }

    private static boolean allow(HttpExchange exchange, String method) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(exchange.getRequestMethod())) { send(exchange, 204, ""); return false; }
        if (!method.equals(exchange.getRequestMethod())) { send(exchange, 405, "{\"error\":\"Method not allowed\"}"); return false; }
        return true;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> formData(String body) {
        Map<String, String> result = new java.util.HashMap<>();
        for (String item : body.split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2) result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
