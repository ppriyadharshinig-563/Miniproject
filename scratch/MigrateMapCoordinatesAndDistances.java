import java.sql.*;

public class MigrateMapCoordinatesAndDistances {

    public static void main(String[] args) {
        System.out.println("Starting safe migration for Map Coordinates & Edge Distances...");

        try (Connection con = DBConnection.getConnection()) {
            DatabaseMetaData meta = con.getMetaData();

            // 1. Create graph_nodes table
            String createGraphNodesTable = "CREATE TABLE IF NOT EXISTS graph_nodes ("
                    + "node_id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "node_name VARCHAR(100) UNIQUE NOT NULL, "
                    + "latitude DECIMAL(10, 6) NOT NULL, "
                    + "longitude DECIMAL(10, 6) NOT NULL, "
                    + "node_type VARCHAR(50) DEFAULT 'STOP', "
                    + "description VARCHAR(255) DEFAULT ''"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

            try (Statement st = con.createStatement()) {
                st.execute(createGraphNodesTable);
                System.out.println("Table graph_nodes verified/created.");
            }

            // 2. Populate graph_nodes with verified coordinates in Erode District
            Object[][] nodes = {
                {"Kongu Engineering College", 11.274200, 77.607000, "DESTINATION_HUB", "College Main Campus & Primary Transit Terminal"},
                {"KEC Campus Gate, Perundurai", 11.273000, 77.603000, "CAMPUS_GATE", "Campus Arch & Main Security Entry Gate"},
                {"Perundurai Old Bus Stand", 11.276400, 77.583300, "BUS_STAND", "Perundurai Municipal Bus Terminal (SH-96)"},
                {"Perundurai RS (Railway Station)", 11.300200, 77.588800, "RAILWAY_STATION", "Perundurai Suburban Railway Station Link"},
                {"Perundurai Depot", 11.278500, 77.586000, "DEPOT", "Central Transport Depot & Emergency Standby Shuttle Base"},
                {"Erode Central Bus Stand", 11.341000, 77.717200, "MAJOR_BUS_STAND", "Erode District Central Transit Hub"},
                {"Thindal Murugan Temple", 11.317500, 77.674400, "TRANSIT_STOP", "Thindal Hill Temple Arterial Junction"},
                {"Bhavani Bus Stand", 11.448500, 77.683300, "MAJOR_BUS_STAND", "Bhavani Town Central Bus Terminal"},
                {"Lakshmi Nagar Bypass", 11.411000, 77.675000, "JUNCTION", "Lakshmi Nagar NH-544 Bypass Junction"},
                {"Chennimalai Bus Stand", 11.168500, 77.612000, "MAJOR_BUS_STAND", "Chennimalai Town Bus Terminal"},
                {"Vijayamangalam Toll Gate", 11.233000, 77.502000, "TOLL_JUNCTION", "NH-544 Salem-Kochi Highway Toll Plaza"},
                {"Chithode Junction", 11.412000, 77.652000, "HIGHWAY_JUNCTION", "Chithode Bypass Crossing"},
                {"Modakkurichi", 11.267000, 77.760000, "RURAL_NODE", "Modakkurichi Arterial Road"}
            };

            String insertOrUpdateNode = "INSERT INTO graph_nodes (node_name, latitude, longitude, node_type, description) "
                    + "VALUES (?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE latitude = VALUES(latitude), longitude = VALUES(longitude), "
                    + "node_type = VALUES(node_type), description = VALUES(description)";

            try (PreparedStatement ps = con.prepareStatement(insertOrUpdateNode)) {
                for (Object[] node : nodes) {
                    ps.setString(1, (String) node[0]);
                    ps.setDouble(2, (Double) node[1]);
                    ps.setDouble(3, (Double) node[2]);
                    ps.setString(4, (String) node[3]);
                    ps.setString(5, (String) node[4]);
                    ps.addBatch();
                }
                ps.executeBatch();
                System.out.println("Populated " + nodes.length + " graph nodes with real Erode district coordinates.");
            }

            // 3. Add distance_km to road_network_edges if not present
            boolean hasDistanceKm = false;
            try (ResultSet rs = meta.getColumns("smart_bus_db", null, "road_network_edges", "distance_km")) {
                if (rs.next()) hasDistanceKm = true;
            }

            if (!hasDistanceKm) {
                try (Statement st = con.createStatement()) {
                    st.execute("ALTER TABLE road_network_edges ADD COLUMN distance_km DECIMAL(5, 2) DEFAULT 0.0");
                    System.out.println("Added distance_km column to road_network_edges.");
                }
            } else {
                System.out.println("Column distance_km already present in road_network_edges.");
            }

            // 4. Update distance_km for road_network_edges
            Object[][] edgeDistances = {
                // Route A Corridor
                {"Erode Central Bus Stand", "Thindal Murugan Temple", 8.5},
                {"Thindal Murugan Temple", "Perundurai Old Bus Stand", 14.0},
                {"Perundurai Old Bus Stand", "Kongu Engineering College", 3.2},

                // Route B Corridor
                {"Bhavani Bus Stand", "Lakshmi Nagar Bypass", 6.8},
                {"Lakshmi Nagar Bypass", "Perundurai RS (Railway Station)", 12.5},
                {"Perundurai RS (Railway Station)", "Kongu Engineering College", 4.1},

                // Route C Corridor
                {"Chennimalai Bus Stand", "Vijayamangalam Toll Gate", 16.2},
                {"Vijayamangalam Toll Gate", "KEC Campus Gate, Perundurai", 11.5},
                {"KEC Campus Gate, Perundurai", "Kongu Engineering College", 1.2},

                // Bypasses & Cross connections
                {"Thindal Murugan Temple", "Lakshmi Nagar Bypass", 13.0},
                {"Lakshmi Nagar Bypass", "Perundurai Old Bus Stand", 11.2},
                {"Perundurai Old Bus Stand", "Perundurai RS (Railway Station)", 4.5},
                {"Perundurai RS (Railway Station)", "Vijayamangalam Toll Gate", 9.8},
                {"Erode Central Bus Stand", "Bhavani Bus Stand", 14.5},
                {"Chennimalai Bus Stand", "Perundurai Old Bus Stand", 13.5},
                {"Vijayamangalam Toll Gate", "Perundurai Old Bus Stand", 8.7},

                // Standby Depot Connections
                {"Perundurai Depot", "Perundurai Old Bus Stand", 1.8},
                {"Perundurai Depot", "Kongu Engineering College", 2.6},
                {"Perundurai Depot", "Thindal Murugan Temple", 12.5},
                {"Perundurai Depot", "Lakshmi Nagar Bypass", 9.5},
                {"Perundurai Depot", "Vijayamangalam Toll Gate", 7.5},
                {"Perundurai Depot", "KEC Campus Gate, Perundurai", 2.1},

                // Demo / Suburban Connections
                {"Erode Central Bus Stand", "Chithode Junction", 11.0},
                {"Chithode Junction", "Lakshmi Nagar Bypass", 7.5},
                {"Chennimalai Bus Stand", "Modakkurichi", 15.0}
            };

            String updateDistance = "UPDATE road_network_edges SET distance_km = ? WHERE "
                    + "(source_node = ? AND target_node = ?) OR (is_bidirectional = TRUE AND source_node = ? AND target_node = ?)";

            try (PreparedStatement ps = con.prepareStatement(updateDistance)) {
                for (Object[] ed : edgeDistances) {
                    double dist = (Double) ed[2];
                    String src = (String) ed[0];
                    String tgt = (String) ed[1];
                    ps.setDouble(1, dist);
                    ps.setString(2, src);
                    ps.setString(3, tgt);
                    ps.setString(4, tgt);
                    ps.setString(5, src);
                    ps.addBatch();
                }
                ps.executeBatch();
                System.out.println("Updated road_network_edges with realistic distances in km.");
            }

            // 5. Add latitude & longitude to stops table if not present
            boolean hasStopLat = false;
            try (ResultSet rs = meta.getColumns("smart_bus_db", null, "stops", "latitude")) {
                if (rs.next()) hasStopLat = true;
            }

            if (!hasStopLat) {
                try (Statement st = con.createStatement()) {
                    st.execute("ALTER TABLE stops ADD COLUMN latitude DECIMAL(10, 6) DEFAULT NULL, ADD COLUMN longitude DECIMAL(10, 6) DEFAULT NULL");
                    System.out.println("Added latitude and longitude columns to stops table.");
                }
            }

            // Sync coordinates to stops table from graph_nodes
            String syncStopsSql = "UPDATE stops s JOIN graph_nodes g ON LOWER(s.stop_name) = LOWER(g.node_name) "
                    + "SET s.latitude = g.latitude, s.longitude = g.longitude";
            try (Statement st = con.createStatement()) {
                int synced = st.executeUpdate(syncStopsSql);
                System.out.println("Synchronized coordinates for " + synced + " rows in stops table.");
            }

            System.out.println("Map Coordinates & Edge Distances migration successfully completed!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
