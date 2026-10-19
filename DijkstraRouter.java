import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * DijkstraRouter: Finds the minimum estimated travel-time route (in minutes)
 * to Kongu Engineering College hub in Erode district using Dijkstra's Algorithm
 * backed by java.util.PriorityQueue (Min-Heap).
 *
 * Algorithm Details & Justification:
 * - Graph Model: G = (V, E) where V are bus stops, road junctions, or depots in Erode,
 *   and E are road segments connecting adjacent stops.
 * - Weights: Each directed edge carries an effective weight = (travel_time_minutes + traffic_delay_minutes).
 * - Distances: Each edge also tracks actual road distance in kilometers (distance_km).
 *
 * Why java.util.PriorityQueue?
 * - Standard Dijkstra's Algorithm requires finding the unvisited node with the minimum tentative distance.
 * - Using an array/list scan takes O(V) per step, yielding an overall O(V^2) time complexity.
 * - java.util.PriorityQueue implements a binary Min-Heap where the root is always the node with minimum travel time.
 * - Insertion (pq.offer) takes O(log V) time, and extraction (pq.poll) takes O(log V) time.
 * - Total Algorithm Time Complexity: O((V + E) * log V), which is asymptotically optimal for sparse road networks.
 * - Space Complexity: O(V + E) to store the adjacency list, predecessor pointers, and priority queue elements.
 */
public class DijkstraRouter {

    /**
     * PathNode represents a vertex entry in the PriorityQueue Min-Heap.
     * Implements Comparable<PathNode> to enforce Min-Heap ordering:
     * the vertex with the lowest accumulated travel time from the source is polled first.
     */
    public static class PathNode implements Comparable<PathNode> {
        public final String nodeName;
        public final int minTravelTime; // Cumulative estimated travel time from source in minutes

        public PathNode(String nodeName, int minTravelTime) {
            this.nodeName = nodeName;
            this.minTravelTime = minTravelTime;
        }

        @Override
        public int compareTo(PathNode other) {
            /*
             * Explicit Min-Heap ordering requirement:
             * java.util.PriorityQueue uses compareTo() to order elements.
             * By comparing minTravelTime, the node with the lowest travel time
             * is placed at the top of the queue (head) and polled in O(log V) time.
             */
            return Integer.compare(this.minTravelTime, other.minTravelTime);
        }

        @Override
        public String toString() {
            return nodeName + " (" + minTravelTime + "m)";
        }
    }

    /**
     * RoadEdge represents a directed road segment connecting two stops or junctions.
     */
    public static class RoadEdge {
        public final String targetNode;
        public final int travelTimeMinutes;
        public final int trafficDelayMinutes;
        public final String roadName;
        public final boolean isDemo;
        public final double distanceKm;

        public RoadEdge(String targetNode, int travelTimeMinutes, int trafficDelayMinutes, String roadName, boolean isDemo, double distanceKm) {
            this.targetNode = targetNode;
            this.travelTimeMinutes = travelTimeMinutes;
            this.trafficDelayMinutes = trafficDelayMinutes;
            this.roadName = roadName;
            this.isDemo = isDemo;
            this.distanceKm = distanceKm;
        }

        public RoadEdge(String targetNode, int travelTimeMinutes, int trafficDelayMinutes, String roadName, boolean isDemo) {
            this(targetNode, travelTimeMinutes, trafficDelayMinutes, roadName, isDemo, 0.0);
        }

        /**
         * Effective weight of the road segment in minutes, accounting for base
         * travel time plus dynamic traffic delays reported on this edge.
         */
        public int getEffectiveWeight() {
            return Math.max(1, travelTimeMinutes + trafficDelayMinutes);
        }
    }

    /**
     * NodeDetail captures rich geographical and cost metrics for each node
     * along the Dijkstra calculated path, used by the Google Maps visualizer.
     */
    public static class NodeDetail {
        public final String nodeName;
        public final double latitude;
        public final double longitude;
        public final int edgeWeightMinutes;
        public final double edgeDistanceKm;
        public final String roadName;

        public NodeDetail(String nodeName, double latitude, double longitude, int edgeWeightMinutes, double edgeDistanceKm, String roadName) {
            this.nodeName = nodeName;
            this.latitude = latitude;
            this.longitude = longitude;
            this.edgeWeightMinutes = edgeWeightMinutes;
            this.edgeDistanceKm = edgeDistanceKm;
            this.roadName = roadName;
        }
    }

