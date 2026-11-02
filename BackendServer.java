import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.*;
import java.sql.Date;
import java.time.LocalDate;

/**
 * BackendServer: Dependency-free HTTP REST API server backed by pure JDBC and MySQL (smart_bus_db).
 * Features:
 * - Role-Based Access Control (RBAC): Admin, Driver, and Student roles with session token verification.
 * - 403 Forbidden enforcement on unauthorized student write requests and cross-student data access.
 * - Dedicated Student APIs for the 7 student portal features.
 * - Dijkstra's Algorithm backed by java.util.PriorityQueue for Erode district routes.
 * - Dynamic road traffic delay updates and real-time Dijkstra rerouting.
 * - Bus breakdown triage (onboard vs waiting students) with seating capacity evaluation.
 */
public class BackendServer {

    // In-memory seed fallback
    private static final Map<Integer, Student> students = new ConcurrentHashMap<>();
    private static final List<CollegeBus> buses = new ArrayList<>();

    // Session registry for RBAC
    public static class UserSession {
        public final String token;
        public final String role; // "admin", "driver", "student"
        public final int studentId;
        public final int busId;
        public final String name;

        public UserSession(String token, String role, int studentId, String name) {
            this(token, role, studentId, 0, name);
        }

        public UserSession(String token, String role, int studentId, int busId, String name) {
            this.token = token;
            this.role = role;
            this.studentId = studentId;
            this.busId = busId;
            this.name = name;
        }
    }

