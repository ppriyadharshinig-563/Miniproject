public class MiniBus extends CollegeBus {

    public MiniBus(int busId, String busNumber, int capacity) {
        super(busId, busNumber, capacity);
    }

    @Override
    public boolean canUseRoad(String roadType) {

        if (roadType.equalsIgnoreCase("Narrow")) {
            return true;
        }

        if (roadType.equalsIgnoreCase("Normal")) {
            return true;
        }

        return false;
    }
}