    /**
     * Result structure containing the optimal path, ordered sequence of stops,
     * total travel time, total distance, and operational route status.
     */
    public static class RouteResult {
        public boolean reachable;
        public String source;
        public String destination;
        public int totalTravelTime;
        public int baseTravelTime;
        public int totalTrafficDelay;
        public double totalDistanceKm;
        public List<String> orderedStops = new ArrayList<>();
        public List<String> assignedStops = new ArrayList<>();
        public List<String> roadSegments = new ArrayList<>();
        public List<NodeDetail> pathNodeDetails = new ArrayList<>();
        public String recommendedPath = "";
        public String routeStatus = "Optimal";
        public String statusDescription = "";
        public int dispatchEtaMinutes = 0;
    }

    // Default verified fallback coordinates for Erode district nodes (used if DB connection fails)
    private static final Map<String, double[]> FALLBACK_COORDS = new HashMap<>();
    static {
        FALLBACK_COORDS.put("Kongu Engineering College", new double[]{11.274200, 77.607000});
        FALLBACK_COORDS.put("KEC Campus Gate, Perundurai", new double[]{11.273000, 77.603000});
        FALLBACK_COORDS.put("Perundurai Old Bus Stand", new double[]{11.276400, 77.583300});
        FALLBACK_COORDS.put("Perundurai RS (Railway Station)", new double[]{11.300200, 77.588800});
        FALLBACK_COORDS.put("Perundurai Depot", new double[]{11.278500, 77.586000});
        FALLBACK_COORDS.put("Erode Central Bus Stand", new double[]{11.341000, 77.717200});
        FALLBACK_COORDS.put("Thindal Murugan Temple", new double[]{11.317500, 77.674400});
        FALLBACK_COORDS.put("Bhavani Bus Stand", new double[]{11.448500, 77.683300});
        FALLBACK_COORDS.put("Lakshmi Nagar Bypass", new double[]{11.411000, 77.675000});
        FALLBACK_COORDS.put("Chennimalai Bus Stand", new double[]{11.168500, 77.612000});
        FALLBACK_COORDS.put("Vijayamangalam Toll Gate", new double[]{11.233000, 77.502000});
        FALLBACK_COORDS.put("Chithode Junction", new double[]{11.412000, 77.652000});
        FALLBACK_COORDS.put("Modakkurichi", new double[]{11.267000, 77.760000});
    }