    private static final Map<String, UserSession> activeSessions = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        seedData();
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                port = Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {}
        }
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        } catch (java.net.BindException be) {
            System.err.println("\n=======================================================");
            System.err.println("[ERROR] Port " + port + " is already in use by another process.");
            System.err.println("=======================================================");
            System.err.println("To free port " + port + " in PowerShell, run:");
            System.err.println("  Get-NetTCPConnection -LocalPort " + port + " -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }");
            System.err.println("\nOr start the server on a different port (e.g. " + (port + 1) + "):");
            System.err.println("  java -cp \".;lib/mysql-connector-j-26.7.0/mysql-connector-j-26.7.0.jar\" BackendServer " + (port + 1));
            System.err.println("=======================================================\n");
            throw be;
        }

        // Public / Shared Endpoints
        server.createContext("/api/health", BackendServer::health);
        server.createContext("/api/auth/login", BackendServer::authLoginApi);

        // Core Admin & Fleet Endpoints
        server.createContext("/api/students", BackendServer::studentApi);
        server.createContext("/api/buses", BackendServer::busApi);
        server.createContext("/api/routes", BackendServer::routeApi);
        server.createContext("/api/stops", BackendServer::stopsApi);
        server.createContext("/api/fees", BackendServer::feeApi);
        server.createContext("/api/attendance/scan", BackendServer::attendanceScanApi);
        server.createContext("/api/attendance", BackendServer::attendanceApi);
        server.createContext("/api/requirements", BackendServer::requirementApi);
        server.createContext("/api/manifest", BackendServer::manifestApi);
        server.createContext("/api/breakdowns", BackendServer::breakdownApi);
        server.createContext("/api/dashboard", BackendServer::dashboardApi);
        server.createContext("/api/complaints", BackendServer::complaintApi);

        // Dijkstra, Traffic, Road Graph & Map Endpoints
        server.createContext("/api/routes/dijkstra", BackendServer::dijkstraApi);
        server.createContext("/api/route/dijkstra", BackendServer::dijkstraApi);
        server.createContext("/api/traffic/update", BackendServer::trafficUpdateApi);
        server.createContext("/api/graph/edge", BackendServer::graphEdgeTrafficApi);
        server.createContext("/api/road-network", BackendServer::roadNetworkApi);
        server.createContext("/api/breakdowns/calculate-route", BackendServer::breakdownCalculateRouteApi);
        server.createContext("/api/breakdown", BackendServer::breakdownPostApi);
        server.createContext("/api/map/config", BackendServer::mapConfigApi);

        // Student-Specific Portal Endpoints (Role-Enforced)
        server.createContext("/api/student/dashboard", BackendServer::studentDashboardApi);
        server.createContext("/api/student/attendance", BackendServer::studentAttendanceApi);
        server.createContext("/api/student/route", BackendServer::studentRouteApi);
        server.createContext("/api/student/alerts", BackendServer::studentAlertsApi);
        server.createContext("/api/student/complaints", BackendServer::studentComplaintsApi);
        server.createContext("/api/student/fees", BackendServer::studentFeesApi);
        server.createContext("/api/student/profile", BackendServer::studentProfileApi);
        server.createContext("/api/driver/dashboard", BackendServer::driverDashboardApi);

        // Static Web Frontend Serving (SPA routing & asset serving)
        server.createContext("/", BackendServer::staticFileHandler);

        server.setExecutor(null);
        server.start();
        System.out.println("CampusTransit Backend running at http://localhost:" + port);

        // Keep main thread alive until terminated
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            server.stop(0);
        }
    }

    private static void seedData() {
        students.put(101, new Student(101, "Rahul Sharma", "Perundurai", true));
        students.put(102, new Student(102, "Priya Nair", "Erode", true));
        students.put(103, new Student(103, "Karthik Raj", "Bhavani", false));
        buses.add(new CollegeBus(1, "TN-33-CB-101", 20));
        buses.add(new CollegeBus(2, "TN-33-CB-102", 20));
        buses.add(new CollegeBus(3, "TN-33-CB-103", 20));

        // Default admin session for backward compatibility
        String defaultAdminToken = "admin-session-token-001";
        activeSessions.put(defaultAdminToken, new UserSession(defaultAdminToken, "admin", 0, "Transport Admin"));
    }

    private static void health(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "GET")) return;
        send(exchange, 200, "{\"status\":\"ok\",\"service\":\"smart-transport-backend\"}");
    }

    // =========================================================================
    // AUTHENTICATION & RBAC MIDDLEWARE
    // =========================================================================

    private static UserSession getSession(HttpExchange exchange) {
        String token = null;
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7).trim();
        }
        if (token == null || token.isEmpty()) {
            token = exchange.getRequestHeaders().getFirst("X-Session-Token");
        }
        if (token == null || token.isEmpty()) {
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2 && ("token".equals(pair[0]) || "session_token".equals(pair[0]))) {
                        token = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                }
            }
        }
        if (token != null && activeSessions.containsKey(token)) {
            return activeSessions.get(token);
        }

        // Support explicit role header for testing or fallback
        String roleHeader = exchange.getRequestHeaders().getFirst("X-User-Role");
        if (roleHeader != null && !roleHeader.isEmpty()) {
            String sidStr = exchange.getRequestHeaders().getFirst("X-Student-Id");
            int sid = (sidStr != null && !sidStr.isEmpty()) ? Integer.parseInt(sidStr) : 0;
            return new UserSession("temp", roleHeader.toLowerCase(), sid, "User (" + roleHeader + ")");
        }

        return null;
    }

    private static void authLoginApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "POST");
            return;
        }

        if (!allow(exchange, "POST")) return;

        Map<String, String> form = formData(readBody(exchange));
        String role = form.getOrDefault("role", "student").trim().toLowerCase();
        String token = UUID.randomUUID().toString();
        String userName = "User";
        int studentId = 0;

        if ("student".equalsIgnoreCase(role)) {
            String idStr = form.get("student_id");
            if (idStr == null || idStr.isBlank()) idStr = form.get("id");
            if (idStr == null || idStr.isBlank()) {
                send(exchange, 400, "{\"error\":\"Student ID is required for student login.\"}");
                return;
            }
            try {
                studentId = Integer.parseInt(idStr);
            } catch (NumberFormatException e) {
                send(exchange, 400, "{\"error\":\"Invalid student ID format.\"}");
                return;
            }

            // Verify student exists in MySQL
            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT name FROM students WHERE student_id = ?")) {
                ps.setInt(1, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        userName = rs.getString("name");
                    } else {
                        send(exchange, 404, "{\"error\":\"No student registered with ID #" + studentId + "\"}");
                        return;
                    }
                }
            } catch (SQLException e) {
                send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
                return;
            }
        } else if ("driver".equalsIgnoreCase(role)) {
            String busIdStr = form.get("bus_id");
            if (busIdStr == null || busIdStr.isBlank()) busIdStr = form.get("id");
            String username = form.get("username");
            String password = form.get("password");

            // Look up driver in MySQL buses table
            int busId = 0;
            String driverName = "";
            String driverPhone = "";
            String busName = "";
            String routeName = "";
            String busNumber = "";

            try (Connection con = DBConnection.getConnection()) {
                int parsedBusId = 0;
                if (busIdStr != null && !busIdStr.isBlank()) {
                    try { parsedBusId = Integer.parseInt(busIdStr.trim()); } catch (Exception ignored) {}
                } else if (username != null && username.toLowerCase().startsWith("driver")) {
                    try { parsedBusId = Integer.parseInt(username.toLowerCase().replace("driver", "").trim()); } catch (Exception ignored) {}
                }

                String cleanPhone = (username != null ? username.replace(" ", "").trim() : "");
                String findSql = "SELECT bus_id, bus_name, driver_name, driver_phone, bus_number, route_name FROM buses "
                        + "WHERE bus_id = ? OR driver_name = ? OR REPLACE(driver_phone, ' ', '') = ? OR bus_number = ? LIMIT 1";

                try (PreparedStatement ps = con.prepareStatement(findSql)) {
                    ps.setInt(1, parsedBusId);
                    ps.setString(2, username != null ? username.trim() : "");
                    ps.setString(3, cleanPhone);
                    ps.setString(4, username != null ? username.trim() : "");

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            busId = rs.getInt("bus_id");
                            busName = rs.getString("bus_name");
                            driverName = rs.getString("driver_name");
                            driverPhone = rs.getString("driver_phone");
                            busNumber = rs.getString("bus_number");
                            routeName = rs.getString("route_name");
                        } else {
                            send(exchange, 404, "{\"error\":\"No driver or bus record found matching the provided identity.\"}");
                            return;
                        }
                    }
                }
            } catch (SQLException e) {
                send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
                return;
            }

            // Credential validation: Check password
            if (password == null || password.isBlank()) {
                send(exchange, 401, "{\"error\":\"Password is required for driver sign-in.\"}");
                return;
            }

            // Valid passwords: "driver123", "password", driver's phone, last 4 digits of phone, or "driver" + busId
            String cleanPhoneDigits = (driverPhone != null ? driverPhone.replaceAll("\\D", "") : "");
            boolean passValid = "driver123".equals(password)
                    || "password".equals(password)
                    || ("driver" + busId).equalsIgnoreCase(password)
                    || (cleanPhoneDigits.length() >= 4 && password.equals(cleanPhoneDigits.substring(cleanPhoneDigits.length() - 4)))
                    || (cleanPhoneDigits.length() >= 5 && password.equals(cleanPhoneDigits));

            if (!passValid) {
                send(exchange, 401, "{\"error\":\"Invalid driver credentials. Please enter a valid driver password.\"}");
                return;
            }

            userName = driverName + " (" + busName + ")";
            UserSession session = new UserSession(token, role, 0, busId, userName);
            activeSessions.put(token, session);

            String json = "{\"success\":true,\"message\":\"Driver sign-in successful\","
                    + "\"token\":\"" + token + "\","
                    + "\"role\":\"driver\","
                    + "\"busId\":" + busId + ","
                    + "\"driverName\":\"" + escape(driverName) + "\","
                    + "\"driverPhone\":\"" + escape(driverPhone != null ? driverPhone : "") + "\","
                    + "\"busName\":\"" + escape(busName) + "\","
                    + "\"busNumber\":\"" + escape(busNumber != null ? busNumber : "") + "\","
                    + "\"routeName\":\"" + escape(routeName != null ? routeName : "") + "\","
                    + "\"name\":\"" + escape(userName) + "\"}";
            send(exchange, 200, json);
            return;
        } else {
            role = "admin";
            userName = "Transport Administrator";
        }

        UserSession session = new UserSession(token, role, studentId, userName);
        activeSessions.put(token, session);

        String json = "{\"success\":true,\"message\":\"Login successful\","
                + "\"token\":\"" + token + "\","
                + "\"role\":\"" + role + "\","
                + "\"studentId\":" + studentId + ","
                + "\"name\":\"" + escape(userName) + "\"}";
        send(exchange, 200, json);
    }

    // =========================================================================
    // STUDENT DIRECTORY & PROFILE API
    // =========================================================================

    private static void studentApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String query = exchange.getRequestURI().getQuery();
            String idParam = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2 && "id".equals(pair[0])) {
                        idParam = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                }
            }

            if (idParam != null && !idParam.isBlank()) {
                // SINGLE STUDENT LOOKUP
                int targetId;
                try {
                    targetId = Integer.parseInt(idParam);
                } catch (NumberFormatException e) {
                    send(exchange, 400, "{\"error\":\"Invalid student ID\"}");
                    return;
                }

                // Check RBAC: If student session, only allow viewing their own student record
                UserSession session = getSession(exchange);
                if (session != null && "student".equalsIgnoreCase(session.role) && session.studentId > 0 && session.studentId != targetId) {
                    send(exchange, 403, "{\"error\":\"Access denied: Students can only view their own transit details.\"}");
                    return;
                }

                LocalDate today = LocalDate.now();
                LocalDate tomorrow = today.plusDays(1);

                String singleSql = "SELECT s.student_id, s.name, s.roll_number, s.department, s.pickup_stop, "
                        + "s.transport_authorized, s.assigned_bus_id, "
                        + "b.bus_name, b.bus_number, b.driver_name, b.driver_phone, "
                        + "f.amount, f.payment_status, f.payment_date, "
                        + "d.bus_required AS declared_tomorrow, "
                        + "a.boarded, a.status AS attendance_status "
                        + "FROM students s "
                        + "LEFT JOIN buses b ON s.assigned_bus_id = b.bus_id "
                        + "LEFT JOIN fees f ON s.student_id = f.student_id "
                        + "LEFT JOIN attendance a ON s.student_id = a.student_id AND a.attendance_date = ? "
                        + "LEFT JOIN attendance d ON s.student_id = d.student_id AND d.attendance_date = ? "
                        + "WHERE s.student_id = ?";

                try (Connection con = DBConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement(singleSql)) {

                    ps.setDate(1, Date.valueOf(today));
                    ps.setDate(2, Date.valueOf(tomorrow));
                    ps.setInt(3, targetId);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            boolean auth = rs.getBoolean("transport_authorized");
                            int busId = rs.getInt("assigned_bus_id");
                            boolean hasBus = !rs.wasNull() && busId > 0;
                            String feeStatus = rs.getString("payment_status");
                            if (feeStatus == null) feeStatus = auth ? "Paid" : "Pending";

                            Object declObj = rs.getObject("declared_tomorrow");
                            Boolean declTomorrow = (declObj != null) ? rs.getBoolean("declared_tomorrow") : null;

                            String json = "{\"id\":" + rs.getInt("student_id")
                                    + ",\"name\":\"" + escape(rs.getString("name")) + "\""
                                    + ",\"rollNumber\":\"" + escape(rs.getString("roll_number")) + "\""
                                    + ",\"department\":\"" + escape(rs.getString("department")) + "\""
                                    + ",\"stop\":\"" + escape(rs.getString("pickup_stop")) + "\""
                                    + ",\"authorized\":" + auth
                                    + ",\"assignedBusId\":" + (hasBus ? busId : "null")
                                    + ",\"bus\":\"" + escape(hasBus ? rs.getString("bus_name") : "") + "\""
                                    + ",\"busNumber\":\"" + escape(hasBus ? rs.getString("bus_number") : "") + "\""
                                    + ",\"driverName\":\"" + escape(hasBus ? rs.getString("driver_name") : "") + "\""
                                    + ",\"driverPhone\":\"" + escape(hasBus ? rs.getString("driver_phone") : "") + "\""
                                    + ",\"feeAmount\":" + rs.getDouble("amount")
                                    + ",\"feeStatus\":\"" + escape(feeStatus) + "\""
                                    + ",\"paymentDate\":\"" + (rs.getDate("payment_date") != null ? rs.getDate("payment_date").toString() : "") + "\""
                                    + ",\"declaredTomorrow\":" + (declTomorrow != null ? declTomorrow.toString() : "null")
                                    + ",\"boarded\":" + rs.getBoolean("boarded")
                                    + ",\"attendanceStatus\":\"" + escape(rs.getString("attendance_status") != null ? rs.getString("attendance_status") : "Absent") + "\""
                                    + "}";

                            send(exchange, 200, json);
                            return;
                        } else {
                            send(exchange, 404, "{\"error\":\"Student not found with ID " + targetId + "\"}");
                            return;
                        }
                    }
                } catch (SQLException e) {
                    send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
                    return;
                }
            }

            // LIST ALL STUDENTS (ADMIN VIEW)
            UserSession session = getSession(exchange);
            if (session != null && "student".equalsIgnoreCase(session.role)) {
                send(exchange, 403, "{\"error\":\"Access denied: Students are not authorized to view the full student directory.\"}");
                return;
            }

            String sql = "SELECT s.student_id, s.name, s.roll_number, s.department, s.pickup_stop, "
                    + "s.transport_authorized, s.assigned_bus_id, "
                    + "b.bus_name, b.bus_number, "
                    + "f.payment_status, f.amount AS fee_amount "
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

                    boolean auth = rs.getBoolean("transport_authorized");
                    int busId = rs.getInt("assigned_bus_id");
                    boolean hasBus = !rs.wasNull() && busId > 0;
                    String feeStatus = rs.getString("payment_status");
                    if (feeStatus == null) feeStatus = auth ? "Paid" : "Pending";

                    json.append("{\"id\":").append(rs.getInt("student_id"))
                        .append(",\"name\":\"").append(escape(rs.getString("name")))
                        .append("\",\"rollNumber\":\"").append(escape(rs.getString("roll_number")))
                        .append("\",\"department\":\"").append(escape(rs.getString("department")))
                        .append("\",\"stop\":\"").append(escape(rs.getString("pickup_stop")))
                        .append("\",\"authorized\":").append(auth)
                        .append(",\"assignedBusId\":").append(hasBus ? busId : "null")
                        .append(",\"bus\":\"").append(escape(hasBus ? rs.getString("bus_name") : ""))
                        .append("\",\"feeStatus\":\"").append(escape(feeStatus))
                        .append("\",\"feeAmount\":").append(rs.getObject("fee_amount") != null ? rs.getDouble("fee_amount") : 0.0)
                        .append("}");
                }
                json.append(']');
                send(exchange, 200, json.toString());
            } catch (SQLException e) {
                send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        // RBAC CHECK ON STUDENT CREATION / MODIFICATION
        UserSession session = getSession(exchange);
        if (session != null && "student".equalsIgnoreCase(session.role)) {
            send(exchange, 403, "{\"error\":\"Access denied: Students are not authorized to enroll or edit student profiles.\"}");
            return;
        }

        Map<String, String> form = formData(readBody(exchange));
        try {
            int id = Integer.parseInt(form.get("id"));
            String name = form.get("name");
            String stop = form.get("stop");
            boolean authorized = Boolean.parseBoolean(form.getOrDefault("authorized", "false"));

            String busIdStr = form.get("bus_id");
            Integer busId = null;
            if (busIdStr != null && !busIdStr.isBlank() && !busIdStr.equalsIgnoreCase("null")) {
                busId = Integer.parseInt(busIdStr);
            }

            try (Connection con = DBConnection.getConnection()) {
                String sql = "INSERT INTO students (student_id, name, roll_number, department, transport_authorized, pickup_stop, assigned_bus_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE name=VALUES(name), pickup_stop=VALUES(pickup_stop), "
                        + "transport_authorized=VALUES(transport_authorized), assigned_bus_id=VALUES(assigned_bus_id)";

                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setInt(1, id);
                    ps.setString(2, name);
                    ps.setString(3, "STU" + id);
                    ps.setString(4, "CSE");
                    ps.setBoolean(5, authorized);
                    ps.setString(6, stop);
                    if (busId != null) ps.setInt(7, busId); else ps.setNull(7, Types.INTEGER);
                    ps.executeUpdate();
                }

                String feeSql = "INSERT INTO fees (student_id, amount, payment_status, payment_date) "
                        + "VALUES (?, 12000.0, ?, ?) ON DUPLICATE KEY UPDATE payment_status = VALUES(payment_status)";
                try (PreparedStatement psFee = con.prepareStatement(feeSql)) {
                    psFee.setInt(1, id);
                    psFee.setString(2, authorized ? "Paid" : "Pending");
                    if (authorized) psFee.setDate(3, Date.valueOf(LocalDate.now())); else psFee.setNull(3, Types.DATE);
                    psFee.executeUpdate();
                }

                send(exchange, 201, "{\"message\":\"Student saved to database\",\"id\":" + id + ",\"assignedBusId\":" + (busId != null ? busId : "null") + "}");
            }
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Invalid student details: " + escape(e.getMessage()) + "\"}");
        }
    }

    // =========================================================================
    // FLEET & BUS APIS
    // =========================================================================

    private static void busApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
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

                    json.append("{\"id\":").append(rs.getInt("bus_id"))
                        .append(",\"busId\":").append(rs.getInt("bus_id"))
                        .append(",\"name\":\"").append(escape(rs.getString("bus_name")))
                        .append("\",\"number\":\"").append(escape(rs.getString("bus_number")))
                        .append("\",\"type\":\"").append(escape(rs.getString("bus_type")))
                        .append("\",\"driver\":\"").append(escape(rs.getString("driver_name")))
                        .append("\",\"driverPhone\":\"").append(escape(rs.getString("driver_phone")))
                        .append("\",\"capacity\":").append(cap)
                        .append(",\"assignedCount\":").append(assigned)
                        .append(",\"availableSeats\":").append(avail)
                        .append(",\"routeName\":\"").append(escape(rs.getString("route_name")))
                        .append("\",\"status\":\"").append(escape(rs.getString("status")))
                        .append("\",\"stops\":\"").append(escape(rs.getString("stops_list") != null ? rs.getString("stops_list") : ""))
                        .append("\"}");
                }
                json.append(']');
                send(exchange, 200, json.toString());
            } catch (SQLException e) {
                send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        UserSession session = getSession(exchange);
        if (session != null && "student".equalsIgnoreCase(session.role)) {
            send(exchange, 403, "{\"error\":\"Access denied: Students cannot alter fleet allocation or capacity.\"}");
            return;
        }

        Map<String, String> form = formData(readBody(exchange));
        try {
            int busId = Integer.parseInt(form.get("id"));
            int capacity = Integer.parseInt(form.get("capacity"));
            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("UPDATE buses SET capacity = ? WHERE bus_id = ?")) {
                ps.setInt(1, capacity);
                ps.setInt(2, busId);
                ps.executeUpdate();
                send(exchange, 200, "{\"message\":\"Bus capacity updated successfully\",\"busId\":" + busId + ",\"capacity\":" + capacity + "}");
            }
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Failed to update bus: " + escape(e.getMessage()) + "\"}");
        }
    }

    // =========================================================================
    // ROUTES & DIJKSTRA ALGORITHM APIS
    // =========================================================================

    private static void routeApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if (!allow(exchange, "GET")) return;

        String sql = "SELECT r.route_id, r.route_name, r.corridor_name, r.start_location, r.end_location, "
                + "r.bus_id, b.bus_name, b.bus_number, "
                + "st.stop_id, st.stop_name, st.stop_order, st.scheduled_time, st.road_type "
                + "FROM routes r "
                + "LEFT JOIN buses b ON r.bus_id = b.bus_id "
                + "LEFT JOIN stops st ON r.route_id = st.route_id "
                + "ORDER BY r.route_id ASC, st.stop_order ASC";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            Map<Integer, Map<String, Object>> routeMap = new LinkedHashMap<>();
            while (rs.next()) {
                int routeId = rs.getInt("route_id");
                Map<String, Object> route = routeMap.computeIfAbsent(routeId, id -> {
                    Map<String, Object> m = new LinkedHashMap<>();
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
                    } catch (SQLException ex) { ex.printStackTrace(); }
                    return m;
                });

                int stopId = rs.getInt("stop_id");
                if (stopId > 0 && rs.getString("stop_name") != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> stopsList = (List<Map<String, Object>>) route.get("stops");
                    Map<String, Object> stop = new LinkedHashMap<>();
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
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    /**
     * Dijkstra's Algorithm Endpoint:
     * Computes the shortest travel-time path (in minutes) to Kongu Engineering College hub
     * using DijkstraRouter and java.util.PriorityQueue.
     */
    private static void dijkstraApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if (!allow(exchange, "GET")) return;

        String query = exchange.getRequestURI().getQuery();
        String source = "Erode Central Bus Stand";
        String destination = "Kongu Engineering College";
        int busId = -1;
        int routeId = -1;
        boolean forceSingleHop = false;
        boolean explicitSource = false;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2) {
                    String k = pair[0];
                    String v = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    if ("start".equalsIgnoreCase(k) || "source".equalsIgnoreCase(k) || "stop".equalsIgnoreCase(k) || "from".equalsIgnoreCase(k)) {
                        source = v;
                        explicitSource = true;
                    }
                    if ("end".equalsIgnoreCase(k) || "destination".equalsIgnoreCase(k) || "target".equalsIgnoreCase(k) || "to".equalsIgnoreCase(k)) destination = v;
                    if ("bus_id".equalsIgnoreCase(k) || "busId".equalsIgnoreCase(k) || "bus".equalsIgnoreCase(k)) {
                        try { busId = Integer.parseInt(v); } catch (Exception ignored) {}
                    }
                    if ("route_id".equalsIgnoreCase(k) || "routeId".equalsIgnoreCase(k) || "route".equalsIgnoreCase(k)) {
                        try { routeId = Integer.parseInt(v); } catch (Exception ignored) {}
                    }
                    if ("single_hop".equalsIgnoreCase(k) || "direct".equalsIgnoreCase(k)) {
                        forceSingleHop = Boolean.parseBoolean(v);
                    }
                }
            }
        }

        try (Connection con = DBConnection.getConnection()) {
            Map<String, List<DijkstraRouter.RoadEdge>> graph = DijkstraRouter.loadGraph(con);
            Map<String, double[]> coords = DijkstraRouter.loadNodeCoordinates(con);

            String resolvedSource = DijkstraRouter.resolveNodeName(graph, source);
            String resolvedDest = DijkstraRouter.resolveNodeName(graph, destination);

            // Check assigned stops for bus or route
            List<String> assignedStopNames = new ArrayList<>();
            List<Map<String, Object>> assignedStopDetails = new ArrayList<>();
            int resolvedRouteId = routeId;
            if (resolvedRouteId <= 0 && busId > 0) {
                try (PreparedStatement psBus = con.prepareStatement("SELECT route_id FROM buses WHERE bus_id = ?")) {
                    psBus.setInt(1, busId);
                    try (ResultSet rsBus = psBus.executeQuery()) {
                        if (rsBus.next()) resolvedRouteId = rsBus.getInt("route_id");
                    }
                }
            }
            if (resolvedRouteId <= 0 && !forceSingleHop) {
                // If routeId not given, check if resolvedSource matches a stop in routes table
                String autoRouteSql = "SELECT s.route_id, b.bus_id FROM stops s LEFT JOIN buses b ON s.route_id = b.route_id WHERE s.stop_name = ? LIMIT 1";
                try (PreparedStatement psAuto = con.prepareStatement(autoRouteSql)) {
                    psAuto.setString(1, resolvedSource);
                    try (ResultSet rsAuto = psAuto.executeQuery()) {
                        if (rsAuto.next()) {
                            resolvedRouteId = rsAuto.getInt("route_id");
                            if (busId <= 0) busId = rsAuto.getInt("bus_id");
                        }
                    }
                }
            }

            if (resolvedRouteId > 0 && !forceSingleHop) {
                String stopQuery = "SELECT stop_id, stop_name, stop_order, scheduled_time, latitude, longitude FROM stops WHERE route_id = ? ORDER BY stop_order ASC";
                try (PreparedStatement psStops = con.prepareStatement(stopQuery)) {
                    psStops.setInt(1, resolvedRouteId);
                    try (ResultSet rsStops = psStops.executeQuery()) {
                        while (rsStops.next()) {
                            String sName = rsStops.getString("stop_name");
                            assignedStopNames.add(sName);
                            Map<String, Object> sd = new HashMap<>();
                            sd.put("id", rsStops.getInt("stop_id"));
                            sd.put("name", sName);
                            sd.put("order", rsStops.getInt("stop_order"));
                            sd.put("time", rsStops.getString("scheduled_time"));
                            sd.put("lat", rsStops.getDouble("latitude"));
                            sd.put("lng", rsStops.getDouble("longitude"));
                            assignedStopDetails.add(sd);
                        }
                    }
                }
            }

            if (!explicitSource && !assignedStopNames.isEmpty()) {
                resolvedSource = DijkstraRouter.resolveNodeName(graph, assignedStopNames.get(0));
            }

            if (!DijkstraRouter.hasNode(graph, resolvedSource) && !coords.containsKey(resolvedSource)) {
                send(exchange, 400, "{\"error\":\"Unknown location: " + escape(source) + "\",\"reachable\":false,\"success\":false}");
                return;
            }

            DijkstraRouter.RouteResult res;
            if (!assignedStopNames.isEmpty()) {
                res = DijkstraRouter.findMultiStopRoute(graph, coords, resolvedSource, assignedStopNames, resolvedDest);
            } else {
                res = DijkstraRouter.findShortestRoute(graph, coords, resolvedSource, resolvedDest);
            }

            StringBuilder json = new StringBuilder("{");
            json.append("\"success\":").append(res.reachable)
                .append(",\"reachable\":").append(res.reachable)
                .append(",\"source\":\"").append(escape(res.source)).append("\"")
                .append(",\"destination\":\"").append(escape(res.destination)).append("\"")
                .append(",\"busId\":").append(busId > 0 ? busId : 1)
                .append(",\"routeId\":").append(resolvedRouteId > 0 ? resolvedRouteId : 1)
                .append(",\"totalTravelTime\":").append(res.totalTravelTime)
                .append(",\"totalTimeMinutes\":").append(res.totalTravelTime)
                .append(",\"totalDistanceKm\":").append(res.totalDistanceKm)
                .append(",\"baseTravelTime\":").append(res.baseTravelTime)
                .append(",\"totalTrafficDelay\":").append(res.totalTrafficDelay)
                .append(",\"recommendedPath\":\"").append(escape(res.recommendedPath)).append("\"")
                .append(",\"routeStatus\":\"").append(escape(res.routeStatus)).append("\"")
                .append(",\"statusDescription\":\"").append(escape(res.statusDescription)).append("\"")
                .append(",\"orderedStops\":[");

            for (int i = 0; i < res.orderedStops.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(res.orderedStops.get(i))).append("\"");
            }
            json.append("],\"path\":[");
            for (int i = 0; i < res.orderedStops.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(res.orderedStops.get(i))).append("\"");
            }
            json.append("],\"roadSegments\":[");
            for (int i = 0; i < res.roadSegments.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(res.roadSegments.get(i))).append("\"");
            }
            json.append("],\"pathNodeDetails\":[");
            for (int i = 0; i < res.pathNodeDetails.size(); i++) {
                if (i > 0) json.append(',');
                DijkstraRouter.NodeDetail nd = res.pathNodeDetails.get(i);
                json.append("{\"name\":\"").append(escape(nd.nodeName)).append("\"")
                    .append(",\"lat\":").append(nd.latitude)
                    .append(",\"latitude\":").append(nd.latitude)
                    .append(",\"lng\":").append(nd.longitude)
                    .append(",\"longitude\":").append(nd.longitude)
                    .append(",\"edgeWeightMinutes\":").append(nd.edgeWeightMinutes)
                    .append(",\"edgeDistanceKm\":").append(nd.edgeDistanceKm)
                    .append(",\"roadName\":\"").append(escape(nd.roadName)).append("\"")
                    .append("}");
            }
            json.append("],\"assignedStops\":[");
            if (!assignedStopDetails.isEmpty()) {
                for (int i = 0; i < assignedStopDetails.size(); i++) {
                    if (i > 0) json.append(',');
                    Map<String, Object> sd = assignedStopDetails.get(i);
                    json.append("{\"id\":").append(sd.get("id"))
                        .append(",\"name\":\"").append(escape((String)sd.get("name"))).append("\"")
                        .append(",\"order\":").append(sd.get("order"))
                        .append(",\"time\":\"").append(escape((String)sd.get("time"))).append("\"")
                        .append(",\"lat\":").append(sd.get("lat"))
                        .append(",\"latitude\":").append(sd.get("lat"))
                        .append(",\"lng\":").append(sd.get("lng"))
                        .append(",\"longitude\":").append(sd.get("lng"))
                        .append("}");
                }
            } else if (res.pathNodeDetails != null) {
                for (int i = 0; i < res.pathNodeDetails.size(); i++) {
                    if (i > 0) json.append(',');
                    DijkstraRouter.NodeDetail nd = res.pathNodeDetails.get(i);
                    json.append("{\"id\":").append(i + 1)
                        .append(",\"name\":\"").append(escape(nd.nodeName)).append("\"")
                        .append(",\"order\":").append(i + 1)
                        .append(",\"time\":\"").append("07:30 AM").append("\"")
                        .append(",\"lat\":").append(nd.latitude)
                        .append(",\"latitude\":").append(nd.latitude)
                        .append(",\"lng\":").append(nd.longitude)
                        .append(",\"longitude\":").append(nd.longitude)
                        .append("}");
                }
            }
            json.append("]}");

            send(exchange, 200, json.toString());
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    /**
     * Road Network Graph Endpoint: returns all weighted edges.
     */
    private static void roadNetworkApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if (!allow(exchange, "GET")) return;

        String sql = "SELECT edge_id, source_node, target_node, travel_time_minutes, traffic_delay_minutes, "
                + "road_name, is_bidirectional, is_demo FROM road_network_edges ORDER BY edge_id ASC";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            while (rs.next()) {
                if (!first) json.append(',');
                first = false;

                json.append("{\"id\":").append(rs.getInt("edge_id"))
                    .append(",\"source\":\"").append(escape(rs.getString("source_node")))
                    .append("\",\"target\":\"").append(escape(rs.getString("target_node")))
                    .append("\",\"travelTime\":").append(rs.getInt("travel_time_minutes"))
                    .append(",\"trafficDelay\":").append(rs.getInt("traffic_delay_minutes"))
                    .append(",\"roadName\":\"").append(escape(rs.getString("road_name")))
                    .append("\",\"bidirectional\":").append(rs.getBoolean("is_bidirectional"))
                    .append(",\"isDemo\":").append(rs.getBoolean("is_demo"))
                    .append('}');
            }
            json.append(']');
            send(exchange, 200, json.toString());
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    /**
     * Dynamic Traffic Update Endpoint:
     * Allows driver or admin to report traffic delays on specific road edges.
     * Updates edge weights and recalculates Dijkstra fastest path.
     */
    private static void trafficUpdateApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "POST");
            return;
        }

        if (!allow(exchange, "POST")) return;

        UserSession session = getSession(exchange);
        if (session != null && "student".equalsIgnoreCase(session.role)) {
            send(exchange, 403, "{\"error\":\"Access denied: Students are not authorized to modify road traffic conditions.\"}");
            return;
        }

        Map<String, String> form = formData(readBody(exchange));
        String sourceNode = form.get("source_node");
        String targetNode = form.get("target_node");
        String edgeIdStr = form.get("edge_id");
        int delayMinutes = 15;
        try {
            delayMinutes = Integer.parseInt(form.getOrDefault("traffic_delay_minutes", "15"));
        } catch (NumberFormatException ignored) {}

        try (Connection con = DBConnection.getConnection()) {
            if (edgeIdStr != null && !edgeIdStr.isBlank()) {
                int edgeId = Integer.parseInt(edgeIdStr);
                String sql = "UPDATE road_network_edges SET traffic_delay_minutes = ?, is_demo = TRUE WHERE edge_id = ?";
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setInt(1, delayMinutes);
                    ps.setInt(2, edgeId);
                    ps.executeUpdate();
                }
            } else if (sourceNode != null && targetNode != null) {
                String sql = "UPDATE road_network_edges SET traffic_delay_minutes = ?, is_demo = TRUE "
                        + "WHERE (LOWER(source_node) LIKE LOWER(?) AND LOWER(target_node) LIKE LOWER(?)) "
                        + "OR (is_bidirectional = TRUE AND LOWER(source_node) LIKE LOWER(?) AND LOWER(target_node) LIKE LOWER(?))";
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setInt(1, delayMinutes);
                    ps.setString(2, "%" + sourceNode.trim() + "%");
                    ps.setString(3, "%" + targetNode.trim() + "%");
                    ps.setString(4, "%" + targetNode.trim() + "%");
                    ps.setString(5, "%" + sourceNode.trim() + "%");
                    ps.executeUpdate();
                }
            } else {
                send(exchange, 400, "{\"error\":\"Please specify edge_id or source_node and target_node.\"}");
                return;
            }

            // Recalculate Dijkstra from source to Kongu Engineering College
            String startNode = (sourceNode != null) ? sourceNode : "Erode Central Bus Stand";
            Map<String, List<DijkstraRouter.RoadEdge>> graph = DijkstraRouter.loadGraph(con);
            DijkstraRouter.RouteResult recalc = DijkstraRouter.findShortestRoute(graph, startNode, "Kongu Engineering College");

            String json = "{\"success\":true,\"message\":\"Traffic delay of " + delayMinutes + " mins recorded. Dijkstra algorithm recalculated fastest route.\","
                    + "\"isDemoTraffic\":true,"
                    + "\"sourceNode\":\"" + escape(sourceNode) + "\","
                    + "\"targetNode\":\"" + escape(targetNode) + "\","
                    + "\"trafficDelayMinutes\":" + delayMinutes + ","
                    + "\"recalculatedRoute\":{"
                    + "\"reachable\":" + recalc.reachable
                    + ",\"totalTravelTime\":" + recalc.totalTravelTime
                    + ",\"recommendedPath\":\"" + escape(recalc.recommendedPath) + "\""
                    + ",\"routeStatus\":\"" + escape(recalc.routeStatus) + "\""
                    + "}}";
            send(exchange, 200, json);
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    /**
     * Map Configuration Endpoint: GET /api/map/config
     * Exposes public Google Maps browser key and map defaults from ConfigLoader.
     */
    private static void mapConfigApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String body = readBody(exchange);
            Map<String, String> form = formData(body);
            String newKey = form.get("google_maps_api_key");
            if (newKey == null) newKey = form.get("googleMapsApiKey");
            if (newKey == null) newKey = form.get("apiKey");
            if (newKey == null) newKey = form.get("api_key");
            if (newKey == null) newKey = form.get("key");
            if (newKey != null && !newKey.trim().isEmpty()) {
                ConfigLoader.setGoogleMapsApiKey(newKey.trim());
            }
        }

        String apiKey = ConfigLoader.getGoogleMapsApiKey();
        boolean configured = ConfigLoader.isGoogleMapsConfigured();
        double centerLat = ConfigLoader.getDefaultLat();
        double centerLng = ConfigLoader.getDefaultLng();
        int zoom = ConfigLoader.getDefaultZoom();
        String destName = ConfigLoader.getDestinationName();
        double destLat = ConfigLoader.getDestinationLat();
        double destLng = ConfigLoader.getDestinationLng();

        StringBuilder json = new StringBuilder("{");
        json.append("\"googleMapsApiKey\":\"").append(escape(apiKey)).append("\"")
            .append(",\"browserApiKey\":\"").append(escape(apiKey)).append("\"")
            .append(",\"configured\":").append(configured)
            .append(",\"center\":{\"lat\":").append(centerLat).append(",\"lng\":").append(centerLng).append("}")
            .append(",\"zoom\":").append(zoom)
            .append(",\"destination\":{")
            .append("\"name\":\"").append(escape(destName)).append("\"")
            .append(",\"lat\":").append(destLat)
            .append(",\"lng\":").append(destLng)
            .append("},\"college\":{")
            .append("\"name\":\"").append(escape(destName)).append("\"")
            .append(",\"lat\":").append(destLat)
            .append(",\"lng\":").append(destLng)
            .append("},\"nodes\":[");

        try (Connection con = DBConnection.getConnection()) {
            Map<String, double[]> coords = DijkstraRouter.loadNodeCoordinates(con);
            boolean first = true;
            for (Map.Entry<String, double[]> entry : coords.entrySet()) {
                if (!first) json.append(',');
                first = false;
                json.append("{\"name\":\"").append(escape(entry.getKey())).append("\"")
                    .append(",\"lat\":").append(entry.getValue()[0])
                    .append(",\"lng\":").append(entry.getValue()[1])
                    .append("}");
            }
        } catch (Exception ignored) {}

        json.append("]}");
        send(exchange, 200, json.toString());
    }

    /**
     * Graph Edge Traffic Endpoint: PUT /api/graph/edge/{id}/traffic
     * Updates road edge travel time / delay and triggers immediate Dijkstra recalculation.
     */
    private static void graphEdgeTrafficApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "PUT, POST, OPTIONS");
            return;
        }

        String method = exchange.getRequestMethod();
        if (!"PUT".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
            send(exchange, 405, "{\"error\":\"Method Not Allowed. Use PUT or POST.\"}");
            return;
        }

        UserSession session = getSession(exchange);
        if (session != null && "student".equalsIgnoreCase(session.role)) {
            send(exchange, 403, "{\"error\":\"Access denied: Students are not authorized to modify road traffic conditions.\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        int edgeId = -1;
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("edge".equalsIgnoreCase(parts[i]) && i + 1 < parts.length) {
                try {
                    edgeId = Integer.parseInt(parts[i + 1]);
                } catch (NumberFormatException ignored) {}
            }
        }

        Map<String, String> form = formData(readBody(exchange));
        if (edgeId <= 0 && form.containsKey("edge_id")) {
            try { edgeId = Integer.parseInt(form.get("edge_id")); } catch (Exception ignored) {}
        }

        int delayMinutes = 15;
        if (form.containsKey("traffic_delay_minutes")) {
            try { delayMinutes = Integer.parseInt(form.get("traffic_delay_minutes")); } catch (Exception ignored) {}
        } else if (form.containsKey("delayMinutes")) {
            try { delayMinutes = Integer.parseInt(form.get("delayMinutes")); } catch (Exception ignored) {}
        } else if (form.containsKey("delay")) {
            try { delayMinutes = Integer.parseInt(form.get("delay")); } catch (Exception ignored) {}
        }

        if (delayMinutes < 0) {
            send(exchange, 400, "{\"error\":\"Traffic delay minutes must be positive or zero.\"}");
            return;
        }

        String sourceNode = form.get("source_node");
        String targetNode = form.get("target_node");

        try (Connection con = DBConnection.getConnection()) {
            int oldTravelTime = 0;
            String edgeSource = sourceNode;
            String edgeTarget = targetNode;

            if (edgeId > 0) {
                String selectSql = "SELECT source_node, target_node, travel_time_minutes, traffic_delay_minutes FROM road_network_edges WHERE edge_id = ?";
                try (PreparedStatement ps = con.prepareStatement(selectSql)) {
                    ps.setInt(1, edgeId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            edgeSource = rs.getString("source_node");
                            edgeTarget = rs.getString("target_node");
                            oldTravelTime = rs.getInt("travel_time_minutes") + rs.getInt("traffic_delay_minutes");
                        } else {
                            send(exchange, 404, "{\"error\":\"Road edge #" + edgeId + " not found.\"}");
                            return;
                        }
                    }
                }

                String updateSql = "UPDATE road_network_edges SET traffic_delay_minutes = ?, is_demo = TRUE WHERE edge_id = ?";
                try (PreparedStatement ps = con.prepareStatement(updateSql)) {
                    ps.setInt(1, delayMinutes);
                    ps.setInt(2, edgeId);
                    ps.executeUpdate();
                }
            } else if (sourceNode != null && targetNode != null) {
                String updateSql = "UPDATE road_network_edges SET traffic_delay_minutes = ?, is_demo = TRUE "
                        + "WHERE (LOWER(source_node) LIKE LOWER(?) AND LOWER(target_node) LIKE LOWER(?)) "
                        + "OR (is_bidirectional = TRUE AND LOWER(source_node) LIKE LOWER(?) AND LOWER(target_node) LIKE LOWER(?))";
                try (PreparedStatement ps = con.prepareStatement(updateSql)) {
                    ps.setInt(1, delayMinutes);
                    ps.setString(2, "%" + sourceNode.trim() + "%");
                    ps.setString(3, "%" + targetNode.trim() + "%");
                    ps.setString(4, "%" + targetNode.trim() + "%");
                    ps.setString(5, "%" + sourceNode.trim() + "%");
                    ps.executeUpdate();
                }
            } else {
                send(exchange, 400, "{\"error\":\"Please provide a valid edge ID or source_node and target_node.\"}");
                return;
            }

            String origin = (edgeSource != null) ? edgeSource : "Erode Central Bus Stand";
            Map<String, List<DijkstraRouter.RoadEdge>> graph = DijkstraRouter.loadGraph(con);
            Map<String, double[]> coords = DijkstraRouter.loadNodeCoordinates(con);
            DijkstraRouter.RouteResult recalc = DijkstraRouter.findShortestRoute(graph, coords, origin, "Kongu Engineering College");

            StringBuilder json = new StringBuilder("{");
            json.append("\"success\":true")
                .append(",\"edgeId\":").append(edgeId)
                .append(",\"sourceNode\":\"").append(escape(edgeSource)).append("\"")
                .append(",\"targetNode\":\"").append(escape(edgeTarget)).append("\"")
                .append(",\"trafficDelayMinutes\":").append(delayMinutes)
                .append(",\"delayMinutes\":").append(delayMinutes)
                .append(",\"impactMinutes\":").append(delayMinutes)
                .append(",\"oldTravelTime\":").append(oldTravelTime)
                .append(",\"oldTravelTimeMinutes\":").append(oldTravelTime)
                .append(",\"newTravelTime\":").append(recalc.totalTravelTime)
                .append(",\"newTravelTimeMinutes\":").append(recalc.totalTravelTime)
                .append(",\"totalDistanceKm\":").append(recalc.totalDistanceKm)
                .append(",\"recalculatedRoute\":{")
                .append("\"success\":").append(recalc.reachable)
                .append(",\"reachable\":").append(recalc.reachable)
                .append(",\"totalTravelTime\":").append(recalc.totalTravelTime)
                .append(",\"totalTimeMinutes\":").append(recalc.totalTravelTime)
                .append(",\"totalDistanceKm\":").append(recalc.totalDistanceKm)
                .append(",\"recommendedPath\":\"").append(escape(recalc.recommendedPath)).append("\"")
                .append(",\"routeStatus\":\"").append(escape(recalc.routeStatus)).append("\"")
                .append(",\"orderedStops\":[");
            for (int i = 0; i < recalc.orderedStops.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(recalc.orderedStops.get(i))).append("\"");
            }
            json.append("],\"path\":[");
            for (int i = 0; i < recalc.orderedStops.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(recalc.orderedStops.get(i))).append("\"");
            }
            json.append("],\"pathNodeDetails\":[");
            for (int i = 0; i < recalc.pathNodeDetails.size(); i++) {
                if (i > 0) json.append(',');
                DijkstraRouter.NodeDetail nd = recalc.pathNodeDetails.get(i);
                json.append("{\"name\":\"").append(escape(nd.nodeName)).append("\"")
                    .append(",\"lat\":").append(nd.latitude)
                    .append(",\"latitude\":").append(nd.latitude)
                    .append(",\"lng\":").append(nd.longitude)
                    .append(",\"longitude\":").append(nd.longitude)
                    .append(",\"edgeWeightMinutes\":").append(nd.edgeWeightMinutes)
                    .append(",\"edgeDistanceKm\":").append(nd.edgeDistanceKm)
                    .append(",\"roadName\":\"").append(escape(nd.roadName)).append("\"")
                    .append("}");
            }
            json.append("]}}");

            send(exchange, 200, json.toString());
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    /**
     * Breakdown Reporting Endpoint: POST /api/breakdown
     * Records breakdown incident, triages students, finds nearest standby bus,
     * calculates alternate route to college and standby pickup route with ETA.
     */
    private static void breakdownPostApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "POST, OPTIONS");
            return;
        }
        if (!allow(exchange, "POST")) return;

        Map<String, String> form = formData(readBody(exchange));
        int busId = 1;
        if (form.containsKey("busId")) {
            try { busId = Integer.parseInt(form.get("busId")); } catch (Exception ignored) {}
        } else if (form.containsKey("bus_id")) {
            try { busId = Integer.parseInt(form.get("bus_id")); } catch (Exception ignored) {}
        }

        double bLat = 0.0, bLng = 0.0;
        try {
            if (form.containsKey("latitude")) bLat = Double.parseDouble(form.get("latitude"));
            else if (form.containsKey("lat")) bLat = Double.parseDouble(form.get("lat"));
            if (form.containsKey("longitude")) bLng = Double.parseDouble(form.get("longitude"));
            else if (form.containsKey("lng")) bLng = Double.parseDouble(form.get("lng"));
        } catch (Exception ignored) {}

        String desc = form.getOrDefault("description", "Engine breakdown");
        String location = form.getOrDefault("breakdown_location", form.getOrDefault("location", ""));
        String destination = form.getOrDefault("destination", "Kongu Engineering College");

        try (Connection con = DBConnection.getConnection()) {
            Map<String, List<DijkstraRouter.RoadEdge>> graph = DijkstraRouter.loadGraph(con);
            Map<String, double[]> coords = DijkstraRouter.loadNodeCoordinates(con);

            if ((location == null || location.isBlank()) && bLat > 0 && bLng > 0) {
                location = DijkstraRouter.findNearestNode(coords, bLat, bLng);
            } else if (location == null || location.isBlank()) {
                location = "Thindal Murugan Temple";
            }

            // 1. Triage students
            List<String> onboardList = new ArrayList<>();
            List<String> waitingList = new ArrayList<>();
            String stuSql = "SELECT s.student_id, s.name, s.pickup_stop, COALESCE(a.boarded, 0) AS boarded "
                    + "FROM students s "
                    + "LEFT JOIN attendance a ON s.student_id = a.student_id AND a.attendance_date = CURRENT_DATE "
                    + "WHERE s.assigned_bus_id = ?";
            try (PreparedStatement ps = con.prepareStatement(stuSql)) {
                ps.setInt(1, busId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String sName = rs.getString("name");
                        String stop = rs.getString("pickup_stop");
                        boolean boarded = rs.getBoolean("boarded");
                        if (boarded || (stop != null && stop.toLowerCase().contains("erode"))) {
                            onboardList.add(sName + " (" + stop + ")");
                        } else {
                            waitingList.add(sName + " (" + stop + ")");
                        }
                    }
                }
            }
            int totalPassengers = onboardList.size() + waitingList.size();

            // 2. Query assigned stops for the broken bus to determine remaining required stops
            List<String> busStops = new ArrayList<>();
            List<String> remainingStopNames = new ArrayList<>();
            int brokenIdx = -1;

            String busStopsSql = "SELECT s.stop_name, s.stop_order "
                    + "FROM stops s JOIN buses b ON s.route_id = b.route_id WHERE b.bus_id = ? ORDER BY s.stop_order ASC";
            try (PreparedStatement psStops = con.prepareStatement(busStopsSql)) {
                psStops.setInt(1, busId);
                try (ResultSet rsStops = psStops.executeQuery()) {
                    int idx = 0;
                    while (rsStops.next()) {
                        String sName = rsStops.getString("stop_name");
                        busStops.add(sName);
                        if (sName.equalsIgnoreCase(location) || location.toLowerCase().contains(sName.toLowerCase()) || sName.toLowerCase().contains(location.toLowerCase())) {
                            brokenIdx = idx;
                        }
                        idx++;
                    }
                }
            }

            if (brokenIdx == -1) {
                String nearest = DijkstraRouter.resolveNodeName(graph, location);
                for (int i = 0; i < busStops.size(); i++) {
                    if (busStops.get(i).equalsIgnoreCase(nearest)) {
                        brokenIdx = i;
                        break;
                    }
                }
            }

            int startRemaining = (brokenIdx >= 0) ? (brokenIdx + 1) : 1;
            for (int i = startRemaining; i < busStops.size(); i++) {
                remainingStopNames.add(busStops.get(i));
            }

            // Phase 1: Standby Mini Bus dispatches from Perundurai Depot to Breakdown Location
            String depotLocation = "Perundurai Depot";
            DijkstraRouter.RouteResult reliefRoute = DijkstraRouter.findShortestRoute(graph, coords, depotLocation, location);

            // Phase 2: Relief bus continues from Breakdown Location, visiting remaining required stops in order, then reaches college
            DijkstraRouter.RouteResult altRoute;
            if (!remainingStopNames.isEmpty()) {
                altRoute = DijkstraRouter.findMultiStopRoute(graph, coords, location, remainingStopNames, destination);
            } else {
                altRoute = DijkstraRouter.findShortestRoute(graph, coords, location, destination);
            }

            int standbyCapacity = 10;
            boolean capacitySufficient = (totalPassengers <= standbyCapacity);
            int seatDeficit = Math.max(0, totalPassengers - standbyCapacity);

            StringBuilder json = new StringBuilder("{");
            json.append("\"success\":true")
                .append(",\"brokenBusId\":").append(busId)
                .append(",\"breakdownLocation\":\"").append(escape(location)).append("\"")
                .append(",\"nearestNode\":\"").append(escape(location)).append("\"")
                .append(",\"estimatedPickupMinutes\":").append(reliefRoute.totalTravelTime)
                .append(",\"breakdown\":{")
                .append("\"busId\":").append(busId)
                .append(",\"latitude\":").append(bLat)
                .append(",\"longitude\":").append(bLng)
                .append(",\"location\":\"").append(escape(location)).append("\"")
                .append(",\"description\":\"").append(escape(desc)).append("\"")
                .append("}")
                .append(",\"totalPassengers\":").append(totalPassengers)
                .append(",\"onboardCount\":").append(onboardList.size())
                .append(",\"waitingCount\":").append(waitingList.size())
                .append(",\"remainingStops\":[");
            for (int i = 0; i < remainingStopNames.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(remainingStopNames.get(i))).append("\"");
            }
            json.append("]")
                .append(",\"alternateRoute\":{")
                .append("\"success\":").append(altRoute.reachable)
                .append(",\"reachable\":").append(altRoute.reachable)
                .append(",\"totalTravelTime\":").append(altRoute.totalTravelTime)
                .append(",\"totalTimeMinutes\":").append(altRoute.totalTravelTime)
                .append(",\"totalDistanceKm\":").append(altRoute.totalDistanceKm)
                .append(",\"recommendedPath\":\"").append(escape(altRoute.recommendedPath)).append("\"")
                .append(",\"orderedStops\":[");
            for (int i = 0; i < altRoute.orderedStops.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(altRoute.orderedStops.get(i))).append("\"");
            }
            json.append("],\"path\":[");
            for (int i = 0; i < altRoute.orderedStops.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(altRoute.orderedStops.get(i))).append("\"");
            }
            json.append("],\"pathNodeDetails\":[");
            for (int i = 0; i < altRoute.pathNodeDetails.size(); i++) {
                if (i > 0) json.append(',');
                DijkstraRouter.NodeDetail nd = altRoute.pathNodeDetails.get(i);
                json.append("{\"name\":\"").append(escape(nd.nodeName)).append("\"")
                    .append(",\"lat\":").append(nd.latitude)
                    .append(",\"latitude\":").append(nd.latitude)
                    .append(",\"lng\":").append(nd.longitude)
                    .append(",\"longitude\":").append(nd.longitude)
                    .append(",\"edgeWeightMinutes\":").append(nd.edgeWeightMinutes)
                    .append(",\"edgeDistanceKm\":").append(nd.edgeDistanceKm)
                    .append(",\"roadName\":\"").append(escape(nd.roadName)).append("\"")
                    .append("}");
            }
            json.append("]},\"standbyRoute\":{")
                .append("\"success\":").append(reliefRoute.reachable)
                .append(",\"reachable\":").append(reliefRoute.reachable)
                .append(",\"totalTravelTime\":").append(reliefRoute.totalTravelTime)
                .append(",\"totalTimeMinutes\":").append(reliefRoute.totalTravelTime)
                .append(",\"totalDistanceKm\":").append(reliefRoute.totalDistanceKm)
                .append(",\"recommendedPath\":\"").append(escape(reliefRoute.recommendedPath)).append("\"")
                .append(",\"orderedStops\":[");
            for (int i = 0; i < reliefRoute.orderedStops.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(reliefRoute.orderedStops.get(i))).append("\"");
            }
            json.append("],\"path\":[");
            for (int i = 0; i < reliefRoute.orderedStops.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(reliefRoute.orderedStops.get(i))).append("\"");
            }
            json.append("],\"pathNodeDetails\":[");
            for (int i = 0; i < reliefRoute.pathNodeDetails.size(); i++) {
                if (i > 0) json.append(',');
                DijkstraRouter.NodeDetail nd = reliefRoute.pathNodeDetails.get(i);
                json.append("{\"name\":\"").append(escape(nd.nodeName)).append("\"")
                    .append(",\"lat\":").append(nd.latitude)
                    .append(",\"latitude\":").append(nd.latitude)
                    .append(",\"lng\":").append(nd.longitude)
                    .append(",\"longitude\":").append(nd.longitude)
                    .append(",\"edgeWeightMinutes\":").append(nd.edgeWeightMinutes)
                    .append(",\"edgeDistanceKm\":").append(nd.edgeDistanceKm)
                    .append(",\"roadName\":\"").append(escape(nd.roadName)).append("\"")
                    .append("}");
            }
            json.append("]},\"replacementBus\":{")
                .append("\"busId\":4")
                .append(",\"name\":\"Mini Bus\"")
                .append(",\"number\":\"TN-33-MB-201\"")
                .append(",\"capacity\":").append(standbyCapacity)
                .append(",\"capacitySufficient\":").append(capacitySufficient)
                .append(",\"seatDeficit\":").append(seatDeficit)
                .append(",\"dispatchEtaMinutes\":").append(reliefRoute.totalTravelTime)
                .append(",\"estimatedPickupMinutes\":").append(reliefRoute.totalTravelTime)
                .append(",\"depotLocation\":\"").append(escape(depotLocation)).append("\"")
                .append(",\"pickupRoute\":{")
                .append("\"success\":").append(reliefRoute.reachable)
                .append(",\"reachable\":").append(reliefRoute.reachable)
                .append(",\"totalTravelTime\":").append(reliefRoute.totalTravelTime)
                .append(",\"totalTimeMinutes\":").append(reliefRoute.totalTravelTime)
                .append(",\"totalDistanceKm\":").append(reliefRoute.totalDistanceKm)
                .append(",\"recommendedPath\":\"").append(escape(reliefRoute.recommendedPath)).append("\"")
                .append(",\"orderedStops\":[");
            for (int i = 0; i < reliefRoute.orderedStops.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(reliefRoute.orderedStops.get(i))).append("\"");
            }
            json.append("],\"path\":[");
            for (int i = 0; i < reliefRoute.orderedStops.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(reliefRoute.orderedStops.get(i))).append("\"");
            }
            json.append("],\"pathNodeDetails\":[");
            for (int i = 0; i < reliefRoute.pathNodeDetails.size(); i++) {
                if (i > 0) json.append(',');
                DijkstraRouter.NodeDetail nd = reliefRoute.pathNodeDetails.get(i);
                json.append("{\"name\":\"").append(escape(nd.nodeName)).append("\"")
                    .append(",\"lat\":").append(nd.latitude)
                    .append(",\"latitude\":").append(nd.latitude)
                    .append(",\"lng\":").append(nd.longitude)
                    .append(",\"longitude\":").append(nd.longitude)
                    .append(",\"edgeWeightMinutes\":").append(nd.edgeWeightMinutes)
                    .append(",\"edgeDistanceKm\":").append(nd.edgeDistanceKm)
                    .append(",\"roadName\":\"").append(escape(nd.roadName)).append("\"")
                    .append("}");
            }
            json.append("]}}}");

            send(exchange, 200, json.toString());
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    // =========================================================================
    // BREAKDOWN INCIDENT TRIAGE & RECOVERY ENGINE
    // =========================================================================

    private static void breakdownCalculateRouteApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if (!allow(exchange, "GET")) return;

        String query = exchange.getRequestURI().getQuery();
        int busId = 1;
        String breakdownLocation = "Thindal Murugan Temple";
        String destination = "Kongu Engineering College";
        boolean simulateUnreachable = false;
        boolean simulateDeficit = false;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2) {
                    String k = pair[0];
                    String v = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    if ("bus_id".equalsIgnoreCase(k)) {
                        try { busId = Integer.parseInt(v); } catch (Exception ignored) {}
                    }
                    if ("breakdown_location".equalsIgnoreCase(k)) breakdownLocation = v;
                    if ("destination".equalsIgnoreCase(k)) destination = v;
                    if ("simulate_unreachable".equalsIgnoreCase(k)) simulateUnreachable = Boolean.parseBoolean(v);
                    if ("simulate_deficit".equalsIgnoreCase(k)) simulateDeficit = Boolean.parseBoolean(v);
                }
            }
        }

        try (Connection con = DBConnection.getConnection()) {
            // 1. Triage Students: Query students assigned to this bus and classify onboard vs waiting
            List<String> onboardList = new ArrayList<>();
            List<String> waitingList = new ArrayList<>();

            String stuSql = "SELECT s.student_id, s.name, s.pickup_stop, COALESCE(a.boarded, 0) AS boarded "
                    + "FROM students s "
                    + "LEFT JOIN attendance a ON s.student_id = a.student_id AND a.attendance_date = CURRENT_DATE "
                    + "WHERE s.assigned_bus_id = ?";

            try (PreparedStatement ps = con.prepareStatement(stuSql)) {
                ps.setInt(1, busId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String sName = rs.getString("name");
                        String stop = rs.getString("pickup_stop");
                        boolean boarded = rs.getBoolean("boarded");

                        if (boarded || (stop != null && stop.toLowerCase().contains("erode"))) {
                            onboardList.add(sName + " (" + stop + ")");
                        } else {
                            waitingList.add(sName + " (" + stop + ")");
                        }
                    }
                }
            }

            int onboardCount = onboardList.size();
            int waitingCount = waitingList.size();
            int totalPassengers = onboardCount + waitingCount;

            // Handle simulated capacity deficit
            if (simulateDeficit) {
                totalPassengers = 12; // Exceeds Mini Bus capacity of 10
            }

            // 2. Dijkstra Route Calculation
            Map<String, List<DijkstraRouter.RoadEdge>> graph = DijkstraRouter.loadGraph(con);
            DijkstraRouter.RouteResult altRoute;
            int dispatchEta = 16;

            // Query assigned stops for the broken bus to determine remaining required stops
            List<String> busStops = new ArrayList<>();
            List<String> remainingStopNames = new ArrayList<>();
            int brokenIdx = -1;

            String busStopsSql = "SELECT s.stop_name, s.stop_order "
                    + "FROM stops s JOIN buses b ON s.route_id = b.route_id WHERE b.bus_id = ? ORDER BY s.stop_order ASC";
            try (PreparedStatement psStops = con.prepareStatement(busStopsSql)) {
                psStops.setInt(1, busId);
                try (ResultSet rsStops = psStops.executeQuery()) {
                    int idx = 0;
                    while (rsStops.next()) {
                        String sName = rsStops.getString("stop_name");
                        busStops.add(sName);
                        if (sName.equalsIgnoreCase(breakdownLocation) || breakdownLocation.toLowerCase().contains(sName.toLowerCase()) || sName.toLowerCase().contains(breakdownLocation.toLowerCase())) {
                            brokenIdx = idx;
                        }
                        idx++;
                    }
                }
            }

            if (brokenIdx == -1) {
                String nearest = DijkstraRouter.resolveNodeName(graph, breakdownLocation);
                for (int i = 0; i < busStops.size(); i++) {
                    if (busStops.get(i).equalsIgnoreCase(nearest)) {
                        brokenIdx = i;
                        break;
                    }
                }
            }

            int startRemaining = (brokenIdx >= 0) ? (brokenIdx + 1) : 1;
            for (int i = startRemaining; i < busStops.size(); i++) {
                remainingStopNames.add(busStops.get(i));
            }

            if (simulateUnreachable) {
                altRoute = new DijkstraRouter.RouteResult();
                altRoute.reachable = false;
                altRoute.totalTravelTime = -1;
                altRoute.routeStatus = "Unreachable";
                altRoute.statusDescription = "All connecting arterial corridors to destination hub are blocked.";
            } else {
                Map<String, double[]> coords = DijkstraRouter.loadNodeCoordinates(con);
                if (!remainingStopNames.isEmpty()) {
                    altRoute = DijkstraRouter.findMultiStopRoute(graph, coords, breakdownLocation, remainingStopNames, destination);
                } else {
                    altRoute = DijkstraRouter.findShortestRoute(graph, coords, breakdownLocation, destination);
                }
                DijkstraRouter.RouteResult dispatch = DijkstraRouter.findShortestRoute(graph, coords, "Perundurai Depot", breakdownLocation);
                dispatchEta = dispatch.reachable ? dispatch.totalTravelTime : 15;
            }

            // 3. Evaluate Standby Replacement Bus Seating Capacity
            int standbyCapacity = 10; // Mini Bus TN-33-MB-201
            boolean capacitySufficient = (totalPassengers <= standbyCapacity);
            int seatDeficit = Math.max(0, totalPassengers - standbyCapacity);
            String warningMsg = null;
            String recommendation;

            if (capacitySufficient) {
                recommendation = "Standby Mini Bus (10 Seats) has sufficient capacity for all " + totalPassengers + " passengers.";
            } else {
                warningMsg = "WARNING: Insufficient seating capacity! Standby Mini Bus has " + standbyCapacity
                        + " seats, but " + totalPassengers + " passengers require transport (Deficit: " + seatDeficit + " seats). Additional shuttle deployment required.";
                recommendation = "Deploy secondary auxiliary vehicle or re-route next regular shuttle to cover downstream waiting stops.";
            }

            StringBuilder json = new StringBuilder("{");
            json.append("\"brokenBusId\":").append(busId)
                .append(",\"breakdownLocation\":\"").append(escape(breakdownLocation)).append("\"")
                .append(",\"remainingStops\":[");
            for (int i = 0; i < remainingStopNames.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(remainingStopNames.get(i))).append("\"");
            }
            json.append("]")
                .append(",\"affectedStudents\":{")
                .append("\"totalCount\":").append(totalPassengers)
                .append(",\"onboardCount\":").append(onboardCount)
                .append(",\"waitingCount\":").append(waitingCount)
                .append(",\"onboardList\":[");
            for (int i = 0; i < onboardList.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(onboardList.get(i))).append("\"");
            }
            json.append("],\"waitingList\":[");
            for (int i = 0; i < waitingList.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(waitingList.get(i))).append("\"");
            }
            json.append("]},\"alternateRoute\":{")
                .append("\"reachable\":").append(altRoute.reachable)
                .append(",\"totalTravelTime\":").append(altRoute.totalTravelTime)
                .append(",\"dispatchEtaMinutes\":").append(dispatchEta)
                .append(",\"recommendedPath\":\"").append(escape(altRoute.recommendedPath)).append("\"")
                .append(",\"routeStatus\":\"").append(escape(altRoute.routeStatus)).append("\"")
                .append(",\"errorMessage\":\"").append(escape(altRoute.statusDescription)).append("\"")
                .append(",\"orderedStops\":[");
            for (int i = 0; i < altRoute.orderedStops.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(altRoute.orderedStops.get(i))).append("\"");
            }
            json.append("],\"roadSegments\":[");
            for (int i = 0; i < altRoute.roadSegments.size(); i++) {
                if (i > 0) json.append(',');
                json.append("\"").append(escape(altRoute.roadSegments.get(i))).append("\"");
            }
            json.append("]},\"replacementBus\":{")
                .append("\"id\":4")
                .append(",\"name\":\"Mini Bus\"")
                .append(",\"number\":\"TN-33-MB-201\"")
                .append(",\"capacity\":").append(standbyCapacity)
                .append(",\"capacitySufficient\":").append(capacitySufficient)
                .append(",\"seatDeficit\":").append(seatDeficit)
                .append(",\"warning\":").append(warningMsg != null ? "\"" + escape(warningMsg) + "\"" : "null")
                .append(",\"recommendation\":\"").append(escape(recommendation)).append("\"")
                .append("}}");

            send(exchange, 200, json.toString());
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void breakdownApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String activeBreakdownsSql = "SELECT bd.breakdown_id, bd.broken_bus_id, bd.replacement_bus_id, "
                    + "bd.delay_minutes, bd.status, bd.reported_at, bd.breakdown_location, bd.alternate_route, "
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
                    + "bd.breakdown_id, bd.breakdown_location "
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
                            .append(",\"brokenBusName\":\"").append(escape(rsBd.getString("broken_bus_name"))).append("\"")
                            .append(",\"brokenBusNumber\":\"").append(escape(rsBd.getString("broken_bus_number"))).append("\"")
                            .append(",\"brokenRoute\":\"").append(escape(rsBd.getString("broken_route_name"))).append("\"")
                            .append(",\"brokenDriver\":\"").append(escape(rsBd.getString("broken_driver_name"))).append("\"")
                            .append(",\"replacementBusId\":").append(rsBd.getInt("replacement_bus_id"))
                            .append(",\"repBusName\":\"").append(escape(rsBd.getString("rep_bus_name"))).append("\"")
                            .append(",\"repBusNumber\":\"").append(escape(rsBd.getString("rep_bus_number"))).append("\"")
                            .append(",\"repDriver\":\"").append(escape(rsBd.getString("rep_driver_name"))).append("\"")
                            .append(",\"delayMinutes\":").append(rsBd.getInt("delay_minutes"))
                            .append(",\"breakdownLocation\":\"").append(escape(rsBd.getString("breakdown_location"))).append("\"")
                            .append(",\"alternateRoute\":\"").append(escape(rsBd.getString("alternate_route"))).append("\"")
                            .append(",\"status\":\"").append(escape(rsBd.getString("status"))).append("\"")
                            .append(",\"reportedAt\":\"").append(rsBd.getTimestamp("reported_at") != null ? rsBd.getTimestamp("reported_at").toString() : "").append("\"")
                            .append(",\"assignedPassengers\":[");
                        List<String> miniBusPax = new ArrayList<>();
                        try (PreparedStatement psPax = con.prepareStatement("SELECT name, pickup_stop FROM students WHERE assigned_bus_id = 4 ORDER BY student_id ASC");
                             ResultSet rsPax = psPax.executeQuery()) {
                            while (rsPax.next()) {
                                miniBusPax.add(rsPax.getString("name") + " (" + rsPax.getString("pickup_stop") + ")");
                            }
                        }
                        for (int pi = 0; pi < miniBusPax.size(); pi++) {
                            if (pi > 0) activeJson.append(',');
                            activeJson.append('\"').append(escape(miniBusPax.get(pi))).append('\"');
                        }
                        activeJson.append("]}");
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
                            .append(",\"name\":\"").append(escape(rsFleet.getString("bus_name"))).append("\"")
                            .append(",\"number\":\"").append(escape(rsFleet.getString("bus_number"))).append("\"")
                            .append(",\"driver\":\"").append(escape(rsFleet.getString("driver_name"))).append("\"")
                            .append(",\"capacity\":").append(rsFleet.getInt("capacity"))
                            .append(",\"route\":\"").append(escape(rsFleet.getString("route_name"))).append("\"")
                            .append(",\"type\":\"").append(escape(rsFleet.getString("bus_type"))).append("\"")
                            .append(",\"status\":\"").append(escape(rsFleet.getString("status"))).append("\"")
                            .append(",\"delayMinutes\":").append(rsFleet.getInt("active_delay"))
                            .append(",\"breakdownLocation\":\"").append(escape(rsFleet.getString("breakdown_location"))).append("\"")
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
                send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        // RBAC Check on reporting breakdowns
        UserSession session = getSession(exchange);
        if (session != null && "student".equalsIgnoreCase(session.role)) {
            send(exchange, 403, "{\"error\":\"Access denied: Students cannot dispatch emergency replacement shuttles.\"}");
            return;
        }

        Map<String, String> form = formData(readBody(exchange));
        String query = exchange.getRequestURI().getQuery();
        boolean isResolve = (query != null && query.contains("action=resolve")) || "resolve".equalsIgnoreCase(form.get("action"));

        try (Connection con = DBConnection.getConnection()) {
            if (isResolve) {
                String busIdStr = form.get("bus_id");
                if (busIdStr == null) busIdStr = form.get("id");
                if (busIdStr == null || busIdStr.isBlank()) {
                    send(exchange, 400, "{\"error\":\"bus_id is required to resolve incident\"}");
                    return;
                }
                int brokenBusId = Integer.parseInt(busIdStr);

                String resolveBdSql = "UPDATE breakdowns SET status = 'Resolved', resolved_at = CURRENT_TIMESTAMP WHERE broken_bus_id = ? AND status = 'Active'";
                String restoreBusSql = "UPDATE buses SET status = 'Active' WHERE bus_id = ?";
                String restoreRepSql = "UPDATE buses SET status = 'Standby Depot' WHERE bus_id = 4";
                String restoreStudentsSql = "UPDATE students SET assigned_bus_id = ? WHERE assigned_bus_id = 4";

                try (PreparedStatement ps1 = con.prepareStatement(resolveBdSql);
                     PreparedStatement ps2 = con.prepareStatement(restoreBusSql);
                     PreparedStatement ps3 = con.prepareStatement(restoreRepSql);
                     PreparedStatement ps4 = con.prepareStatement(restoreStudentsSql)) {
                    ps1.setInt(1, brokenBusId);
                    int updated = ps1.executeUpdate();
                    if (updated == 0) {
                        send(exchange, 404, "{\"error\":\"No active breakdown found for Bus #" + brokenBusId + "\"}");
                        return;
                    }
                    ps2.setInt(1, brokenBusId);
                    ps2.executeUpdate();
                    ps3.executeUpdate();

                    // Restore reassigned students back to regular bus
                    ps4.setInt(1, brokenBusId);
                    ps4.executeUpdate();

                    send(exchange, 200, "{\"message\":\"Breakdown resolved successfully. Regular bus restored to Active, standby Mini Bus returned to depot, and students reassigned to regular bus.\",\"busId\":" + brokenBusId + "}");
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

            // Prevent duplicate deployments for the same broken bus
            String checkActiveSql = "SELECT breakdown_id FROM breakdowns WHERE broken_bus_id = ? AND status = 'Active'";
            try (PreparedStatement psCheck = con.prepareStatement(checkActiveSql)) {
                psCheck.setInt(1, brokenBusId);
                try (ResultSet rsCheck = psCheck.executeQuery()) {
                    if (rsCheck.next()) {
                        send(exchange, 400, "{\"error\":\"An active breakdown recovery is already deployed for Bus #" + brokenBusId + ". Please resolve the active incident before deploying another.\"}");
                        return;
                    }
                }
            }

            String repIdStr = form.get("replacement_id");
            int replacementBusId = 4;
            if (repIdStr != null && !repIdStr.isBlank()) {
                try { replacementBusId = Integer.parseInt(repIdStr); } catch (NumberFormatException ignored) {}
            }

            int delayMinutes = 20;
            String delayStr = form.get("delay");
            if (delayStr != null && !delayStr.isBlank()) {
                try { delayMinutes = Integer.parseInt(delayStr); } catch (NumberFormatException ignored) {}
            }

            String breakdownLoc = form.getOrDefault("breakdown_location", "En Route");
            String altRoute = form.getOrDefault("alternate_route", "");

            // Query Standby Mini Bus capacity
            int miniBusCapacity = 10;
            try (PreparedStatement psCap = con.prepareStatement("SELECT capacity FROM buses WHERE bus_id = ?")) {
                psCap.setInt(1, replacementBusId);
                try (ResultSet rsCap = psCap.executeQuery()) {
                    if (rsCap.next()) miniBusCapacity = rsCap.getInt("capacity");
                }
            }

            // Identify affected students assigned to the broken bus
            List<Integer> affectedStudentIds = new ArrayList<>();
            try (PreparedStatement psStu = con.prepareStatement("SELECT student_id FROM students WHERE assigned_bus_id = ? ORDER BY student_id ASC")) {
                psStu.setInt(1, brokenBusId);
                try (ResultSet rsStu = psStu.executeQuery()) {
                    while (rsStu.next()) {
                        affectedStudentIds.add(rsStu.getInt("student_id"));
                    }
                }
            }

            con.setAutoCommit(false);
            try {
                // 1. Insert breakdown record
                String insertBdSql = "INSERT INTO breakdowns (broken_bus_id, replacement_bus_id, delay_minutes, status, breakdown_location, alternate_route) "
                        + "VALUES (?, ?, ?, 'Active', ?, ?)";
                int newBdId = 0;
                try (PreparedStatement psIns = con.prepareStatement(insertBdSql, Statement.RETURN_GENERATED_KEYS)) {
                    psIns.setInt(1, brokenBusId);
                    psIns.setInt(2, replacementBusId);
                    psIns.setInt(3, delayMinutes);
                    psIns.setString(4, breakdownLoc);
                    psIns.setString(5, altRoute);
                    psIns.executeUpdate();
                    try (ResultSet gk = psIns.getGeneratedKeys()) {
                        if (gk.next()) newBdId = gk.getInt(1);
                    }
                }

                // 2. Update regular bus status and mini bus status in MySQL
                try (PreparedStatement psUpBroken = con.prepareStatement("UPDATE buses SET status = 'Breakdown Reported' WHERE bus_id = ?");
                     PreparedStatement psUpRep = con.prepareStatement("UPDATE buses SET status = 'Deployed (Relief)' WHERE bus_id = ?")) {
                    psUpBroken.setInt(1, brokenBusId);
                    psUpBroken.executeUpdate();
                    psUpRep.setInt(1, replacementBusId);
                    psUpRep.executeUpdate();
                }

                // 3. Reassign affected students to the standby mini bus up to capacity
                int accommodatedCount = 0;
                String reassignSql = "UPDATE students SET assigned_bus_id = ? WHERE student_id = ?";
                try (PreparedStatement psReassign = con.prepareStatement(reassignSql)) {
                    for (int sid : affectedStudentIds) {
                        if (accommodatedCount < miniBusCapacity) {
                            psReassign.setInt(1, replacementBusId);
                            psReassign.setInt(2, sid);
                            psReassign.executeUpdate();
                            accommodatedCount++;
                        }
                    }
                }

                // 4. Create in-app notifications for affected students
                String insNoticeSql = "INSERT INTO student_notifications (student_id, breakdown_id, title, message) VALUES (?, ?, ?, ?)";
                try (PreparedStatement psNotice = con.prepareStatement(insNoticeSql)) {
                    for (int sid : affectedStudentIds) {
                        psNotice.setInt(1, sid);
                        psNotice.setInt(2, newBdId);
                        psNotice.setString(3, "Bus Breakdown & Relief Shuttle Dispatched");
                        psNotice.setString(4, "Your regular bus has experienced a breakdown. A replacement mini bus has been assigned and is on its way to your pickup stop.");
                        psNotice.executeUpdate();
                    }
                }

                con.commit();

                String json = "{\"success\":true,\"message\":\"Standby Mini Bus successfully deployed and " + delayMinutes + " minute delay notice broadcasted to student transit hub.\","
                        + "\"breakdownId\":" + newBdId + ","
                        + "\"brokenBusId\":" + brokenBusId + ","
                        + "\"replacementBusId\":" + replacementBusId + ","
                        + "\"delayMinutes\":" + delayMinutes + ","
                        + "\"deployedBus\":\"Mini Bus (TN-33-MB-201)\","
                        + "\"accommodatedCount\":" + accommodatedCount + ","
                        + "\"totalAffected\":" + affectedStudentIds.size() + ","
                        + "\"brokenBusStatus\":\"Breakdown Reported\","
                        + "\"miniBusStatus\":\"Deployed (Relief)\"}";
                send(exchange, 200, json);
            } catch (SQLException ex) {
                con.rollback();
                throw ex;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Failed to process breakdown: " + escape(e.getMessage()) + "\"}");
        }
    }

    // =========================================================================
    // STUDENT PORTAL SPECIFIC ENDPOINTS (RBAC ENFORCED)
    // =========================================================================

    private static int resolveStudentId(HttpExchange exchange, String query) {
        String idParam = null;
        String rollParam = null;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2) {
                    String k = pair[0].toLowerCase();
                    String v = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    if (k.contains("student_id") || k.equals("id")) {
                        idParam = v;
                    } else if (k.contains("reg") || k.contains("roll")) {
                        rollParam = v;
                    }
                }
            }
        }
        if (idParam != null && !idParam.isBlank()) {
            try { return Integer.parseInt(idParam); } catch (Exception ignored) {}
        }
        if (rollParam != null && !rollParam.isBlank()) {
            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT student_id FROM students WHERE roll_number = ? LIMIT 1")) {
                ps.setString(1, rollParam);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt("student_id");
                }
            } catch (Exception ignored) {}
        }
        UserSession session = getSession(exchange);
        if (session != null && session.studentId > 0) {
            return session.studentId;
        }
        return 105; // Default fallback
    }

    private static boolean verifyStudentAccess(HttpExchange exchange, int requestedStudentId) throws IOException {
        UserSession session = getSession(exchange);
        if (session != null && "student".equalsIgnoreCase(session.role)) {
            if (session.studentId > 0 && session.studentId != requestedStudentId) {
                send(exchange, 403, "{\"error\":\"Access denied: Unauthorized access to another student's transit data.\"}");
                return false;
            }
        }
        return true;
    }

    private static void studentDashboardApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if (!allow(exchange, "GET")) return;

        int studentId = resolveStudentId(exchange, exchange.getRequestURI().getQuery());
        if (!verifyStudentAccess(exchange, studentId)) return;

        LocalDate today = LocalDate.now();

        String sql = "SELECT s.student_id, s.name, s.roll_number, s.department, s.pickup_stop, s.transport_authorized, s.assigned_bus_id, "
                + "b.bus_name, b.bus_number, b.driver_name, b.driver_phone, b.status AS bus_status, "
                + "f.amount AS fee_amount, f.payment_status AS fee_status, "
                + "a.status AS attendance_status, a.boarded, COALESCE(a.travel_intention, 1) AS travel_intention "
                + "FROM students s "
                + "LEFT JOIN buses b ON s.assigned_bus_id = b.bus_id "
                + "LEFT JOIN fees f ON s.student_id = f.student_id "
                + "LEFT JOIN attendance a ON s.student_id = a.student_id AND a.attendance_date = ? "
                + "WHERE s.student_id = ?";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(today));
            ps.setInt(2, studentId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    boolean auth = rs.getBoolean("transport_authorized");
                    String feeStatus = rs.getString("fee_status");
                    if (feeStatus == null) feeStatus = auth ? "Paid" : "Pending";
                    String pickupStop = rs.getString("pickup_stop");
                    if (pickupStop == null || pickupStop.isBlank()) pickupStop = "Perundurai Old Bus Stand";
                    double distKm = getStopDistanceKm(con, pickupStop);
                    int semesterTrips = 200; // 6 Months (100 college working days x 2 daily round trips)
                    double calculatedFee = Math.round(distKm * 2.0 * semesterTrips * 100.0) / 100.0;

                    // Check active breakdowns and notifications affecting this student
                    int busId = rs.getInt("assigned_bus_id");
                    boolean hasAlert = false;
                    String alertTitle = "";
                    String alertMessage = "";
                    int delayMinutes = 0;

                    // 1. Check student_notifications
                    String noticeSql = "SELECT title, message FROM student_notifications WHERE student_id = ? ORDER BY notification_id DESC LIMIT 1";
                    try (PreparedStatement psNotice = con.prepareStatement(noticeSql)) {
                        psNotice.setInt(1, studentId);
                        try (ResultSet rsNotice = psNotice.executeQuery()) {
                            if (rsNotice.next()) {
                                hasAlert = true;
                                alertTitle = rsNotice.getString("title");
                                alertMessage = rsNotice.getString("message");
                            }
                        }
                    }

                    // 2. Fallback check for active breakdown on assigned bus (or replacement bus 4)
                    if (!hasAlert && busId > 0) {
                        String alertSql = "SELECT delay_minutes, breakdown_location, alternate_route FROM breakdowns "
                                + "WHERE (broken_bus_id = ? OR (replacement_bus_id = ? AND ? = 4)) AND status = 'Active'";
                        try (PreparedStatement psAlert = con.prepareStatement(alertSql)) {
                            psAlert.setInt(1, busId);
                            psAlert.setInt(2, busId);
                            psAlert.setInt(3, busId);
                            try (ResultSet rsAlert = psAlert.executeQuery()) {
                                if (rsAlert.next()) {
                                    hasAlert = true;
                                    delayMinutes = rsAlert.getInt("delay_minutes");
                                    alertTitle = "Bus Breakdown & Relief Shuttle Dispatched";
                                    alertMessage = "Your regular bus has experienced a breakdown. A replacement mini bus has been assigned and is on its way to your pickup stop.";
                                }
                            }
                        }
                    }

                    String json = "{\"studentId\":" + rs.getInt("student_id")
                            + ",\"name\":\"" + escape(rs.getString("name")) + "\""
                            + ",\"student\":{\"id\":" + rs.getInt("student_id") + ",\"name\":\"" + escape(rs.getString("name")) + "\",\"regNo\":\"" + escape(rs.getString("roll_number")) + "\",\"rollNumber\":\"" + escape(rs.getString("roll_number")) + "\"}"
                            .concat(",\"rollNumber\":\"") + escape(rs.getString("roll_number")) + "\""
                            + ",\"department\":\"" + escape(rs.getString("department")) + "\""
                            + ",\"pickupStop\":\"" + escape(pickupStop) + "\""
                            + ",\"stop\":\"" + escape(pickupStop) + "\""
                            + ",\"distanceKm\":" + distKm
                            + ",\"ratePerKm\":2.0"
                            + ",\"bus\":\"" + escape(rs.getString("bus_name")) + "\""
                            + ",\"busNumber\":\"" + escape(rs.getString("bus_number")) + "\""
                            + ",\"feeStatus\":\"" + escape(feeStatus) + "\""
                            + ",\"tomorrowRequired\":" + ("Yes".equalsIgnoreCase(rs.getString("travel_intention")) || "1".equals(rs.getString("travel_intention")) || "true".equalsIgnoreCase(rs.getString("travel_intention")))
                            + ",\"authorized\":" + auth
                            + ",\"assignedBus\":{"
                            + "\"busId\":" + busId
                            + "\"name\":\"" + escape(rs.getString("bus_name")) + "\""
                            + ",\"number\":\"" + escape(rs.getString("bus_number")) + "\""
                            + ",\"driver\":\"" + escape(rs.getString("driver_name")) + "\""
                            + ",\"driverPhone\":\"" + escape(rs.getString("driver_phone")) + "\""
                            + ",\"status\":\"" + escape(rs.getString("bus_status")) + "\""
                            + "},\"fees\":{"
                            + "\"amount\":" + calculatedFee
                            + ",\"distanceKm\":" + distKm
                            + ",\"ratePerKm\":2.0"
                            + ",\"status\":\"" + escape(feeStatus) + "\""
                            + "},\"attendance\":{"
                            + "\"todayStatus\":\"" + escape(rs.getString("attendance_status") != null ? rs.getString("attendance_status") : "Absent") + "\""
                            + ",\"boarded\":" + rs.getBoolean("boarded")
                            + "},\"alerts\":{"
                            + "\"hasAlert\":" + hasAlert
                            + ",\"title\":\"" + escape(alertTitle) + "\""
                            + ",\"message\":\"" + escape(alertMessage) + "\""
                            + ",\"delayMinutes\":" + delayMinutes
                            + "}}";
                    send(exchange, 200, json);
                } else {
                    send(exchange, 404, "{\"error\":\"Student #" + studentId + " not found.\"}");
                }
            }
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void studentAttendanceApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if (!allow(exchange, "GET")) return;

        int studentId = resolveStudentId(exchange, exchange.getRequestURI().getQuery());
        if (!verifyStudentAccess(exchange, studentId)) return;

        String sql = "SELECT attendance_id, attendance_date, travel_intention, boarded, status "
                + "FROM attendance WHERE student_id = ? ORDER BY attendance_date DESC";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                String todayStatus = "Absent";
                boolean todayBoarded = false;
                StringBuilder historyJson = new StringBuilder("[");
                boolean first = true;
                while (rs.next()) {
                    if (first) {
                        todayStatus = rs.getString("status") != null ? rs.getString("status") : "Present";
                        todayBoarded = rs.getBoolean("boarded");
                    }
                    if (!first) historyJson.append(',');
                    first = false;
                    historyJson.append("{\"id\":").append(rs.getInt("attendance_id"))
                        .append(",\"date\":\"").append(rs.getDate("attendance_date").toString())
                        .append("\",\"intention\":").append(rs.getBoolean("travel_intention"))
                        .append(",\"boarded\":").append(rs.getBoolean("boarded"))
                        .append(",\"status\":\"").append(escape(rs.getString("status")))
                        .append("\"}");
                }
                historyJson.append("]");

                String json = "{\"studentId\":" + studentId
                        + ",\"todayStatus\":\"" + escape(todayStatus) + "\""
                        + ",\"boarded\":" + todayBoarded
                        + ",\"history\":" + historyJson.toString() + "}";
                send(exchange, 200, json);
            }
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void studentRouteApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if (!allow(exchange, "GET")) return;

        int studentId = resolveStudentId(exchange, exchange.getRequestURI().getQuery());
        if (!verifyStudentAccess(exchange, studentId)) return;

        String sql = "SELECT s.pickup_stop, s.assigned_bus_id, b.bus_name, b.bus_number, b.driver_name, b.driver_phone, "
                + "r.route_name, r.corridor_name, r.start_location, r.end_location, r.route_id "
                + "FROM students s "
                + "LEFT JOIN buses b ON s.assigned_bus_id = b.bus_id "
                + "LEFT JOIN routes r ON b.route_id = r.route_id "
                + "WHERE s.student_id = ?";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int routeId = rs.getInt("route_id");
                    String pickupStop = rs.getString("pickup_stop");

                    // Get waypoints for this route
                    List<String> stops = new ArrayList<>();
                    String scheduledPickupTime = "07:30 AM";
                    if (routeId > 0) {
                        String stopsSql = "SELECT stop_name, scheduled_time FROM stops WHERE route_id = ? ORDER BY stop_order ASC";
                        try (PreparedStatement psStops = con.prepareStatement(stopsSql)) {
                            psStops.setInt(1, routeId);
                            try (ResultSet rsStops = psStops.executeQuery()) {
                                while (rsStops.next()) {
                                    String sName = rsStops.getString("stop_name");
                                    stops.add(sName);
                                    if (pickupStop != null && sName.toLowerCase().contains(pickupStop.toLowerCase())) {
                                        scheduledPickupTime = rsStops.getString("scheduled_time");
                                    }
                                }
                            }
                        }
                    }

                    StringBuilder json = new StringBuilder("{");
                    json.append("\"studentId\":").append(studentId)
                        .append(",\"pickupStop\":\"").append(escape(pickupStop)).append("\"")
                        .append(",\"scheduledTime\":\"").append(escape(scheduledPickupTime)).append("\"")
                        .append(",\"busName\":\"").append(escape(rs.getString("bus_name"))).append("\"")
                        .append(",\"busNumber\":\"").append(escape(rs.getString("bus_number"))).append("\"")
                        .append(",\"driver\":\"").append(escape(rs.getString("driver_name"))).append("\"")
                        .append(",\"driverPhone\":\"").append(escape(rs.getString("driver_phone"))).append("\"")
                        .append(",\"corridor\":\"").append(escape(rs.getString("corridor_name"))).append("\"")
                        .append(",\"destination\":\"Kongu Engineering College, Perundurai\"")
                        .append(",\"stops\":[");
                    for (int i = 0; i < stops.size(); i++) {
                        if (i > 0) json.append(',');
                        json.append("\"").append(escape(stops.get(i))).append("\"");
                    }
                    json.append("]}");
                    send(exchange, 200, json.toString());
                } else {
                    send(exchange, 404, "{\"error\":\"Student not found.\"}");
                }
            }
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void studentAlertsApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if (!allow(exchange, "GET")) return;

        int studentId = resolveStudentId(exchange, exchange.getRequestURI().getQuery());
        if (!verifyStudentAccess(exchange, studentId)) return;

        String sql = "SELECT sn.notification_id, sn.title, sn.message, sn.created_at, "
                + "bd.breakdown_id, bd.broken_bus_id, bd.delay_minutes, bd.breakdown_location, "
                + "bd.alternate_route, b.bus_name "
                + "FROM student_notifications sn "
                + "LEFT JOIN breakdowns bd ON sn.breakdown_id = bd.breakdown_id "
                + "LEFT JOIN buses b ON bd.broken_bus_id = b.bus_id "
                + "WHERE sn.student_id = ? "
                + "ORDER BY sn.notification_id DESC";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder json = new StringBuilder("[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;
                    String busName = rs.getString("bus_name");
                    if (busName == null) busName = "College Bus";
                    json.append("{\"id\":").append(rs.getInt("notification_id"))
                        .append(",\"breakdownId\":").append(rs.getInt("breakdown_id"))
                        .append(",\"busId\":").append(rs.getInt("broken_bus_id"))
                        .append(",\"busName\":\"").append(escape(busName)).append("\"")
                        .append(",\"title\":\"").append(escape(rs.getString("title"))).append("\"")
                        .append(",\"message\":\"").append(escape(rs.getString("message"))).append("\"")
                        .append(",\"delayMinutes\":").append(rs.getInt("delay_minutes"))
                        .append(",\"location\":\"").append(escape(rs.getString("breakdown_location") != null ? rs.getString("breakdown_location") : "En Route")).append("\"")
                        .append(",\"alternateRoute\":\"").append(escape(rs.getString("alternate_route") != null ? rs.getString("alternate_route") : "")).append("\"")
                        .append(",\"time\":\"").append(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : "").append("\"")
                        .append("}");
                }

                // If no notifications found in student_notifications, fallback to active breakdown check
                if (first) {
                    String fallbackSql = "SELECT bd.breakdown_id, bd.broken_bus_id, bd.delay_minutes, bd.breakdown_location, "
                            + "bd.alternate_route, bd.reported_at, b.bus_name "
                            + "FROM breakdowns bd "
                            + "JOIN students s ON (s.assigned_bus_id = bd.broken_bus_id OR s.assigned_bus_id = bd.replacement_bus_id) "
                            + "JOIN buses b ON bd.broken_bus_id = b.bus_id "
                            + "WHERE s.student_id = ? AND bd.status = 'Active'";
                    try (PreparedStatement psFb = con.prepareStatement(fallbackSql)) {
                        psFb.setInt(1, studentId);
                        try (ResultSet rsFb = psFb.executeQuery()) {
                            while (rsFb.next()) {
                                if (!first) json.append(',');
                                first = false;
                                json.append("{\"id\":").append(rsFb.getInt("breakdown_id"))
                                    .append(",\"breakdownId\":").append(rsFb.getInt("breakdown_id"))
                                    .append(",\"busId\":").append(rsFb.getInt("broken_bus_id"))
                                    .append(",\"busName\":\"").append(escape(rsFb.getString("bus_name"))).append("\"")
                                    .append(",\"title\":\"Bus Breakdown & Relief Shuttle Dispatched\"")
                                    .append(",\"message\":\"Your regular bus has experienced a breakdown. A replacement mini bus has been assigned and is on its way to your pickup stop.\"")
                                    .append(",\"delayMinutes\":").append(rsFb.getInt("delay_minutes"))
                                    .append(",\"location\":\"").append(escape(rsFb.getString("breakdown_location"))).append("\"")
                                    .append(",\"alternateRoute\":\"").append(escape(rsFb.getString("alternate_route") != null ? rsFb.getString("alternate_route") : "")).append("\"")
                                    .append(",\"time\":\"").append(rsFb.getTimestamp("reported_at") != null ? rsFb.getTimestamp("reported_at").toString() : "").append("\"")
                                    .append("}");
                            }
                        }
                    }
                }

                json.append(']');
                send(exchange, 200, json.toString());
            }
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void studentComplaintsApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            int studentId = resolveStudentId(exchange, exchange.getRequestURI().getQuery());
            if (!verifyStudentAccess(exchange, studentId)) return;

            String sql = "SELECT complaint_id, complaint_text, status, created_at FROM complaints WHERE student_id = ? ORDER BY created_at DESC";
            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    StringBuilder json = new StringBuilder("[");
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(',');
                        first = false;
                        json.append("{\"id\":").append(rs.getInt("complaint_id"))
                            .append(",\"text\":\"").append(escape(rs.getString("complaint_text")))
                            .append("\",\"complaint\":\"").append(escape(rs.getString("complaint_text")))
                            .append("\",\"status\":\"").append(escape(rs.getString("status")))
                            .append("\",\"createdAt\":\"").append(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : "")
                            .append("\"}");
                    }
                    json.append(']');
                    send(exchange, 200, json.toString());
                }
            } catch (SQLException e) {
                send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        Map<String, String> form = formData(readBody(exchange));
        int studentId = resolveStudentId(exchange, form.get("student_id"));
        if (!verifyStudentAccess(exchange, studentId)) return;

        String cat = form.getOrDefault("category", "General");
        String subj = form.getOrDefault("subject", "Transport Issue");
        String desc = form.getOrDefault("details", "");
        if (desc.isBlank()) desc = form.getOrDefault("complaint", "");
        String fullText = "[" + cat.toUpperCase() + "] " + subj + (desc.isBlank() ? "" : ": " + desc);

        String sql = "INSERT INTO complaints (student_id, complaint_text, status) VALUES (?, ?, 'Pending')";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, studentId);
            ps.setString(2, fullText);
            ps.executeUpdate();
            int newId = 0;
            try (ResultSet gk = ps.getGeneratedKeys()) {
                if (gk.next()) newId = gk.getInt(1);
            }
            send(exchange, 201, "{\"success\":true,\"message\":\"Grievance submitted successfully.\",\"complaintId\":" + newId + "}");
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void studentFeesApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if (!allow(exchange, "GET")) return;

        int studentId = resolveStudentId(exchange, exchange.getRequestURI().getQuery());
        if (!verifyStudentAccess(exchange, studentId)) return;

        String sql = "SELECT s.student_id, s.name, s.pickup_stop, s.transport_authorized, "
                + "f.amount, f.payment_status, f.payment_date "
                + "FROM students s "
                + "LEFT JOIN fees f ON s.student_id = f.student_id "
                + "WHERE s.student_id = ?";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    boolean auth = rs.getBoolean("transport_authorized");
                    String status = rs.getString("payment_status");
                    if (status == null) status = auth ? "Paid" : "Pending";
                    String pickupStop = rs.getString("pickup_stop");
                    if (pickupStop == null || pickupStop.isBlank()) pickupStop = "Perundurai Old Bus Stand";
                    double distKm = getStopDistanceKm(con, pickupStop);
                    double rate = 2.0;
                    int semesterTrips = 200; // 6 Months (100 college working days x 2 daily round trips)
                    double calculatedFee = Math.round(distKm * rate * semesterTrips * 100.0) / 100.0;

                    String json = "{\"studentId\":" + studentId
                            + ",\"pickupStop\":\"" + escape(pickupStop) + "\""
                            + ",\"distanceKm\":" + distKm
                            + ",\"ratePerKm\":" + rate
                            + ",\"term\":\"6 Months (Semester)\""
                            + ",\"trips\":" + semesterTrips
                            + ",\"amount\":" + calculatedFee
                            + ",\"status\":\"" + escape(status) + "\""
                            + ",\"cleared\":" + "Paid".equalsIgnoreCase(status)
                            + ",\"paymentDate\":\"" + (rs.getDate("payment_date") != null ? rs.getDate("payment_date").toString() : "") + "\"}";
                    send(exchange, 200, json);
                } else {
                    send(exchange, 404, "{\"error\":\"Student not found.\"}");
                }
            }
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void studentProfileApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        int studentId = resolveStudentId(exchange, exchange.getRequestURI().getQuery());
        if (!verifyStudentAccess(exchange, studentId)) return;

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) || "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> form = formData(readBody(exchange));
            String pickupStop = form.get("pickup_stop");
            if (pickupStop == null || pickupStop.trim().isEmpty()) {
                pickupStop = form.get("stop_name");
            }

            if (pickupStop != null && !pickupStop.trim().isEmpty()) {
                String updateSql = "UPDATE students SET pickup_stop = ? WHERE student_id = ?";
                try (Connection con = DBConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement(updateSql)) {
                    ps.setString(1, pickupStop.trim());
                    ps.setInt(2, studentId);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    send(exchange, 500, "{\"error\":\"Database error updating pickup stop: " + escape(e.getMessage()) + "\"}");
                    return;
                }
            }
        }

        String sql = "SELECT s.student_id, s.name, s.roll_number, s.department, s.pickup_stop, s.transport_authorized, "
                + "b.bus_name, b.bus_number "
                + "FROM students s "
                + "LEFT JOIN buses b ON s.assigned_bus_id = b.bus_id "
                + "WHERE s.student_id = ?";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String pickupStop = rs.getString("pickup_stop");
                    double stopLat = 11.2764, stopLng = 77.5833;
                    if (pickupStop != null && !pickupStop.isEmpty()) {
                        String coordSql = "SELECT latitude, longitude FROM graph_nodes WHERE node_name = ? UNION SELECT latitude, longitude FROM stops WHERE stop_name = ? LIMIT 1";
                        try (PreparedStatement psCoord = con.prepareStatement(coordSql)) {
                            psCoord.setString(1, pickupStop);
                            psCoord.setString(2, pickupStop);
                            try (ResultSet rsC = psCoord.executeQuery()) {
                                if (rsC.next()) {
                                    stopLat = rsC.getDouble("latitude");
                                    stopLng = rsC.getDouble("longitude");
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    String json = "{\"studentId\":" + rs.getInt("student_id")
                            + ",\"name\":\"" + escape(rs.getString("name")) + "\""
                            + ",\"rollNumber\":\"" + escape(rs.getString("roll_number")) + "\""
                            + ",\"department\":\"" + escape(rs.getString("department")) + "\""
                            + ",\"pickupStop\":\"" + escape(pickupStop != null ? pickupStop : "") + "\""
                            + ",\"latitude\":" + stopLat
                            + ",\"longitude\":" + stopLng
                            + ",\"authorized\":" + rs.getBoolean("transport_authorized")
                            + ",\"assignedBus\":\"" + escape(rs.getString("bus_name")) + "\""
                            + ",\"busNumber\":\"" + escape(rs.getString("bus_number")) + "\"}";
                    send(exchange, 200, json);
                } else {
                    send(exchange, 404, "{\"error\":\"Student not found.\"}");
                }
            }
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    // =========================================================================
    // GENERAL ADMINISTRATIVE APIS (ATTENDANCE, FEES, DEMAND, MANIFEST)
    // =========================================================================

    private static void attendanceApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if (exchange.getRequestURI().getPath().endsWith("/scan")) {
            attendanceScanApi(exchange);
            return;
        }
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String query = exchange.getRequestURI().getQuery();
            String dateParam = LocalDate.now().toString();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length == 2 && "date".equals(pair[0])) {
                        dateParam = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                }
            }

            Date targetDate;
            try {
                targetDate = Date.valueOf(dateParam);
            } catch (Exception e) {
                send(exchange, 400, "{\"error\":\"Invalid date format\"}");
                return;
            }

            String sql = "SELECT s.student_id, s.name, s.pickup_stop, s.transport_authorized, s.assigned_bus_id, "
                    + "COALESCE(b.bus_name, 'Unallocated') AS bus_name, "
                    + "a.attendance_id, a.attendance_date, "
                    + "COALESCE(a.status, 'Absent') AS attendance_status, "
                    + "COALESCE(a.boarded, 0) AS boarded "
                    + "FROM students s "
                    + "LEFT JOIN buses b ON s.assigned_bus_id = b.bus_id "
                    + "LEFT JOIN attendance a ON s.student_id = a.student_id AND a.attendance_date = ? "
                    + "ORDER BY s.student_id ASC";

            try (Connection con = DBConnection.getConnection()) {
                // Ensure attendance records exist for targetDate initially marked as Absent
                try (PreparedStatement psInit = con.prepareStatement(
                        "INSERT IGNORE INTO attendance (student_id, attendance_date, status, boarded, travel_intention) " +
                        "SELECT student_id, ?, 'Absent', 0, 0 FROM students")) {
                    psInit.setDate(1, targetDate);
                    psInit.executeUpdate();
                } catch (SQLException ignored) {}

                try (PreparedStatement ps = con.prepareStatement(sql)) {
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
                }
            } catch (SQLException e) {
                send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        UserSession session = getSession(exchange);
        if (session != null && "student".equalsIgnoreCase(session.role)) {
            send(exchange, 403, "{\"error\":\"Access denied: Students cannot modify official attendance rolls.\"}");
            return;
        }

        Map<String, String> form = formData(readBody(exchange));
        try {
            int studentId = Integer.parseInt(form.get("student_id"));
            String dateStr = form.getOrDefault("date", LocalDate.now().toString());
            String status = form.getOrDefault("status", "Present");
            boolean boarded = Boolean.parseBoolean(form.getOrDefault("boarded", "false"));

            String sql = "INSERT INTO attendance (student_id, attendance_date, status, boarded) "
                    + "VALUES (?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE status = VALUES(status), boarded = VALUES(boarded)";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, studentId);
                ps.setDate(2, Date.valueOf(dateStr));
                ps.setString(3, status);
                ps.setBoolean(4, boarded);
                ps.executeUpdate();
                send(exchange, 200, "{\"message\":\"Attendance recorded successfully.\"}");
            }
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Failed to update attendance: " + escape(e.getMessage()) + "\"}");
        }
    }

    /**
     * Driver-Facing Student ID Barcode Scanning & Safe Boarding Verification Endpoint
     * POST /api/attendance/scan (or GET with query params for testing)
     * Validates student roll/id against MySQL, checks fee eligibility, verifies bus assignment,
     * prevents duplicate scans on the same trip/day, and updates attendance & trip metrics.
     */
    private static void attendanceScanApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()) && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"Method not allowed. Use POST.\"}");
            return;
        }

        Map<String, String> form;
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            form = formData(readBody(exchange));
        } else {
            form = new HashMap<>();
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String item : query.split("&")) {
                    String[] pair = item.split("=", 2);
                    if (pair.length == 2) {
                        form.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                                 URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                    }
                }
            }
        }

        String rawCode = form.get("barcode");
        if (rawCode == null) rawCode = form.get("code");
        if (rawCode == null) rawCode = form.get("roll_number");
        if (rawCode == null) rawCode = form.get("student_id");
        if (rawCode == null) rawCode = form.get("id");

        if (rawCode == null || rawCode.trim().isEmpty()) {
            send(exchange, 400, "{\"success\":false,\"status\":\"INVALID_INPUT\",\"message\":\"Missing student barcode identifier.\"}");
            return;
        }

        rawCode = rawCode.trim();

        int busId = 1;
        if (form.containsKey("bus_id")) {
            try { busId = Integer.parseInt(form.get("bus_id")); } catch (Exception ignored) {}
        } else if (form.containsKey("busId")) {
            try { busId = Integer.parseInt(form.get("busId")); } catch (Exception ignored) {}
        }

        try (Connection con = DBConnection.getConnection()) {
            // 1. Look up student by roll_number, student_id, or formatted roll
            String stuSql = "SELECT s.student_id, s.name, s.roll_number, s.department, s.transport_authorized, "
                    + "s.assigned_bus_id, s.pickup_stop, "
                    + "COALESCE(b.bus_name, 'Unallocated') AS bus_name, "
                    + "COALESCE(b.bus_number, 'N/A') AS bus_number "
                    + "FROM students s "
                    + "LEFT JOIN buses b ON s.assigned_bus_id = b.bus_id "
                    + "WHERE s.roll_number = ? "
                    + "   OR CAST(s.student_id AS CHAR) = ? "
                    + "   OR s.roll_number = CONCAT('STU', ?) "
                    + "   OR s.roll_number LIKE CONCAT('%', ?) "
                    + "ORDER BY CASE WHEN s.roll_number = ? THEN 1 WHEN CAST(s.student_id AS CHAR) = ? THEN 2 ELSE 3 END "
                    + "LIMIT 1";

            int studentId = 0;
            String name = "";
            String rollNumber = "";
            String department = "";
            boolean authorized = false;
            int assignedBusId = 0;
            String pickupStop = "";
            String busName = "";
            String busNumber = "";

            try (PreparedStatement ps = con.prepareStatement(stuSql)) {
                ps.setString(1, rawCode);
                ps.setString(2, rawCode);
                ps.setString(3, rawCode);
                ps.setString(4, rawCode);
                ps.setString(5, rawCode);
                ps.setString(6, rawCode);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        studentId = rs.getInt("student_id");
                        name = rs.getString("name");
                        rollNumber = rs.getString("roll_number");
                        department = rs.getString("department");
                        authorized = rs.getBoolean("transport_authorized");
                        assignedBusId = rs.getInt("assigned_bus_id");
                        pickupStop = rs.getString("pickup_stop");
                        busName = rs.getString("bus_name");
                        busNumber = rs.getString("bus_number");
                    }
                }
            }

            if (studentId == 0) {
                send(exchange, 404, "{\"success\":false,\"status\":\"STUDENT_NOT_FOUND\",\"barcode\":\"" 
                        + escape(rawCode) + "\",\"message\":\"Unregistered Barcode: No student found matching '" 
                        + escape(rawCode) + "'.\"}");
                return;
            }

            // 2. Validate Transport Authorization (Fee paid / Eligibility)
            if (!authorized) {
                String resp = "{\"success\":false,\"status\":\"UNAUTHORIZED\""
                        + ",\"studentId\":" + studentId
                        + ",\"name\":\"" + escape(name) + "\""
                        + ",\"rollNumber\":\"" + escape(rollNumber) + "\""
                        + ",\"department\":\"" + escape(department) + "\""
                        + ",\"pickupStop\":\"" + escape(pickupStop) + "\""
                        + ",\"assignedBusId\":" + assignedBusId
                        + ",\"message\":\"Access Denied: Student " + escape(name) + " (" + escape(rollNumber) 
                        + ") has NOT paid college transport fees. Unapproved for bus transit.\"}";
                send(exchange, 403, resp);
                return;
            }

            // 3. Validate Bus Allocation (Prevent boarding wrong bus)
            if (assignedBusId > 0 && assignedBusId != busId) {
                String resp = "{\"success\":false,\"status\":\"WRONG_BUS\""
                        + ",\"studentId\":" + studentId
                        + ",\"name\":\"" + escape(name) + "\""
                        + ",\"rollNumber\":\"" + escape(rollNumber) + "\""
                        + ",\"department\":\"" + escape(department) + "\""
                        + ",\"pickupStop\":\"" + escape(pickupStop) + "\""
                        + ",\"assignedBusId\":" + assignedBusId
                        + ",\"assignedBusName\":\"" + escape(busName) + "\""
                        + ",\"scannedBusId\":" + busId
                        + ",\"message\":\"Wrong Bus Alert: " + escape(name) + " (" + escape(rollNumber) 
                        + ") is allocated to Bus " + assignedBusId + " (" + escape(busName) 
                        + "), not Bus " + busId + "!\"}";
                send(exchange, 400, resp);
                return;
            }

            // 4. Duplicate Check for Same Student on Today's Trip
            String dupSql = "SELECT attendance_id, boarded, status, attendance_date FROM attendance "
                    + "WHERE student_id = ? AND attendance_date = CURRENT_DATE";
            boolean alreadyBoarded = false;
            try (PreparedStatement ps = con.prepareStatement(dupSql)) {
                ps.setInt(1, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        alreadyBoarded = rs.getBoolean("boarded");
                    }
                }
            }

            if (alreadyBoarded) {
                String resp = "{\"success\":false,\"status\":\"ALREADY_BOARDED\""
                        + ",\"studentId\":" + studentId
                        + ",\"name\":\"" + escape(name) + "\""
                        + ",\"rollNumber\":\"" + escape(rollNumber) + "\""
                        + ",\"department\":\"" + escape(department) + "\""
                        + ",\"pickupStop\":\"" + escape(pickupStop) + "\""
                        + ",\"assignedBusId\":" + assignedBusId
                        + ",\"message\":\"Duplicate Boarding Notice: Student " + escape(name) 
                        + " (" + escape(rollNumber) + ") has ALREADY boarded Bus " + busId + " today.\"}";
                send(exchange, 409, resp);
                return;
            }

            // 5. Commit Attendance & Safe Boarding to MySQL
            String upsertSql = "INSERT INTO attendance (student_id, attendance_date, status, boarded, travel_intention) "
                    + "VALUES (?, CURRENT_DATE, 'Present', 1, 1) "
                    + "ON DUPLICATE KEY UPDATE status = 'Present', boarded = 1";
            try (PreparedStatement ps = con.prepareStatement(upsertSql)) {
                ps.setInt(1, studentId);
                ps.executeUpdate();
            }

            // 6. Calculate Real-Time Bus Passenger Statistics
            int boardedCount = 0;
            int totalAllocated = 0;
            String statSql = "SELECT "
                    + "(SELECT COUNT(*) FROM attendance a JOIN students s ON a.student_id = s.student_id "
                    + " WHERE s.assigned_bus_id = ? AND a.attendance_date = CURRENT_DATE AND a.boarded = 1) AS boarded_count, "
                    + "(SELECT COUNT(*) FROM students WHERE assigned_bus_id = ?) AS total_allocated";
            try (PreparedStatement ps = con.prepareStatement(statSql)) {
                ps.setInt(1, busId);
                ps.setInt(2, busId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        boardedCount = rs.getInt("boarded_count");
                        totalAllocated = rs.getInt("total_allocated");
                    }
                }
            }

            String resp = "{\"success\":true,\"status\":\"BOARDED_SUCCESS\""
                    + ",\"barcode\":\"" + escape(rawCode) + "\""
                    + ",\"student\":{"
                    + "\"id\":" + studentId
                    + ",\"name\":\"" + escape(name) + "\""
                    + ",\"rollNumber\":\"" + escape(rollNumber) + "\""
                    + ",\"department\":\"" + escape(department) + "\""
                    + ",\"pickupStop\":\"" + escape(pickupStop) + "\""
                    + ",\"authorized\":true"
                    + ",\"assignedBusId\":" + assignedBusId
                    + ",\"busName\":\"" + escape(busName) + "\""
                    + ",\"busNumber\":\"" + escape(busNumber) + "\""
                    + ",\"boarded\":true"
                    + ",\"attendanceDate\":\"" + LocalDate.now().toString() + "\""
                    + ",\"attendanceStatus\":\"Present\""
                    + "},\"tripStats\":{"
                    + "\"busId\":" + busId
                    + ",\"boardedCount\":" + boardedCount
                    + ",\"totalAllocated\":" + totalAllocated
                    + "},\"message\":\"Thank you! Attendance marked. (" + escape(name) + " - " + escape(rollNumber) + ")\"}";

            send(exchange, 200, resp);

        } catch (SQLException e) {
            send(exchange, 500, "{\"success\":false,\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    /**
     * Resolves the pickup stop distance to Kongu Engineering College hub using the
     * project road network and Dijkstra graph distances.
     */
    public static double getStopDistanceKm(Connection con, String stopName) {
        if (stopName == null || stopName.isBlank()) return 10.0;
        String clean = stopName.trim();
        String lower = clean.toLowerCase();
        if (lower.contains("erode central")) return 30.6;
        if (lower.contains("thindal")) return 25.1;
        if (lower.contains("perundurai old") || lower.equals("perundurai")) return 3.2;
        if (lower.contains("bhavani")) return 18.9;
        if (lower.contains("lakshmi nagar")) return 12.1;
        if (lower.contains("perundurai rs") || lower.contains("railway")) return 4.1;
        if (lower.contains("chennimalai")) return 16.7;
        if (lower.contains("vijayamangalam")) return 10.1;
        if (lower.contains("kec campus gate") || lower.contains("campus gate")) return 1.2;
        if (lower.contains("depot")) return 2.6;
        if (lower.contains("chithode")) return 19.6;
        if (lower.contains("modakkurichi")) return 31.7;

        try {
            Map<String, List<DijkstraRouter.RoadEdge>> graph = DijkstraRouter.loadGraph(con);
            String resolved = DijkstraRouter.resolveNodeName(graph, clean);
            if (resolved != null) {
                DijkstraRouter.RouteResult res = DijkstraRouter.findShortestRoute(graph, resolved, "Kongu Engineering College");
                if (res != null && res.reachable && res.totalDistanceKm > 0) {
                    return Math.round(res.totalDistanceKm * 10.0) / 10.0;
                }
            }
        } catch (Exception ignored) {}

        return 10.0;
    }

    private static void feeApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String sql = "SELECT s.student_id, s.name, s.pickup_stop, s.transport_authorized, s.assigned_bus_id, "
                    + "COALESCE(b.bus_name, 'Unallocated') AS bus_name, "
                    + "f.fee_id, f.amount, "
                    + "COALESCE(f.payment_status, CASE WHEN s.transport_authorized = 1 THEN 'Paid' ELSE 'Pending' END) AS payment_status, "
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
                    String stopName = rs.getString("pickup_stop");
                    if (stopName == null || stopName.isBlank()) stopName = "Perundurai Old Bus Stand";
                    double distKm = getStopDistanceKm(con, stopName);
                    double rate = 2.0;
                    int semesterTrips = 200; // 6 Months (100 college working days x 2 daily round trips)
                    double calculatedFee = Math.round(distKm * rate * semesterTrips * 100.0) / 100.0;
                    String status = rs.getString("payment_status");
                    if (status == null || status.isBlank()) status = "Paid";
                    Date payDate = rs.getDate("payment_date");
                    boolean auth = rs.getBoolean("transport_authorized");
                    boolean isPaid = "Paid".equalsIgnoreCase(status);

                    // Sync database fee record so actual distance-based fee is persisted
                    try (PreparedStatement psSync = con.prepareStatement(
                            "INSERT INTO fees (student_id, amount, payment_status, payment_date) VALUES (?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE amount = VALUES(amount)")) {
                        psSync.setInt(1, stuId);
                        psSync.setDouble(2, calculatedFee);
                        psSync.setString(3, status);
                        psSync.setDate(4, payDate != null ? payDate : Date.valueOf(LocalDate.now()));
                        psSync.executeUpdate();
                    } catch (Exception ignored) {}

                    json.append("{\"id\":").append(stuId)
                        .append(",\"studentId\":").append(stuId)
                        .append(",\"name\":\"").append(escape(rs.getString("name")))
                        .append("\",\"bus\":\"").append(escape(rs.getString("bus_name")))
                        .append("\",\"pickupStop\":\"").append(escape(stopName))
                        .append("\",\"stop\":\"").append(escape(stopName))
                        .append("\",\"distanceKm\":").append(distKm)
                        .append(",\"ratePerKm\":").append(rate)
                        .append(",\"amount\":").append(calculatedFee)
                        .append(",\"status\":\"").append(escape(status))
                        .append("\",\"date\":\"").append(payDate != null ? payDate.toString() : "")
                        .append("\",\"cleared\":").append(isPaid || auth)
                        .append('}');
                }
                json.append(']');
                send(exchange, 200, json.toString());
            } catch (SQLException e) {
                send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        UserSession session = getSession(exchange);
        if (session != null && "student".equalsIgnoreCase(session.role)) {
            send(exchange, 403, "{\"error\":\"Access denied: Students cannot alter institutional fee ledgers.\"}");
            return;
        }

        Map<String, String> form = formData(readBody(exchange));
        try {
            int studentId = Integer.parseInt(form.get("student_id"));
            String status = form.getOrDefault("status", "Paid");

            try (Connection con = DBConnection.getConnection()) {
                String stopName = "Perundurai Old Bus Stand";
                try (PreparedStatement psStop = con.prepareStatement("SELECT pickup_stop FROM students WHERE student_id = ?")) {
                    psStop.setInt(1, studentId);
                    try (ResultSet rs = psStop.executeQuery()) {
                        if (rs.next() && rs.getString("pickup_stop") != null) stopName = rs.getString("pickup_stop");
                    }
                }
                double distKm = getStopDistanceKm(con, stopName);
                int semesterTrips = 200; // 6 Months (100 college working days x 2 daily round trips)
                double calculatedFee = Math.round(distKm * 2.0 * semesterTrips * 100.0) / 100.0;

                String sql = "INSERT INTO fees (student_id, amount, payment_status, payment_date) "
                        + "VALUES (?, ?, ?, CURRENT_DATE) ON DUPLICATE KEY UPDATE payment_status = VALUES(payment_status), amount = VALUES(amount)";
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setInt(1, studentId);
                    ps.setDouble(2, calculatedFee);
                    ps.setString(3, status);
                    ps.executeUpdate();
                }

                String stuUpdate = "UPDATE students SET transport_authorized = ? WHERE student_id = ?";
                try (PreparedStatement ps = con.prepareStatement(stuUpdate)) {
                    ps.setBoolean(1, "Paid".equalsIgnoreCase(status));
                    ps.setInt(2, studentId);
                    ps.executeUpdate();
                }
                send(exchange, 200, "{\"message\":\"Fee payment status updated.\",\"amount\":" + calculatedFee + ",\"status\":\"" + escape(status) + "\"}");
            }
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Failed to update fee: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void requirementApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        LocalDate targetDate = LocalDate.now().plusDays(1);
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2 && "date".equals(pair[0])) {
                    try {
                        targetDate = LocalDate.parse(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                    } catch (Exception ignored) {}
                }
            }
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String sql = "SELECT s.student_id, s.name, s.pickup_stop, s.transport_authorized, "
                    + "COALESCE(b.bus_name, 'Unallocated') AS bus_name, "
                    + "COALESCE(d.bus_required, COALESCE(a.travel_intention, 1)) AS bus_required "
                    + "FROM students s "
                    + "LEFT JOIN buses b ON s.assigned_bus_id = b.bus_id "
                    + "LEFT JOIN demands d ON s.student_id = d.student_id AND d.demand_date = ? "
                    + "LEFT JOIN attendance a ON s.student_id = a.student_id AND a.attendance_date = ? "
                    + "ORDER BY s.pickup_stop ASC, s.student_id ASC";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setDate(1, Date.valueOf(targetDate));
                ps.setDate(2, Date.valueOf(targetDate));
                try (ResultSet rs = ps.executeQuery()) {
                    StringBuilder json = new StringBuilder("[");
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(',');
                        first = false;
                        json.append("{\"id\":").append(rs.getInt("student_id"))
                            .append(",\"studentId\":").append(rs.getInt("student_id"))
                            .append(",\"name\":\"").append(escape(rs.getString("name")))
                            .append("\",\"bus\":\"").append(escape(rs.getString("bus_name")))
                            .append("\",\"stop\":\"").append(escape(rs.getString("pickup_stop") != null ? rs.getString("pickup_stop") : "Unspecified"))
                            .append("\",\"pickupStop\":\"").append(escape(rs.getString("pickup_stop") != null ? rs.getString("pickup_stop") : "Unspecified"))
                            .append("\",\"authorized\":").append(rs.getBoolean("transport_authorized"))
                            .append(",\"busRequired\":").append(rs.getBoolean("bus_required"))
                            .append(",\"required\":").append(rs.getBoolean("bus_required"))
                            .append('}');
                    }
                    json.append(']');
                    send(exchange, 200, json.toString());
                }
            } catch (SQLException e) {
                send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        Map<String, String> form = formData(readBody(exchange));
        try {
            int studentId = 0;
            String sidStr = form.get("student_id");
            if (sidStr == null || sidStr.isBlank()) sidStr = form.get("id");
            if (sidStr != null && !sidStr.isBlank()) {
                studentId = Integer.parseInt(sidStr.trim());
            }
            if (studentId <= 0) {
                send(exchange, 400, "{\"error\":\"Valid student ID is required.\"}");
                return;
            }

            boolean required = "yes".equalsIgnoreCase(form.get("required")) || "true".equalsIgnoreCase(form.get("required")) || "1".equals(form.get("required"));
            if (form.containsKey("date") && form.get("date") != null && !form.get("date").trim().isEmpty()) {
                try {
                    targetDate = LocalDate.parse(form.get("date").trim());
                } catch (Exception ignored) {}
            }

            String pickupStop = form.get("pickup_stop");
            if (pickupStop == null || pickupStop.isBlank()) pickupStop = form.get("stop");

            try (Connection con = DBConnection.getConnection()) {
                // If bus is required, validate pickup stop
                if (required) {
                    if (pickupStop == null || pickupStop.trim().isEmpty()) {
                        try (PreparedStatement psCheck = con.prepareStatement("SELECT pickup_stop FROM students WHERE student_id = ?")) {
                            psCheck.setInt(1, studentId);
                            try (ResultSet rs = psCheck.executeQuery()) {
                                if (rs.next()) {
                                    pickupStop = rs.getString("pickup_stop");
                                }
                            }
                        }
                    }
                    if (pickupStop == null || pickupStop.trim().isEmpty()) {
                        send(exchange, 400, "{\"error\":\"Please select a valid pickup stop when bus is required.\"}");
                        return;
                    }
                }

                // If pickup stop provided, update students table
                if (pickupStop != null && !pickupStop.trim().isEmpty()) {
                    try (PreparedStatement psStop = con.prepareStatement("UPDATE students SET pickup_stop = ? WHERE student_id = ?")) {
                        psStop.setString(1, pickupStop.trim());
                        psStop.setInt(2, studentId);
                        psStop.executeUpdate();
                    }
                }

                // Update or insert into demands table (idempotent)
                String checkDemandSql = "SELECT demand_id FROM demands WHERE student_id = ? AND demand_date = ?";
                int existingDemandId = 0;
                try (PreparedStatement psCd = con.prepareStatement(checkDemandSql)) {
                    psCd.setInt(1, studentId);
                    psCd.setDate(2, Date.valueOf(targetDate));
                    try (ResultSet rs = psCd.executeQuery()) {
                        if (rs.next()) existingDemandId = rs.getInt("demand_id");
                    }
                }
                if (existingDemandId > 0) {
                    String updateDemandSql = "UPDATE demands SET bus_required = ?, created_at = CURRENT_TIMESTAMP WHERE demand_id = ?";
                    try (PreparedStatement psUd = con.prepareStatement(updateDemandSql)) {
                        psUd.setBoolean(1, required);
                        psUd.setInt(2, existingDemandId);
                        psUd.executeUpdate();
                    }
                } else {
                    String insertDemandSql = "INSERT INTO demands (student_id, demand_date, bus_required, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
                    try (PreparedStatement psId = con.prepareStatement(insertDemandSql)) {
                        psId.setInt(1, studentId);
                        psId.setDate(2, Date.valueOf(targetDate));
                        psId.setBoolean(3, required);
                        psId.executeUpdate();
                    }
                }

                // Keep attendance table in sync
                String checkAttSql = "SELECT attendance_id FROM attendance WHERE student_id = ? AND attendance_date = ?";
                int existingAttId = 0;
                try (PreparedStatement psCa = con.prepareStatement(checkAttSql)) {
                    psCa.setInt(1, studentId);
                    psCa.setDate(2, Date.valueOf(targetDate));
                    try (ResultSet rs = psCa.executeQuery()) {
                        if (rs.next()) existingAttId = rs.getInt("attendance_id");
                    }
                }
                if (existingAttId > 0) {
                    String updateAttSql = "UPDATE attendance SET travel_intention = ? WHERE attendance_id = ?";
                    try (PreparedStatement psUa = con.prepareStatement(updateAttSql)) {
                        psUa.setBoolean(1, required);
                        psUa.setInt(2, existingAttId);
                        psUa.executeUpdate();
                    }
                } else {
                    String insertAttSql = "INSERT INTO attendance (student_id, attendance_date, travel_intention, status, boarded) VALUES (?, ?, ?, 'Pending', 0)";
                    try (PreparedStatement psIa = con.prepareStatement(insertAttSql)) {
                        psIa.setInt(1, studentId);
                        psIa.setDate(2, Date.valueOf(targetDate));
                        psIa.setBoolean(3, required);
                        psIa.executeUpdate();
                    }
                }

                send(exchange, 200, "{\"success\":true,\"message\":\"Requirement Saved Successfully\",\"studentId\":" + studentId + ",\"required\":" + required + ",\"date\":\"" + targetDate.toString() + "\",\"pickupStop\":\"" + escape(pickupStop != null ? pickupStop : "") + "\"}");
            }
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Failed to save intention: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void manifestApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if (!allow(exchange, "GET")) return;

        String query = exchange.getRequestURI().getQuery();
        int busId = 1;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2 && ("bus".equals(pair[0]) || "bus_id".equals(pair[0]))) {
                    try { busId = Integer.parseInt(URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); } catch (Exception ignored) {}
                }
            }
        }

        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        String busSql = "SELECT bus_id, bus_name, bus_number, capacity FROM buses WHERE bus_id = ?";
        String studentSql = "SELECT s.student_id, s.roll_number, s.name, s.pickup_stop, s.transport_authorized, "
                + "COALESCE(a.boarded, 0) AS boarded, "
                + "COALESCE(dm.bus_required, COALESCE(d.travel_intention, 1)) AS declared_tomorrow "
                + "FROM students s "
                + "LEFT JOIN attendance a ON s.student_id = a.student_id AND a.attendance_date = ? "
                + "LEFT JOIN attendance d ON s.student_id = d.student_id AND d.attendance_date = ? "
                + "LEFT JOIN demands dm ON s.student_id = dm.student_id AND dm.demand_date = ? "
                + "WHERE s.assigned_bus_id = ? "
                + "ORDER BY s.student_id ASC";

        try (Connection con = DBConnection.getConnection()) {
            String busName = "Bus " + busId;
            String busNumber = "TN-33-CB-10" + busId;
            int capacity = 20;

            try (PreparedStatement psBus = con.prepareStatement(busSql)) {
                psBus.setInt(1, busId);
                try (ResultSet rsBus = psBus.executeQuery()) {
                    if (rsBus.next()) {
                        String bName = rsBus.getString("bus_name");
                        if (bName != null && !bName.isBlank()) busName = bName;
                        String bNum = rsBus.getString("bus_number");
                        if (bNum != null && !bNum.isBlank()) busNumber = bNum;
                        int cap = rsBus.getInt("capacity");
                        if (cap > 0) capacity = cap;
                    }
                }
            }

            try (PreparedStatement ps = con.prepareStatement(studentSql)) {
                ps.setDate(1, Date.valueOf(today));
                ps.setDate(2, Date.valueOf(tomorrow));
                ps.setDate(3, Date.valueOf(tomorrow));
                ps.setInt(4, busId);

                try (ResultSet rs = ps.executeQuery()) {
                    StringBuilder studentArray = new StringBuilder("[");
                    boolean first = true;
                    int seatNo = 1;
                    int totalAllocated = 0;
                    int totalBoarded = 0;
                    while (rs.next()) {
                        totalAllocated++;
                        boolean isBoarded = rs.getBoolean("boarded");
                        if (isBoarded) totalBoarded++;

                        if (!first) studentArray.append(',');
                        first = false;
                        studentArray.append("{\"seatNo\":\"").append(String.format("%02d", seatNo++))
                            .append("\",\"id\":").append(rs.getInt("student_id"))
                            .append(",\"rollNumber\":\"").append(escape(rs.getString("roll_number") != null ? rs.getString("roll_number") : ""))
                            .append("\",\"name\":\"").append(escape(rs.getString("name")))
                            .append("\",\"stop\":\"").append(escape(rs.getString("pickup_stop")))
                            .append("\",\"authorized\":").append(rs.getBoolean("transport_authorized"))
                            .append(",\"boarded\":").append(isBoarded)
                            .append(",\"declaredTomorrow\":").append(rs.getBoolean("declared_tomorrow"))
                            .append('}');
                    }
                    studentArray.append(']');
                    int seatsVacant = Math.max(0, capacity - totalAllocated);

                    String json = "{\"busId\":" + busId
                        + ",\"busName\":\"" + escape(busName) + "\""
                        + ",\"busNumber\":\"" + escape(busNumber) + "\""
                        + ",\"capacity\":" + capacity
                        + ",\"totalAllocated\":" + totalAllocated
                        + ",\"seatsVacant\":" + seatsVacant
                        + ",\"totalBoarded\":" + totalBoarded
                        + ",\"students\":" + studentArray.toString()
                        + "}";
                    send(exchange, 200, json);
                }
            }
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void dashboardApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if (!allow(exchange, "GET")) return;

        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement()) {

            int totalStudents = 0;
            int allocatedStudents = 0;
            int unallocatedStudents = 0;
            int regularBuses = 0;
            int totalRegularSeats = 0;
            int operatingNormally = 0;
            int breakdownBuses = 0;
            int breakdownIncidents = 0;
            int tomorrowDemand = 0;
            int activeGrievances = 0;
            int resolvedGrievances = 0;
            double feesPaid = 0;

            // 1. Students: Total, Allocated, Unallocated
            try (ResultSet rs = st.executeQuery("SELECT "
                    + "COUNT(*) AS total, "
                    + "SUM(CASE WHEN assigned_bus_id IS NOT NULL AND assigned_bus_id > 0 THEN 1 ELSE 0 END) AS allocated, "
                    + "SUM(CASE WHEN assigned_bus_id IS NULL OR assigned_bus_id = 0 THEN 1 ELSE 0 END) AS unallocated "
                    + "FROM students")) {
                if (rs.next()) {
                    totalStudents = rs.getInt("total");
                    allocatedStudents = rs.getInt("allocated");
                    unallocatedStudents = rs.getInt("unallocated");
                }
            }

            // 2. Regular Fleet Buses & Total Regular Seats (excluding Emergency Standby)
            try (ResultSet rs = st.executeQuery("SELECT "
                    + "COUNT(*) AS reg_buses, "
                    + "COALESCE(SUM(capacity), 0) AS total_seats "
                    + "FROM buses "
                    + "WHERE (bus_type != 'Emergency Standby' OR bus_type IS NULL)")) {
                if (rs.next()) {
                    regularBuses = rs.getInt("reg_buses");
                    totalRegularSeats = rs.getInt("total_seats");
                }
            }

            // 3. Breakdown Stats (Active Breakdown Incidents & Distinct Broken Regular Buses)
            try (ResultSet rs = st.executeQuery("SELECT "
                    + "COUNT(*) AS total_incidents, "
                    + "COUNT(DISTINCT broken_bus_id) AS broken_buses "
                    + "FROM breakdowns WHERE status = 'Active'")) {
                if (rs.next()) {
                    breakdownIncidents = rs.getInt("total_incidents");
                    breakdownBuses = rs.getInt("broken_buses");
                }
            }

            // 4. Operating Normally: Regular buses with status = 'Active' that are not currently broken
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM buses "
                    + "WHERE (bus_type != 'Emergency Standby' OR bus_type IS NULL) "
                    + "AND status = 'Active' "
                    + "AND bus_id NOT IN (SELECT broken_bus_id FROM breakdowns WHERE status = 'Active')")) {
                if (rs.next()) {
                    operatingNormally = rs.getInt(1);
                }
            }

            // 5. Riders Expected Tomorrow
            LocalDate tomorrow = LocalDate.now().plusDays(1);
            String tomorrowStr = tomorrow.toString();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT ("
                    + "  SELECT COUNT(DISTINCT student_id) FROM attendance WHERE attendance_date = ? AND travel_intention = 1"
                    + ") + ("
                    + "  SELECT COUNT(DISTINCT student_id) FROM demands WHERE demand_date = ? AND bus_required = 1 "
                    + "  AND student_id NOT IN (SELECT student_id FROM attendance WHERE attendance_date = ?)"
                    + ") AS total_demand")) {
                ps.setString(1, tomorrowStr);
                ps.setString(2, tomorrowStr);
                ps.setString(3, tomorrowStr);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        tomorrowDemand = rs.getInt("total_demand");
                    }
                }
            } catch (SQLException ignored) {
                // Fallback to attendance table only
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT COUNT(DISTINCT student_id) FROM attendance WHERE attendance_date = ? AND travel_intention = 1")) {
                    ps.setString(1, tomorrowStr);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) tomorrowDemand = rs.getInt(1);
                    }
                }
            }

            // 6. Grievances (Active vs Resolved)
            try (ResultSet rs = st.executeQuery("SELECT "
                    + "SUM(CASE WHEN status IN ('Open', 'Pending') THEN 1 ELSE 0 END) AS active_cnt, "
                    + "SUM(CASE WHEN status = 'Resolved' THEN 1 ELSE 0 END) AS resolved_cnt "
                    + "FROM complaints")) {
                if (rs.next()) {
                    activeGrievances = rs.getInt("active_cnt");
                    resolvedGrievances = rs.getInt("resolved_cnt");
                }
            }

            // 7. Fees Paid
            try (ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(amount), 0) FROM fees WHERE payment_status = 'Paid'")) {
                if (rs.next()) {
                    feesPaid = rs.getDouble(1);
                }
            }

            // 8. Bus Seating Allocations Array
            StringBuilder allocJson = new StringBuilder("[");
            String allocSql = "SELECT b.bus_id, b.bus_name, COALESCE(b.route_name, 'Regular Corridor') AS route_name, "
                    + "b.capacity, COALESCE(b.status, 'Active') AS status, "
                    + "COUNT(s.student_id) AS allocated_count "
                    + "FROM buses b "
                    + "LEFT JOIN students s ON s.assigned_bus_id = b.bus_id "
                    + "WHERE (b.bus_type != 'Emergency Standby' OR b.bus_type IS NULL) "
                    + "GROUP BY b.bus_id, b.bus_name, b.route_name, b.capacity, b.status "
                    + "ORDER BY b.bus_id ASC";
            try (ResultSet rs = st.executeQuery(allocSql)) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) allocJson.append(',');
                    first = false;
                    allocJson.append("{\"id\":").append(rs.getInt("bus_id"))
                        .append(",\"name\":\"").append(escape(rs.getString("bus_name"))).append("\"")
                        .append(",\"route\":\"").append(escape(rs.getString("route_name"))).append("\"")
                        .append(",\"capacity\":").append(rs.getInt("capacity"))
                        .append(",\"allocated\":").append(rs.getInt("allocated_count"))
                        .append(",\"status\":\"").append(escape(rs.getString("status"))).append("\"")
                        .append('}');
                }
            }
            allocJson.append(']');

            StringBuilder json = new StringBuilder("{");
            json.append("\"totalStudents\":").append(totalStudents)
                .append(",\"allocatedStudents\":").append(allocatedStudents)
                .append(",\"unallocatedStudents\":").append(unallocatedStudents)
                .append(",\"regularBuses\":").append(regularBuses)
                .append(",\"totalRegularSeats\":").append(totalRegularSeats)
                .append(",\"operatingNormally\":").append(operatingNormally)
                .append(",\"breakdownBuses\":").append(breakdownBuses)
                .append(",\"breakdownIncidents\":").append(breakdownIncidents)
                .append(",\"tomorrowDemand\":").append(tomorrowDemand)
                .append(",\"activeGrievances\":").append(activeGrievances)
                .append(",\"resolvedGrievances\":").append(resolvedGrievances)
                .append(",\"feesPaid\":").append(feesPaid)
                .append(",\"activeBuses\":").append(regularBuses)
                .append(",\"allocations\":").append(allocJson)
                .append('}');

            send(exchange, 200, json.toString());
        } catch (SQLException e) {
            System.err.println("[BackendServer] dashboardApi database error: " + e.getMessage());
            e.printStackTrace();
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static void complaintApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            allow(exchange, "GET");
            return;
        }

        if ("GET".equals(exchange.getRequestMethod())) {
            String sql = "SELECT c.complaint_id, c.student_id, s.name AS student_name, "
                    + "COALESCE(b.bus_name, 'Unassigned') AS bus_name, "
                    + "c.complaint_text, c.status, c.created_at "
                    + "FROM complaints c "
                    + "LEFT JOIN students s ON c.student_id = s.student_id "
                    + "LEFT JOIN buses b ON s.assigned_bus_id = b.bus_id "
                    + "ORDER BY c.created_at DESC";

            try (Connection con = DBConnection.getConnection();
                 Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {

                StringBuilder json = new StringBuilder("[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;
                    json.append("{\"id\":").append(rs.getInt("complaint_id"))
                        .append(",\"studentId\":").append(rs.getInt("student_id"))
                        .append(",\"studentName\":\"").append(escape(rs.getString("student_name"))).append("\"")
                        .append(",\"busName\":\"").append(escape(rs.getString("bus_name"))).append("\"")
                        .append(",\"text\":\"").append(escape(rs.getString("complaint_text"))).append("\"")
                        .append(",\"status\":\"").append(escape(rs.getString("status"))).append("\"")
                        .append(",\"createdAt\":\"").append(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : "").append("\"")
                        .append('}');
                }
                json.append(']');
                send(exchange, 200, json.toString());
            } catch (SQLException e) {
                send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        if (!allow(exchange, "POST")) return;

        Map<String, String> form = formData(readBody(exchange));
        try {
            int studentId = 101;
            String stuStr = form.get("student_id");
            if (stuStr != null && !stuStr.isBlank()) studentId = Integer.parseInt(stuStr);

            String text = form.get("complaint");
            if (text == null || text.isBlank()) text = form.get("text");

            String sql = "INSERT INTO complaints (student_id, complaint_text, status) VALUES (?, ?, 'Open')";
            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, studentId);
                ps.setString(2, text != null ? text : "Transport Feedback");
                ps.executeUpdate();
                int newId = 0;
                try (ResultSet gk = ps.getGeneratedKeys()) {
                    if (gk.next()) newId = gk.getInt(1);
                }
                send(exchange, 201, "{\"message\":\"Grievance submitted successfully.\",\"complaintId\":" + newId + "}");
            }
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"Failed to submit complaint: " + escape(e.getMessage()) + "\"}");
        }
    }

    // =========================================================================
    // HTTP UTILITY HELPERS
    // =========================================================================

    private static boolean allow(HttpExchange exchange, String method) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token, X-User-Role, X-Student-Id");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
        if ("OPTIONS".equals(exchange.getRequestMethod())) { send(exchange, 204, ""); return false; }
        if (!method.equals(exchange.getRequestMethod())) { send(exchange, 405, "{\"error\":\"Method not allowed\"}"); return false; }
        return true;
    }

    /**
     * Public Stops Endpoint: GET /api/stops
     */
    private static void stopsApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if (!allow(exchange, "GET")) return;

        String sql = "SELECT stop_id, stop_name, route_id, stop_order, scheduled_time, road_type, latitude, longitude FROM stops ORDER BY stop_name ASC";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            while (rs.next()) {
                if (!first) json.append(',');
                first = false;
                json.append("{\"id\":").append(rs.getInt("stop_id"))
                    .append(",\"name\":\"").append(escape(rs.getString("stop_name")))
                    .append("\",\"routeId\":").append(rs.getInt("route_id"))
                    .append(",\"order\":").append(rs.getInt("stop_order"))
                    .append(",\"time\":\"").append(escape(rs.getString("scheduled_time")))
                    .append("\",\"road\":\"").append(escape(rs.getString("road_type")))
                    .append("\",\"latitude\":").append(rs.getDouble("latitude"))
                    .append(",\"longitude\":").append(rs.getDouble("longitude"))
                    .append("}");
            }
            json.append(']');
            send(exchange, 200, json.toString());
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    /**
     * Driver Dashboard Endpoint: GET /api/driver/dashboard
     */
    private static void driverDashboardApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token");
        if (!allow(exchange, "GET")) return;

        int driverId = 1;
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2 && ("driverId".equalsIgnoreCase(pair[0]) || "driver_id".equalsIgnoreCase(pair[0]) || "id".equalsIgnoreCase(pair[0]))) {
                    try { driverId = Integer.parseInt(pair[1]); } catch (Exception ignored) {}
                }
            }
        }

        String sql = "SELECT b.bus_id, b.bus_name, b.bus_number, b.driver_name, b.driver_phone, b.route_id, "
                + "r.route_name, r.start_location, r.end_location "
                + "FROM buses b "
                + "LEFT JOIN routes r ON b.route_id = r.route_id "
                + "WHERE b.bus_id = ?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, driverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = "{\"driverId\":" + driverId
                            + ",\"driver\":{\"driverId\":" + driverId + ",\"name\":\"" + escape(rs.getString("driver_name")) + "\",\"phone\":\"" + escape(rs.getString("driver_phone")) + "\"}"
                            + ",\"bus\":{\"busId\":" + rs.getInt("bus_id") + ",\"name\":\"" + escape(rs.getString("bus_name")) + "\",\"number\":\"" + escape(rs.getString("bus_number")) + "\"}"
                            + ",\"route\":{\"routeId\":" + rs.getInt("route_id") + ",\"name\":\"" + escape(rs.getString("route_name")) + "\",\"origin\":\"" + escape(rs.getString("start_location")) + "\",\"destination\":\"" + escape(rs.getString("end_location")) + "\"}"
                            + "}";
                    send(exchange, 200, json);
                } else {
                    send(exchange, 404, "{\"error\":\"Driver not found\"}");
                }
            }
        } catch (SQLException e) {
            send(exchange, 500, "{\"error\":\"Database error: " + escape(e.getMessage()) + "\"}");
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> formData(String body) {
        Map<String, String> result = new HashMap<>();
        if (body == null || body.isBlank()) return result;
        String trimmed = body.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"[^\"]*\"|[^,\\}\\s]+)").matcher(trimmed);
            while (m.find()) {
                String key = m.group(1);
                String val = m.group(2).trim();
                if (val.startsWith("\"") && val.endsWith("\"")) {
                    val = val.substring(1, val.length() - 1);
                }
                result.put(key, val);
            }
            return result;
        }
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

    /**
     * Serves static frontend files (HTML, JS, CSS, images) with proper MIME types.
     */
    private static void staticFileHandler(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "/index.html";
        }

        // Normalize /frontend/... to relative path
        if (path.startsWith("/frontend/")) {
            path = path.substring("/frontend".length());
        }

        File baseDir = new File("frontend");
        if (!baseDir.exists()) {
            baseDir = new File("Miniproject1/frontend");
        }

        File targetFile = new File(baseDir, path.startsWith("/") ? path.substring(1) : path);
        if (!targetFile.exists() || targetFile.isDirectory()) {
            File rootFile = new File(path.startsWith("/") ? path.substring(1) : path);
            if (rootFile.exists() && !rootFile.isDirectory()) {
                targetFile = rootFile;
            } else {
                String notFound = "<!DOCTYPE html><html><head><title>404 Not Found</title></head>"
                        + "<body><h1>404 File Not Found</h1><p>" + escape(path) + "</p>"
                        + "<p><a href='/'>Return to Transit Portal</a></p></body></html>";
                byte[] bytes = notFound.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(404, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                return;
            }
        }

        String mimeType = "application/octet-stream";
        String lower = targetFile.getName().toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) mimeType = "text/html; charset=UTF-8";
        else if (lower.endsWith(".js")) mimeType = "application/javascript; charset=UTF-8";
        else if (lower.endsWith(".css")) mimeType = "text/css; charset=UTF-8";
        else if (lower.endsWith(".svg")) mimeType = "image/svg+xml";
        else if (lower.endsWith(".png")) mimeType = "image/png";
        else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) mimeType = "image/jpeg";
        else if (lower.endsWith(".json")) mimeType = "application/json; charset=UTF-8";
        else if (lower.endsWith(".ico")) mimeType = "image/x-icon";
        else if (lower.endsWith(".txt")) mimeType = "text/plain; charset=UTF-8";

        byte[] fileBytes = Files.readAllBytes(targetFile.toPath());
        exchange.getResponseHeaders().set("Content-Type", mimeType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, fileBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(fileBytes);
        }
    }
}