    /**
     * Loads node coordinates from MySQL table graph_nodes into memory.
     */
    public static Map<String, double[]> loadNodeCoordinates(Connection con) {
        Map<String, double[]> coords = new HashMap<>(FALLBACK_COORDS);
        if (con == null) return coords;

        String sql = "SELECT node_name, latitude, longitude FROM graph_nodes";
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("node_name");
                double lat = rs.getDouble("latitude");
                double lng = rs.getDouble("longitude");
                coords.put(name, new double[]{lat, lng});
            }
        } catch (SQLException ignored) {
            // Gracefully use fallback coords if table is not yet migrated
        }
        return coords;
    }

    /**
     * Loads the road network from MySQL table road_network_edges into an adjacency list graph.
     */
    public static Map<String, List<RoadEdge>> loadGraph(Connection con) throws SQLException {
        Map<String, List<RoadEdge>> graph = new HashMap<>();

        // Check whether distance_km column exists
        boolean hasDist = true;
        String sql = "SELECT source_node, target_node, travel_time_minutes, traffic_delay_minutes, "
                + "road_name, is_bidirectional, is_demo, distance_km FROM road_network_edges";

        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String u = rs.getString("source_node");
                String v = rs.getString("target_node");
                int time = rs.getInt("travel_time_minutes");
                int delay = rs.getInt("traffic_delay_minutes");
                String road = rs.getString("road_name");
                boolean bidir = rs.getBoolean("is_bidirectional");
                boolean demo = rs.getBoolean("is_demo");
                double dist = rs.getDouble("distance_km");

                graph.computeIfAbsent(u, k -> new ArrayList<>())
                     .add(new RoadEdge(v, time, delay, road, demo, dist));

                if (bidir) {
                    graph.computeIfAbsent(v, k -> new ArrayList<>())
                         .add(new RoadEdge(u, time, delay, road, demo, dist));
                }
            }
        } catch (SQLException e) {
            // Fallback for schemas without distance_km
            String fallbackSql = "SELECT source_node, target_node, travel_time_minutes, traffic_delay_minutes, "
                    + "road_name, is_bidirectional, is_demo FROM road_network_edges";
            try (PreparedStatement ps = con.prepareStatement(fallbackSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String u = rs.getString("source_node");
                    String v = rs.getString("target_node");
                    int time = rs.getInt("travel_time_minutes");
                    int delay = rs.getInt("traffic_delay_minutes");
                    String road = rs.getString("road_name");
                    boolean bidir = rs.getBoolean("is_bidirectional");
                    boolean demo = rs.getBoolean("is_demo");

                    graph.computeIfAbsent(u, k -> new ArrayList<>())
                         .add(new RoadEdge(v, time, delay, road, demo, 0.0));

                    if (bidir) {
                        graph.computeIfAbsent(v, k -> new ArrayList<>())
                             .add(new RoadEdge(u, time, delay, road, demo, 0.0));
                    }
                }
            }
        }
        return graph;
    }

    /**
     * Resolves user query location against existing graph nodes with smart case and prefix matching.
     */
    public static String resolveNodeName(Map<String, List<RoadEdge>> graph, String query) {
        if (query == null) return null;
        String trimmed = query.trim();
        if (graph.containsKey(trimmed)) return trimmed;

        Set<String> allNodes = new HashSet<>(graph.keySet());
        for (List<RoadEdge> edges : graph.values()) {
            for (RoadEdge edge : edges) {
                allNodes.add(edge.targetNode);
            }
        }

        for (String node : allNodes) {
            if (node.equalsIgnoreCase(trimmed)) return node;
        }

        String lower = trimmed.toLowerCase();
        for (String node : allNodes) {
            String nodeLower = node.toLowerCase();
            if (nodeLower.contains(lower) || lower.contains(nodeLower)) return node;
        }

        return trimmed;
    }

    public static boolean hasNode(Map<String, List<RoadEdge>> graph, String name) {
        if (graph == null || name == null) return false;
        String trimmed = name.trim();
        if (graph.containsKey(trimmed)) return true;
        for (List<RoadEdge> edges : graph.values()) {
            for (RoadEdge edge : edges) {
                if (edge.targetNode.equalsIgnoreCase(trimmed)) return true;
            }
        }
        return false;
    }

    /**
     * Finds the nearest graph node to a given GPS coordinate (lat, lng) using Haversine formula.
     */
    public static String findNearestNode(Map<String, double[]> coordinates, double lat, double lng) {
        if (coordinates == null || coordinates.isEmpty()) return "Perundurai Old Bus Stand";
        String bestNode = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<String, double[]> entry : coordinates.entrySet()) {
            double[] c = entry.getValue();
            if (c == null || c.length < 2) continue;
            double dLat = Math.toRadians(c[0] - lat);
            double dLng = Math.toRadians(c[1] - lng);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(c[0]))
                    * Math.sin(dLng / 2) * Math.sin(dLng / 2);
            double cVal = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            double distKm = 6371.0 * cVal;
            if (distKm < bestDist) {
                bestDist = distKm;
                bestNode = entry.getKey();
            }
        }
        return (bestNode != null) ? bestNode : "Perundurai Old Bus Stand";
    }

    public static RouteResult findShortestRoute(Map<String, List<RoadEdge>> graph, String source, String destination) {
        return findShortestRoute(graph, FALLBACK_COORDS, source, destination);
    }

    /**
     * Core implementation of Dijkstra's Algorithm using java.util.PriorityQueue.
     * Finds the minimum estimated travel-time route from source to destination,
     * computing both accumulated travel time and accumulated physical distance in km.
     *
     * @param graph Adjacency list representation of Erode district road graph
     * @param coordinates Coordinates lookup map (nodeName -> [lat, lng])
     * @param source Starting bus stop or breakdown junction
     * @param destination Target hub (typically 'Kongu Engineering College')
     * @return RouteResult with ordered stops, road segments, coordinates, and minimum travel time
     */
    public static RouteResult findShortestRoute(Map<String, List<RoadEdge>> graph, Map<String, double[]> coordinates, String source, String destination) {
        RouteResult result = new RouteResult();
        result.source = source;
        String resolvedSource = resolveNodeName(graph, source);
        String resolvedDest = resolveNodeName(graph, destination);
        result.source = resolvedSource;
        result.destination = resolvedDest;

        if (resolvedSource == null || resolvedDest == null || resolvedSource.trim().isEmpty() || resolvedDest.trim().isEmpty()) {
            result.reachable = false;
            result.totalTravelTime = -1;
            result.routeStatus = "Invalid Parameters";
            result.statusDescription = "Source and destination must be valid non-empty location names.";
            return result;
        }

        // Exact match case
        if (resolvedSource.trim().equalsIgnoreCase(resolvedDest.trim())) {
            result.reachable = true;
            result.totalTravelTime = 0;
            result.totalDistanceKm = 0.0;
            result.orderedStops.add(resolvedSource);
            result.recommendedPath = resolvedSource + " (Already at destination)";
            result.routeStatus = "At Destination";

            double[] c = (coordinates != null) ? coordinates.getOrDefault(resolvedSource, FALLBACK_COORDS.getOrDefault(resolvedSource, new double[]{11.2742, 77.6070})) : new double[]{11.2742, 77.6070};
            result.pathNodeDetails.add(new NodeDetail(resolvedSource, c[0], c[1], 0, 0.0, "Origin"));
            return result;
        }

        /*
         * STEP 1: INITIALIZE DATA STRUCTURES FOR DIJKSTRA'S ALGORITHM
         * - dist: Maps each node to its shortest known estimated travel time from source.
         * - prevNode: Records the predecessor vertex on the shortest path.
         * - prevEdge: Records the road segment traversed to reach this vertex.
         * - visited: Set of nodes whose shortest path is finalized.
         */
        Map<String, Integer> dist = new HashMap<>();
        Map<String, String> prevNode = new HashMap<>();
        Map<String, RoadEdge> prevEdge = new HashMap<>();
        Set<String> visited = new HashSet<>();

        /*
         * STEP 2: INITIALIZE java.util.PriorityQueue (Min-Heap)
         * The PriorityQueue is parameterized with PathNode and acts as a Min-Heap.
         * At each step, pq.poll() extracts the unvisited vertex with the minimum travel time in O(log V) time.
         */
        PriorityQueue<PathNode> pq = new PriorityQueue<>();

        // Distance from source to itself is 0 minutes
        dist.put(resolvedSource, 0);
        pq.offer(new PathNode(resolvedSource, 0));

        boolean found = false;

        /*
         * STEP 3: MAIN DIJKSTRA LOOP
         * Greedily select the vertex with the lowest tentative travel time from the Min-Heap.
         */
        while (!pq.isEmpty()) {
            // Min-Heap Extraction: Extracts the vertex with minimum travel time in O(log V) time
            PathNode current = pq.poll();
            String u = current.nodeName;

            // Skip if this node has already been visited (handles duplicate entries in priority queue)
            if (visited.contains(u)) {
                continue;
            }
            visited.add(u);

            // Early exit optimization: stop searching once the destination hub is reached
            if (u.equalsIgnoreCase(resolvedDest)) {
                found = true;
                break;
            }

            // Retrieve outgoing edges from vertex u
            List<RoadEdge> edges = graph.get(u);
            if (edges == null) {
                continue;
            }

            /*
             * STEP 4: RELAXATION OF ADJACENT EDGES
             * For each neighbor v of u, calculate tentative distance = dist[u] + weight(u, v).
             * If tentative distance < dist[v], update dist[v] and insert into PriorityQueue.
             */
            for (RoadEdge edge : edges) {
                String v = edge.targetNode;

                if (visited.contains(v)) {
                    continue;
                }

                int effectiveWeight = edge.getEffectiveWeight(); // base travel time + traffic delay
                int newDist = current.minTravelTime + effectiveWeight;
                int currentBest = dist.getOrDefault(v, Integer.MAX_VALUE);

                if (newDist < currentBest) {
                    dist.put(v, newDist);
                    prevNode.put(v, u);
                    prevEdge.put(v, edge);

                    // Insert the relaxed vertex into the PriorityQueue
                    pq.offer(new PathNode(v, newDist));
                }
            }
        }

        /*
         * STEP 5: RECONSTRUCT SHORTEST PATH & COMPUTE DISTANCE
         * Backtrack from destination to source using prevNode and prevEdge pointers.
         */
        if (!found || !dist.containsKey(resolvedDest)) {
            result.reachable = false;
            result.totalTravelTime = -1;
            result.totalDistanceKm = 0.0;
            result.routeStatus = "Unreachable";
            result.statusDescription = "No navigable road path found from '" + source + "' to '" + destination + "'.";
            return result;
        }

        result.reachable = true;
        result.totalTravelTime = dist.get(resolvedDest);

        // Backtrack to collect ordered stops and segments
        LinkedList<String> stopList = new LinkedList<>();
        LinkedList<String> segmentList = new LinkedList<>();
        LinkedList<RoadEdge> edgeList = new LinkedList<>();
        int baseTime = 0;
        int trafficDelay = 0;
        double totalDist = 0.0;

        String curr = resolvedDest;
        stopList.addFirst(curr);

        while (prevNode.containsKey(curr)) {
            RoadEdge edge = prevEdge.get(curr);
            if (edge != null) {
                segmentList.addFirst(edge.roadName);
                edgeList.addFirst(edge);
                baseTime += edge.travelTimeMinutes;
                trafficDelay += edge.trafficDelayMinutes;
                totalDist += edge.distanceKm;
            }
            curr = prevNode.get(curr);
            stopList.addFirst(curr);
        }

        result.orderedStops = new ArrayList<>(stopList);
        result.roadSegments = new ArrayList<>(segmentList);
        result.baseTravelTime = baseTime;
        result.totalTrafficDelay = trafficDelay;
        result.totalDistanceKm = Math.round(totalDist * 100.0) / 100.0;

        // Build path node details with coordinates and edge costs
        for (int i = 0; i < result.orderedStops.size(); i++) {
            String name = result.orderedStops.get(i);
            double[] c = (coordinates != null) ? coordinates.get(name) : null;
            if (c == null) {
                c = FALLBACK_COORDS.getOrDefault(name, new double[]{11.2748, 77.5828});
            }

            int edgeWeight = 0;
            double edgeKm = 0.0;
            String roadName = (i == 0) ? "Departure Hub" : "En Route";

            if (i > 0 && i - 1 < edgeList.size()) {
                RoadEdge re = edgeList.get(i - 1);
                edgeWeight = re.getEffectiveWeight();
                edgeKm = re.distanceKm;
                roadName = re.roadName;
            }

            result.pathNodeDetails.add(new NodeDetail(name, c[0], c[1], edgeWeight, edgeKm, roadName));
        }

        // Build human-readable path string
        result.recommendedPath = String.join(" -> ", result.orderedStops)
                + " (" + result.totalTravelTime + " mins, " + result.totalDistanceKm + " km"
                + (trafficDelay > 0 ? ", including " + trafficDelay + "m traffic delay" : "") + ")";

        if (trafficDelay > 0) {
            result.routeStatus = "Traffic Delay Active";
            result.statusDescription = "Route includes " + trafficDelay + " mins traffic delay on affected segments.";
        } else {
            result.routeStatus = "Optimal";
            result.statusDescription = "Optimal clear corridor without active traffic delays.";
        }

        return result;
    }

    /**
     * Computes a multi-stop bus run visiting all assigned stops in their required sequence,
     * ending at the college destination hub.
     * Uses Java PriorityQueue Dijkstra between consecutive stops to compute shortest travel-time road hops.
     *
     * @param graph Road network adjacency graph
     * @param coordinates Node coordinates map
     * @param origin Starting location or driver position (can be first stop or custom origin)
     * @param requiredStops List of assigned bus stops in their required order
     * @param destination Target college hub (typically "Kongu Engineering College")
     * @return RouteResult with combined orderedStops, roadSegments, pathNodeDetails, and assignedStops
     */
    public static RouteResult findMultiStopRoute(Map<String, List<RoadEdge>> graph, Map<String, double[]> coordinates,
                                                 String origin, List<String> requiredStops, String destination) {
        RouteResult multiResult = new RouteResult();
        multiResult.source = origin;
        multiResult.destination = destination;

        // Build list of sequential waypoints
        List<String> waypoints = new ArrayList<>();
        if (origin != null && !origin.trim().isEmpty()) {
            waypoints.add(origin.trim());
        }

        if (requiredStops != null) {
            for (String s : requiredStops) {
                if (s != null && !s.trim().isEmpty()) {
                    String clean = s.trim();
                    // Avoid duplicate consecutive waypoint
                    if (waypoints.isEmpty() || !waypoints.get(waypoints.size() - 1).equalsIgnoreCase(clean)) {
                        waypoints.add(clean);
                    }
                    multiResult.assignedStops.add(clean);
                }
            }
        }

        if (destination != null && !destination.trim().isEmpty()) {
            String cleanDest = destination.trim();
            if (waypoints.isEmpty() || !waypoints.get(waypoints.size() - 1).equalsIgnoreCase(cleanDest)) {
                waypoints.add(cleanDest);
            }
            if (multiResult.assignedStops.isEmpty() || !multiResult.assignedStops.get(multiResult.assignedStops.size() - 1).equalsIgnoreCase(cleanDest)) {
                multiResult.assignedStops.add(cleanDest);
            }
        }

        if (waypoints.size() < 2) {
            multiResult.reachable = false;
            multiResult.routeStatus = "Insufficient Stops";
            multiResult.statusDescription = "At least two valid waypoints are required to form a bus route.";
            return multiResult;
        }

        multiResult.reachable = true;
        int accumulatedTime = 0;
        int accumulatedBaseTime = 0;
        int accumulatedTrafficDelay = 0;
        double accumulatedDistKm = 0.0;

        for (int i = 0; i < waypoints.size() - 1; i++) {
            String legStart = waypoints.get(i);
            String legEnd = waypoints.get(i + 1);

            RouteResult legResult = findShortestRoute(graph, coordinates, legStart, legEnd);
            if (!legResult.reachable) {
                multiResult.reachable = false;
                multiResult.routeStatus = "Unreachable Segment";
                multiResult.statusDescription = "No navigable road path found between '" + legStart + "' and '" + legEnd + "'.";
                return multiResult;
            }

            accumulatedTime += legResult.totalTravelTime;
            accumulatedBaseTime += legResult.baseTravelTime;
            accumulatedTrafficDelay += legResult.totalTrafficDelay;
            accumulatedDistKm += legResult.totalDistanceKm;

            // Merge orderedStops (skip the first element of subsequent legs to avoid duplicating junction)
            int stopStartIdx = (i == 0) ? 0 : 1;
            for (int sIdx = stopStartIdx; sIdx < legResult.orderedStops.size(); sIdx++) {
                multiResult.orderedStops.add(legResult.orderedStops.get(sIdx));
            }

            // Merge road segments
            multiResult.roadSegments.addAll(legResult.roadSegments);

            // Merge pathNodeDetails (skip first element of subsequent legs)
            int detailStartIdx = (i == 0) ? 0 : 1;
            for (int dIdx = detailStartIdx; dIdx < legResult.pathNodeDetails.size(); dIdx++) {
                multiResult.pathNodeDetails.add(legResult.pathNodeDetails.get(dIdx));
            }
        }

        multiResult.totalTravelTime = accumulatedTime;
        multiResult.baseTravelTime = accumulatedBaseTime;
        multiResult.totalTrafficDelay = accumulatedTrafficDelay;
        multiResult.totalDistanceKm = Math.round(accumulatedDistKm * 100.0) / 100.0;

        multiResult.recommendedPath = String.join(" -> ", multiResult.orderedStops)
                + " (" + multiResult.totalTravelTime + " mins, " + multiResult.totalDistanceKm + " km"
                + (accumulatedTrafficDelay > 0 ? ", including " + accumulatedTrafficDelay + "m traffic delay" : "") + ")";

        if (accumulatedTrafficDelay > 0) {
            multiResult.routeStatus = "Traffic Delay Active";
            multiResult.statusDescription = "Multi-stop bus route includes " + accumulatedTrafficDelay + " mins traffic delay on affected road segments.";
        } else {
            multiResult.routeStatus = "Optimal Multi-Stop";
            multiResult.statusDescription = "All assigned stops successfully sequenced via optimal road segments.";
        }

        return multiResult;
    }

    public static RouteResult findMultiStopRoute(Map<String, List<RoadEdge>> graph, Map<String, double[]> coordinates,
                                                 List<String> requiredStops, String destination) {
        String origin = (requiredStops != null && !requiredStops.isEmpty()) ? requiredStops.get(0) : "Perundurai Old Bus Stand";
        return findMultiStopRoute(graph, coordinates, origin, requiredStops, destination);
    }
}
